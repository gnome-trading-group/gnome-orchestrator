// The gateway reconciler's alerts: what only it can see of a gateway, from one tick to the next. Each tick builds the
// alerts that hold now from what it observed of every gateway and what it kept from earlier ticks; the handler raises
// those (repeats of an open key change nothing) and resolves the ones the last tick left firing that no longer hold,
// as market data's collection reconciler does for shards. Each condition waits before it raises, so the registry's
// catalog doesn't debounce them again.

import type { Activity, Outcome } from './reconciler';
import type { Roll } from './roll';

// A cold start (host, image, jar, venue connections) takes 3-5 minutes; a starting session gives up at 10.
export const START_STUCK_MS = 6 * 60 * 1000;
export const DEGRADED_TICKS = 2;
export const FAILED_TICKS = 3;
// How long a restart nobody asked for stays raised, for someone to look at what it left; a failed roll the same.
export const RESTARTED_HOLD_MS = 15 * 60 * 1000;
// How long a gateway may wait for a host in its home zone before it is called short of capacity there: a host's launch
// takes a minute or two, and its group tries each of its instance types first.
export const UNPLACED_MS = 5 * 60 * 1000;
// What ECS says of a service whose task no host can take.
const UNPLACED_EVENT = /unable to place a task/i;

/** What one tick saw of one gateway. */
export interface Observation {
  gatewayKey: string;
  // Its row's mode, which its alerts carry: the registry's catalog keeps paper alerts below paging.
  mode: string;
  region: string;
  // Absent when reconciling it failed, with the error instead.
  outcome?: Outcome;
  error?: string;
  // Its endpoint item; null when it has never written one, `expired` when it has stopped writing.
  activity: Activity | null;
  // Its ECS service's newest event, which says why a task can't be placed.
  serviceEvent?: string;
  // Why its most recently stopped task stopped; looked up only while it is running but not up.
  stoppedReason?: string;
  // Its running task's id, while it runs.
  taskId?: string;
  // Its roll item: the roll under way, or the last one, ended.
  roll?: Roll;
  // Its exchange's home zone, and the capacity provider it starts on.
  homeZoneId?: string;
  capacityProvider?: string;
  // When its task was created, while that task still waits for a host (PROVISIONING); looked up only for a gateway
  // with a home zone that is running but not up.
  provisioningSinceMs?: number;
}

/** What is kept of a gateway between ticks. */
export interface GatewayMemory {
  // When the reconciler started it, or saw it vanish and ECS start it again, until it comes up.
  startingSinceMs?: number;
  // Its sessions and orders the last time it said it was up with either; cleared when it stops as asked.
  lastInUse?: { linkedSessions: number; workingOrders: number };
  // The orders it said it still held while stopping, its planned stop's last word; cleared when it is up again.
  stopping?: { workingOrders: number };
  degradedTicks: number;
  failedTicks: number;
  // The task it runs as; a new one this reconciler didn't start is a restart nobody asked for.
  taskId?: string;
  // When such a restart was seen while orders worked, the task it replaced, and why that one stopped once known.
  restarted?: { atMs: number; fromTask: string; workingOrders: number; reason?: string };
  // Since when it has been set to run in its home zone with no host there to take it.
  unplacedSinceMs?: number;
}

export interface AlertState {
  gateways: Record<string, GatewayMemory>;
  // Alert key to type, for every alert the last tick left firing.
  firing: Record<string, string>;
  // Alert key to its gateway's mode, for the same alerts, so each resolves in the mode it fired in.
  modes?: Record<string, string>;
}

export interface Alert {
  type: string;
  mode: string;
  title: string;
  labels: Record<string, string>;
}

export const EMPTY_STATE: AlertState = { gateways: {}, firing: {}, modes: {} };

/** The mode an alert the last tick left firing was raised in; live for a state kept before alerts had modes. */
export function firingMode(state: AlertState, key: string): string {
  return state.modes?.[key] ?? 'live';
}

const UP = new Set(['READY', 'DEGRADED']);

/** What it says now; null when it has said nothing, or nothing lately. */
function present(activity: Activity | null): Activity | null {
  return activity && !activity.expired ? activity : null;
}

/** The alerts that hold after this tick, by key, and what to keep for the next. */
export function evaluate(observations: Observation[], previous: AlertState, nowMs: number):
  { firing: Map<string, Alert>; state: AlertState } {
  const firing = new Map<string, Alert>();
  const gateways: Record<string, GatewayMemory> = {};
  for (const seen of observations) {
    const before = previous.gateways[seen.gatewayKey];
    const memory = next(before, seen, nowMs);
    memory.unplacedSinceMs = seen.homeZoneId !== undefined && seen.provisioningSinceMs !== undefined
      ? seen.provisioningSinceMs
      : unplaced(seen) ? (before?.unplacedSinceMs ?? nowMs) : undefined;
    gateways[seen.gatewayKey] = memory;
    for (const alert of alertsFor(seen, memory, nowMs)) {
      firing.set(`${alert.type}:${seen.gatewayKey}`, { ...alert, mode: seen.mode });
    }
  }
  const keys: Record<string, string> = {};
  const modes: Record<string, string> = {};
  firing.forEach((alert, key) => {
    keys[key] = alert.type;
    modes[key] = alert.mode;
  });
  return { firing, state: { gateways, firing: keys, modes } };
}

/**
 * Set to run in its home zone and no host takes it, by ECS's word: it can't place the task and none runs. The usual
 * sign on a capacity provider is a task left PROVISIONING (`provisioningSinceMs`, counted from the task's creation);
 * this is the other. A failed tick keeps what was known; without a home zone this is the gateway start's own business
 * (GATEWAY_START_STUCK).
 */
function unplaced(seen: Observation): boolean {
  if (seen.homeZoneId === undefined || seen.error !== undefined || seen.taskId !== undefined) return false;
  if (seen.outcome !== 'started' && seen.outcome !== 'created' && seen.outcome !== 'running') return false;
  return UNPLACED_EVENT.test(seen.serviceEvent ?? '');
}

function next(before: GatewayMemory | undefined, seen: Observation, nowMs: number): GatewayMemory {
  const memory: GatewayMemory = { degradedTicks: 0, failedTicks: 0, ...before };
  if (seen.error !== undefined) {
    // Nothing else is known this tick; what was kept stands.
    return { ...memory, failedTicks: memory.failedTicks + 1 };
  }
  memory.failedTicks = 0;
  const outcome = seen.outcome;
  if (outcome === 'rolling') {
    // Stopped and started again as asked, with what it held kept for its return: its gap is no outage and its new task
    // no restart nobody asked for; the roll has its own limit on coming up.
    return { degradedTicks: 0, failedTicks: 0, lastInUse: memory.lastInUse };
  }
  if (outcome === 'stopped' || outcome === 'stopped already' || outcome === 'rehoming') {
    // Stopped as asked (or to start it in its home zone, holding nothing): its planned stop cancels and tells everyone,
    // so its going is no outage.
    return { degradedTicks: 0, failedTicks: 0 };
  }
  if (outcome === 'starting' || outcome === 'moving' || outcome === 'started' || outcome === 'created') {
    // A start that waits for the old deployment to drain counts from when it began.
    return { degradedTicks: 0, failedTicks: 0, startingSinceMs: memory.startingSinceMs ?? nowMs };
  }
  if (seen.taskId !== undefined && memory.taskId !== undefined && seen.taskId !== memory.taskId
      && (memory.lastInUse?.workingOrders ?? 0) > 0) {
    memory.restarted = { atMs: nowMs, fromTask: memory.taskId, workingOrders: memory.lastInUse!.workingOrders };
  }
  if (memory.restarted && nowMs - memory.restarted.atMs >= RESTARTED_HOLD_MS) memory.restarted = undefined;
  if (memory.restarted && seen.stoppedReason && !memory.restarted.reason) {
    memory.restarted = { ...memory.restarted, reason: seen.stoppedReason };
  }
  if (seen.taskId !== undefined) memory.taskId = seen.taskId;
  const activity = present(seen.activity);
  if (activity && UP.has(activity.state)) {
    memory.startingSinceMs = undefined;
    memory.stopping = undefined;
    memory.lastInUse = activity.linkedSessions > 0 || activity.workingOrders > 0
      ? { linkedSessions: activity.linkedSessions, workingOrders: activity.workingOrders }
      : undefined;
  } else if (activity && activity.state === 'STOPPING' && !activity.handover) {
    // Stopping, though not asked to by this reconciler (its host shutting down, or a stop by hand): it says what it
    // still holds as it cancels, and once more when it is done.
    memory.stopping = { workingOrders: activity.workingOrders };
  } else if (memory.startingSinceMs === undefined) {
    // Running but not up, and not started by this reconciler (gone without being asked, so ECS starts it again; or
    // started by hand): it must come up in time like any start.
    memory.startingSinceMs = nowMs;
  }
  memory.degradedTicks = activity?.state === 'DEGRADED' ? memory.degradedTicks + 1 : 0;
  return memory;
}

function alertsFor(seen: Observation, memory: GatewayMemory, nowMs: number): Omit<Alert, 'mode'>[] {
  const [, exchange = ''] = seen.gatewayKey.split(':');
  const labels = (extra: Record<string, string> = {}) => ({
    gateway: seen.gatewayKey, exchange, region: seen.region, ...extra,
  });
  const reason = seen.stoppedReason ?? seen.serviceEvent;
  const why = reason ? `: ${reason}` : '';
  const alerts: Omit<Alert, 'mode'>[] = [];
  if (memory.failedTicks >= FAILED_TICKS) {
    alerts.push({
      type: 'GATEWAY_RECONCILE_FAILED',
      title: `Gateway ${seen.gatewayKey}: reconciling failed ${memory.failedTicks} ticks in a row: ${seen.error ?? 'unknown'}`,
      labels: labels({ ticks: String(memory.failedTicks) }),
    });
  }
  if (seen.error !== undefined) return alerts;
  const roll = seen.roll;
  if (roll && (roll.phase === 'rolled_back' || roll.phase === 'failed') && roll.endedAtMs !== undefined) {
    const up = present(seen.activity) != null && UP.has(seen.activity!.state);
    const rolledBack = roll.phase === 'rolled_back';
    // A rollback that came up holds for a while; one that didn't, until the gateway is up again.
    if (nowMs - roll.endedAtMs < RESTARTED_HOLD_MS || (!rolledBack && !up)) {
      alerts.push({
        type: 'GATEWAY_ROLL_FAILED',
        title: rolledBack
          ? `Gateway ${seen.gatewayKey}'s roll to ${roll.toVersion} failed and it was rolled back to ${roll.fromVersion}: ${roll.reason ?? 'unknown'}`
          : `Gateway ${seen.gatewayKey}'s roll to ${roll.toVersion} failed and it isn't up: ${roll.reason ?? 'unknown'}`,
        labels: labels({
          rolledBack: String(rolledBack), fromVersion: roll.fromVersion ?? '', toVersion: roll.toVersion ?? '',
          ...(roll.instanceId ? { instanceId: roll.instanceId } : {}),
        }),
      });
    }
  }
  if (seen.outcome === 'rolling') return alerts;
  if (memory.startingSinceMs !== undefined && nowMs - memory.startingSinceMs >= START_STUCK_MS) {
    const minutes = Math.floor((nowMs - memory.startingSinceMs) / 60_000);
    alerts.push({
      type: 'GATEWAY_START_STUCK',
      title: `Gateway ${seen.gatewayKey} isn't ready ${minutes} min after starting${why}`,
      labels: labels({ minutes: String(minutes), state: present(seen.activity)?.state ?? 'none' }),
    });
  }
  if (present(seen.activity) == null && memory.lastInUse) {
    const { linkedSessions } = memory.lastInUse;
    const workingOrders = memory.stopping?.workingOrders ?? memory.lastInUse.workingOrders;
    if (memory.stopping && workingOrders === 0) {
      // Its planned stop ran to the end: every order cancelled and every session told, only the gateway is missing.
      alerts.push({
        type: 'GATEWAY_DOWN',
        title: `Gateway ${seen.gatewayKey} stopped without being asked to, with ${linkedSessions} session(s) linked;`
          + ` its planned stop cancelled every order${why}`,
        labels: labels({ linkedSessions: String(linkedSessions), workingOrders: '0', cleanStop: 'true' }),
      });
    } else {
      alerts.push({
        type: 'GATEWAY_DOWN',
        title: `Gateway ${seen.gatewayKey} is gone with ${linkedSessions} session(s) linked and ${workingOrders} order(s) working${why}`,
        labels: labels({ linkedSessions: String(linkedSessions), workingOrders: String(workingOrders) }),
      });
    }
  }
  if (memory.restarted) {
    const { fromTask, workingOrders, reason: stopped } = memory.restarted;
    alerts.push({
      type: 'GATEWAY_RESTARTED',
      title: `Gateway ${seen.gatewayKey} was replaced without being asked to, with ${workingOrders} order(s) working`
        + (stopped ? `: ${stopped}` : ''),
      labels: labels({ fromTask, workingOrders: String(workingOrders), ...(stopped ? { stoppedReason: stopped } : {}) }),
    });
  }
  if (memory.unplacedSinceMs !== undefined && nowMs - memory.unplacedSinceMs >= UNPLACED_MS) {
    const minutes = Math.floor((nowMs - memory.unplacedSinceMs) / 60_000);
    alerts.push({
      type: 'HOME_ZONE_NO_CAPACITY',
      title: `Gateway ${seen.gatewayKey} has had no host in its home zone ${seen.homeZoneId} for ${minutes} min${why}`,
      labels: labels({
        zoneId: seen.homeZoneId ?? '', capacityProvider: seen.capacityProvider ?? '', minutes: String(minutes),
      }),
    });
  }
  if (memory.degradedTicks >= DEGRADED_TICKS) {
    alerts.push({
      type: 'GATEWAY_DEGRADED',
      title: `Gateway ${seen.gatewayKey} has been DEGRADED for ${memory.degradedTicks} ticks`,
      labels: labels({ ticks: String(memory.degradedTicks) }),
    });
  }
  return alerts;
}
