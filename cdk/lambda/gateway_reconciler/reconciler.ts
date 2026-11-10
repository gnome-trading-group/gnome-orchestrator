import { APIGatewayClient, GetApiKeyCommand } from '@aws-sdk/client-api-gateway';
import { DynamoDBClient, GetItemCommand } from '@aws-sdk/client-dynamodb';
import { AllocateAddressCommand, DescribeAddressesCommand, EC2Client } from '@aws-sdk/client-ec2';
import {
  CreateServiceCommand, DescribeServicesCommand, DescribeTaskDefinitionCommand, ECSClient, KeyValuePair,
  RegisterTaskDefinitionCommand, UpdateServiceCommand,
} from '@aws-sdk/client-ecs';
import { GetParameterCommand, SSMClient } from '@aws-sdk/client-ssm';
import { platformRegions } from '@gnome-trading-group/gnome-shared-cdk/dist/regions';

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
// Where the registry API is (gnome-registry's ApiStack publishes it).
const REGISTRY_API_URL_PARAMETER = '/gnome/registry/api-url';
// The table gateways write their endpoint to, in its one region (gnome-shared-cdk SERVICE_ENDPOINTS).
const ENDPOINTS_TABLE = 'service-endpoints';
const ENDPOINTS_REGION = 'us-east-1';

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
  ecs(region: string): Client;
  ec2(region: string): Client;
  nowMs(): number;
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
      outcomes.set(row.gateway_key, await reconcileOne(row, deps));
    } catch (error) {
      console.error(`Gateway ${row.gateway_key}: couldn't reconcile`, error);
    }
  }
  return outcomes;
}

async function reconcileOne(row: GatewayRow, deps: ReconcileDeps): Promise<Outcome> {
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
  const wanted = shouldRun(row, await deps.activity(row.gateway_key, now), running, now);
  if (!wanted) {
    if (!running) return 'stopped already';
    await ecs.send(new UpdateServiceCommand({ cluster: names.cluster, service, desiredCount: 0 }));
    return 'stopped';
  }
  // A running gateway keeps its release: a new pin or release reaches it at its next start.
  if (running) return 'running';
  const version = row.orchestrator_version ?? await deps.latestVersion();
  await ensureAddress(deps.ec2(row.region), names, row.gateway_key);
  const taskDefinition = await revision(ecs, names, row.gateway_key, version);
  if (current) {
    await ecs.send(new UpdateServiceCommand({ cluster: names.cluster, service, taskDefinition, desiredCount: 1 }));
    return 'started';
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
  return 'created';
}

/** The gateway's own revision: the base task definition as deployed, with its key and release; reused when unchanged. */
async function revision(ecs: Client, names: GatewayNames, gatewayKey: string, version: string): Promise<string> {
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
        ...(container.environment ?? []).filter((e: KeyValuePair) =>
          e.name !== 'GATEWAY_KEY' && e.name !== 'ORCHESTRATOR_VERSION'),
        { name: 'GATEWAY_KEY', value: gatewayKey },
        { name: 'ORCHESTRATOR_VERSION', value: version },
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
  ecs: (region) => new ECSClient({ region }),
  ec2: (region) => new EC2Client({ region }),
  nowMs: () => Date.now(),
};

/** Woken by the queue (a wake-up from gateways/ensure, or the minute's tick); every message means one tick. */
export const handler = async () => {
  const outcomes = await reconcile({ ...lambdaDeps, names: environmentNames() });
  for (const [gateway, outcome] of outcomes) {
    if (outcome !== 'running' && outcome !== 'stopped already') console.log(`Gateway ${gateway}: ${outcome}`);
  }
};
