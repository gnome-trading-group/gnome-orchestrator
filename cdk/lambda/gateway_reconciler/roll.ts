import { AttributeValue } from '@aws-sdk/client-dynamodb';
import {
  DescribeContainerInstancesCommand, DescribeTasksCommand, ListTasksCommand, PlacementConstraint, UpdateServiceCommand,
} from '@aws-sdk/client-ecs';
import {
  activeService, Activity, Client, gatewayEnvironment, GatewayNames, GatewayRow, hostActive, ModeNames, ReconcileDeps,
  revision, runningTasks, UP_STATES,
} from './reconciler';

// Rolling a running gateway onto the release a fresh start would run (its pin, else the latest) without cancelling its
// orders, when an operator asks (gnome-registry's POST /gateways/roll stamps the row's roll_requested_at). The gateway's
// roll item records each step, and is what the gateway reads at SIGTERM: while it says `stopping` (or `rolling_back`)
// for this host and this release, the gateway hands over (refuses new orders, waits for the venue's answers, cancels
// nothing, says no goodbye) and leaves its orders in its ownership log on the host. The service is then pinned to that
// host and raised again on the new revision, so the next process takes the orders back from the log; sessions re-link
// by themselves and get their orders as order statuses. A roll that doesn't come up is rolled back once the same way.
//
//   stopping      the roll item written, the service moved onto the new revision at zero and pinned to the host; once
//                 the old task has gone (polled within the tick), raised to one
//   starting      raised; done once the gateway is up on the new release, rolled back if it isn't in ROLL_READY_MS or
//                 its task stopped ROLL_TASK_STOPS times
//   rolling_back  the same onto the revision it ran before; rolled_back once up on it, failed if it isn't either
//   done | rolled_back | failed | refused   ended; kept a day, for the controller
//
// A paper gateway's orders live only in its process, so its roll is a plain restart onto the release: the same steps,
// with no pin; its planned stop cancels its paper orders.

/** The first release whose gateway hands over when rolled, which every roll's target must be at least. */
export const MIN_ROLL_VERSION = '2.2.0';
// How long a rolled gateway has to come up: a warm start on its host is under a minute.
export const ROLL_READY_MS = 5 * 60_000;
export const ROLL_TASK_STOPS = 3;
// How long the gateway may hand over under the roll item: past this a SIGTERM stops it as planned. Covers the stop
// (ECS's stop timeout is 2 minutes) with room; rewritten for a rollback.
export const ROLL_MARKER_MS = 10 * 60_000;
// Within one tick, how often and how many times in all the reconciler looks whether the old task has gone, so the
// gap doesn't wait for the next tick: about a minute of the Lambda's two.
export const ROLL_DRAIN_POLL_MS = 2_000;
export const ROLL_DRAIN_POLLS_PER_TICK = 30;
const ROLL_KEPT_SECONDS = 24 * 3600;

export type RollPhase = 'stopping' | 'starting' | 'rolling_back' | 'done' | 'rolled_back' | 'failed' | 'refused';
const UNDER_WAY = new Set<RollPhase>(['stopping', 'starting', 'rolling_back']);

export interface Roll {
  requestedAtMs: number;
  phase: RollPhase;
  // The host the gateway is pinned to; none for a paper gateway.
  instanceId?: string;
  fromVersion?: string;
  toVersion?: string;
  // The revision it ran before, which a rollback goes back to.
  fromTaskDefinition?: string;
  // The release whose process the current stop is for: the gateway hands over only if it runs it.
  stopsVersion?: string;
  startedAtMs?: number;
  // When the service was raised again in this phase; unset while the old task is still being stopped.
  raisedAtMs?: number;
  endedAtMs?: number;
  untilMs?: number;
  // Why it was refused, rolled back or failed, or how it ended, in a few words.
  reason?: string;
  // The gateway stopped as planned (cancelling its orders) rather than handing over: its venue can't keep orders
  // resting that long without it.
  cancelled?: boolean;
}

export function rollUnderWay(roll: Roll): boolean {
  return UNDER_WAY.has(roll.phase);
}

/** What one tick may still spend waiting on rolls' old tasks to go. */
export interface RollTick {
  pollsLeft: number;
}

export function rollTick(): RollTick {
  return { pollsLeft: ROLL_DRAIN_POLLS_PER_TICK };
}

export interface RollContext {
  row: GatewayRow;
  deps: ReconcileDeps;
  ecs: Client;
  mode: ModeNames;
  service: string;
  // Its ECS service as the tick began.
  current: any;
  running: boolean;
  activity: Activity | null;
  now: number;
  tick: RollTick;
}

/** Compares two releases by their numbers, major first. */
export function compareVersions(a: string, b: string): number {
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
}

/**
 * Takes the gateway's roll a step on: a new request is checked and begun, one under way moves on. Returns the roll as
 * it now stands, or null without one.
 */
export async function stepRoll(ctx: RollContext, roll: Roll | null): Promise<Roll | null> {
  const requested = ctx.row.roll_requested_at ? Date.parse(ctx.row.roll_requested_at) : null;
  if (roll && rollUnderWay(roll)) return proceed(ctx, roll);
  if (requested !== null && (!roll || requested > roll.requestedAtMs)) return begin(ctx, requested);
  return roll;
}

const pin = (instanceId: string | undefined): { placementConstraints?: PlacementConstraint[] } => (instanceId
  ? { placementConstraints: [{ type: 'memberOf', expression: `ec2InstanceId == ${instanceId}` }] }
  : {});

async function save(ctx: RollContext, roll: Roll): Promise<Roll> {
  await ctx.deps.saveRoll(ctx.row.gateway_key, roll, ctx.deps.nowMs());
  return roll;
}

async function end(ctx: RollContext, roll: Roll, phase: RollPhase, reason?: string): Promise<Roll> {
  const ended = { ...roll, phase, endedAtMs: ctx.deps.nowMs(), reason: reason ?? roll.reason };
  console.log(`Gateway ${ctx.row.gateway_key}: roll ${phase}${ended.reason ? ` (${ended.reason})` : ''}`);
  return save(ctx, ended);
}

async function begin(ctx: RollContext, requestedAtMs: number): Promise<Roll> {
  const { row, deps, ecs, current, activity, now } = ctx;
  const refuse = (reason: string) => end(ctx, { requestedAtMs, phase: 'refused' }, 'refused', reason);
  if (!ctx.running) return refuse("it isn't running; its next start runs the release anyway");
  const up = activity && !activity.expired ? activity : null;
  if (!up || !UP_STATES.has(up.state)) return refuse(`it isn't up (${up?.state ?? 'silent'})`);
  const paper = row.mode === 'paper';
  if (!paper && !up.canHandOver) {
    return refuse(`its release ${up.version ?? '?'} can't hand over: a restart would cancel every order`);
  }
  const tasks = await runningTasks(ecs, deps.names, ctx.service);
  if (tasks.length !== 1) return refuse(`it runs ${tasks.length} tasks`);
  const toVersion = row.orchestrator_version ?? await deps.latestVersion();
  if (!paper && compareVersions(toVersion, MIN_ROLL_VERSION) < 0) {
    return refuse(`${toVersion} is older than ${MIN_ROLL_VERSION}, the first release that hands over`);
  }
  const instanceId = paper ? undefined : await hostOf(ecs, deps.names, tasks[0]);
  if (!paper && !instanceId) return refuse("its host couldn't be found");
  const roll = await save(ctx, {
    requestedAtMs,
    phase: 'stopping',
    instanceId,
    fromVersion: up.version,
    toVersion,
    fromTaskDefinition: current.taskDefinition,
    stopsVersion: up.version,
    startedAtMs: now,
    untilMs: now + ROLL_MARKER_MS,
  });
  console.log(`Gateway ${row.gateway_key}: rolling ${up.version} -> ${toVersion}`
    + (instanceId ? ` on ${instanceId}` : ' (paper: a plain restart)'));
  const taskDefinition = await revision(
    ecs, deps.names, ctx.mode, row.gateway_key, toVersion, await gatewayEnvironment(row, deps));
  // The roll item is written before the stop: it is what makes the gateway hand over rather than cancel.
  await ecs.send(new UpdateServiceCommand({
    cluster: deps.names.cluster, service: ctx.service, taskDefinition, desiredCount: 0, ...pin(instanceId),
  }));
  return raiseOnceStopped(ctx, roll);
}

async function proceed(ctx: RollContext, roll: Roll): Promise<Roll> {
  if (roll.raisedAtMs === undefined) return raiseOnceStopped(ctx, roll);
  const { deps, ecs, activity } = ctx;
  const target = roll.phase === 'rolling_back' ? roll.fromVersion : roll.toVersion;
  const up = activity && !activity.expired ? activity : null;
  // Its endpoint written since it was raised, so it is the new process's and not what the old one last said.
  if (up && UP_STATES.has(up.state) && up.version === target && (up.writtenAtMs ?? 0) > roll.raisedAtMs) {
    if (roll.phase === 'rolling_back') return end(ctx, roll, 'rolled_back');
    return end(ctx, roll, 'done', roll.cancelled
      ? 'restarted as planned: its venue cancels resting orders sooner than a roll takes, so every order was cancelled'
      : undefined);
  }
  if (roll.instanceId && !await hostActive(ecs, deps.names, roll.instanceId)) {
    // The pin can't be met: left to the reconciler's handling of a lost host, which lets the pin go.
    return end(ctx, roll, 'failed', `its host ${roll.instanceId} is gone`);
  }
  const waitedMs = ctx.now - roll.raisedAtMs;
  const stops = await tasksStoppedSince(ecs, deps.names, ctx.service, roll.raisedAtMs);
  if (waitedMs < ROLL_READY_MS && stops < ROLL_TASK_STOPS) return roll;
  const why = waitedMs >= ROLL_READY_MS
    ? `not up on ${target} ${Math.floor(waitedMs / 60_000)} min after it was started`
    : `its task stopped ${stops} times on ${target}`;
  if (roll.phase === 'rolling_back') {
    return end(ctx, roll, 'failed', `rolled back after ${roll.reason ?? 'failing'}, but ${why} too`);
  }
  console.log(`Gateway ${ctx.row.gateway_key}: rolling back to ${roll.fromVersion}: ${why}`);
  // Its process, if one runs, is on the release it was rolled to: that is the stop the gateway hands over at.
  const back = await save(ctx, {
    ...roll, phase: 'rolling_back', reason: why, raisedAtMs: undefined, stopsVersion: roll.toVersion,
    untilMs: ctx.now + ROLL_MARKER_MS,
  });
  await ecs.send(new UpdateServiceCommand({
    cluster: deps.names.cluster, service: ctx.service, taskDefinition: roll.fromTaskDefinition, desiredCount: 0,
    ...pin(roll.instanceId),
  }));
  return raiseOnceStopped(ctx, back);
}

/**
 * Waits, within what the tick has left, for the old task to go and its deployment to drain, then raises the service to
 * one, so the new task starts at once on the pinned host. If the tick runs out first, the next one carries on.
 */
async function raiseOnceStopped(ctx: RollContext, roll: Roll): Promise<Roll> {
  const { deps, ecs, service } = ctx;
  for (;;) {
    const found = await activeService(ecs, deps.names, service);
    const drained = found && (found.deployments ?? []).length <= 1
      && (found.runningCount ?? 0) === 0 && (found.pendingCount ?? 0) === 0;
    if (drained) break;
    if (ctx.tick.pollsLeft <= 0) return roll;
    ctx.tick.pollsLeft--;
    await deps.sleep(ROLL_DRAIN_POLL_MS);
  }
  // The old process's last word: STOPPING, and whether it handed over or cancelled.
  const last = await deps.activity(ctx.row.gateway_key, deps.nowMs());
  const cancelled = roll.phase === 'stopping' && ctx.row.mode !== 'paper' && last?.state === 'STOPPING'
    && last.handover !== true;
  await ecs.send(new UpdateServiceCommand({ cluster: deps.names.cluster, service, desiredCount: 1 }));
  return save(ctx, {
    ...roll,
    phase: roll.phase === 'stopping' ? 'starting' : roll.phase,
    raisedAtMs: deps.nowMs(),
    cancelled: roll.cancelled || cancelled,
  });
}

/** The EC2 instance the task runs on. */
async function hostOf(ecs: Client, names: GatewayNames, taskId: string): Promise<string | undefined> {
  const task = (await ecs.send(new DescribeTasksCommand({ cluster: names.cluster, tasks: [taskId] }))).tasks?.[0];
  if (!task?.containerInstanceArn) return undefined;
  const instance = (await ecs.send(new DescribeContainerInstancesCommand({
    cluster: names.cluster, containerInstances: [task.containerInstanceArn],
  }))).containerInstances?.[0];
  return instance?.ec2InstanceId;
}

/** How many of the service's tasks started since then have stopped. */
async function tasksStoppedSince(ecs: Client, names: GatewayNames, service: string, sinceMs: number): Promise<number> {
  const listed = await ecs.send(new ListTasksCommand({
    cluster: names.cluster, serviceName: service, desiredStatus: 'STOPPED', maxResults: 10,
  }));
  if (!listed.taskArns?.length) return 0;
  const tasks = (await ecs.send(new DescribeTasksCommand({ cluster: names.cluster, tasks: listed.taskArns }))).tasks ?? [];
  return tasks.filter((t: any) => t.createdAt && new Date(t.createdAt).getTime() >= sinceMs).length;
}

// ----- the item -----

type Item = Record<string, AttributeValue>;

const s = (value: string | undefined) => (value === undefined ? undefined : { S: value });
const n = (value: number | undefined) => (value === undefined ? undefined : { N: String(value) });

/** The roll as its item in service-endpoints, read by the gateway at SIGTERM and by the registry for the controller. */
export function rollItem(serviceId: string, roll: Roll, nowMs: number): Item {
  const fields: Record<string, AttributeValue | undefined> = {
    service_id: { S: serviceId },
    kind: { S: 'order-gateway-roll' },
    requested_at_ms: n(roll.requestedAtMs),
    phase: s(roll.phase),
    instance_id: s(roll.instanceId),
    from_version: s(roll.fromVersion),
    to_version: s(roll.toVersion),
    from_task_definition: s(roll.fromTaskDefinition),
    stops_version: s(roll.stopsVersion),
    started_at_ms: n(roll.startedAtMs),
    raised_at_ms: n(roll.raisedAtMs),
    ended_at_ms: n(roll.endedAtMs),
    until_ms: n(roll.untilMs),
    reason: s(roll.reason),
    cancelled: roll.cancelled ? { BOOL: true } : undefined,
    expires_at: n(Math.floor(nowMs / 1000) + ROLL_KEPT_SECONDS),
  };
  return Object.fromEntries(Object.entries(fields).filter(([, v]) => v !== undefined)) as Item;
}

export function readRoll(item: Item): Roll | null {
  if (!item.phase?.S || !item.requested_at_ms?.N) return null;
  const num = (name: string) => (item[name]?.N !== undefined ? Number(item[name].N) : undefined);
  return {
    requestedAtMs: Number(item.requested_at_ms.N),
    phase: item.phase.S as RollPhase,
    instanceId: item.instance_id?.S,
    fromVersion: item.from_version?.S,
    toVersion: item.to_version?.S,
    fromTaskDefinition: item.from_task_definition?.S,
    stopsVersion: item.stops_version?.S,
    startedAtMs: num('started_at_ms'),
    raisedAtMs: num('raised_at_ms'),
    endedAtMs: num('ended_at_ms'),
    untilMs: num('until_ms'),
    reason: item.reason?.S,
    cancelled: item.cancelled?.BOOL === true,
  };
}
