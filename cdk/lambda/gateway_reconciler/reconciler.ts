import { APIGatewayClient, GetApiKeyCommand } from '@aws-sdk/client-api-gateway';
import { DynamoDBClient, GetItemCommand, PutItemCommand } from '@aws-sdk/client-dynamodb';
import { AllocateAddressCommand, DescribeAddressesCommand, EC2Client } from '@aws-sdk/client-ec2';
import { EventBridgeClient, PutEventsCommand } from '@aws-sdk/client-eventbridge';
import {
  CreateServiceCommand, DescribeServicesCommand, DescribeTaskDefinitionCommand, DescribeTasksCommand, ECSClient,
  KeyValuePair, ListTasksCommand, RegisterTaskDefinitionCommand, UpdateServiceCommand,
} from '@aws-sdk/client-ecs';
import { GetParameterCommand, SSMClient } from '@aws-sdk/client-ssm';
import { platformRegions } from '@gnome-trading-group/gnome-shared-cdk/dist/regions';
import { Alert, AlertState, EMPTY_STATE, evaluate, Observation } from './alerts';

// Decides which live order gateways run, and makes AWS match: the only writer of the gateways' ECS services and
// Elastic IPs, run one tick at a time (a FIFO queue with one message group into a Lambda with one concurrent run, and a
// tick every minute as the resync), as market data's collection reconciler is for shards. Sessions and the launcher
// only record that they want a gateway (gnome-registry's gateways/ensure), and wake it; the gateway rows they mark
// wanted are the registry's.
//
// A gateway runs while it is wanted (asked for within WANT_WINDOW_MS: a starting session asks every 10 s, a linked one
// every minute) or in use (its endpoint item, written every 15 s, says sessions are linked or orders work). A running
// one that says nothing (its item expired: crashed, and ECS restarting it) is left running, as it may hold orders
// nobody else would cancel. Otherwise its service goes to zero: ECS stops it, and its planned stop runs. Where it runs is its row's region, on its pinned
// release or the latest; OrderGatewayStack, in every platform region, holds the cluster, the hosts that scale from
// zero, and the base task definition each gateway's own revision is copied from.

/** What gateways are run by: the gateway stack's names (config.ts ORDER_GATEWAYS), given in the environment. */
export interface GatewayNames {
  cluster: string;
  baseFamily: string;
  capacityProvider: string;
  servicePrefix: string;
  addressTag: string;
}
// How long a gateway nobody has asked for keeps running: well past a linked session's minute between asks.
export const WANT_WINDOW_MS = 30 * 60 * 1000;
const LATEST_VERSION_PARAMETER = '/gnome/orchestrator/latest-version';
// Where gateways raise their own alerts (gnome-registry's AlertingStack publishes it).
const ALERTS_API_URL_PARAMETER = '/gnome/alerting/api-url';
// Where the registry API is (gnome-registry's ApiStack publishes it).
const REGISTRY_API_URL_PARAMETER = '/gnome/registry/api-url';
// The table gateways write their endpoint to, in its one region (gnome-shared-cdk SERVICE_ENDPOINTS).
const ENDPOINTS_TABLE = 'service-endpoints';
const ENDPOINTS_REGION = 'us-east-1';
// What the alerts keep between ticks: one item beside the gateways' own, with no expiry.
const ALERT_STATE_ID = 'order-gateway-reconciler#alerts';
// gnome-registry's alert bus, in this account and region (its alerts/model.ts).
const ALERT_BUS = 'gnome-alerts';

export interface GatewayRow {
  gateway_key: string;
  mode: string;
  region: string;
  orchestrator_version: string | null;
  wanted_at: string | null;
}

/** What the gateway says of itself, from its endpoint item; null when it has none or it has expired. */
export interface Activity {
  state: string;
  linkedSessions: number;
  workingOrders: number;
}

type Client = { send(command: any): Promise<any> };

export interface ReconcileDeps {
  names: GatewayNames;
  liveGateways(): Promise<GatewayRow[]>;
  activity(gatewayKey: string, nowMs: number): Promise<Activity | null>;
  latestVersion(): Promise<string>;
  alertsApiUrl(): Promise<string>;
  ecs(region: string): Client;
  ec2(region: string): Client;
  nowMs(): number;
  // Told what the tick saw of each gateway, for its alerts.
  observed?(observation: Observation): void;
}

export type Outcome = 'started' | 'created' | 'stopped' | 'running' | 'stopped already';

export function serviceName(names: GatewayNames, gatewayKey: string): string {
  return names.servicePrefix + gatewayKey.replace(/:/g, '-');
}

/**
 * Whether a gateway should run now: wanted recently, or in use; or running and saying nothing of itself, which may be
 * a gateway restarting with orders still working.
 */
export function shouldRun(row: GatewayRow, activity: Activity | null, running: boolean, nowMs: number): boolean {
  const wanted = row.wanted_at != null && nowMs - Date.parse(row.wanted_at) < WANT_WINDOW_MS;
  const inUse = activity != null && (activity.linkedSessions > 0 || activity.workingOrders > 0);
  return wanted || inUse || (running && activity == null);
}

/** One tick: each live gateway brought to where it should be. One that fails is logged and tried again next tick. */
export async function reconcile(deps: ReconcileDeps): Promise<Map<string, Outcome>> {
  const outcomes = new Map<string, Outcome>();
  for (const row of await deps.liveGateways()) {
    if (row.mode !== 'live') continue;
    try {
      const seen = await reconcileOne(row, deps);
      outcomes.set(row.gateway_key, seen.outcome!);
      deps.observed?.(seen);
    } catch (error) {
      console.error(`Gateway ${row.gateway_key}: couldn't reconcile`, error);
      deps.observed?.({ gatewayKey: row.gateway_key, region: row.region, activity: null, error: String(error) });
    }
  }
  return outcomes;
}

async function reconcileOne(row: GatewayRow, deps: ReconcileDeps): Promise<Observation> {
  if (!platformRegions().includes(row.region)) {
    throw new Error(`${row.gateway_key} trades from ${row.region}, where no gateway can run`);
  }
  const { names } = deps;
  const ecs = deps.ecs(row.region);
  const service = serviceName(names, row.gateway_key);
  const described = await ecs.send(new DescribeServicesCommand({ cluster: names.cluster, services: [service] }));
  const current = (described.services ?? []).find((s: any) => s.status === 'ACTIVE');
  const running = current != null && current.desiredCount > 0;
  const now = deps.nowMs();
  const activity = await deps.activity(row.gateway_key, now);
  const seen = (outcome: Outcome): Observation => ({
    gatewayKey: row.gateway_key, region: row.region, outcome, activity, serviceEvent: current?.events?.[0]?.message,
  });
  if (!shouldRun(row, activity, running, now)) {
    if (!running) return seen('stopped already');
    await ecs.send(new UpdateServiceCommand({ cluster: names.cluster, service, desiredCount: 0 }));
    return seen('stopped');
  }
  // A running gateway keeps its release: a new pin or release reaches it at its next start.
  if (running) {
    if (activity && UP_STATES.has(activity.state)) return seen('running');
    // Not up: why its last task stopped, for the alert should it stay so.
    return { ...seen('running'), stoppedReason: await lastStopped(ecs, names, service) };
  }
  const version = row.orchestrator_version ?? await deps.latestVersion();
  await ensureAddress(deps.ec2(row.region), names, row.gateway_key);
  const taskDefinition = await revision(ecs, names, row.gateway_key, version, await deps.alertsApiUrl());
  if (current) {
    if (current.taskDefinition !== taskDefinition) {
      // Onto the new revision while nothing is wanted, then wanted: set together, ECS first raises the count on the old
      // deployment, and a gateway on the previous release takes sessions until the new one replaces it.
      await ecs.send(new UpdateServiceCommand({ cluster: names.cluster, service, taskDefinition, desiredCount: 0 }));
    }
    await ecs.send(new UpdateServiceCommand({ cluster: names.cluster, service, desiredCount: 1 }));
    return seen('started');
  }
  await ecs.send(new CreateServiceCommand({
    cluster: names.cluster,
    serviceName: service,
    taskDefinition,
    desiredCount: 1,
    capacityProviderStrategy: [{ capacityProvider: names.capacityProvider, weight: 1 }],
    // One host per gateway and fixed ports: the old task stops before a new one starts.
    deploymentConfiguration: { minimumHealthyPercent: 0, maximumPercent: 100 },
  }));
  return seen('created');
}

// A gateway's endpoint states in which it is up.
const UP_STATES = new Set(['READY', 'DEGRADED']);

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
const GATEWAY_ENVIRONMENT = new Set(['GATEWAY_KEY', 'ORCHESTRATOR_VERSION', 'ALERTS_API_URL']);

/**
 * The gateway's own revision: the base task definition as deployed, with its key, its release and where it raises
 * alerts; reused when unchanged.
 */
async function revision(
  ecs: Client, names: GatewayNames, gatewayKey: string, version: string, alertsApiUrl: string,
): Promise<string> {
  const base = (await ecs.send(new DescribeTaskDefinitionCommand({ taskDefinition: names.baseFamily }))).taskDefinition;
  if (!base) throw new Error(`No ${names.baseFamily} task definition: the gateway stack isn't deployed there`);
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
        { name: 'ALERTS_API_URL', value: alertsApiUrl },
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
    baseFamily: required('GATEWAY_BASE_FAMILY'),
    capacityProvider: required('GATEWAY_CAPACITY_PROVIDER'),
    servicePrefix: required('GATEWAY_SERVICE_PREFIX'),
    addressTag: required('GATEWAY_ADDRESS_TAG'),
  };
}

const lambdaDeps: Omit<ReconcileDeps, 'names'> = {
  async liveGateways() {
    // The registry's own rows, read through its API.
    const res = await fetch(`${await registryApiUrl()}gateways?${new URLSearchParams({ mode: 'live' })}`, {
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
    if (!item || Number(item.expires_at?.N ?? 0) * 1000 <= nowMs) return null;
    return {
      state: item.state?.S ?? '',
      linkedSessions: Number(item.linked_sessions?.N ?? 0),
      workingOrders: Number(item.working_orders?.N ?? 0),
    };
  },
  latestVersion: () => parameter(LATEST_VERSION_PARAMETER),
  alertsApiUrl: () => parameter(ALERTS_API_URL_PARAMETER),
  ecs: (region) => new ECSClient({ region }),
  ec2: (region) => new EC2Client({ region }),
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
async function publish(action: 'FIRING' | 'RESOLVED', key: string, type: string, alert?: Alert) {
  const detail = {
    action, key, type, source: 'ingest', mode: 'live',
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
  for (const [key, alert] of firing) await publish('FIRING', key, alert.type, alert);
  for (const [key, type] of Object.entries(previous.firing)) {
    if (!firing.has(key)) await publish('RESOLVED', key, type);
  }
  await saveAlertState(state);
};
