// The gateway reconciler's alerts: what only it can see of a gateway, from one tick to the next. Each tick builds the
// alerts that hold now from what it observed of every gateway and what it kept from earlier ticks; the handler raises
// those (repeats of an open key change nothing) and resolves the ones the last tick left firing that no longer hold,
// as market data's collection reconciler does for shards. Each condition waits before it raises, so the registry's
// catalog doesn't debounce them again.

import type { Activity, Outcome } from './reconciler';

// A cold start (host, image, jar, venue connections) takes 3-5 minutes; a starting session gives up at 10.
export const START_STUCK_MS = 6 * 60 * 1000;
export const DEGRADED_TICKS = 2;
export const FAILED_TICKS = 3;

/** What one tick saw of one gateway. */
export interface Observation {
  gatewayKey: string;
  region: string;
  // Absent when reconciling it failed, with the error instead.
  outcome?: Outcome;
  error?: string;
  // Its endpoint item; null when it has none or it has expired.
  activity: Activity | null;
  // Its ECS service's newest event, which says why a task can't start or stopped.
  serviceEvent?: string;
}

/** What is kept of a gateway between ticks. */
export interface GatewayMemory {
  // When the reconciler started it, or saw it vanish and ECS start it again, until it comes up.
  startingSinceMs?: number;
  // Its sessions and orders the last time it said it was up with either; cleared when it stops as asked.
  lastInUse?: { linkedSessions: number; workingOrders: number };
  degradedTicks: number;
  failedTicks: number;
}

export interface AlertState {
  gateways: Record<string, GatewayMemory>;
  // Alert key to type, for every alert the last tick left firing.
  firing: Record<string, string>;
}

export interface Alert {
  type: string;
  title: string;
  labels: Record<string, string>;
}

export const EMPTY_STATE: AlertState = { gateways: {}, firing: {} };

const UP = new Set(['READY', 'DEGRADED']);

/** The alerts that hold after this tick, by key, and what to keep for the next. */
export function evaluate(observations: Observation[], previous: AlertState, nowMs: number):
  { firing: Map<string, Alert>; state: AlertState } {
  const firing = new Map<string, Alert>();
  const gateways: Record<string, GatewayMemory> = {};
  for (const seen of observations) {
    const memory = next(previous.gateways[seen.gatewayKey], seen, nowMs);
    gateways[seen.gatewayKey] = memory;
    for (const alert of alertsFor(seen, memory, nowMs)) firing.set(`${alert.type}:${seen.gatewayKey}`, alert);
  }
  const keys: Record<string, string> = {};
  firing.forEach((alert, key) => { keys[key] = alert.type; });
  return { firing, state: { gateways, firing: keys } };
}

function next(before: GatewayMemory | undefined, seen: Observation, nowMs: number): GatewayMemory {
  const memory: GatewayMemory = { degradedTicks: 0, failedTicks: 0, ...before };
  if (seen.error !== undefined) {
    // Nothing else is known this tick; what was kept stands.
    return { ...memory, failedTicks: memory.failedTicks + 1 };
  }
  memory.failedTicks = 0;
  const outcome = seen.outcome;
  if (outcome === 'stopped' || outcome === 'stopped already') {
    // Stopped as asked: its planned stop cancels and tells everyone, so its going is no outage.
    return { degradedTicks: 0, failedTicks: 0 };
  }
  if (outcome === 'started' || outcome === 'created') {
    return { degradedTicks: 0, failedTicks: 0, startingSinceMs: nowMs };
  }
  const activity = seen.activity;
  if (activity && UP.has(activity.state)) {
    memory.startingSinceMs = undefined;
    memory.lastInUse = activity.linkedSessions > 0 || activity.workingOrders > 0
      ? { linkedSessions: activity.linkedSessions, workingOrders: activity.workingOrders }
      : undefined;
  } else if (memory.startingSinceMs === undefined) {
    // Running but not up, and not started by this reconciler (gone without being asked, so ECS starts it again; or
    // started by hand): it must come up in time like any start.
    memory.startingSinceMs = nowMs;
  }
  memory.degradedTicks = activity?.state === 'DEGRADED' ? memory.degradedTicks + 1 : 0;
  return memory;
}

function alertsFor(seen: Observation, memory: GatewayMemory, nowMs: number): Alert[] {
  const [, exchange = ''] = seen.gatewayKey.split(':');
  const labels = (extra: Record<string, string> = {}) => ({
    gateway: seen.gatewayKey, exchange, region: seen.region, ...extra,
  });
  const why = seen.serviceEvent ? `: ${seen.serviceEvent}` : '';
  const alerts: Alert[] = [];
  if (memory.failedTicks >= FAILED_TICKS) {
    alerts.push({
      type: 'GATEWAY_RECONCILE_FAILED',
      title: `Gateway ${seen.gatewayKey}: reconciling failed ${memory.failedTicks} ticks in a row: ${seen.error ?? 'unknown'}`,
      labels: labels({ ticks: String(memory.failedTicks) }),
    });
  }
  if (seen.error !== undefined) return alerts;
  if (memory.startingSinceMs !== undefined && nowMs - memory.startingSinceMs >= START_STUCK_MS) {
    const minutes = Math.floor((nowMs - memory.startingSinceMs) / 60_000);
    alerts.push({
      type: 'GATEWAY_START_STUCK',
      title: `Gateway ${seen.gatewayKey} isn't ready ${minutes} min after starting${why}`,
      labels: labels({ minutes: String(minutes), state: seen.activity?.state ?? 'none' }),
    });
  }
  if (seen.activity == null && memory.lastInUse) {
    const { linkedSessions, workingOrders } = memory.lastInUse;
    alerts.push({
      type: 'GATEWAY_DOWN',
      title: `Gateway ${seen.gatewayKey} is gone with ${linkedSessions} session(s) linked and ${workingOrders} order(s) working${why}`,
      labels: labels({ linkedSessions: String(linkedSessions), workingOrders: String(workingOrders) }),
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
