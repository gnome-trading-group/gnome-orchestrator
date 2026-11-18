import { Observation } from '../lambda/gateway_reconciler/alerts';
import {
  Activity, GatewayNames, GatewayRow, IDLE_POLLS, IDLE_POLLS_PER_TICK, reconcile, ReconcileDeps, serviceName as nameOf,
  shouldRun, WANT_WINDOW_MS,
} from '../lambda/gateway_reconciler/reconciler';
import { MIN_ROLL_VERSION, Roll, ROLL_READY_MS } from '../lambda/gateway_reconciler/roll';
import { ORDER_GATEWAYS, zoneCapacityProvider } from '../lib/config';

const NAMES: GatewayNames = ORDER_GATEWAYS;
const { cluster: CLUSTER, addressTag: ADDRESS_TAG } = NAMES;
const LIVE = ORDER_GATEWAYS.modes.live;
const PAPER = ORDER_GATEWAYS.modes.paper;
const serviceName = (key: string) => nameOf(NAMES, key);
// Each capacity provider's Auto Scaling group, by its name in the group's ARN.
const HOME = 'use2-az2';
const LIVE_HOME = zoneCapacityProvider(LIVE.capacityProvider, HOME);
const PAPER_HOME = zoneCapacityProvider(PAPER.capacityProvider, HOME);
const GROUPS: Record<string, string> = {
  [LIVE.capacityProvider]: 'GatewayHostsASG',
  [PAPER.capacityProvider]: 'GatewayPaperHostsASG',
  [LIVE_HOME]: 'Gatewayuse2az2HostsASG',
  [PAPER_HOME]: 'GatewayPaperuse2az2HostsASG',
};

type Service = {
  serviceName: string; status: string; desiredCount: number; taskDefinition?: string; runningCount?: number;
  pendingCount?: number; placementConstraints?: { type: string; expression: string }[];
  deployments?: { taskDefinition?: string }[];
  capacityProviderStrategy?: { capacityProvider: string; weight: number }[];
};

/** A gateway service as the reconciler creates it, on its mode's capacity provider. */
function service(key: string, desiredCount: number): Service {
  const provider = key.startsWith('paper:') ? PAPER.capacityProvider : LIVE.capacityProvider;
  return {
    serviceName: serviceName(key), status: 'ACTIVE', desiredCount,
    capacityProviderStrategy: [{ capacityProvider: provider, weight: 1 }],
  };
}

// What ECS and EC2 hold in one region, and every command each was sent, by name.
class FakeRegion {
  services: Service[] = [];
  addresses: { AllocationId: string; Tags: { Key: string; Value: string }[] }[] = [];
  revisions = new Map<string, any[]>();
  sent: { command: string; input: any }[] = [];
  stoppedTasks: {
    taskArn: string; stoppedAt: string; stoppedReason: string; containers: { exitCode?: number }[]; createdAt?: string;
  }[] = [];
  runningTasks: { taskArn: string; containerInstanceArn: string; lastStatus?: string; createdAt?: string }[] = [];
  // The cluster's hosts, by container instance ARN.
  hostsInCluster: { containerInstanceArn: string; ec2InstanceId: string; status: string }[] = [];
  // The live and the paper capacity providers' Auto Scaling groups.
  hosts = { DesiredCapacity: 0, MaxSize: 4 };
  paperHosts = { DesiredCapacity: 0, MaxSize: 4 };
  // The home zone's live and paper groups.
  zoneHosts = { DesiredCapacity: 0, MaxSize: 4 };
  zonePaperHosts = { DesiredCapacity: 0, MaxSize: 4 };
  hostsFail = false;
  // A region whose gateway stack isn't deployed yet.
  noCluster = false;

  group(name: string) {
    if (name === GROUPS[LIVE_HOME]) return this.zoneHosts;
    if (name === GROUPS[PAPER_HOME]) return this.zonePaperHosts;
    return name === GROUPS[PAPER.capacityProvider] ? this.paperHosts : this.hosts;
  }

  ecs = {
    send: async (command: any) => {
      const name = command.constructor.name;
      this.sent.push({ command: name, input: command.input });
      if (name === 'DescribeCapacityProvidersCommand') {
        const group = GROUPS[command.input.capacityProviders[0]];
        return { capacityProviders: [{ autoScalingGroupProvider: {
          autoScalingGroupArn: `arn:aws:autoscaling:us-east-1:1:autoScalingGroup:uuid:autoScalingGroupName/${group}`,
        } }] };
      }
      if (name === 'ListServicesCommand') {
        return { serviceArns: this.services.map((s) => `arn:aws:ecs:us-east-1:1:service/${CLUSTER}/${s.serviceName}`) };
      }
      if (name === 'DescribeServicesCommand') {
        if (this.noCluster) throw Object.assign(new Error('Cluster not found.'), { name: 'ClusterNotFoundException' });
        return { services: this.services.filter((s) => command.input.services.includes(s.serviceName)) };
      }
      if (name === 'DescribeTaskDefinitionCommand') {
        if (command.input.taskDefinition === LIVE.baseFamily) return { taskDefinition: BASE };
        if (command.input.taskDefinition === PAPER.baseFamily) return { taskDefinition: PAPER_BASE };
        const registered = this.revisions.get(command.input.taskDefinition);
        if (!registered) throw Object.assign(new Error('Unable to describe task definition.'), { name: 'ClientException' });
        return { taskDefinition: registered[registered.length - 1] };
      }
      if (name === 'RegisterTaskDefinitionCommand') {
        const family = command.input.family;
        const list = this.revisions.get(family) ?? [];
        const revision = { ...command.input, taskDefinitionArn: `${family}:${list.length + 1}` };
        list.push(revision);
        this.revisions.set(family, list);
        return { taskDefinition: revision };
      }
      if (name === 'CreateServiceCommand') {
        this.services.push({
          serviceName: command.input.serviceName, status: 'ACTIVE', desiredCount: 1, taskDefinition: command.input.taskDefinition,
          capacityProviderStrategy: command.input.capacityProviderStrategy,
        });
        return {};
      }
      if (name === 'ListTasksCommand') {
        const tasks = command.input.desiredStatus === 'RUNNING' ? this.runningTasks : this.stoppedTasks;
        return { taskArns: tasks.map((t) => t.taskArn) };
      }
      if (name === 'DescribeTasksCommand') {
        const all = [...this.stoppedTasks, ...this.runningTasks] as any[];
        // By ARN or by id, as ECS takes either.
        return { tasks: all.filter((t) => command.input.tasks.some((id: string) => t.taskArn === id || t.taskArn.endsWith(`/${id}`))) };
      }
      if (name === 'DescribeContainerInstancesCommand') {
        return { containerInstances: this.hostsInCluster.filter((h) =>
          command.input.containerInstances.includes(h.containerInstanceArn)) };
      }
      if (name === 'ListContainerInstancesCommand') {
        const wanted = /ec2InstanceId == (\S+)/.exec(command.input.filter)![1];
        return { containerInstanceArns: this.hostsInCluster
          .filter((h) => h.ec2InstanceId === wanted && h.status === command.input.status)
          .map((h) => h.containerInstanceArn) };
      }
      if (name === 'UpdateServiceCommand') {
        const service = this.services.find((s) => s.serviceName === command.input.service)!;
        if (command.input.desiredCount !== undefined) service.desiredCount = command.input.desiredCount;
        if (command.input.placementConstraints !== undefined) service.placementConstraints = command.input.placementConstraints;
        if (command.input.capacityProviderStrategy !== undefined) {
          service.capacityProviderStrategy = command.input.capacityProviderStrategy;
        }
        if (command.input.taskDefinition && command.input.taskDefinition !== service.taskDefinition) {
          // As ECS does: the old deployment stays until it has drained.
          service.deployments = [{ taskDefinition: command.input.taskDefinition }, { taskDefinition: service.taskDefinition }];
          service.taskDefinition = command.input.taskDefinition;
        }
        return {};
      }
      throw new Error(`unexpected ${name}`);
    },
  };

  ec2 = {
    send: async (command: any) => {
      const name = command.constructor.name;
      this.sent.push({ command: name, input: command.input });
      if (name === 'DescribeAddressesCommand') {
        const wanted = command.input.Filters[0].Values[0];
        return { Addresses: this.addresses.filter((a) => a.Tags.some((t) => t.Key === ADDRESS_TAG && t.Value === wanted)) };
      }
      if (name === 'AllocateAddressCommand') {
        this.addresses.push({ AllocationId: `eipalloc-${this.addresses.length + 1}`, Tags: command.input.TagSpecifications[0].Tags });
        return {};
      }
      throw new Error(`unexpected ${name}`);
    },
  };

  autoscaling = {
    send: async (command: any) => {
      const name = command.constructor.name;
      this.sent.push({ command: name, input: command.input });
      if (this.hostsFail) throw new Error('throttled');
      if (name === 'DescribeAutoScalingGroupsCommand') {
        const group = command.input.AutoScalingGroupNames[0];
        return { AutoScalingGroups: [{ AutoScalingGroupName: group, ...this.group(group) }] };
      }
      if (name === 'SetDesiredCapacityCommand') {
        this.group(command.input.AutoScalingGroupName).DesiredCapacity = command.input.DesiredCapacity;
        return {};
      }
      throw new Error(`unexpected ${name}`);
    },
  };

  /** Every service's old deployments gone, as once ECS has drained them. */
  drain() {
    for (const service of this.services) service.deployments = [{ taskDefinition: service.taskDefinition }];
  }

  commands(): string[] {
    return this.sent.map((s) => s.command);
  }
}

const BASE = {
  family: LIVE.baseFamily,
  taskRoleArn: 'arn:task',
  executionRoleArn: 'arn:exec',
  networkMode: 'host',
  volumes: [{ name: 'state', host: { sourcePath: '/var/lib/gnome/gateway' } }],
  requiresCompatibilities: ['EC2'],
  containerDefinitions: [{
    name: 'gateway',
    image: 'image:1',
    memoryReservation: 2048,
    environment: [{ name: 'STAGE', value: 'dev' }],
  }],
};

const PAPER_BASE = {
  ...BASE,
  family: PAPER.baseFamily,
  taskRoleArn: 'arn:paper-task',
  containerDefinitions: [{ ...BASE.containerDefinitions[0], memoryReservation: 1536 }],
};

const NOW = Date.parse('2026-11-10T12:00:00Z');
const ago = (ms: number) => new Date(NOW - ms).toISOString();

function row(key: string, region: string, wantedAgoMs: number | null, version: string | null = null): GatewayRow {
  return {
    gateway_key: key, mode: key.split(':')[0], region, orchestrator_version: version,
    wanted_at: wantedAgoMs == null ? null : ago(wantedAgoMs),
  };
}

// The gateways' roll items, as the reconciler last wrote them.
const rolls = new Map<string, Roll>();
// What a test does when the reconciler waits within a tick (an old task stopping, say).
let onSleep: (() => void) | undefined;

function deps(rows: GatewayRow[], regions: Record<string, FakeRegion>, activity: Record<string, Activity | null> = {}): ReconcileDeps {
  // Every platform region is looked in; one a test doesn't name holds nothing.
  const at = (region: string) => (regions[region] ??= new FakeRegion());
  return {
    roll: async (key) => rolls.get(key) ?? null,
    saveRoll: async (key, roll) => {
      rolls.set(key, roll);
    },
    sleep: async () => {
      onSleep?.();
    },
    names: NAMES,
    gateways: async () => rows,
    activity: async (key) => activity[key] ?? null,
    latestVersion: async () => '1.32.1',
    alertsApiUrl: async () => 'https://alerts.example/',
    marketDataApi: async () => ({ url: 'https://market-data.example', keySecret: 'gnome/api-keys/market-data' }),
    ecs: (region) => at(region).ecs,
    ec2: (region) => at(region).ec2,
    autoscaling: (region) => at(region).autoscaling,
    nowMs: () => NOW,
  };
}

const KALSHI = 'live:kalshi:main';
const INTL = 'live:polymarket_intl:main';
const PAPER_KALSHI = 'paper:kalshi:main';
const idle: Activity = { state: 'READY', linkedSessions: 0, workingOrders: 0 };

describe('which gateways should run', () => {
  it('one wanted within the window, or in use, or running and saying nothing of itself', () => {
    expect(shouldRun(row(KALSHI, 'us-east-1', 60_000), idle, true, NOW)).toBe(true);
    expect(shouldRun(row(KALSHI, 'us-east-1', WANT_WINDOW_MS + 1), idle, true, NOW)).toBe(false);
    expect(shouldRun(row(KALSHI, 'us-east-1', null), { ...idle, linkedSessions: 1 }, true, NOW)).toBe(true);
    expect(shouldRun(row(KALSHI, 'us-east-1', null), { ...idle, workingOrders: 3 }, true, NOW)).toBe(true);
    // Gone quiet while it had orders, and restarting: it may hold orders nobody else would cancel.
    const wentQuiet = { ...idle, workingOrders: 2, expired: true };
    expect(shouldRun(row(KALSHI, 'us-east-1', null), wentQuiet, true, NOW)).toBe(true);
    expect(shouldRun(row(KALSHI, 'us-east-1', null), wentQuiet, false, NOW)).toBe(false);
    // Never said anything (it never came up), or went quiet with nothing: it holds nothing.
    expect(shouldRun(row(KALSHI, 'us-east-1', null), null, true, NOW)).toBe(false);
    expect(shouldRun(row(KALSHI, 'us-east-1', null), { ...idle, expired: true }, true, NOW)).toBe(false);
  });
});

describe('the gateway reconciler', () => {
  it('creates a wanted gateway that never ran, in its exchange\'s region, on the latest release, with an address', async () => {
    const east = new FakeRegion();
    const west = new FakeRegion();

    const outcomes = await reconcile(deps([row(KALSHI, 'us-east-1', 5_000)], { 'us-east-1': east, 'eu-west-1': west }));

    expect(outcomes.get(KALSHI)).toBe('created');
    // Looked in, for a copy of it left from before a move, and nothing changed there.
    expect(west.commands()).toEqual(['DescribeServicesCommand']);
    const registered = east.sent.find((s) => s.command === 'RegisterTaskDefinitionCommand')!.input;
    expect(registered.family).toBe('order-gateway-live-kalshi-main');
    expect(registered.containerDefinitions[0].environment).toEqual([
      { name: 'STAGE', value: 'dev' },
      { name: 'GATEWAY_KEY', value: KALSHI },
      { name: 'ORCHESTRATOR_VERSION', value: '1.32.1' },
      { name: 'ALERTS_API_URL', value: 'https://alerts.example/' },
    ]);
    expect(east.sent.find((s) => s.command === 'CreateServiceCommand')!.input).toMatchObject({
      cluster: CLUSTER,
      serviceName: serviceName(KALSHI),
      taskDefinition: 'order-gateway-live-kalshi-main:1',
      desiredCount: 1,
      capacityProviderStrategy: [{ capacityProvider: LIVE.capacityProvider, weight: 1 }],
    });
    expect(east.addresses).toHaveLength(1);
  });

  it('creates a wanted paper gateway on the paper hosts, from the paper base, reading leases, with no address', async () => {
    const east = new FakeRegion();

    const outcomes = await reconcile(deps([row(PAPER_KALSHI, 'us-east-1', 5_000)], { 'us-east-1': east }));

    expect(outcomes.get(PAPER_KALSHI)).toBe('created');
    const registered = east.sent.find((s) => s.command === 'RegisterTaskDefinitionCommand')!.input;
    expect(registered).toMatchObject({ family: 'order-gateway-paper-kalshi-main', taskRoleArn: 'arn:paper-task' });
    expect(registered.containerDefinitions[0].memoryReservation).toBe(1536);
    expect(registered.containerDefinitions[0].environment).toEqual([
      { name: 'STAGE', value: 'dev' },
      { name: 'GATEWAY_KEY', value: PAPER_KALSHI },
      { name: 'ORCHESTRATOR_VERSION', value: '1.32.1' },
      { name: 'ALERTS_API_URL', value: 'https://alerts.example/' },
      { name: 'MARKET_DATA_API_URL', value: 'https://market-data.example' },
      { name: 'MARKET_DATA_API_KEY_SECRET', value: 'gnome/api-keys/market-data' },
    ]);
    expect(east.sent.find((s) => s.command === 'CreateServiceCommand')!.input).toMatchObject({
      serviceName: serviceName(PAPER_KALSHI),
      taskDefinition: 'order-gateway-paper-kalshi-main:1',
      capacityProviderStrategy: [{ capacityProvider: PAPER.capacityProvider, weight: 1 }],
    });
    expect(east.commands()).not.toContain('DescribeAddressesCommand');
    expect(east.addresses).toEqual([]);
  });

  it('runs a live and a paper gateway of one venue side by side, each from its own base', async () => {
    const east = new FakeRegion();

    await reconcile(deps([row(KALSHI, 'us-east-1', 5_000), row(PAPER_KALSHI, 'us-east-1', 5_000)], { 'us-east-1': east }));

    expect(east.services.map((s) => [s.serviceName, s.capacityProviderStrategy?.[0].capacityProvider])).toEqual([
      ['order-gateway-live-kalshi-main', LIVE.capacityProvider],
      ['order-gateway-paper-kalshi-main', PAPER.capacityProvider],
    ]);
    const families = east.sent.filter((s) => s.command === 'DescribeTaskDefinitionCommand').map((s) => s.input.taskDefinition);
    expect(families).toContain(LIVE.baseFamily);
    expect(families).toContain(PAPER.baseFamily);
    // Only the live one has an address.
    expect(east.addresses.map((a) => a.Tags[0].Value)).toEqual([KALSHI]);
  });

  it('tells what it saw of each gateway with its row\'s mode, for its alerts', async () => {
    const east = new FakeRegion();
    const seen: Observation[] = [];
    const errors = jest.spyOn(console, 'error').mockImplementation(() => undefined);

    await reconcile({
      ...deps([row(KALSHI, 'us-east-1', 5_000), row(PAPER_KALSHI, 'us-east-1', 5_000), row('paper:nowhere:main', 'sa-east-1', 5_000)],
        { 'us-east-1': east }),
      observed: (o) => seen.push(o),
    });

    expect(seen.map((o) => [o.gatewayKey, o.mode, o.error === undefined])).toEqual([
      [KALSHI, 'live', true], [PAPER_KALSHI, 'paper', true], ['paper:nowhere:main', 'paper', false],
    ]);
    errors.mockRestore();
  });

  it('starts a stopped gateway on its pinned release, reusing its revision and its address', async () => {
    const west = new FakeRegion();
    west.services.push(service(INTL, 0));
    west.addresses.push({ AllocationId: 'eipalloc-9', Tags: [{ Key: ADDRESS_TAG, Value: INTL }] });
    // Started on this release before, from the same base.
    await reconcile(deps([row(INTL, 'eu-west-1', 5_000, '1.30.0')], { 'eu-west-1': west }));
    west.drain();
    west.services[0].desiredCount = 0;
    west.sent = [];

    const outcomes = await reconcile(deps([row(INTL, 'eu-west-1', 5_000, '1.30.0')], { 'eu-west-1': west }));

    expect(outcomes.get(INTL)).toBe('started');
    expect(west.services[0]).toMatchObject({ desiredCount: 1, taskDefinition: 'order-gateway-live-polymarket_intl-main:1' });
    expect(west.commands()).not.toContain('RegisterTaskDefinitionCommand');
    expect(west.commands()).not.toContain('AllocateAddressCommand');
    expect(west.sent.filter((s) => s.command === 'UpdateServiceCommand').map((s) => s.input))
      .toEqual([{ cluster: CLUSTER, service: serviceName(INTL), desiredCount: 1 }]);
  });

  it('wants a gateway on a new revision only once the old deployment has drained, so no task starts on it', async () => {
    const east = new FakeRegion();
    await reconcile(deps([row(KALSHI, 'us-east-1', 5_000, '1.32.0')], { 'us-east-1': east }));
    east.services[0].desiredCount = 0;
    const tick = async () => {
      east.sent = [];
      const outcome = (await reconcile(deps([row(KALSHI, 'us-east-1', 5_000, '1.32.1')], { 'us-east-1': east }))).get(KALSHI);
      return { outcome, updates: east.sent.filter((s) => s.command === 'UpdateServiceCommand').map((s) => s.input) };
    };

    expect(await tick()).toEqual({
      outcome: 'starting',
      updates: [{
        cluster: CLUSTER, service: serviceName(KALSHI), taskDefinition: 'order-gateway-live-kalshi-main:2', desiredCount: 0,
        placementConstraints: [],
      }],
    });
    expect(await tick()).toEqual({ outcome: 'starting', updates: [] });
    east.drain();
    expect(await tick()).toEqual({
      outcome: 'started', updates: [{ cluster: CLUSTER, service: serviceName(KALSHI), desiredCount: 1 }],
    });
    expect(east.services[0]).toMatchObject({ desiredCount: 1, taskDefinition: 'order-gateway-live-kalshi-main:2' });
  });

  it('says why a running gateway that isn\'t up last stopped, from its newest stopped task', async () => {
    const east = new FakeRegion();
    east.services.push(service(KALSHI, 1));
    east.stoppedTasks.push(
      { taskArn: 't-old', stoppedAt: ago(120_000), stoppedReason: 'Scaling activity', containers: [{ exitCode: 143 }] },
      { taskArn: 't-new', stoppedAt: ago(10_000), stoppedReason: 'Essential container in task exited', containers: [{ exitCode: 22 }] },
    );
    const seen: Observation[] = [];
    const up: Observation[] = [];

    await reconcile({ ...deps([row(KALSHI, 'us-east-1', 5_000)], { 'us-east-1': east }), observed: (o) => seen.push(o) });
    await reconcile({
      ...deps([row(KALSHI, 'us-east-1', 5_000)], { 'us-east-1': east }, { [KALSHI]: idle }), observed: (o) => up.push(o),
    });

    expect(seen[0].stoppedReason).toBe('Essential container in task exited, exit 22');
    expect(up[0].stoppedReason).toBeUndefined();
  });

  it('registers a new revision when the release changed since the gateway last ran', async () => {
    const east = new FakeRegion();
    await reconcile(deps([row(KALSHI, 'us-east-1', 5_000, '1.32.0')], { 'us-east-1': east }));
    east.services[0].desiredCount = 0;

    await reconcile(deps([row(KALSHI, 'us-east-1', 5_000, '1.32.1')], { 'us-east-1': east }));

    expect(east.services[0].taskDefinition).toBe('order-gateway-live-kalshi-main:2');
    expect(east.revisions.get('order-gateway-live-kalshi-main')).toHaveLength(2);
  });

  it('leaves a running gateway as it is while it is wanted or in use', async () => {
    const east = new FakeRegion();
    east.services.push(service(KALSHI, 1));

    const outcomes = await reconcile(deps(
      [row(KALSHI, 'us-east-1', WANT_WINDOW_MS * 2)], { 'us-east-1': east }, { [KALSHI]: { ...idle, linkedSessions: 2 } }));

    expect(outcomes.get(KALSHI)).toBe('running');
    // Only reads: its running task, to tell a restart nobody asked for from one this reconciler made.
    expect(east.commands()).toEqual(['DescribeServicesCommand', 'ListTasksCommand']);
  });

  it('stops a gateway nobody has wanted for the window and that holds no session or order', async () => {
    const east = new FakeRegion();
    east.services.push(service(KALSHI, 1));

    const outcomes = await reconcile(deps(
      [row(KALSHI, 'us-east-1', WANT_WINDOW_MS + 1)], { 'us-east-1': east }, { [KALSHI]: idle }));

    expect(outcomes.get(KALSHI)).toBe('stopped');
    expect(east.services[0].desiredCount).toBe(0);
  });

  it('keeps a running gateway gone quiet with orders working, as it may be restarting with them', async () => {
    const east = new FakeRegion();
    east.services.push(service(KALSHI, 1));

    const outcomes = await reconcile(deps(
      [row(KALSHI, 'us-east-1', null)], { 'us-east-1': east }, { [KALSHI]: { ...idle, workingOrders: 2, expired: true } }));

    expect(outcomes.get(KALSHI)).toBe('running');
    expect(east.services[0].desiredCount).toBe(1);
  });

  it('stops an unwanted gateway that never came up, however long it has been failing', async () => {
    const east = new FakeRegion();
    east.services.push(service(KALSHI, 1));

    const outcomes = await reconcile(deps([row(KALSHI, 'us-east-1', WANT_WINDOW_MS + 1)], { 'us-east-1': east }));

    expect(outcomes.get(KALSHI)).toBe('stopped');
    expect(east.services[0].desiredCount).toBe(0);
  });

  it('tries every gateway even when one fails, and leaves the failed one for the next tick', async () => {
    const east = new FakeRegion();
    const outcomes = await reconcile(deps(
      [row('live:somewhere:main', 'sa-east-1', 5_000), row(KALSHI, 'us-east-1', 5_000)], { 'us-east-1': east }));

    expect(outcomes.has('live:somewhere:main')).toBe(false);
    expect(outcomes.get(KALSHI)).toBe('created');
  });

  it('runs nothing for a mode it has no hosts or base for', async () => {
    const east = new FakeRegion();
    const errors = jest.spyOn(console, 'error').mockImplementation(() => undefined);

    const outcomes = await reconcile(deps(
      [{ ...row('backtest:kalshi:main', 'us-east-1', 5_000), mode: 'backtest' }, row(KALSHI, 'us-east-1', 5_000)],
      { 'us-east-1': east }));

    expect(outcomes.has('backtest:kalshi:main')).toBe(false);
    expect(outcomes.get(KALSHI)).toBe('created');
    expect(east.services.map((s) => s.serviceName)).toEqual([serviceName(KALSHI)]);
    errors.mockRestore();
  });
});

describe('a gateway whose row moved region', () => {
  it('stops it in the old region and starts it in the new one only once nothing of it runs in the old', async () => {
    const east = new FakeRegion();
    const ohio = new FakeRegion();
    east.services.push({ ...service(KALSHI, 1), runningCount: 1 });
    const regions = { 'us-east-1': east, 'us-east-2': ohio };
    const tick = async () => (await reconcile(deps([row(KALSHI, 'us-east-2', 5_000)], regions))).get(KALSHI);

    expect(await tick()).toBe('moving');
    expect(east.services[0].desiredCount).toBe(0);
    expect(ohio.commands()).not.toContain('CreateServiceCommand');

    // ECS is still stopping the old task, whose planned stop cancels its orders.
    expect(await tick()).toBe('moving');
    expect(east.commands().filter((c) => c === 'UpdateServiceCommand')).toHaveLength(1);
    expect(ohio.commands()).not.toContain('CreateServiceCommand');

    east.services[0].runningCount = 0;
    expect(await tick()).toBe('created');
    expect(ohio.services.map((s) => s.serviceName)).toEqual([serviceName(KALSHI)]);
    expect(ohio.addresses).toHaveLength(1);
    expect(east.services[0].desiredCount).toBe(0);
  });

  it('stops it in the old region even while nobody wants it in the new one', async () => {
    const east = new FakeRegion();
    east.services.push({ ...service(KALSHI, 1), runningCount: 1 });
    const outcomes = await reconcile(deps([row(KALSHI, 'us-east-2', WANT_WINDOW_MS + 1)], { 'us-east-1': east }));
    expect(outcomes.get(KALSHI)).toBe('stopped already');
    expect(east.services[0].desiredCount).toBe(0);
  });

  it('takes a region with no gateway cluster yet to hold nothing of it', async () => {
    const ohio = new FakeRegion();
    ohio.noCluster = true;
    const east = new FakeRegion();
    const outcomes = await reconcile(deps([row(KALSHI, 'us-east-1', 5_000)], { 'us-east-1': east, 'us-east-2': ohio }));
    expect(outcomes.get(KALSHI)).toBe('created');
  });
});

describe('the gateway reconciler\'s hosts', () => {
  const sets = (region: FakeRegion) => region.sent.filter((s) => s.command === 'SetDesiredCapacityCommand').map((s) => s.input);

  it('asks for a host at once when it starts a gateway in a region that has none', async () => {
    const west = new FakeRegion();

    expect((await reconcile(deps([row(INTL, 'eu-west-1', 5_000)], { 'eu-west-1': west }))).get(INTL)).toBe('created');

    expect(sets(west)).toEqual([{ AutoScalingGroupName: 'GatewayHostsASG', DesiredCapacity: 1, HonorCooldown: false }]);
  });

  it('asks for one host per gateway set to run, the same when a stopped one starts again', async () => {
    const east = new FakeRegion();
    east.services.push(service('live:polymarket_us:main', 1));
    east.hosts.DesiredCapacity = 1;

    await reconcile(deps([row(KALSHI, 'us-east-1', 5_000)], { 'us-east-1': east }));
    expect(east.hosts.DesiredCapacity).toBe(2);

    east.drain();
    east.services.find((s) => s.serviceName === serviceName(KALSHI))!.desiredCount = 0;
    east.hosts.DesiredCapacity = 1;
    east.sent = [];
    expect((await reconcile(deps([row(KALSHI, 'us-east-1', 5_000)], { 'us-east-1': east }))).get(KALSHI)).toBe('started');
    expect(sets(east)).toEqual([{ AutoScalingGroupName: 'GatewayHostsASG', DesiredCapacity: 2, HonorCooldown: false }]);
  });

  it('raises only the group of the gateway\'s mode, counting only the gateways on it', async () => {
    const east = new FakeRegion();
    east.services.push(service('live:polymarket_us:main', 1));
    east.hosts.DesiredCapacity = 1;

    await reconcile(deps([row(PAPER_KALSHI, 'us-east-1', 5_000)], { 'us-east-1': east }));
    expect(sets(east)).toEqual([{ AutoScalingGroupName: 'GatewayPaperHostsASG', DesiredCapacity: 1, HonorCooldown: false }]);
    expect(east.hosts.DesiredCapacity).toBe(1);

    east.sent = [];
    await reconcile(deps([row(KALSHI, 'us-east-1', 5_000)], { 'us-east-1': east }));
    expect(sets(east)).toEqual([{ AutoScalingGroupName: 'GatewayHostsASG', DesiredCapacity: 2, HonorCooldown: false }]);
    expect(east.paperHosts.DesiredCapacity).toBe(1);
  });

  it('gives a live and a paper gateway started in one tick a host each, from their own groups', async () => {
    const east = new FakeRegion();

    await reconcile(deps([row(KALSHI, 'us-east-1', 5_000), row(PAPER_KALSHI, 'us-east-1', 5_000)], { 'us-east-1': east }));

    expect(east.hosts.DesiredCapacity).toBe(1);
    expect(east.paperHosts.DesiredCapacity).toBe(1);
  });

  it('leaves more hosts than it needs alone, and never asks past the group\'s maximum', async () => {
    const roomy = new FakeRegion();
    roomy.hosts.DesiredCapacity = 3;
    await reconcile(deps([row(KALSHI, 'us-east-1', 5_000)], { 'us-east-1': roomy }));
    expect(sets(roomy)).toEqual([]);
    expect(roomy.hosts.DesiredCapacity).toBe(3);

    const full = new FakeRegion();
    full.hosts = { DesiredCapacity: 0, MaxSize: 0 };
    await reconcile(deps([row(KALSHI, 'us-east-1', 5_000)], { 'us-east-1': full }));
    expect(sets(full)).toEqual([]);
  });

  it('asks for nothing for a gateway it leaves running or stops', async () => {
    const east = new FakeRegion();
    await reconcile(deps([row(KALSHI, 'us-east-1', 5_000)], { 'us-east-1': east }));
    east.sent = [];

    await reconcile(deps([row(KALSHI, 'us-east-1', 5_000)], { 'us-east-1': east }, { [KALSHI]: idle }));
    await reconcile(deps([row(KALSHI, 'us-east-1', WANT_WINDOW_MS + 1)], { 'us-east-1': east }, { [KALSHI]: idle }));

    expect(east.commands()).not.toContain('DescribeAutoScalingGroupsCommand');
    expect(east.commands()).not.toContain('SetDesiredCapacityCommand');
  });

  it('still starts the gateway when the hosts can\'t be raised, leaving them to managed scaling', async () => {
    const east = new FakeRegion();
    east.hostsFail = true;
    const errors = jest.spyOn(console, 'error').mockImplementation(() => undefined);

    const outcomes = await reconcile(deps([row(KALSHI, 'us-east-1', 5_000)], { 'us-east-1': east }));

    expect(outcomes.get(KALSHI)).toBe('created');
    expect(east.services[0].desiredCount).toBe(1);
    expect(errors).toHaveBeenCalledWith(expect.stringContaining('couldn\'t raise them'), expect.any(Error));
    errors.mockRestore();
  });
});

describe('rolling a gateway', () => {
  const FROM = MIN_ROLL_VERSION;
  const TO = '9.1.0';
  const OLD_REVISION = 'order-gateway-live-kalshi-main:7';
  const NEW_REVISION = 'order-gateway-live-kalshi-main:1';
  const PIN = [{ type: 'memberOf', expression: 'ec2InstanceId == i-0abc' }];
  const ASKED_AGO = 60_000;

  beforeEach(() => {
    rolls.clear();
    onSleep = undefined;
  });

  /** A live gateway running one task on host i-0abc. */
  function running(region: FakeRegion, key = KALSHI, taskDefinition = OLD_REVISION) {
    region.services.push({
      ...service(key, 1), taskDefinition, runningCount: 1, pendingCount: 0, deployments: [{ taskDefinition }],
    });
    region.runningTasks = [{
      taskArn: `arn:aws:ecs:us-east-1:1:task/${CLUSTER}/task-1`, containerInstanceArn: 'arn:ci-1', lastStatus: 'RUNNING',
    }];
    region.hostsInCluster = [{ containerInstanceArn: 'arn:ci-1', ec2InstanceId: 'i-0abc', status: 'ACTIVE' }];
  }
  const upOn = (version: string, writtenAtMs = NOW - 5_000): Activity => ({
    state: 'READY', linkedSessions: 2, workingOrders: 5, version, writtenAtMs, canHandOver: true,
  });
  const asked = (key = KALSHI, version: string | null = TO): GatewayRow =>
    ({ ...row(key, 'us-east-1', 5_000, version), roll_requested_at: ago(ASKED_AGO) });
  // ECS stopping the old task once it is told to, by the reconciler's first wait in the tick.
  const stopsOnWait = (region: FakeRegion) => {
    onSleep = () => stopTask(region);
  };
  // ECS once the old task's container has exited: the service's counts drop and the task is STOPPED.
  const stopTask = (region: FakeRegion) => {
    region.services[0].runningCount = 0;
    region.drain();
    for (const task of region.runningTasks) task.lastStatus = 'STOPPED';
  };
  const updates = (region: FakeRegion) => region.sent.filter((s) => s.command === 'UpdateServiceCommand').map((s) => s.input);
  const underWay = (phase: Roll['phase'], extra: Partial<Roll> = {}): Roll => ({
    requestedAtMs: NOW - ASKED_AGO, phase, instanceId: 'i-0abc', fromVersion: FROM, toVersion: TO,
    fromTaskDefinition: OLD_REVISION, stopsVersion: FROM, startedAtMs: NOW - 50_000, untilMs: NOW + 500_000, ...extra,
  });

  it('writes the roll item before the stop, pins the host, and raises the new revision once the old task has gone', async () => {
    const east = new FakeRegion();
    running(east);
    stopsOnWait(east);
    const timeline: string[] = [];
    const d = deps([asked()], { 'us-east-1': east }, { [KALSHI]: upOn(FROM) });
    const save = d.saveRoll;
    d.saveRoll = async (key, roll, nowMs) => {
      timeline.push(`roll ${roll.phase}`);
      await save(key, roll, nowMs);
    };
    const send = east.ecs.send;
    east.ecs.send = async (command: any) => {
      if (command.constructor.name === 'UpdateServiceCommand') timeline.push(`service ${command.input.desiredCount}`);
      return send(command);
    };

    expect((await reconcile(d)).get(KALSHI)).toBe('rolling');

    expect(timeline).toEqual(['roll stopping', 'service 0', 'service 1', 'roll starting']);
    expect(updates(east)).toEqual([
      { cluster: CLUSTER, service: serviceName(KALSHI), taskDefinition: NEW_REVISION, desiredCount: 0, placementConstraints: PIN },
      { cluster: CLUSTER, service: serviceName(KALSHI), desiredCount: 1 },
    ]);
    expect(rolls.get(KALSHI)).toMatchObject({
      requestedAtMs: NOW - ASKED_AGO, phase: 'starting', instanceId: 'i-0abc', fromVersion: FROM, toVersion: TO,
      fromTaskDefinition: OLD_REVISION, stopsVersion: FROM, raisedAtMs: NOW, untilMs: NOW + 10 * 60_000,
    });
    // Its host is already there.
    expect(east.commands()).not.toContain('DescribeCapacityProvidersCommand');
  });

  it('is done once the gateway is up on the new release, as written by the new process', async () => {
    const east = new FakeRegion();
    running(east, KALSHI, NEW_REVISION);
    rolls.set(KALSHI, underWay('starting', { raisedAtMs: NOW - 30_000 }));

    // The old process's last READY, from before the stop: not the new one yet.
    expect((await reconcile(deps([asked()], { 'us-east-1': east }, { [KALSHI]: upOn(TO, NOW - 40_000) }))).get(KALSHI))
      .toBe('rolling');
    expect(rolls.get(KALSHI)!.phase).toBe('starting');

    expect((await reconcile(deps([asked()], { 'us-east-1': east }, { [KALSHI]: upOn(TO, NOW - 1_000) }))).get(KALSHI))
      .toBe('running');
    expect(rolls.get(KALSHI)).toMatchObject({ phase: 'done', endedAtMs: NOW });
    expect(rolls.get(KALSHI)!.reason).toBeUndefined();

    // The request it took isn't taken again.
    east.sent = [];
    await reconcile(deps([asked()], { 'us-east-1': east }, { [KALSHI]: upOn(TO, NOW - 1_000) }));
    expect(updates(east)).toEqual([]);
  });

  it('waits for the old task itself to stop, not only for the service to stop counting it', async () => {
    const east = new FakeRegion();
    running(east);
    // As ECS does at once when told to stop: the counts drop while the task's process still runs its stop.
    onSleep = () => {
      east.services[0].runningCount = 0;
      east.drain();
    };

    await reconcile(deps([asked()], { 'us-east-1': east }, { [KALSHI]: upOn(FROM) }));
    expect(rolls.get(KALSHI)).toMatchObject({ phase: 'stopping', stoppingTasks: ['task-1'] });
    expect(updates(east).map((u) => u.desiredCount)).toEqual([0]);

    east.runningTasks[0].lastStatus = 'STOPPED';
    await reconcile(deps([asked()], { 'us-east-1': east }, { [KALSHI]: null }));
    expect(rolls.get(KALSHI)).toMatchObject({ phase: 'starting', raisedAtMs: NOW });
  });

  it('rolls onto the release it already runs, as a restart in place', async () => {
    const east = new FakeRegion();
    running(east);
    stopsOnWait(east);

    await reconcile(deps([asked(KALSHI, FROM)], { 'us-east-1': east }, { [KALSHI]: upOn(FROM) }));

    expect(rolls.get(KALSHI)).toMatchObject({ phase: 'starting', fromVersion: FROM, toVersion: FROM });
  });

  it('refuses a roll it can\'t make safely, and goes on as it would have', async () => {
    const cases: [string, (east: FakeRegion) => void, Activity | null, string | null, string][] = [
      ['not running', (east) => {
        east.services.push({ ...service(KALSHI, 0), taskDefinition: OLD_REVISION });
      }, null, TO, "isn't running"],
      ['not up', (east) => running(east), { ...upOn(FROM), state: 'STARTING' }, TO, "isn't up (STARTING)"],
      ['a release that cancels when stopped', (east) => running(east), { ...upOn(FROM), canHandOver: false }, TO,
        "can't hand over"],
      ['two tasks', (east) => {
        running(east);
        east.runningTasks.push({ taskArn: 'arn:task/task-2', containerInstanceArn: 'arn:ci-1' });
      }, upOn(FROM), TO, 'it runs 2 tasks'],
      ['a target older than the first release that hands over', (east) => running(east), upOn(FROM), '2.1.9',
        `older than ${MIN_ROLL_VERSION}`],
    ];
    for (const [what, set, activity, version, reason] of cases) {
      rolls.clear();
      const east = new FakeRegion();
      set(east);
      await reconcile(deps([asked(KALSHI, version)], { 'us-east-1': east }, { [KALSHI]: activity }));
      expect([what, rolls.get(KALSHI)?.phase]).toEqual([what, 'refused']);
      expect(rolls.get(KALSHI)!.reason).toContain(reason);
      expect(updates(east).some((u) => u.placementConstraints?.length)).toBe(false);
    }
  });

  it('keeps the gateway running through the gap, unwanted and silent as it is', async () => {
    const east = new FakeRegion();
    running(east, KALSHI, NEW_REVISION);
    east.runningTasks = [];
    rolls.set(KALSHI, underWay('starting', { raisedAtMs: NOW - 20_000 }));
    const quiet = { ...row(KALSHI, 'us-east-1', null, TO), roll_requested_at: ago(ASKED_AGO) };

    const outcome = (await reconcile(deps([quiet], { 'us-east-1': east }, {
      [KALSHI]: { ...upOn(FROM), state: 'STOPPING', handover: true, expired: true },
    }))).get(KALSHI);

    expect(outcome).toBe('rolling');
    expect(updates(east)).toEqual([]);
  });

  it('leaves the raise to the next tick when the old task outlasts this one', async () => {
    const east = new FakeRegion();
    running(east);
    await reconcile(deps([asked()], { 'us-east-1': east }, { [KALSHI]: upOn(FROM) }));
    expect(rolls.get(KALSHI)).toMatchObject({ phase: 'stopping' });
    expect(rolls.get(KALSHI)!.raisedAtMs).toBeUndefined();
    expect(updates(east).map((u) => u.desiredCount)).toEqual([0]);

    stopTask(east);
    east.sent = [];
    await reconcile(deps([asked()], { 'us-east-1': east }, { [KALSHI]: null }));
    expect(updates(east)).toEqual([{ cluster: CLUSTER, service: serviceName(KALSHI), desiredCount: 1 }]);
    expect(rolls.get(KALSHI)).toMatchObject({ phase: 'starting', raisedAtMs: NOW });
  });

  it('rolls back to the revision it ran before when it isn\'t up in time, handing over the new release\'s process', async () => {
    const east = new FakeRegion();
    running(east, KALSHI, NEW_REVISION);
    rolls.set(KALSHI, underWay('starting', { raisedAtMs: NOW - ROLL_READY_MS }));
    stopsOnWait(east);

    await reconcile(deps([asked()], { 'us-east-1': east }, { [KALSHI]: { ...upOn(TO), state: 'STARTING' } }));

    expect(updates(east)).toEqual([
      { cluster: CLUSTER, service: serviceName(KALSHI), taskDefinition: OLD_REVISION, desiredCount: 0, placementConstraints: PIN },
      { cluster: CLUSTER, service: serviceName(KALSHI), desiredCount: 1 },
    ]);
    expect(rolls.get(KALSHI)).toMatchObject({ phase: 'rolling_back', stopsVersion: TO, raisedAtMs: NOW });
    expect(rolls.get(KALSHI)!.reason).toContain(`not up on ${TO} 5 min`);

    await reconcile(deps([asked()], { 'us-east-1': east }, { [KALSHI]: upOn(FROM, NOW + 1) }));
    expect(rolls.get(KALSHI)).toMatchObject({ phase: 'rolled_back', endedAtMs: NOW });
  });

  it('rolls back on a task that keeps stopping, and fails when the rollback doesn\'t come up either', async () => {
    const east = new FakeRegion();
    running(east, KALSHI, NEW_REVISION);
    rolls.set(KALSHI, underWay('starting', { raisedAtMs: NOW - 60_000 }));
    east.stoppedTasks = [1, 2, 3].map((i) => ({
      taskArn: `t-${i}`, createdAt: ago(50_000 - i * 1_000), stoppedAt: ago(40_000 - i * 1_000),
      stoppedReason: 'Essential container in task exited', containers: [{ exitCode: 1 }],
    }));
    stopsOnWait(east);

    await reconcile(deps([asked()], { 'us-east-1': east }, { [KALSHI]: null }));
    expect(rolls.get(KALSHI)).toMatchObject({ phase: 'rolling_back', reason: `its task stopped 3 times on ${TO}` });

    rolls.set(KALSHI, { ...rolls.get(KALSHI)!, raisedAtMs: NOW - ROLL_READY_MS });
    east.stoppedTasks = [];
    await reconcile(deps([asked()], { 'us-east-1': east }, { [KALSHI]: null }));
    expect(rolls.get(KALSHI)!.phase).toBe('failed');
    expect(rolls.get(KALSHI)!.reason).toContain('too');
  });

  it('fails a roll whose host is gone, and lets the gateway start on another', async () => {
    const east = new FakeRegion();
    running(east, KALSHI, NEW_REVISION);
    east.services[0].placementConstraints = PIN;
    east.hostsInCluster = [];
    rolls.set(KALSHI, underWay('starting', { raisedAtMs: NOW - 30_000 }));

    await reconcile(deps([asked()], { 'us-east-1': east }, { [KALSHI]: null }));

    expect(rolls.get(KALSHI)).toMatchObject({ phase: 'failed', reason: 'its host i-0abc is gone' });
    expect(updates(east)).toEqual([{ cluster: CLUSTER, service: serviceName(KALSHI), placementConstraints: [] }]);
  });

  it('lets a roll\'s pin go at the gateway\'s next start', async () => {
    const east = new FakeRegion();
    await reconcile(deps([row(KALSHI, 'us-east-1', 5_000, TO)], { 'us-east-1': east }));
    east.services[0].desiredCount = 0;
    east.services[0].placementConstraints = PIN;
    east.sent = [];

    const outcome = (await reconcile(deps([row(KALSHI, 'us-east-1', 5_000, TO)], { 'us-east-1': east }))).get(KALSHI);

    expect(outcome).toBe('starting');
    expect(updates(east)).toEqual([{
      cluster: CLUSTER, service: serviceName(KALSHI), taskDefinition: NEW_REVISION, desiredCount: 0, placementConstraints: [],
    }]);
  });

  it('restarts a paper gateway plainly onto the release, with no host to pin', async () => {
    const east = new FakeRegion();
    running(east, PAPER_KALSHI, 'order-gateway-paper-kalshi-main:7');
    stopsOnWait(east);

    await reconcile(deps([asked(PAPER_KALSHI, '1.40.0')], { 'us-east-1': east }, {
      [PAPER_KALSHI]: { ...upOn('1.39.0'), canHandOver: false },
    }));

    expect(updates(east)[0]).toEqual({
      cluster: CLUSTER, service: serviceName(PAPER_KALSHI), taskDefinition: 'order-gateway-paper-kalshi-main:1', desiredCount: 0,
    });
    expect(rolls.get(PAPER_KALSHI)).toMatchObject({ phase: 'starting', toVersion: '1.40.0' });
    expect(rolls.get(PAPER_KALSHI)!.instanceId).toBeUndefined();
    expect(east.commands()).not.toContain('DescribeContainerInstancesCommand');
  });

  it('restarts a paper gateway of an exchange with a home zone onto that zone\'s hosts, as it restarts anyway', async () => {
    const east = new FakeRegion();
    running(east, PAPER_KALSHI, 'order-gateway-paper-kalshi-main:7');
    stopsOnWait(east);
    const paperHome = zoneCapacityProvider(PAPER.capacityProvider, 'use1-az2');

    await reconcile(deps([{ ...asked(PAPER_KALSHI, '1.40.0'), home_zone_id: 'use1-az2' }], { 'us-east-1': east }, {
      [PAPER_KALSHI]: { ...upOn('1.39.0'), canHandOver: false },
    }));

    expect(updates(east)[0]).toMatchObject({
      desiredCount: 0, capacityProviderStrategy: [{ capacityProvider: paperHome, weight: 1 }], forceNewDeployment: true,
    });
  });

  it('keeps a live roll on its host, whatever its home zone', async () => {
    const east = new FakeRegion();
    running(east);
    stopsOnWait(east);

    await reconcile(deps([{ ...asked(), home_zone_id: 'use1-az2' }], { 'us-east-1': east }, { [KALSHI]: upOn(FROM) }));

    expect(updates(east)[0]).toEqual(
      { cluster: CLUSTER, service: serviceName(KALSHI), taskDefinition: NEW_REVISION, desiredCount: 0, placementConstraints: PIN });
  });

  it('starts a gateway whose host is gone on its home zone\'s hosts once it lets the pin go', async () => {
    const east = new FakeRegion();
    running(east, KALSHI, NEW_REVISION);
    east.services[0].placementConstraints = PIN;
    east.hostsInCluster = [];
    rolls.set(KALSHI, underWay('starting', { raisedAtMs: NOW - 30_000 }));

    await reconcile(deps([{ ...asked(), home_zone_id: 'use1-az2' }], { 'us-east-1': east }, { [KALSHI]: null }));

    expect(updates(east)).toEqual([{
      cluster: CLUSTER, service: serviceName(KALSHI), placementConstraints: [],
      capacityProviderStrategy: [{ capacityProvider: zoneCapacityProvider(LIVE.capacityProvider, 'use1-az2'), weight: 1 }],
      forceNewDeployment: true,
    }]);
  });

  it('says so when the gateway cancelled everything instead of handing over', async () => {
    const east = new FakeRegion();
    running(east);
    const activity: Record<string, Activity | null> = { [KALSHI]: upOn(FROM) };
    onSleep = () => {
      stopTask(east);
      activity[KALSHI] = { ...upOn(FROM), state: 'STOPPING', workingOrders: 0, handover: false };
    };
    await reconcile(deps([asked()], { 'us-east-1': east }, activity));
    expect(rolls.get(KALSHI)!.cancelled).toBe(true);

    await reconcile(deps([asked()], { 'us-east-1': east }, { [KALSHI]: upOn(TO, NOW + 1) }));
    expect(rolls.get(KALSHI)!.phase).toBe('done');
    expect(rolls.get(KALSHI)!.reason).toContain('every order was cancelled');
  });
});

describe('a gateway whose exchange has a home zone', () => {
  const homed = (key: string, wantedAgoMs: number | null) => ({ ...row(key, 'us-east-2', wantedAgoMs), home_zone_id: HOME });
  const at = (zoneId: string, writtenAtMs: number, linkedSessions = 0, workingOrders = 0): Activity =>
    ({ state: 'READY', linkedSessions, workingOrders, zoneId, writtenAtMs });

  /** Deps whose gateway reports these endpoint writes in turn, the last one standing. */
  function writes(rows: GatewayRow[], region: FakeRegion, sequence: Activity[]): ReconcileDeps {
    let read = 0;
    return { ...deps(rows, { 'us-east-2': region }), activity: async () => sequence[Math.min(read++, sequence.length - 1)] };
  }

  /** A gateway running on the regional hosts, with a task. */
  function runningAt(region: FakeRegion, key: string) {
    region.services.push({ ...service(key, 1), taskDefinition: 'x:1', runningCount: 1 });
    region.runningTasks.push({ taskArn: 'arn:aws:ecs:us-east-2:1:task/order-gateways/t1', containerInstanceArn: 'ci-1' });
  }

  it('starts a live gateway only on its home zone\'s hosts, and raises those', async () => {
    const east2 = new FakeRegion();

    const outcomes = await reconcile(deps([homed(KALSHI, 5_000)], { 'us-east-2': east2 }));

    expect(outcomes.get(KALSHI)).toBe('created');
    expect(east2.sent.find((s) => s.command === 'CreateServiceCommand')!.input.capacityProviderStrategy)
      .toEqual([{ capacityProvider: LIVE_HOME, weight: 1 }]);
    expect(east2.zoneHosts.DesiredCapacity).toBe(1);
    expect(east2.hosts.DesiredCapacity).toBe(0);
  });

  it('starts a paper gateway on its home zone\'s paper hosts', async () => {
    const east2 = new FakeRegion();

    await reconcile(deps([homed(PAPER_KALSHI, 5_000)], { 'us-east-2': east2 }));

    expect(east2.services[0].capacityProviderStrategy).toEqual([{ capacityProvider: PAPER_HOME, weight: 1 }]);
    expect(east2.zonePaperHosts.DesiredCapacity).toBe(1);
  });

  it('moves a stopped gateway\'s service onto its home zone\'s hosts before it starts it again', async () => {
    const east2 = new FakeRegion();
    east2.services.push({ ...service(KALSHI, 0), taskDefinition: 'order-gateway-live-kalshi-main:1' });
    east2.revisions.set('order-gateway-live-kalshi-main', []);
    const d = deps([homed(KALSHI, 5_000)], { 'us-east-2': east2 });

    expect((await reconcile(d)).get(KALSHI)).toBe('starting');
    const moved = east2.sent.find((s) => s.command === 'UpdateServiceCommand')!.input;
    expect(moved).toMatchObject({
      desiredCount: 0, capacityProviderStrategy: [{ capacityProvider: LIVE_HOME, weight: 1 }], forceNewDeployment: true,
    });

    east2.drain();
    expect((await reconcile(d)).get(KALSHI)).toBe('started');
    expect(east2.services[0]).toMatchObject({ desiredCount: 1, capacityProviderStrategy: [{ capacityProvider: LIVE_HOME, weight: 1 }] });
    expect(east2.zoneHosts.DesiredCapacity).toBe(1);
  });

  it('leaves a gateway already on its home zone\'s hosts where it is', async () => {
    const east2 = new FakeRegion();
    east2.services.push({ ...service(KALSHI, 1), capacityProviderStrategy: [{ capacityProvider: LIVE_HOME, weight: 1 }] });
    east2.runningTasks.push({ taskArn: 'arn:aws:ecs:us-east-2:1:task/order-gateways/t1', containerInstanceArn: 'ci-1' });

    const outcomes = await reconcile(writes([homed(KALSHI, 5_000)], east2, [at(HOME, NOW)]));

    expect(outcomes.get(KALSHI)).toBe('running');
    expect(east2.commands()).not.toContain('UpdateServiceCommand');
  });

  it('stops one running in another zone that holds nothing on two writes in a row, so it starts at home', async () => {
    const east2 = new FakeRegion();
    runningAt(east2, KALSHI);

    const outcomes = await reconcile(writes([homed(KALSHI, 5_000)], east2, [at('use2-az1', NOW - 10_000), at('use2-az1', NOW)]));

    expect(outcomes.get(KALSHI)).toBe('rehoming');
    expect(east2.sent.find((s) => s.command === 'UpdateServiceCommand')!.input).toEqual({
      cluster: CLUSTER, service: serviceName(KALSHI), desiredCount: 0,
    });
  });

  it('never stops one running in another zone with a session linked or an order working', async () => {
    for (const [linked, orders] of [[1, 0], [0, 2]]) {
      const east2 = new FakeRegion();
      runningAt(east2, KALSHI);

      const outcomes = await reconcile(writes([homed(KALSHI, 5_000)], east2, [at('use2-az1', NOW, linked, orders)]));

      expect(outcomes.get(KALSHI)).toBe('running');
      expect(east2.commands()).not.toContain('UpdateServiceCommand');
    }
  });

  it('keeps one that takes a session before its next write, or writes nothing new in time', async () => {
    for (const later of [at('use2-az1', NOW, 1, 0), at('use2-az1', NOW - 10_000)]) {
      const east2 = new FakeRegion();
      runningAt(east2, KALSHI);
      const d = writes([homed(KALSHI, 5_000)], east2, [at('use2-az1', NOW - 10_000), later]);
      let slept = 0;
      d.sleep = async () => { slept++; };

      expect((await reconcile(d)).get(KALSHI)).toBe('running');
      expect(east2.commands()).not.toContain('UpdateServiceCommand');
      if (later.linkedSessions === 0) expect(slept).toBe(IDLE_POLLS);
    }
  });

  it('tells how long its task has waited for a host while it is running but not up', async () => {
    const east2 = new FakeRegion();
    east2.services.push({ ...service(KALSHI, 1), capacityProviderStrategy: [{ capacityProvider: LIVE_HOME, weight: 1 }] });
    east2.runningTasks.push({
      taskArn: 'arn:aws:ecs:us-east-2:1:task/order-gateways/t1', containerInstanceArn: '',
      lastStatus: 'PROVISIONING', createdAt: new Date(NOW - 6 * 60_000).toISOString(),
    });
    const seen: Observation[] = [];

    await reconcile({ ...deps([homed(KALSHI, 5_000)], { 'us-east-2': east2 }), observed: (o) => seen.push(o) });

    expect(seen[0]).toMatchObject({ outcome: 'running', taskId: 't1', provisioningSinceMs: NOW - 6 * 60_000 });
  });

  it('spends at most a tick\'s budget of re-reads on idle gateways away from home, leaving the rest to the next tick', async () => {
    const east2 = new FakeRegion();
    runningAt(east2, KALSHI);
    runningAt(east2, PAPER_KALSHI);
    // Kalshi's endpoint never writes again within the tick: it uses the whole budget waiting.
    const writesOf: Record<string, Activity[]> = {
      [KALSHI]: [at('use2-az1', NOW - 10_000)],
      [PAPER_KALSHI]: [at('use2-az1', NOW - 10_000), at('use2-az1', NOW)],
    };
    const reads: Record<string, number> = {};
    const d = { ...deps([homed(KALSHI, 5_000), homed(PAPER_KALSHI, 5_000)], { 'us-east-2': east2 }),
      activity: async (key: string) => {
        const list = writesOf[key];
        reads[key] = (reads[key] ?? 0) + 1;
        return list[Math.min(reads[key] - 1, list.length - 1)];
      } };
    let slept = 0;
    d.sleep = async () => { slept++; };

    const outcomes = await reconcile(d);

    expect(outcomes.get(KALSHI)).toBe('running');
    expect(outcomes.get(PAPER_KALSHI)).toBe('running');
    expect(slept).toBe(IDLE_POLLS_PER_TICK);
  });

  it('never moves one whose release doesn\'t say its zone, nor one of an exchange with no home zone', async () => {
    const east2 = new FakeRegion();
    runningAt(east2, KALSHI);
    const unsaid: Activity = { state: 'READY', linkedSessions: 0, workingOrders: 0, writtenAtMs: NOW };
    expect((await reconcile(writes([homed(KALSHI, 5_000)], east2, [unsaid]))).get(KALSHI)).toBe('running');

    const plain = new FakeRegion();
    runningAt(plain, KALSHI);
    const elsewhere = writes([row(KALSHI, 'us-east-2', 5_000)], plain, [at('use2-az1', NOW - 10_000), at('use2-az1', NOW)]);
    expect((await reconcile(elsewhere)).get(KALSHI)).toBe('running');
    expect([...east2.commands(), ...plain.commands()]).not.toContain('UpdateServiceCommand');
  });
});

