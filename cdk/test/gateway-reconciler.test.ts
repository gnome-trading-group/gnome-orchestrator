import { Observation } from '../lambda/gateway_reconciler/alerts';
import {
  Activity, GatewayNames, GatewayRow, reconcile, ReconcileDeps, serviceName as nameOf, shouldRun, WANT_WINDOW_MS,
} from '../lambda/gateway_reconciler/reconciler';
import { ORDER_GATEWAYS } from '../lib/config';

const NAMES: GatewayNames = ORDER_GATEWAYS;
const { cluster: CLUSTER, addressTag: ADDRESS_TAG } = NAMES;
const LIVE = ORDER_GATEWAYS.modes.live;
const PAPER = ORDER_GATEWAYS.modes.paper;
const serviceName = (key: string) => nameOf(NAMES, key);
// Each capacity provider's Auto Scaling group, by its name in the group's ARN.
const GROUPS: Record<string, string> = {
  [LIVE.capacityProvider]: 'GatewayHostsASG',
  [PAPER.capacityProvider]: 'GatewayPaperHostsASG',
};

type Service = {
  serviceName: string; status: string; desiredCount: number; taskDefinition?: string;
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
  stoppedTasks: { taskArn: string; stoppedAt: string; stoppedReason: string; containers: { exitCode?: number }[] }[] = [];
  // The live and the paper capacity providers' Auto Scaling groups.
  hosts = { DesiredCapacity: 0, MaxSize: 4 };
  paperHosts = { DesiredCapacity: 0, MaxSize: 4 };
  hostsFail = false;

  group(name: string) {
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
        return { taskArns: this.stoppedTasks.map((t) => t.taskArn) };
      }
      if (name === 'DescribeTasksCommand') {
        return { tasks: this.stoppedTasks.filter((t) => command.input.tasks.includes(t.taskArn)) };
      }
      if (name === 'UpdateServiceCommand') {
        const service = this.services.find((s) => s.serviceName === command.input.service)!;
        service.desiredCount = command.input.desiredCount;
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

function deps(rows: GatewayRow[], regions: Record<string, FakeRegion>, activity: Record<string, Activity | null> = {}): ReconcileDeps {
  return {
    names: NAMES,
    gateways: async () => rows,
    activity: async (key) => activity[key] ?? null,
    latestVersion: async () => '1.32.1',
    alertsApiUrl: async () => 'https://alerts.example/',
    marketDataApi: async () => ({ url: 'https://market-data.example', keySecret: 'gnome/api-keys/market-data' }),
    ecs: (region) => regions[region].ecs,
    ec2: (region) => regions[region].ec2,
    autoscaling: (region) => regions[region].autoscaling,
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
    expect(west.sent).toEqual([]);
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
      updates: [{ cluster: CLUSTER, service: serviceName(KALSHI), taskDefinition: 'order-gateway-live-kalshi-main:2', desiredCount: 0 }],
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
