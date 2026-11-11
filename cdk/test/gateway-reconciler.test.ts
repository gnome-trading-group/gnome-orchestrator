import { Observation } from '../lambda/gateway_reconciler/alerts';
import {
  Activity, GatewayNames, GatewayRow, reconcile, ReconcileDeps, serviceName as nameOf, shouldRun, WANT_WINDOW_MS,
} from '../lambda/gateway_reconciler/reconciler';
import { ORDER_GATEWAYS } from '../lib/config';

const NAMES: GatewayNames = ORDER_GATEWAYS;
const { cluster: CLUSTER, baseFamily: BASE_FAMILY, capacityProvider: CAPACITY_PROVIDER, addressTag: ADDRESS_TAG } = NAMES;
const serviceName = (key: string) => nameOf(NAMES, key);

// What ECS and EC2 hold in one region, and every command each was sent, by name.
class FakeRegion {
  services: {
    serviceName: string; status: string; desiredCount: number; taskDefinition?: string;
    deployments?: { taskDefinition?: string }[];
  }[] = [];
  addresses: { AllocationId: string; Tags: { Key: string; Value: string }[] }[] = [];
  revisions = new Map<string, any[]>();
  sent: { command: string; input: any }[] = [];
  stoppedTasks: { taskArn: string; stoppedAt: string; stoppedReason: string; containers: { exitCode?: number }[] }[] = [];

  ecs = {
    send: async (command: any) => {
      const name = command.constructor.name;
      this.sent.push({ command: name, input: command.input });
      if (name === 'DescribeServicesCommand') {
        return { services: this.services.filter((s) => command.input.services.includes(s.serviceName)) };
      }
      if (name === 'DescribeTaskDefinitionCommand') {
        if (command.input.taskDefinition === BASE_FAMILY) return { taskDefinition: BASE };
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

  /** Every service's old deployments gone, as once ECS has drained them. */
  drain() {
    for (const service of this.services) service.deployments = [{ taskDefinition: service.taskDefinition }];
  }

  commands(): string[] {
    return this.sent.map((s) => s.command);
  }
}

const BASE = {
  family: BASE_FAMILY,
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

const NOW = Date.parse('2026-11-10T12:00:00Z');
const ago = (ms: number) => new Date(NOW - ms).toISOString();

function row(key: string, region: string, wantedAgoMs: number | null, version: string | null = null): GatewayRow {
  return { gateway_key: key, mode: 'live', region, orchestrator_version: version, wanted_at: wantedAgoMs == null ? null : ago(wantedAgoMs) };
}

function deps(rows: GatewayRow[], regions: Record<string, FakeRegion>, activity: Record<string, Activity | null> = {}): ReconcileDeps {
  return {
    names: NAMES,
    liveGateways: async () => rows,
    activity: async (key) => activity[key] ?? null,
    latestVersion: async () => '1.32.1',
    alertsApiUrl: async () => 'https://alerts.example/',
    ecs: (region) => regions[region].ecs,
    ec2: (region) => regions[region].ec2,
    nowMs: () => NOW,
  };
}

const KALSHI = 'live:kalshi:main';
const INTL = 'live:polymarket_intl:main';
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
      capacityProviderStrategy: [{ capacityProvider: CAPACITY_PROVIDER, weight: 1 }],
    });
    expect(east.addresses).toHaveLength(1);
  });

  it('starts a stopped gateway on its pinned release, reusing its revision and its address', async () => {
    const west = new FakeRegion();
    west.services.push({ serviceName: serviceName(INTL), status: 'ACTIVE', desiredCount: 0 });
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
    east.services.push({ serviceName: serviceName(KALSHI), status: 'ACTIVE', desiredCount: 1 });
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
    east.services.push({ serviceName: serviceName(KALSHI), status: 'ACTIVE', desiredCount: 1 });

    const outcomes = await reconcile(deps(
      [row(KALSHI, 'us-east-1', WANT_WINDOW_MS * 2)], { 'us-east-1': east }, { [KALSHI]: { ...idle, linkedSessions: 2 } }));

    expect(outcomes.get(KALSHI)).toBe('running');
    // Only reads: its running task, to tell a restart nobody asked for from one this reconciler made.
    expect(east.commands()).toEqual(['DescribeServicesCommand', 'ListTasksCommand']);
  });

  it('stops a gateway nobody has wanted for the window and that holds no session or order', async () => {
    const east = new FakeRegion();
    east.services.push({ serviceName: serviceName(KALSHI), status: 'ACTIVE', desiredCount: 1 });

    const outcomes = await reconcile(deps(
      [row(KALSHI, 'us-east-1', WANT_WINDOW_MS + 1)], { 'us-east-1': east }, { [KALSHI]: idle }));

    expect(outcomes.get(KALSHI)).toBe('stopped');
    expect(east.services[0].desiredCount).toBe(0);
  });

  it('keeps a running gateway gone quiet with orders working, as it may be restarting with them', async () => {
    const east = new FakeRegion();
    east.services.push({ serviceName: serviceName(KALSHI), status: 'ACTIVE', desiredCount: 1 });

    const outcomes = await reconcile(deps(
      [row(KALSHI, 'us-east-1', null)], { 'us-east-1': east }, { [KALSHI]: { ...idle, workingOrders: 2, expired: true } }));

    expect(outcomes.get(KALSHI)).toBe('running');
    expect(east.services[0].desiredCount).toBe(1);
  });

  it('stops an unwanted gateway that never came up, however long it has been failing', async () => {
    const east = new FakeRegion();
    east.services.push({ serviceName: serviceName(KALSHI), status: 'ACTIVE', desiredCount: 1 });

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
});
