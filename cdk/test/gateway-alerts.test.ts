import { AlertState, EMPTY_STATE, evaluate, Observation, START_STUCK_MS } from '../lambda/gateway_reconciler/alerts';
import { Activity, Outcome } from '../lambda/gateway_reconciler/reconciler';

const KEY = 'live:kalshi:main';
const T0 = Date.parse('2026-11-10T12:00:00Z');
const MINUTE = 60_000;

const ready = (linkedSessions = 0, workingOrders = 0): Activity => ({ state: 'READY', linkedSessions, workingOrders });
const starting: Activity = { state: 'STARTING', linkedSessions: 0, workingOrders: 0 };
const degraded: Activity = { state: 'DEGRADED', linkedSessions: 1, workingOrders: 0 };

function seen(outcome: Outcome, activity: Activity | null, serviceEvent?: string): Observation {
  return { gatewayKey: KEY, region: 'us-east-1', outcome, activity, serviceEvent };
}

function failed(error: string): Observation {
  return { gatewayKey: KEY, region: 'us-east-1', activity: null, error };
}

/** Runs ticks one minute apart (the first at T0), each through JSON as the state is kept, returning each tick's alert types. */
function ticks(observations: Observation[], start: AlertState = EMPTY_STATE): string[][] {
  let state = start;
  return observations.map((observation, i) => {
    const result = evaluate([observation], JSON.parse(JSON.stringify(state)), T0 + i * MINUTE);
    state = result.state;
    return [...result.firing.values()].map((alert) => alert.type);
  });
}

describe('a gateway starting', () => {
  it('is stuck once it isn\'t up 6 minutes after the reconciler started it, and clears when it is', () => {
    const minutes = START_STUCK_MS / MINUTE;
    const observations = [
      seen('started', null),
      ...Array.from({ length: minutes - 1 }, () => seen('running', starting)),
      seen('running', starting, 'unable to place a task: no container instance met all of its requirements'),
      seen('running', ready()),
    ];
    const types = ticks(observations);

    expect(types.slice(0, minutes)).toEqual(Array(minutes).fill([]));
    expect(types[minutes]).toEqual(['GATEWAY_START_STUCK']);
    expect(types[minutes + 1]).toEqual([]);
  });

  it('says why in the title, from the service\'s newest event', () => {
    const first = evaluate([seen('started', null)], EMPTY_STATE, T0).state;
    const late = evaluate([seen('running', null, 'has stopped 1 running tasks')], first, T0 + START_STUCK_MS);
    expect(late.firing.get(`GATEWAY_START_STUCK:${KEY}`)?.title).toContain('has stopped 1 running tasks');
  });
});

describe('a gateway waiting for its old deployment to drain', () => {
  it('counts its start from the first tick it began, not each tick it waits', () => {
    const minutes = START_STUCK_MS / MINUTE;
    const types = ticks([
      seen('starting', null),
      ...Array.from({ length: minutes - 1 }, () => seen('starting', null)),
      seen('started', null),
    ]);
    expect(types[minutes]).toEqual(['GATEWAY_START_STUCK']);
  });
});

describe('a gateway whose endpoint has expired', () => {
  it('counts as gone, as if it had none', () => {
    const up = evaluate([seen('running', ready(1, 2))], EMPTY_STATE, T0);
    const quiet = evaluate([seen('running', { ...ready(1, 2), expired: true })], up.state, T0 + MINUTE);
    expect([...quiet.firing.values()].map((a) => a.type)).toEqual(['GATEWAY_DOWN']);
  });
});

describe('a gateway going away', () => {
  it('is down when it vanishes while orders work, with what it held, and pages by its labels', () => {
    const up = evaluate([seen('running', ready(1, 2))], EMPTY_STATE, T0);
    const down = evaluate([seen('running', null)], up.state, T0 + MINUTE);

    expect(up.firing.size).toBe(0);
    expect(down.firing.get(`GATEWAY_DOWN:${KEY}`)?.labels)
      .toMatchObject({ gateway: KEY, exchange: 'kalshi', linkedSessions: '1', workingOrders: '2' });
  });

  it('is no outage when it was stopped as asked', () => {
    expect(ticks([seen('running', ready(1, 2)), seen('stopped', null), seen('stopped already', null)]))
      .toEqual([[], [], []]);
  });

  it('is nothing to page about when it went with no session or order', () => {
    expect(ticks([seen('running', ready()), seen('running', null)])).toEqual([[], []]);
  });

  it('stops being down once ECS has it back, and is stuck if that doesn\'t come up in time', () => {
    const minutes = START_STUCK_MS / MINUTE;
    const types = ticks([
      seen('running', ready(1, 2)),
      seen('running', null),
      ...Array.from({ length: minutes }, () => seen('running', starting)),
      seen('running', ready(1, 2)),
    ]);

    expect(types[1]).toEqual(['GATEWAY_DOWN']);
    expect(types[2]).toEqual([]);
    expect(types[1 + minutes]).toEqual(['GATEWAY_START_STUCK']);
    expect(types[2 + minutes]).toEqual([]);
  });
});

describe('a gateway up but unwell', () => {
  it('is degraded after two ticks DEGRADED, and clears on READY', () => {
    expect(ticks([seen('running', degraded), seen('running', degraded), seen('running', ready(1))]))
      .toEqual([[], ['GATEWAY_DEGRADED'], []]);
  });

  it('fails to reconcile after three ticks in a row, keeping what it knew, and clears on the next good one', () => {
    const types = ticks([
      seen('running', ready(1, 1)),
      failed('AccessDenied'),
      failed('AccessDenied'),
      failed('AccessDenied'),
      seen('running', ready(1, 1)),
    ]);

    expect(types).toEqual([[], [], [], ['GATEWAY_RECONCILE_FAILED'], []]);
  });
});

describe('what the alerts keep', () => {
  it('names every alert left firing by key, so the next tick can resolve those that cleared', () => {
    const { state } = evaluate([seen('running', null)], evaluate([seen('running', ready(0, 1))], EMPTY_STATE, T0).state,
      T0 + MINUTE);
    expect(state.firing).toEqual({ [`GATEWAY_DOWN:${KEY}`]: 'GATEWAY_DOWN' });
  });

  it('forgets a gateway no longer listed', () => {
    const { state } = evaluate([], evaluate([seen('running', ready(1, 1))], EMPTY_STATE, T0).state, T0 + MINUTE);
    expect(state).toEqual(EMPTY_STATE);
  });
});
