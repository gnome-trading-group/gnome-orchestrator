import { APIGatewayClient, GetApiKeyCommand } from '@aws-sdk/client-api-gateway';
import {
  AutoScalingClient, DescribeAutoScalingGroupsCommand, SetDesiredCapacityCommand,
} from '@aws-sdk/client-auto-scaling';
import { DynamoDBClient, GetItemCommand, PutItemCommand } from '@aws-sdk/client-dynamodb';
import { AllocateAddressCommand, DescribeAddressesCommand, EC2Client } from '@aws-sdk/client-ec2';
import { EventBridgeClient, PutEventsCommand } from '@aws-sdk/client-eventbridge';
import {
  CreateServiceCommand, DescribeCapacityProvidersCommand, DescribeServicesCommand, DescribeTaskDefinitionCommand,
  DescribeTasksCommand, ECSClient, KeyValuePair, ListContainerInstancesCommand, ListServicesCommand, ListTasksCommand,
  RegisterTaskDefinitionCommand, UpdateServiceCommand,
} from '@aws-sdk/client-ecs';
import { GetParameterCommand, SSMClient } from '@aws-sdk/client-ssm';
import { platformRegions } from '@gnome-trading-group/gnome-shared-cdk/dist/regions';
import { Alert, AlertState, EMPTY_STATE, evaluate, firingMode, Observation } from './alerts';
import { readRoll, Roll, rollItem, RollTick, rollTick, rollUnderWay, stepRoll } from './roll';

// Decides which order gateways run, live and paper, and makes AWS match: the only writer of the gateways' ECS services
// and Elastic IPs, run one tick at a time (a FIFO queue with one message group into a Lambda with one concurrent run,
// and a tick every minute as the resync), as market data's collection reconciler is for shards. Sessions and the
// launcher only record that they want a gateway (gnome-registry's gateways/ensure), and wake it; the gateway rows they
// mark wanted are the registry's.
//
// A gateway runs while it is wanted (asked for within WANT_WINDOW_MS: a starting session asks every 10 s, a linked one
// every minute) or in use (its endpoint item, written every 15 s, says sessions are linked or orders work). A running
// one that says nothing (its item expired: crashed, and ECS restarting it) is left running, as it may hold orders
// nobody else would cancel. Otherwise its service goes to zero: ECS stops it, and its planned stop runs. Where it runs
// is its row's region, on its pinned release or the latest; OrderGatewayStack, in every platform region, holds the
// cluster and, for each mode, the hosts that scale from zero and the base task definition each gateway's own revision
// is copied from. A paper gateway simulates its venue: it runs on the paper hosts, reads its sessions' market-data
// leases, and has no Elastic IP.
//
// An operator may ask for a running gateway to be rolled (the row's roll_requested_at): stopped without cancelling and
// started again on its host on the release a start would run, its orders taken back from the ownership log there. The
// roll's steps are in roll.ts; while one is under way the gateway is kept running whatever it says.

/** A mode's base task definition and the capacity provider of its hosts. */
export interface ModeNames {
  baseFamily: string;
  capacityProvider: string;
}

/** What gateways are run by: the gateway stack's names (config.ts ORDER_GATEWAYS), given in the environment. */
export interface GatewayNames {
  cluster: string;
  // By gateway row mode.
  modes: Readonly<Record<string, ModeNames>>;
  servicePrefix: string;
  addressTag: string;
}

/** Where a paper gateway reads its sessions' leases, as the sessions themselves do. */
export interface MarketDataApi {
  url: string;
  keySecret: string;
}

// How long a gateway nobody has asked for keeps running: well past a linked session's minute between asks.
export const WANT_WINDOW_MS = 30 * 60 * 1000;
const LATEST_VERSION_PARAMETER = '/gnome/orchestrator/latest-version';
// Where gateways raise their own alerts (gnome-registry's AlertingStack publishes it).
const ALERTS_API_URL_PARAMETER = '/gnome/alerting/api-url';
// Where the registry API is (gnome-registry's ApiStack publishes it).
const REGISTRY_API_URL_PARAMETER = '/gnome/registry/api-url';
// Where the market-data API is (gnome-market-data's BackendStack publishes it), for paper gateways' leases.
const MARKET_DATA_API_URL_PARAMETER = '/gnome/market-data/api-url';
// The table gateways write their endpoint to, in its one region (gnome-shared-cdk SERVICE_ENDPOINTS).
const ENDPOINTS_TABLE = 'service-endpoints';
const ENDPOINTS_REGION = 'us-east-1';
// What the alerts keep between ticks: one item beside the gateways' own, with no expiry.
const ALERT_STATE_ID = 'order-gateway-reconciler#alerts';
// A gateway's roll item, beside its endpoint: what the gateway reads at SIGTERM, and the controller shows.
const rollId = (gatewayKey: string) => `order-gateway#${gatewayKey}#roll`;
// gnome-registry's alert bus, in this account and region (its alerts/model.ts).
const ALERT_BUS = 'gnome-alerts';

export interface GatewayRow {
  gateway_key: string;
  mode: string;
  region: string;
  orchestrator_version: string | null;
  wanted_at: string | null;
  // When an operator last asked for it to be rolled.
  roll_requested_at?: string | null;
}

/**
 * What the gateway last said of itself, from its endpoint item; null when it has never written one. An expired item
 * (no write for its TTL) is what it said before it went: it is gone, but may have left orders working.
 */
export interface Activity {
  state: string;
  linkedSessions: number;
  workingOrders: number;
  expired?: boolean;
  // The release it runs; when it last wrote; whether its release stops without cancelling when rolled, and whether it
  // is stopping so now.
  version?: string;
  writtenAtMs?: number;
  canHandOver?: boolean;
  handover?: boolean;
}

export type Client = { send(command: any): Promise<any> };

export interface ReconcileDeps {
  names: GatewayNames;
  gateways(): Promise<GatewayRow[]>;
  activity(gatewayKey: string, nowMs: number): Promise<Activity | null>;
  latestVersion(): Promise<string>;
  alertsApiUrl(): Promise<string>;
  marketDataApi(): Promise<MarketDataApi>;
  ecs(region: string): Client;
  ec2(region: string): Client;
  autoscaling(region: string): Client;
  // The gateway's roll item, null without one; and writing it.
  roll(gatewayKey: string): Promise<Roll | null>;
  saveRoll(gatewayKey: string, roll: Roll, nowMs: number): Promise<void>;
  sleep(ms: number): Promise<void>;
  nowMs(): number;
  // Told what the tick saw of each gateway, for its alerts.
  observed?(observation: Observation): void;
}

// 'starting': moved onto a new revision with nothing wanted yet, waiting for the old deployment to drain. 'moving': its
// row names a new region, and it waits there until it has stopped in the old one.
// 'rolling': a roll an operator asked for is under way.
export type Outcome =
  'starting' | 'moving' | 'started' | 'created' | 'stopped' | 'running' | 'stopped already' | 'rolling';

export function serviceName(names: GatewayNames, gatewayKey: string): string {
  return names.servicePrefix + gatewayKey.replace(/:/g, '-');
}

/**
 * Whether a gateway should run now: wanted recently, or in use; or running and gone silent while it last had sessions
 * or orders, which may be a gateway restarting with orders still working. One that never said anything never came up,
 * so holds nothing.
 */
export function shouldRun(row: GatewayRow, activity: Activity | null, running: boolean, nowMs: number): boolean {
  const wanted = row.wanted_at != null && nowMs - Date.parse(row.wanted_at) < WANT_WINDOW_MS;
  const held = activity != null && (activity.linkedSessions > 0 || activity.workingOrders > 0);
  return wanted || (held && (!activity!.expired || running));
}

/** One tick: each gateway brought to where it should be. One that fails is logged and tried again next tick. */
export async function reconcile(deps: ReconcileDeps): Promise<Map<string, Outcome>> {
  const outcomes = new Map<string, Outcome>();
  const tick = rollTick();
  for (const row of await deps.gateways()) {
    try {
      const seen = await reconcileOne(row, deps, tick);
      outcomes.set(row.gateway_key, seen.outcome!);
      deps.observed?.(seen);
    } catch (error) {
      console.error(`Gateway ${row.gateway_key}: couldn't reconcile`, error);
      deps.observed?.({
        gatewayKey: row.gateway_key, mode: row.mode, region: row.region, activity: null, error: String(error),
      });
    }
  }
  return outcomes;
}

async function reconcileOne(row: GatewayRow, deps: ReconcileDeps, tick: RollTick): Promise<Observation> {
  if (!platformRegions().includes(row.region)) {
    throw new Error(`${row.gateway_key} trades from ${row.region}, where no gateway can run`);
  }
  const { names } = deps;
  const mode = names.modes[row.mode];
  if (!mode) throw new Error(`${row.gateway_key} is a ${row.mode} gateway, which nothing here runs`);
  const stoppedElsewhere = await stopElsewhere(row, deps);
  const ecs = deps.ecs(row.region);
  const service = serviceName(names, row.gateway_key);
  const current = await activeService(ecs, names, service);
  const running = current != null && current.desiredCount > 0;
  const now = deps.nowMs();
  const activity = await deps.activity(row.gateway_key, now);
  let roll = await deps.roll(row.gateway_key);
  const seen = (outcome: Outcome): Observation => ({
    gatewayKey: row.gateway_key, mode: row.mode, region: row.region, outcome, activity,
    serviceEvent: current?.events?.[0]?.message, roll: roll ?? undefined,
  });
  if (stoppedElsewhere) {
    roll = await stepRoll({ row, deps, ecs, mode, service, current, running, activity, now, tick }, roll);
    if (roll && rollUnderWay(roll)) return seen('rolling');
  }
  if (!shouldRun(row, activity, running, now)) {
    if (!running) return seen('stopped already');
    await ecs.send(new UpdateServiceCommand({ cluster: names.cluster, service, desiredCount: 0 }));
    return seen('stopped');
  }
  // A running gateway keeps its release: a new pin or release reaches it at its next start.
  if (running) {
    const taskId = await runningTask(ecs, names, service);
    if (activity && !activity.expired && UP_STATES.has(activity.state)) return { ...seen('running'), taskId };
    await releaseLostHost(ecs, names, service, current);
    // Not up: why its last task stopped, for the alert should it stay so.
    return { ...seen('running'), taskId, stoppedReason: await lastStopped(ecs, names, service) };
  }
  if (!stoppedElsewhere) return seen('moving');
  const version = row.orchestrator_version ?? await deps.latestVersion();
  if (row.mode !== 'paper') await ensureAddress(deps.ec2(row.region), names, row.gateway_key);
  const taskDefinition = await revision(ecs, names, mode, row.gateway_key, version, await gatewayEnvironment(row, deps));
  if (current) {
    // Onto a new revision with nothing wanted, and wanted only once the old deployment has drained: while it is still
    // there, ECS starts a task of the previous release on it, which takes sessions until the new one replaces it. A
    // starting session wakes this every 10 s, so the next tick comes soon. A host a roll pinned it to is let go: a
    // fresh start may go on any host.
    if (current.taskDefinition !== taskDefinition || (current.placementConstraints ?? []).length > 0) {
      await ecs.send(new UpdateServiceCommand({
        cluster: names.cluster, service, taskDefinition, desiredCount: 0, placementConstraints: [],
      }));
      return seen('starting');
    }
    if ((current.deployments ?? []).length > 1) return seen('starting');
    await ecs.send(new UpdateServiceCommand({ cluster: names.cluster, service, desiredCount: 1 }));
    await ensureHosts(deps, row.region, mode);
    return seen('started');
  }
  await ecs.send(new CreateServiceCommand({
    cluster: names.cluster,
    serviceName: service,
    taskDefinition,
    desiredCount: 1,
    capacityProviderStrategy: [{ capacityProvider: mode.capacityProvider, weight: 1 }],
    // One host per gateway and fixed ports: the old task stops before a new one starts.
    deploymentConfiguration: { minimumHealthyPercent: 0, maximumPercent: 100 },
  }));
  await ensureHosts(deps, row.region, mode);
  return seen('created');
}

/**
 * What each gateway's revision sets over its base's environment beside its key and release: where it raises alerts,
 * and for a paper gateway, which trades with no venue, where it reads its sessions' market-data leases.
 */
export async function gatewayEnvironment(row: GatewayRow, deps: ReconcileDeps): Promise<KeyValuePair[]> {
  const environment = [{ name: 'ALERTS_API_URL', value: await deps.alertsApiUrl() }];
  if (row.mode === 'paper') {
    const marketData = await deps.marketDataApi();
    environment.push(
      { name: 'MARKET_DATA_API_URL', value: marketData.url },
      { name: 'MARKET_DATA_API_KEY_SECRET', value: marketData.keySecret },
    );
  }
  return environment;
}

/**
 * A running gateway pinned by a roll to a host that has left the cluster (lost, or taken away) can't be placed again:
 * the pin is let go, so ECS starts it on another host, as for any host lost (its orders there are swept and reclaimed
 * through the sessions' hellos).
 */
async function releaseLostHost(ecs: Client, names: GatewayNames, service: string, current: any): Promise<void> {
  const pinned = /ec2InstanceId\s*==\s*(\S+)/.exec(current?.placementConstraints?.[0]?.expression ?? '')?.[1];
  if (!pinned || await hostActive(ecs, names, pinned)) return;
  await ecs.send(new UpdateServiceCommand({ cluster: names.cluster, service, placementConstraints: [] }));
  console.log(`Gateway service ${service}: its host ${pinned} is gone; letting it start on another`);
}

/** Whether the EC2 instance is an active host in the gateways' cluster. */
export async function hostActive(ecs: Client, names: GatewayNames, instanceId: string): Promise<boolean> {
  const listed = await ecs.send(new ListContainerInstancesCommand({
    cluster: names.cluster, filter: `ec2InstanceId == ${instanceId}`, status: 'ACTIVE',
  }));
  return (listed.containerInstanceArns ?? []).length > 0;
}

/** The gateway's service in the region's cluster; undefined when it has none, or the region has no cluster yet. */
export async function activeService(ecs: Client, names: GatewayNames, service: string): Promise<any | undefined> {
  try {
    const described = await ecs.send(new DescribeServicesCommand({ cluster: names.cluster, services: [service] }));
    return (described.services ?? []).find((s: any) => s.status === 'ACTIVE');
  } catch (error: any) {
    if (error?.name === 'ClusterNotFoundException') return undefined;
    throw error;
  }
}

/**
 * Stops the gateway in every region but its row's, where it ran before its row moved, and says whether nothing of it
 * runs there any more. It must not start in its new region before then: its accounts would be traded from two
 * processes and two addresses at once. Its planned stop in the old region cancels and tells everyone, as any stop does.
 */
async function stopElsewhere(row: GatewayRow, deps: ReconcileDeps): Promise<boolean> {
  const { names } = deps;
  const service = serviceName(names, row.gateway_key);
  let stopped = true;
  for (const region of platformRegions()) {
    if (region === row.region) continue;
    const ecs = deps.ecs(region);
    const found = await activeService(ecs, names, service);
    if (!found) continue;
    if (found.desiredCount > 0) {
      await ecs.send(new UpdateServiceCommand({ cluster: names.cluster, service, desiredCount: 0 }));
      console.log(`Gateway ${row.gateway_key}: moved to ${row.region}, stopping it in ${region}`);
      stopped = false;
    } else if ((found.runningCount ?? 0) > 0 || (found.pendingCount ?? 0) > 0) {
      stopped = false;
    }
  }
  return stopped;
}

/**
 * Raises the region's hosts of the gateway's mode to one per gateway set to run on them, when fewer are asked for.
 * Managed scaling would get there too, but only once its alarm has seen the task waiting for a few minutes. Never
 * lowers the count: managed scaling takes empty hosts away. A failure is logged and left to managed scaling, so the
 * start still stands.
 */
async function ensureHosts(deps: ReconcileDeps, region: string, mode: ModeNames): Promise<void> {
  const { names } = deps;
  try {
    const ecs = deps.ecs(region);
    const provider = (await ecs.send(new DescribeCapacityProvidersCommand({
      capacityProviders: [mode.capacityProvider],
    }))).capacityProviders?.[0];
    const groupArn: string | undefined = provider?.autoScalingGroupProvider?.autoScalingGroupArn;
    const group = groupArn?.split('autoScalingGroupName/')[1];
    if (!group) throw new Error(`capacity provider ${mode.capacityProvider} names no Auto Scaling group`);
    const needed = await gatewaysSetToRun(ecs, names, mode.capacityProvider);
    const autoscaling = deps.autoscaling(region);
    const described = (await autoscaling.send(new DescribeAutoScalingGroupsCommand({
      AutoScalingGroupNames: [group],
    }))).AutoScalingGroups?.[0];
    if (!described) throw new Error(`no Auto Scaling group ${group}`);
    const wanted = Math.min(needed, described.MaxSize ?? needed);
    if ((described.DesiredCapacity ?? 0) >= wanted) return;
    await autoscaling.send(new SetDesiredCapacityCommand({
      AutoScalingGroupName: group, DesiredCapacity: wanted, HonorCooldown: false,
    }));
    console.log(`Gateway hosts ${group} in ${region}: ${described.DesiredCapacity ?? 0} -> ${wanted}`
      + ` for ${needed} gateway(s)`);
  } catch (error) {
    console.error(`Gateway hosts for ${mode.capacityProvider} in ${region}: couldn't raise them; managed scaling will`,
      error);
  }
}

/** The region's gateway services set to run on the provider's hosts: each needs a host of its own (fixed ports). */
async function gatewaysSetToRun(ecs: Client, names: GatewayNames, capacityProvider: string): Promise<number> {
  const arns: string[] = [];
  let nextToken: string | undefined;
  do {
    const page = await ecs.send(new ListServicesCommand({ cluster: names.cluster, maxResults: 100, nextToken }));
    arns.push(...(page.serviceArns ?? []));
    nextToken = page.nextToken;
  } while (nextToken);
  const ours = arns.map((arn) => arn.substring(arn.lastIndexOf('/') + 1)).filter((n) => n.startsWith(names.servicePrefix));
  let running = 0;
  for (let i = 0; i < ours.length; i += 10) {
    const page = await ecs.send(new DescribeServicesCommand({ cluster: names.cluster, services: ours.slice(i, i + 10) }));
    running += (page.services ?? []).filter((s: any) => s.status === 'ACTIVE' && s.desiredCount > 0
      && (s.capacityProviderStrategy ?? []).some((p: any) => p.capacityProvider === capacityProvider)).length;
  }
  return running;
}

// A gateway's endpoint states in which it is up.
export const UP_STATES = new Set(['READY', 'DEGRADED']);

/** The service's running task, by its id; undefined while none is. */
async function runningTask(ecs: Client, names: GatewayNames, service: string): Promise<string | undefined> {
  return (await runningTasks(ecs, names, service))[0];
}

/** The service's running tasks, by their ids. */
export async function runningTasks(ecs: Client, names: GatewayNames, service: string): Promise<string[]> {
  const listed = await ecs.send(new ListTasksCommand({ cluster: names.cluster, serviceName: service, desiredStatus: 'RUNNING' }));
  return (listed.taskArns ?? []).map((arn: string) => arn.substring(arn.lastIndexOf('/') + 1));
}

/** Why the service's most recently stopped task stopped, as ECS and its container say; undefined when none has. */
async function lastStopped(ecs: Client, names: GatewayNames, service: string): Promise<string | undefined> {
  const listed = await ecs.send(new ListTasksCommand({
    cluster: names.cluster, serviceName: service, desiredStatus: 'STOPPED', maxResults: 10,
  }));
  if (!listed.taskArns?.length) return undefined;
  const tasks = (await ecs.send(new DescribeTasksCommand({ cluster: names.cluster, tasks: listed.taskArns }))).tasks ?? [];
  const newest = [...tasks].sort((a: any, b: any) => Date.parse(b.stoppedAt ?? 0) - Date.parse(a.stoppedAt ?? 0))[0];
  if (!newest) return undefined;
  const container = newest.containers?.[0];
  const exit = container?.exitCode != null ? `exit ${container.exitCode}` : container?.reason;
  return [newest.stoppedReason, exit].filter(Boolean).join(', ') || undefined;
}

// What each gateway's revision sets over the base's environment.
const GATEWAY_ENVIRONMENT = new Set([
  'GATEWAY_KEY', 'ORCHESTRATOR_VERSION', 'ALERTS_API_URL', 'MARKET_DATA_API_URL', 'MARKET_DATA_API_KEY_SECRET',
]);

/**
 * The gateway's own revision: its mode's base task definition as deployed, with its key, its release, where it raises
 * alerts and, for paper, where it reads leases; reused when unchanged.
 */
export async function revision(
  ecs: Client, names: GatewayNames, mode: ModeNames, gatewayKey: string, version: string, environment: KeyValuePair[],
): Promise<string> {
  const base = (await ecs.send(new DescribeTaskDefinitionCommand({ taskDefinition: mode.baseFamily }))).taskDefinition;
  if (!base) throw new Error(`No ${mode.baseFamily} task definition: the gateway stack isn't deployed there`);
  const wanted = {
    family: serviceName(names, gatewayKey),
    taskRoleArn: base.taskRoleArn,
    executionRoleArn: base.executionRoleArn,
    networkMode: base.networkMode,
    containerDefinitions: base.containerDefinitions.map((container: any) => ({
      ...container,
      environment: [
        ...(container.environment ?? []).filter((e: KeyValuePair) => !GATEWAY_ENVIRONMENT.has(e.name ?? '')),
        { name: 'GATEWAY_KEY', value: gatewayKey },
        { name: 'ORCHESTRATOR_VERSION', value: version },
        ...environment,
      ],
    })),
    volumes: base.volumes,
    requiresCompatibilities: base.requiresCompatibilities,
    placementConstraints: base.placementConstraints,
  };
  const latest = await latestRevision(ecs, wanted.family);
  if (latest && sameRevision(latest, wanted)) return latest.taskDefinitionArn;
  const registered = await ecs.send(new RegisterTaskDefinitionCommand(wanted));
  return registered.taskDefinition.taskDefinitionArn;
}

async function latestRevision(ecs: Client, family: string): Promise<any | undefined> {
  try {
    return (await ecs.send(new DescribeTaskDefinitionCommand({ taskDefinition: family }))).taskDefinition;
  } catch (error: any) {
    // A gateway that has never run has no revision of its own yet.
    if (error?.name === 'ClientException') return undefined;
    throw error;
  }
}

/** Whether a registered revision runs what is wanted: the same roles, network, and each container as asked. */
export function sameRevision(registered: any, wanted: any): boolean {
  const fields = (r: any) => ({
    taskRoleArn: r.taskRoleArn,
    executionRoleArn: r.executionRoleArn,
    networkMode: r.networkMode,
    containers: (r.containerDefinitions ?? []).map((c: any) => ({
      name: c.name,
      image: c.image,
      memoryReservation: c.memoryReservation,
      environment: [...(c.environment ?? [])]
        .sort((a: KeyValuePair, b: KeyValuePair) => (a.name ?? '').localeCompare(b.name ?? '')),
    })),
  });
  return JSON.stringify(fields(registered)) === JSON.stringify(fields(wanted));
}

/** The gateway's Elastic IP, allocated once and kept, so it trades from one address whatever host it is on. */
async function ensureAddress(ec2: Client, names: GatewayNames, gatewayKey: string) {
  const found = await ec2.send(new DescribeAddressesCommand({
    Filters: [{ Name: `tag:${names.addressTag}`, Values: [gatewayKey] }],
  }));
  if ((found.Addresses ?? []).length > 0) return;
  await ec2.send(new AllocateAddressCommand({
    Domain: 'vpc',
    TagSpecifications: [{
      ResourceType: 'elastic-ip',
      Tags: [{ Key: names.addressTag, Value: gatewayKey }, { Key: 'Name', Value: `order-gateway ${gatewayKey}` }],
    }],
  }));
}

// ----- the Lambda -----

const REGISTRY_API_KEY_ID = process.env.REGISTRY_API_KEY_ID ?? '';
const ssm = new SSMClient({ region: 'us-east-1' });
let cachedApiKey: string | undefined;
let cachedApiUrl: string | undefined;

async function parameter(name: string): Promise<string> {
  const res = await ssm.send(new GetParameterCommand({ Name: name }));
  if (!res.Parameter?.Value) throw new Error(`No ${name}`);
  return res.Parameter.Value;
}

async function registryApiUrl(): Promise<string> {
  cachedApiUrl ??= await parameter(REGISTRY_API_URL_PARAMETER);
  return cachedApiUrl;
}

async function registryApiKey(): Promise<string> {
  if (cachedApiKey) return cachedApiKey;
  const res = await new APIGatewayClient({}).send(new GetApiKeyCommand({ apiKey: REGISTRY_API_KEY_ID, includeValue: true }));
  if (!res.value) throw new Error('Registry API key value not found');
  cachedApiKey = res.value;
  return cachedApiKey;
}

const dynamo = new DynamoDBClient({ region: ENDPOINTS_REGION });

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set`);
  return value;
}

function environmentNames(): GatewayNames {
  return {
    cluster: required('GATEWAY_CLUSTER'),
    modes: {
      live: {
        baseFamily: required('GATEWAY_LIVE_BASE_FAMILY'),
        capacityProvider: required('GATEWAY_LIVE_CAPACITY_PROVIDER'),
      },
      paper: {
        baseFamily: required('GATEWAY_PAPER_BASE_FAMILY'),
        capacityProvider: required('GATEWAY_PAPER_CAPACITY_PROVIDER'),
      },
    },
    servicePrefix: required('GATEWAY_SERVICE_PREFIX'),
    addressTag: required('GATEWAY_ADDRESS_TAG'),
  };
}

const lambdaDeps: Omit<ReconcileDeps, 'names'> = {
  async gateways() {
    // The registry's own rows, of every mode, read through its API.
    const res = await fetch(`${await registryApiUrl()}gateways`, {
      headers: { 'x-api-key': await registryApiKey() },
    });
    if (!res.ok) throw new Error(`GET /gateways failed (${res.status})`);
    return await res.json() as GatewayRow[];
  },
  async activity(gatewayKey, nowMs) {
    const item = (await dynamo.send(new GetItemCommand({
      TableName: ENDPOINTS_TABLE,
      Key: { service_id: { S: `order-gateway#${gatewayKey}` } },
      ConsistentRead: true,
    }))).Item;
    if (!item) return null;
    return {
      state: item.state?.S ?? '',
      linkedSessions: Number(item.linked_sessions?.N ?? 0),
      workingOrders: Number(item.working_orders?.N ?? 0),
      // DynamoDB removes expired items only eventually.
      expired: Number(item.expires_at?.N ?? 0) * 1000 <= nowMs,
      version: item.version?.S,
      // Written in epoch nanoseconds.
      writtenAtMs: Number(BigInt(item.written_at_ns?.N ?? '0') / 1_000_000n),
      canHandOver: item.can_hand_over?.BOOL === true,
      handover: item.handover?.BOOL === true,
    };
  },
  async roll(gatewayKey) {
    const item = (await dynamo.send(new GetItemCommand({
      TableName: ENDPOINTS_TABLE, Key: { service_id: { S: rollId(gatewayKey) } }, ConsistentRead: true,
    }))).Item;
    return item ? readRoll(item) : null;
  },
  async saveRoll(gatewayKey, roll, nowMs) {
    await dynamo.send(new PutItemCommand({
      TableName: ENDPOINTS_TABLE, Item: rollItem(rollId(gatewayKey), roll, nowMs),
    }));
  },
  sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  latestVersion: () => parameter(LATEST_VERSION_PARAMETER),
  alertsApiUrl: () => parameter(ALERTS_API_URL_PARAMETER),
  async marketDataApi() {
    // As the launcher gives it to sessions, which add each route's path to it.
    const url = (await parameter(MARKET_DATA_API_URL_PARAMETER)).replace(/\/$/, '');
    return { url, keySecret: required('MARKET_DATA_API_KEY_SECRET') };
  },
  ecs: (region) => new ECSClient({ region }),
  ec2: (region) => new EC2Client({ region }),
  autoscaling: (region) => new AutoScalingClient({ region }),
  nowMs: () => Date.now(),
};

const events = new EventBridgeClient({ region: ENDPOINTS_REGION });

async function loadAlertState(): Promise<AlertState> {
  try {
    const item = (await dynamo.send(new GetItemCommand({
      TableName: ENDPOINTS_TABLE, Key: { service_id: { S: ALERT_STATE_ID } }, ConsistentRead: true,
    }))).Item;
    return item?.state?.S ? JSON.parse(item.state.S) as AlertState : EMPTY_STATE;
  } catch (error) {
    // Started afresh: alerts the last tick left firing are raised again if they still hold, or wait for a person.
    console.error('Couldn\'t read the alert state; starting afresh', error);
    return EMPTY_STATE;
  }
}

async function saveAlertState(state: AlertState) {
  await dynamo.send(new PutItemCommand({
    TableName: ENDPOINTS_TABLE,
    Item: { service_id: { S: ALERT_STATE_ID }, kind: { S: 'order-gateway-reconciler' }, state: { S: JSON.stringify(state) } },
  }));
}

/** An alert as gnome-registry's AlertEvent, onto its bus; a failure is logged and the next tick sends it again. */
async function publish(action: 'FIRING' | 'RESOLVED', key: string, type: string, mode: string, alert?: Alert) {
  const detail = {
    // The registry's catalog keeps paper alerts below paging.
    action, key, type, source: 'ingest', mode,
    title: alert?.title ?? `${type} cleared`,
    labels: alert?.labels ?? {},
    stateAt: new Date().toISOString(),
  };
  try {
    await events.send(new PutEventsCommand({
      Entries: [{ EventBusName: ALERT_BUS, Source: 'gnome.alerts', DetailType: 'Alert', Detail: JSON.stringify(detail) }],
    }));
  } catch (error) {
    console.error(`Couldn't publish alert ${key}`, error);
  }
}

/** Woken by the queue (a wake-up from gateways/ensure, or the minute's tick); every message means one tick. */
export const handler = async () => {
  const previous = await loadAlertState();
  const observations: Observation[] = [];
  const outcomes = await reconcile({ ...lambdaDeps, names: environmentNames(), observed: (seen) => observations.push(seen) });
  for (const [gateway, outcome] of outcomes) {
    if (outcome !== 'running' && outcome !== 'stopped already') console.log(`Gateway ${gateway}: ${outcome}`);
  }
  const { firing, state } = evaluate(observations, previous, Date.now());
  for (const [key, alert] of firing) await publish('FIRING', key, alert.type, alert.mode, alert);
  for (const [key, type] of Object.entries(previous.firing)) {
    if (!firing.has(key)) await publish('RESOLVED', key, type, firingMode(previous, key));
  }
  await saveAlertState(state);
};
