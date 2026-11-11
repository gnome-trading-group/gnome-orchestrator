import {
  AlertState, EMPTY_STATE, evaluate, Observation, RESTARTED_HOLD_MS, START_STUCK_MS,
} from '../lambda/gateway_reconciler/alerts';
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

  it('only warns when it stopped on its own but its planned stop cancelled everything first', () => {
    const stopping = (workingOrders: number): Activity => ({ state: 'STOPPING', linkedSessions: 0, workingOrders });
    const up = evaluate([seen('running', ready(1, 2))], EMPTY_STATE, T0);
    const cancelling = evaluate([seen('running', stopping(2))], up.state, T0 + 10_000);
    const done = evaluate([seen('running', stopping(0))], cancelling.state, T0 + 20_000);
    const gone = evaluate([seen('running', null)], done.state, T0 + MINUTE);

    expect([cancelling, done].map((tick) => tick.firing.size)).toEqual([0, 0]);
    const down = gone.firing.get(`GATEWAY_DOWN:${KEY}`);
    expect(down?.labels).toMatchObject({ linkedSessions: '1', workingOrders: '0', cleanStop: 'true' });
    expect(down?.title).toContain('its planned stop cancelled every order');
  });

  it('is down with the orders it last said it held when its planned stop didn\'t finish', () => {
    const stopping: Activity = { state: 'STOPPING', linkedSessions: 1, workingOrders: 1 };
    const types = ticks([seen('running', ready(1, 2)), seen('running', stopping), seen('running', null)]);
    expect(types).toEqual([[], [], ['GATEWAY_DOWN']]);
    const up = evaluate([seen('running', ready(1, 2))], EMPTY_STATE, T0);
    const partway = evaluate([seen('running', stopping)], up.state, T0 + MINUTE);
    const gone = evaluate([seen('running', null)], partway.state, T0 + 2 * MINUTE);
    expect(gone.firing.get(`GATEWAY_DOWN:${KEY}`)?.labels).toMatchObject({ workingOrders: '1' });
    expect(gone.firing.get(`GATEWAY_DOWN:${KEY}`)?.labels?.cleanStop).toBeUndefined();
  });

  it('forgets a stop once it is up again', () => {
    const stopped: Activity = { state: 'STOPPING', linkedSessions: 0, workingOrders: 0 };
    const up = evaluate([seen('running', ready(1, 2))], EMPTY_STATE, T0);
    const stop = evaluate([seen('running', stopped)], up.state, T0 + MINUTE);
    const back = evaluate([seen('running', ready(1, 3))], stop.state, T0 + 2 * MINUTE);
    const gone = evaluate([seen('running', null)], back.state, T0 + 3 * MINUTE);
    expect(gone.firing.get(`GATEWAY_DOWN:${KEY}`)?.labels).toMatchObject({ workingOrders: '3' });
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

describe('a gateway replaced without being asked', () => {
  const on = (task: string, activity: Activity | null, stoppedReason?: string): Observation =>
    ({ ...seen('running', activity), taskId: task, stoppedReason });

  it('is restarted when a new task appears while orders worked, saying why the old one stopped, for a while', () => {
    const hold = RESTARTED_HOLD_MS / MINUTE;
    const observations = [
      on('task-a', ready(1, 2)),
      on('task-b', starting, 'Essential container in task exited, exit code 137'),
      ...Array.from({ length: hold - 1 }, () => on('task-b', ready(1, 0))),
      on('task-b', ready(1, 0)),
    ];
    const types = ticks(observations);
    expect(types[0]).toEqual([]);
    expect(types[1]).toContain('GATEWAY_RESTARTED');
    expect(types[hold]).toContain('GATEWAY_RESTARTED');
    expect(types[hold + 1]).not.toContain('GATEWAY_RESTARTED');

    let state: AlertState = EMPTY_STATE;
    let alert;
    for (const [i, observation] of observations.slice(0, 2).entries()) {
      const result = evaluate([observation], state, T0 + i * MINUTE);
      state = result.state;
      alert = result.firing.get(`GATEWAY_RESTARTED:${KEY}`);
    }
    expect(alert?.title).toContain('exit code 137');
    expect(alert?.labels).toMatchObject({ fromTask: 'task-a', workingOrders: '2' });
  });

  it('is nothing to raise when no order was working', () => {
    expect(ticks([on('task-a', ready(1, 0)), on('task-b', ready(1, 0))]).flat()).not.toContain('GATEWAY_RESTARTED');
  });

  it('is no restart when the reconciler started the new task itself', () => {
    expect(ticks([
      on('task-a', ready(1, 2)),
      seen('started', ready(1, 2)),
      on('task-b', ready(1, 0)),
    ]).flat()).not.toContain('GATEWAY_RESTARTED');
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
