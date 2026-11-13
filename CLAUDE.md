# CLAUDE.md

gnome-orchestrator is the Java process a strategy session runs on its own EC2 instance: one JVM that wires the
session's market data gateways, the strategy, gnome-oms and the venue order gateways (gnome-gateways) over
sequenced ring buffers. The same jar also runs the market data shards (`shard/ShardOrchestrator`) on ECS.
Nobody starts a session by hand: the registry's `strategy-sessions/launch` launcher starts the EC2 instance, whose user
data writes `/etc/gnome/session.json` and starts the `gnome-strategy` systemd unit.

## Commands

- Build with Maven, never Gradle. Needs GitHub Packages credentials in `~/.m2/settings.xml` (internal deps).
- `mvn clean package` builds the shaded jar `target/gnome-orchestrator-<version>.jar`.
- `mvn test` runs the unit tests. One class: `mvn test -Dtest=group.gnometrading.orchestrator.session.lifecycle.SessionExitTest` (always
  the full class name).
- `@Tag("timing")` tests check timing on real sockets and threads, which a busy machine can fail: `mvn test` leaves
  them out; run them on a quiet machine with `mvn test-compile surefire:test@timing`.
- `mvn spotless:apply` fixes formatting. Spotless (palantir-java-format) and checkstyle both run at `validate` and
  fail the build, so run it before committing.
- `*LiveSmoke` (test package `live/`), `*Probe` (`live/probes/`), `*LiveCheck`, `*CrossCheck` under `src/test` are
  manual checks against real venues (run by hand with `-D` flags documented in each class). Surefire skips them; never
  wire them into `mvn test`.
- Local runs (Docker, same install script as the AMI; needs `~/.m2/settings.xml` and `~/.aws` with a `dev`
  profile): `docker compose up --build strategy` (a paper session; starts `collector` and `paper-gateway`, the paper
  gateway it trades through, too) or `collector` alone. `paper-gateway` runs without the registry: it takes only the
  session in its `GATEWAY_LOCAL_SESSION` (`gateway.local.session`: slot, strategy, listings and simulation, matching
  `strategy`'s `SESSION_SEQ`, `STRATEGY_ID` and `LISTINGS`), advertises nothing to `service-endpoints`, and simulates
  one venue (its `GATEWAY_KEY`'s), so a local session trades one exchange. The two share a network namespace and
  reach each other on 127.0.0.1.
  `docker compose stop strategy` sends the same SIGTERM an instance stop does. The `collector` service is shard
  `local`: put its item in the dev `collection-shards` table first (`shard_id` `local`, numeric `generation`,
  `streams` and `publish` as packed big-endian int32s, see `DynamoAssignment.pack`). `strategy` has no lease: its
  `MARKET_DATA_LOCAL_SHARD=collector:40000` stands in for one, so its listings' book streams must be in both lists.
- CDK: `cd cdk && npm ci && npx cdk synth` (needs an npm token for `@gnome-trading-group` packages; the gateway
  stacks look up the platform network, so a local synth needs `--profile pipelines`). `npm test` runs the gateway
  reconciler's tests. Deploys only happen through the pipeline; do not `cdk deploy` by hand.

## Layout

Everything is under `group.gnometrading.orchestrator`, laid out by process: `session/` and `shard/` are separate
programs that share only the layers below them (`aeron/`, `marketdata/`, `runtime/`, `aws/`, `credentials/`, `di/`), so neither
uses the other and the shared layers use neither. `LayoutTest` (ArchUnit) enforces it.

- `Main` - the only entry point anything outside this repo starts: `Main <role>` (`session`, `shard`, `gateway`;
  also the jar's `Main-Class`), or `Main.start(role, args)` from gnomepy's runner. Callers
  name a role, never a class, so classes can move; a new process is a new `Main.Role`. `start-session.sh` takes the
  role from `ORCHESTRATOR_ROLE` (default `session`).
- `session/SessionOrchestrator` - the session process: listings, the OMS, positions, ledger (with startup recovery)
  and risk sync, wired from the pieces beside it: `SessionBuffers` (every ring buffer on the session sequence and its
  journal source), `SessionStrategy` (python or java strategy), `SessionAgents` (start order, close order, heartbeat),
  `marketdata/SessionMarketData`, `orders/SessionOrders` (a gateway link per venue, live or paper, and the bound venue
  accounts) and `journal/SessionJournal`.
- `session/marketdata/`: `SessionMarketData` wires the shared `marketdata/` reader for a session. Every session reads its streams from shards, live ones from on-demand PRODUCTION shards,
  paper ones from RESEARCH shards. The launcher leases them (`MARKET_DATA_API_URL` set); a local run names one shard
  instead (`market.data.local.shard`, `MarketDataLeases.local`); a session with neither fails at start. A shard outage
  marks the book bad and the OMS refuses orders on it; the session doesn't exit. `ShardFeedAgent` (HOT_PATH) is the only writer of the journaled strategy buffer and of which subscription
  each stream reads; `ShardMarketData`'s control thread makes and closes subscriptions (they block on the driver),
  waits up to `market.data.start.timeout.seconds` for the lease to be live, polls it every 10 s, follows moves (switch
  on the new shard's first clean record; a move that doesn't deliver in 60 s, or the lease stops naming, is given up)
  and re-subscribes a stalled stream after closing the old subscription (so the new one joins at the live position).
  A stream is marked bad (its last record repeated with `badData`) when its shard's status stops for 5 s, nothing
  arrives for 15 s (also never), its image goes, the shard reports it reconnecting or reports records it couldn't send
  (`ShardStatus.dropped` rising), or it stalls behind the publication position (same Aeron session id) for 1 s. Each
  bad spell is logged with its reason ("Market data: stream N marked bad: ..."; for drops, the shard's total). A
  shard's re-send is marked in the Aeron frame's reserved value (`AeronReservedValues.RESENT`) and dropped only when
  it repeats the last record passed on byte for byte; unmarked repeats always pass (one venue message can carry
  several identical fills, which share every byte).
  The market-data path's threads (shard publisher and driver, session driver and `ShardFeedAgent`, i.e. the
  `AeronPathAgent`s and both `AeronRuntime`s) park at most `AeronPathIdle` when idle: each park delays a record
  that arrives during it, so on a quiet stream the parks add up along the path. Shards use
  `shard.market.data.idle.max.park.micros` (50: saves every subscriber ~1–1.5 ms for ~7% of the host's CPU); sessions
  `market.data.idle.max.park.micros` (1000; a session's config may lower it, but at 50 a t3.small runs ~15 points
  hotter, past its credits). Low-latency sessions spin their Aeron network thread and pin the ingress agent anyway.
  `MarketDataLeases` reads `GET /collection/leases/{sessionId}` with the `gnome/api-keys/market-data` key (read again
  on 401/403.
- `session/lifecycle/`: `SessionExit` (graceful exit), `SessionFatalHandler` (every error exit),
  `SessionHeartbeatAgent`, `SessionStatusReporter` (STARTING -> RUNNING), `SessionReports` (alerts + registry PATCH).
- `session/journal/`: `JournalManagerAgent`, `JournalSegmentSealer`, `JournalSource`.
- `session/orders/`: every session trades only through order gateways: one `GatewayLink` per venue (`live:<exchange>:main`
  on the venue account the registry bound it to, holding no trading key; or for paper `paper:<exchange>:main` on
  account 0, which simulates the venue; found in `service-endpoints`, or `order.gateway.local=<ip>:<port>`; the key's
  kind is the hello's mode; a run without a registry session names its slot in `session.seq`), opened by
  `GatewayLinks` on a control thread, and one `GatewayClient` agent reading the OMS's orders and writing its reports
  (no router). A NEW on a link not ready is rejected `GATEWAY_UNAVAILABLE`; cancels wait for the link; a gateway's
  `OrderStatus` becomes the reports the OMS lacks. The client starts, and every link is welcomed (within
  `order.gateway.start.timeout.seconds`), before startup recovery, which asks a live venue through it (a paper session
  asks nothing: it settles from the ledger alone, the simulated venue keeping nothing past a gateway's process)
  (`GatewayVenueOrderQuery`: `VenueQuery` for open orders and lookups, `CancelEnded` to cancel what an ended session
  of the strategy left; a query a dropped link lost is sent again). On close the client sends each ready link what it
  still holds, then a `SessionGoodbye` (`SESSION_EXIT`), so the gateway cancels and unlinks the session at once
  instead of after its silence (a goodbye not taken within 100 ms is left to that); the link is then `GONE`, which the
  control thread never reopens. A link that doesn't connect within 5 s (a crashed gateway's address, still in
  `service-endpoints` until its replacement writes) goes down, and the control thread looks the address up again. The slot is the registry's `session_seq`. The
  session's `AeronRuntime` is launched by `SessionOrchestrator` (shared by market data and the links) and closed
  last.
- `session/recovery/`: `StartupRecovery`.
- `session/rewards/`: `RewardsAgent` (BACKGROUND, paper sessions only, `rewards.enabled`): estimates the venue
  liquidity rewards the session's resting orders earn with gnome-simulation's `RewardScorer`, for listings whose venue
  `RewardVenues` supports. It reads the market-data, OMS-order and OMS-report rings through controlled lapping pollers
  (never gating them; adds no ring, so journal coverage is unchanged), merged by global sequence, rebuilding our resting
  orders from orders and reports; a lap marks every market a gap until its next book. It reloads programs from
  `/incentive-programs` every `rewards.programs.refresh.interval.ms` and posts to `/ledger/reward-accruals` every
  `rewards.post.interval.ms` and on close, on its own registry connection; failures are logged, never fatal.
- `shard/` - `ShardOrchestrator` (a shard: `SHARD_ID`), `ShardRuntime` (applies the assignment every 10 s,
  records state changes in `StreamTransitions`, opens an Aeron publication for each stream in the item's `publish`),
  `ShardStream` + `MarketDataCollectorAgent` (minute files, written by `MarketDataCollector` / `RawDataCollector`
  into pooled direct memory (`DirectBufferPool`, `CycleBuffer`, `CycleManager`) and uploaded off the data thread by
  `CycleUploader`), `StreamPublisherAgent` (drains every ring a second time,
  offers leased streams' records, re-sends a quiet stream's last record every 5 s (marked `RESENT`), sends the core SBE `ShardStatus`
  every 1 s; reads the rings through a controlled `LappingPoller`, so it never gates collection: a record its
  publication has no room for (one venue message can carry hundreds of fills, past the 128 KB window) waits in the
  ring, with the stream's later records, for the next pass; one the ring laps first is dropped and counted, and
  sessions see the count),
  `CollectorHeartbeat` log lines, `ShardStatusWriter` (status for the reconciler), `ShardIdentity`, `ShardAccounts`,
  `CollectorHealth`.
- `shard/feeds/` - `FeedVenues` (per `md.source.adapter`: the venue's `FEED` from gnome-gateways `venues/`, and its
  protocol built with the credentials this process loads, and its REST probe), `FeedSources` (adds a registry source,
  sized by its capacity columns), `WebSocketFeedOpener`, `Assignment` / `DynamoAssignment` (the shard's item in the
  reconciler's `collection-shards` table, us-east-1).
- `gateway/` - the order gateway (`Main gateway`, `GATEWAY_KEY` `live:<exchange>:<instance>`): one process per venue
  holding that venue's accounts (registry `venue-accounts/gateway`) for every live session. A paper gateway
  (`paper:<exchange>:<instance>`, any exchange the security master has; `GatewaySettings.paper()`) simulates its venue
  for paper sessions instead, in the same process with the live-only parts left out (no venue accounts, ownership log,
  restart take-back, startup sweep or PM Intl heartbeats): `gateway/simulated/`'s `SimulatedAccount` (account 0, which
  paper hellos name; `GatewayControl.servePaper()` takes only PAPER hellos and reports an order it doesn't know as
  CANCELED, lost with the simulated venue) runs one `SimulatedLane` thread for every paper session on the venue. Each
  session gets its own books (gnome-simulation's `MbpSimulatedExchange`, one per listing, built at its hello
  (`SimulatedAccount.Simulations`; deployed, `fromRegistry`) from its
  registry `config.simulation`, as the session's own simulation was; a re-hello keeps them) and its own budget and caps
  as if alone on a quiet account (its budget starts full, where a live one starts drained; gnome-gateways' `account/admission/` queue, caps, hold and expiry, and the venue's
  `VenueRateLimits` where it has order entry, `VenueAccounts.limitsFor`; none otherwise). Each pass: commands first,
  then each session's queued orders as its own budget allows, then at most 256 market-data records, then each book's
  arrivals and reports; a NEW on a book with no clean record yet is refused `GATEWAY_NOT_READY`; a record flagged bad
  fills nothing. Its books read from the shards through each session's own lease (`marketdata/ShardMarketData.shared`;
  `market.data.local.shard` for a local run). The paper profile: Aeron-path parks up to 1 ms, self-trade check `OFF`, a
  30 s watchdog limit for the simulated lane, alerts in paper mode, a minute line of its sessions, working orders and
  books' worst lag (the oldest record new to its stream when a book took it, from the venue's receipt: a book's
  first record and a shard's re-send of a quiet stream's last record don't count, being old without being late).
  Layered, and `LayoutTest`
  enforces it: `core/` is what every thread shares (`GatewaySession`, `SessionTable`, `GatewayMessage`, `ReplayRing`,
  `OrderTotals`, `GatewayStatus`, `JournalRecorder`, `AccountLink`, `CrossKey`/`CrossKeys`) and uses none of the rest; `accounts/` (accounts,
  their lanes' admissions and liveness, `GatewaySettings`), `transport/` (ingress, egress, `CrossIndex`,
  `OrderStatuses`, `GatewayRejects`), `control/` (`GatewayControl`, `HelloPages` + `Hello`, `SessionRegistry`, `SessionsFile`) and
  `journal/` (`GatewayJournal`, `OwnershipLog`) each use only `core/`; the root (`OrderGatewayOrchestrator`,
  `GatewayRestart`, `Watchdog`) assembles them. A session the gateway forgot (unlinked 10 min) that says hello again
  within the same epoch carries on its report numbering from its hello's `lastReportSeq`.
  - `OrderGatewayOrchestrator` builds a `GatewayAccount` per account through `VenueAccounts` (gnome-gateways'
    `AccountLane`, `AccountReader` + `AccountConnection`, and on Polymarket Intl `PolymarketIntlHeartbeats`, beating only
    while the lane's passes move, `LaneLiveness`), sized by `gateway.max.orders` and `gateway.lane.connections`.
  - Sessions reach it over Aeron on `gateway.ingress.port` (40010). `GatewayIngress` tells the sender by the image's
    Aeron session id (core orders carry no session), reads each order by its own SBE header (any core version whose
    block reaches the v2 fields it reads, so sessions and gateways change schema version apart), turns orders into lane commands or refuses them straight back
    (`GatewayRejects`); `GatewayEgress` numbers each session's reports into its `ReplayRing` (`gateway.replay.reports`)
    and sends them with the number as the frame's reserved value, at that session's pace, plus a 100 ms heartbeat;
    `GatewayControl` checks hellos against the registry (`SessionRegistry`, a 10-minute fallback cache), loads the
    listings they name, opens reply publications, sends the welcome and replay, cancels a silent or deaf session's
    orders (lane `CANCEL_SESSION`) and asks it to hello again, evicts a session that sends something unreadable
    (refused 5 min), keeps the state (STARTING/READY/DEGRADED) and writes `order-gateway#<key>` to `service-endpoints`
    every 15 s and at once on a change of state (sessions link only to one it calls READY or DEGRADED). Once a minute
    `GatewayMinutes` writes its last hour beside it, as JSON in `order-gateway#<key>#minutes` (10-minute TTL; none for
    a local run): per account each minute's sends, budget waits, holds and refusals, the venue's answer times, 429s and
    unanswered requests (`LaneAdmissions.minute`), and the last minute's sends per session slot (counted on the lane in
    presized maps, so nothing allocates there); for a paper gateway, its simulated venue's minutes. The registry's
    `GET /gateways/status` reads both items for the controller's Order Gateways page. SIGTERM is a planned stop: every session's orders cancelled, then, once the lanes are empty, a goodbye
    sent last (after those cancels' reports) before anything closes.
  - Caps: `gateway.session.max.open.orders` (200) and `gateway.session.max.new.per.second` (50) per session,
    `gateway.account.max.open.orders` (2,000) per account, in each lane (`AccountLane.Caps`); a session's hello may
    ask for less (`order.gateway.max.open.orders`, `order.gateway.max.new.per.second`, session properties), never
    more. Past one, a new order is refused `SESSION_CAP`; cancels and amends always go.
  - Kills behind the OMS (`control/GatewayKills`, its own thread, every second): reads the registry's enabled
    `KILL_SWITCH` rows (`KillSource.over`; `gateway.kills.file`, a JSON array of rows, instead when set, for runs on
    real venues), resolves sessions to slots and listing ids to keys, publishes `core/KillRules` through `Kills` for
    ingress to refuse covered new orders and amends `HALTED`, and sends each covered session's lane `CANCEL_ORDERS`
    (all its listings, or the kill's one) on the first two passes it covers it, so an order that slipped past the
    first sweep is caught by the second. Until the first read ingress refuses new orders `GATEWAY_NOT_READY`: at
    startup the kills are unknown, not none. Cancels go per session, so one strategy's kill never touches another's orders. Account
    kills (`risk.policy.account_id`) are the gateway's alone. A failed read keeps the kills in force (logged
    `GATEWAY_KILLS_STALE` past 30 s). No venue-wide cancel-all yet: an account or exchange kill cancels order by
    order.
  - Self-trade check across sessions and accounts (`CrossIndex`, ingress's): each listing's load returns its
    `CrossKey`, from the venue's `<Venue>Venue.crossMarketOf` in gnome-gateways (the market it matches on, and
    whether the listing prices its complement), published through `CrossKeys`. Every order counts from ingress's admission (so two sent within one round trip
    can't both pass) until egress, which sees every report, says it ended; an amend counts at both prices until
    accepted. A new order or amend that meets another session's order on the same market, in first-side terms, is
    refused `SELF_TRADE` before its lane (`gateway.self.trade.mode` `enforce`; `shadow` sends it and counts). A full
    index refuses `SESSION_CAP`. A restart refills it from the ownership log. Where a venue has its own prevention
    (`VenueAccounts`), it is set so the crossing order is the one cancelled, as a backstop. Matches a venue makes
    across markets (Polymarket Intl neg-risk conversions) aren't covered.
  - Each account's budget is shared fairly: an accepted hello introduces the session to its lane (`LaneCommand.SESSION`)
    with its strategy's `rate_weight` (registry) and its `order.gateway.max.queue.ms` (50), and a forgotten session
    gives its place up (`SESSION_ENDED`). A hello past `AccountLane.MAX_SESSIONS` (1,024) on one account is refused
    `SESSION_CAP`. Each lane's decisions go to the journal as `AdmissionRecord`s (sources 16+) through
    `LaneAdmissions`, which also counts them; every minute the gateway logs per account what was sent, how long
    cancels and orders waited (p99, max), what was held, refused or dropped, how long the venue took to answer orders
    and cancels and how many it never answered, how often every lane connection was in use, and any venue 429s
    (`VENUE_RATE_LIMITED`). It also logs, when any rose, what ingress and egress dropped (orders from unlinked
    images, session messages for a full control ring, reports for no session, orders untracked so missing from a
    resync, orders the self-trade index had no room for, ends lost to it), and how many orders the self-trade check
    refused or (shadow) would have.
  - Idle parks on the Aeron path: `gateway.idle.max.park.micros` (50). A lane or connector, ingress or egress failure
    exits the process; so does `Watchdog` when ingress, egress, a lane or a reader (unless paused for a reconnect) makes
    no progress for `gateway.watchdog.stall.millis` (3000), or control (counted per pass and per message) for a
    minute; it logs the stalled thread's stack and exits with its frames. Nothing on the control thread blocks past a
    deadline: registry records and listings 5 s (`runtime/BoundedCalls`, one client call at a time; a missed record
    falls back to the cache), each venue query 10 s (`accounts/BoundedOrderQuery`), one hello's order lookups 20 s in
    all; the endpoint item is written by `EndpointWriter` on its own thread (5 s call timeout), control only handing it
    the latest state. `/health` on :8080 fails when a lane stops.
  - `GatewayJournal` (when `journal.enabled`): ingress, egress and each lane record into their own ring on one global
    sequence, read through lapping pollers into a `skipsLost` `JournalWriter` under
    `<journal.directory>/gateway-<key>/<start millis>/`; each record is the session's slot (8 bytes LE, 0 if unlinked)
    then the message. A lap is written as a gap and logged `GATEWAY_JOURNAL_GAP`. Nothing ships it yet, so deployed
    gateways run with `JOURNAL_ENABLED=false`.
  - Starting sessions' venue questions (`control/VenueQueries`, on the control thread, blocking it while the venue
    answers): `VenueQuery` open orders and lookups by id with the account's own `VenueOrderQuery`, answered in
    `VenueQueryResult` pages (owners 0: recovery attributes by its ledger); `CancelEnded` cancels every order the
    strategy's ended sessions (not linked, not running in the registry) hold on the listing, then refuses if the named
    order still rests.
  - Restart (`GatewayRestart`, before any thread starts): under `gateway.state.directory/<key>/`, each account's
    `OwnershipLog` (memory-mapped, CRC-checked records written by the lane before each send: ADD, VENUE_ID, END; a torn
    tail counts as never written; rewritten with the open orders when `gateway.ownership.log.mb` fills) gives back its
    open orders, handed unsent to the lane and to the reader (whose first catch-up reaches back to the oldest), and
    `SessionsFile` the sessions accepted (whose slot, cod timeout, registry record for the fallback cache). A recovered
    session's first hello gets a welcome then one `OrderStatus` per order (`OrderStatuses` sums its ring), never the
    reports themselves, and is reconciled: an order it lists that the log never had is reported REJECTED, one it is
    cancelling is cancelled again, one it doesn't list is cancelled (`GATEWAY_UNATTRIBUTED_EVENTS`). A session not back
    within 10 s of READY plus its cod timeout has its orders cancelled. A hello past its replay ring gets statuses too.
    A session new to the gateway (not recovered, not held) that lists open orders has each looked up at the venue and
    reported as the venue has it, still-resting ones cancelled: they were another gateway's.
  - Startup sweep (`StartupSweep`, its own thread): the ownership log is on the host, so a gateway back on another host
    starts with none. 30 s after start (plus the longest cod timeout of the sessions it took back) it lists each
    account's open orders (`VenueOrderQuery.listAllOpenOrders`, through the account's bounded query) and cancels those
    the venue created before this process started that the restart doesn't hold, alerting `GATEWAY_UNATTRIBUTED_ORDERS`
    for each, named as the session knows it: where the venue's id names a slot rather than the session (Polymarket US,
    `VenueOrderQuery.ownerOf`), `<session>-<counter>`, the session found by `session_seq` in the registry, else the
    slot. A list that fails is retried every 10 s, alerting `GATEWAY_SWEEP_FAILED` past 5 minutes.
  - Tests: `GatewayFixture` (test scope, from gnome-gateways' test jar) runs a whole gateway in process on a fake
    venue; `session/orders/GatewayInProcessTest` drives sessions through it, restart included. `PaperGatewayFixture`
    runs a paper gateway on books the test feeds (re-sending each quote every 20 ms, as a shard re-sends a quiet
    stream), driven by `PaperGatewayInProcessTest`; `SimulatedParityTest` holds the paper gateway to the reports
    sessions' own simulation gave (`src/test/resources/paper/`).
  - Whether it runs is not its own decision: the gateway reconciler (`cdk/lambda/gateway_reconciler`) starts and stops
    it. Its endpoint item
    (every 15 s) carries `linked_sessions` and `working_orders`, which the reconciler reads; scaled to zero, ECS sends
    SIGTERM and the planned stop runs, which writes the item once more at its end (STOPPING, what it still holds) and
    waits up to 2 s for that write, so a stop the reconciler didn't ask for (host shut down) is seen to have left
    nothing. Sessions say they want it through `POST /gateways/ensure` (`GatewayLinks`: every
    10 s until linked, then every minute).
- `marketdata/` - reading market-data streams from collector shards over Aeron, shared by sessions and order gateways
  (uses neither `session/`, `shard/` nor `gateway/`; `LayoutTest`). `ShardFeedAgent` (HOT_PATH, zero allocation per
  record) is the only writer of its ring and of which subscription each stream reads; `ShardMarketData`'s control
  thread makes and closes subscriptions (they block on the driver), polls each lease (`MarketDataLeases`, `GET
  /collection/leases/{sessionId}`; `MarketDataLeases.ApiKey.fromSecret` reads the key) every 10 s, follows moves and
  re-subscribes stalled streams, and streams are marked bad exactly as described under `session/marketdata/`. A
  session builds it with its fixed feeds and one lease, and `start` waits up to `market.data.start.timeout.seconds`
  for every stream to be live. A process reading for several sessions uses `ShardMarketData.shared(logger, aeron,
  localIp, ring, nanoTime)`: `start` waits for nothing; `addSession(sessionId, leases, streamIds)` and
  `removeSession(sessionId)` (any thread, applied on the next 100 ms control tick) count each stream across sessions,
  so it is read while at least one session names it and dropped when the last goes; a stream several sessions name is
  one subscription, placed by the earliest such session's lease. Every record goes into the one ring given.
- `aeron/` - the Aeron layer shards, sessions and order gateways share:
  `AeronRuntime` (embedded media driver + client per process, `AeronProfile` per host kind), `AeronPathIdle` (how long
  Aeron-path agents and drivers park; `AeronPathAgent` marks them), `AeronChannels`
  (shard MDC publications with `fc=max`, per-shard tagged wildcard receive ports), `AeronPorts` (split of UDP
  40000-40999; mirrors gnome-shared-cdk `AERON_PORT_MAP`), `AeronStreamIds` (market data = `stream_id`, >= 2^30
  reserved), `AeronIngressAgent` (one thread polls every subscription into sinks, zero allocation; a sink with no room
  leaves the message in Aeron; removal takes effect next pass and the caller closes, since closing blocks), `ServiceEndpoints` (the `service-endpoints` table, us-east-1), `HostAddress`.
  A shard's 1 s status is core SBE `ShardStatus` (gnome-schemas; per published stream its state and publication
  position), never journaled. `AeronTransportTest`
  pins the Aeron behaviour the design relies on: re-run it on every Aeron upgrade (a lagging subscriber stalls
  silently rather than losing its image; a fixed receive port can't serve two shards).
- `runtime/` - what every process runs on: `AgentRuntimeInstaller` (latency profile / CPU pinning), `ProcessExit`,
  `GatewayErrorHandler`, `GatewayRunners` (order gateways), `GatewayHealth`, `HealthCheckServer`, `DaemonThreads`,
  `AgentUtils`, `LatencyHistogram`, `SchedulingLatencyProbe`, `ClockReadings` (the host clock's source and error
  bound from `/run/gnome/clock.status`, read in place without allocating; in the session heartbeat's `clock`, the
  shard's process line and the gateway's minutes).
- `credentials/` - venue keys as stored in Secrets Manager (`KalshiCredentials`, `PolymarketIntlCredentials`,
  `PolymarketUsCredentials`), read by sessions' order entry and shards' feeds.
- `aws/` - DI modules: `PropertiesModule`, `AwsModule`, `RiskModule`, `SecurityMasterModule`, `SecretCache`.
- `di/` - the in-house DI container (`Orchestrator`, `@Provides`, `@Singleton`, `@Named`, child orchestrators).
- `src/test/.../shard/feeds/FeedLiveCheck` - manual check of live markets through the shards' feeds (`-Dmarkets`,
  `-Daccount`, `-Draw`, `-Ddrop`).
- `src/main/resources/orchestrator.{dev,prod}.properties` - per-stage defaults.
- `ami/` - Image Builder inputs: `scripts/install-runtime.sh`, `scripts/tune-kernel.sh`, `files/run-strategy.sh`
  (EC2 wrapper), `files/start-session.sh` (shared with Docker), systemd units, `files/journal-shipper.py` (+ test).
- `cdk/` - `OrchestratorPipelineStack`, `lib/stacks/{storage,network,ec2,ami,emergency-cancel}-stack.ts`. Ignore
  stale local `ecs-stack.js` / `ecr-stack.js` (untracked build output, no source).
- `cdk/lambda/emergency_cancel/` - the order gateway's break-glass, Python, sharing no code with gnome-gateways:
  lists one venue account's open orders with its trading key, cancels each (the gateway's own verified endpoints and
  signing, no venue-wide cancel-all), lists again, and puts an `EMERGENCY_CANCEL` alert on `gnome-alerts`. Tests run
  against local fake venues that check real signatures: `pip install cryptography boto3 pytest` in a venv, then
  `AWS_REGION=us-east-1 python -m pytest cdk/lambda/emergency_cancel/tests`. `cdk synth` bundles it in Docker.

## Conventions and invariants

- Hot path (agents, gateways, OMS, journal, heartbeat encoding) is zero-allocation and GC-free. Allocation goes on
  side threads (collector heartbeat, journal shipper outside the JVM). Check every change on an agent's loop for this.
- Prices are scaled x1e9 and sizes x1e6 (`long`), everywhere.
- Properties: every env var becomes a property (`STRATEGY_ID` -> `strategy.id`, lowercased, `_` -> `.`), which is
  lossy. Structured values travel as one JSON env var: `STRATEGY_ARGS_JSON`, `SIMULATION_JSON`, `LISTINGS`.
  A new property needs a default in both `orchestrator.*.properties` files: the pipeline publishes those keys to SSM
  and the launcher rejects per-session overrides the session's version does not list. Mode-keyed settings
  (`risk.<mode>.*`) must exist for both `live` and `paper`; a missing one fails startup on purpose.
- Exact replay: strategy market data plus every trading buffer (intents, OMS orders, exec reports, metrics) share one
  session `GlobalSequence`, and every buffer on it must be journaled. `SessionJournal.verifyCoverage` fails startup for a
  buffer with no `JournalSource`, even with the journal off. Create pollers before the producers start.
- Time: every process's `EpochNanoClock` is gnome-core's `SampledEpochNanoClock` (allocation-free; Agrona's
  `SystemEpochNanoClock` builds an `Instant` per read). Every ring stamps each event when it is published, and the
  journal (format 2: a `GNJ2` segment header, 22-byte record headers) keeps the stamp; `journal.json` names the
  format. gnomepy reads format 2 and the older 14-byte format.
- Causes (core v3): each intent carries the event the strategy was handling (`triggerSequence`, `TriggerKind`, ring
  arrival, platform receipt) and when it started (`dispatchTimestamp`); each order, modify and cancel the OMS sends
  carries its `intentSequence` and that trigger (gnome-oms `OrderCause`), or `EXEC_REPORT`/`RISK` for the OMS's own
  actions. Journal sequences join them: an order's `intentSequence` and `triggerSequence` are records in the same
  journal.
- Close order matters: a producer blocks on a full ring whose reader has closed. Keep `SessionAgents.close` ordered
  market data -> strategy -> OMS -> gateways -> ledger -> journal -> heartbeat.
- Every process starts with `java -jar` and takes its module access from the jar's manifest (`Add-Opens`/`Add-Exports`
  in the shade plugin, kept equal to gnomepy's `DEFAULT_JVM_ARGS` minus jdk.compiler, which the Python runner passes to
  JPype instead). Sessions' other JVM options live only in `src/main/resources/session-jvm-options` (one per line, at
  the jar root), which `start-session.sh` passes to `java -jar` and gnomepy's runner to JPype; the gateway's GC choice is in `gateway-start.sh`, a shard's in gnome-market-data's `shard-start.sh`. Add no
  other JVM flags to scripts, units or Dockerfiles here. A release without the manifest entries can't start this way.
- Java 17 (parent pom, `openjdk-17` in the AMI, `eclipse-temurin-17` in Docker). Do not bump it.
- One session per instance. `run-strategy.sh` refuses a second start; any exit of the unit shuts the instance down
  (launch template turns shutdown into terminate). Do not add restarts.
- No backward-compat shims or deprecated stubs: change callers and remove unused code outright.

## Operations

**Deploys.** Push to `main` -> `bump-and-deploy.yml` (gnome-build-tools) cuts a Maven release and tag ->
`push-release.yml` force-pushes the tag to `release` -> `OrchestratorPipeline` (CodePipeline in the InfraPipelines
account) deploys stage `Dev`, waits on `ApproveProd`, then `Prod`. Stacks per stage: `<Stage>-OrchestratorStorageStack`
(journal bucket `gnome-journals-<stage>`, SSM params), `<Stage>-OrchestratorAmiStack` (us-east-1, AMIs copied to all
regions), and per region in us-east-1, ap-northeast-1, eu-west-1: `<Stage>-OrchestratorEcsStack-<region>` (VPC,
security group, log group; the name is historical, renaming recreates the VPC) and `<Stage>-OrchestratorEc2Stack-<region>`
(launch templates `gnome-orchestrator` and `gnome-orchestrator-live`; no session role can read `gnome/accounts/*`;
live ones can read `order-gateway#*` items in `service-endpoints`), `<Stage>-OrchestratorOrderGatewayStack-<region>`
(where live order gateways run, with nothing about which: ECS cluster `order-gateways`, on-demand `c7a.large` hosts
(`c7i.large` when no `c7a.large` launches) in the region's precision-time placement group, scaling from zero
(capacity provider `order-gateway-hosts`), base task definition `order-gateway` (a Java 17 runtime and
`cdk/lib/stacks/gateway-docker/gateway-start.sh`, which runs `java -jar <release> gateway` as its child: the jar's
manifest carries the module access gnomepy's JVM args give sessions; it forwards SIGTERM to Java and, once `/health`
has answered, kills Java (`kill -9`) when it hasn't answered for 15 s, so a frozen JVM is restarted in seconds rather
than after ECS's health check and 120 s stop timeout), state on a host volume, logs in
`/gnome/order-gateway/<region>`), and `<Stage>-OrchestratorOrderGatewayReconcilerStack` (us-east-1 only): the gateway
reconciler, Lambda `GatewayReconciler` (`cdk/lambda/gateway_reconciler`, TypeScript; one tick at a time from FIFO queue
`gateway-reconciler.fifo`, woken by the registry's `POST /gateways/ensure`, which the launcher and sessions call, and
every minute; names in `cdk/lib/config.ts` `ORDER_GATEWAYS`, given to the Lambda in its environment). It reads the registry's gateway rows and runs a gateway while it was wanted in
the last 30 minutes or reports sessions or orders (or, running, went quiet while it had either: it may be restarting
with orders; one that never wrote an endpoint holds nothing), in its row's region, as service
`order-gateway-<key with : as ->` from its own revision (key, release and `ALERTS_API_URL` filled in), and allocates
its Elastic IP (tag `gnome:order-gateway`), which `gateway-start.sh` attaches at boot; otherwise it sets the service to
zero. When it starts or creates one, it raises the region's host group (found through the capacity provider) to one
host per gateway set to run, at once, rather than leave it to managed scaling's alarm (~2 min); it never lowers it. Paper gateways (`paper:<exchange>:main`, rows the registry creates on first want, in the exchange's region) run in the same cluster on hosts of their own, `order-gateway-paper-hosts` (on-demand `c7a.medium`, scaling from zero, max 4 per region), from base task definition `order-gateway-paper` (same image and `gateway-start.sh`, 1536 MiB reservation, 384 MiB `/dev/shm`, task role `gnome-order-gateway-paper-task-<region>`: the market-data key `gnome/api-keys/market-data` in us-east-1, the gh token, the registry key and `order-gateway#*` endpoint writes, but no `gnome/accounts/*` and no Elastic IP actions). The reconciler reads rows of every mode; a row's mode picks its base family and capacity provider (`ORDER_GATEWAYS.modes`), a paper revision also gets `MARKET_DATA_API_URL` and `MARKET_DATA_API_KEY_SECRET` to read its sessions' leases, and paper gateways get no Elastic IP (`gateway-start.sh` skips attaching one for a `paper:` key and gives a paper JVM half the host for heap); host raises count only the services on that mode's provider; its alerts carry the row's mode, so paper gateways' never page. `gateway-start.sh` sets ZGC's `ConcGCThreads` from the host's CPUs (1 on a 1-vCPU host, otherwise 2). Paper and live sessions' roles may both read `order-gateway#*` in `service-endpoints`. A start on a new revision moves the service onto it at zero and raises the count on a later tick, once the old
deployment has drained: raised sooner, ECS starts a task of the previous release on the old one),
and `<Stage>-OrchestratorEmergencyCancelStack-<region>`: Lambda `EmergencyCancel` (event names the registry's
exchange code; run in the exchange's region, `sm.exchange.region`, where its gateway runs; reads only that region's
`gnome/accounts/*/trading`; 5-minute timeout, never retried, dropped unless started within a minute). Started from the controller's Treasury
(registry `POST /venue-accounts/emergency-cancel`, async) or by `aws lambda invoke` (registry runbook
`stop-or-kill.md`).

**How a version reaches sessions.** The jar is not baked into the AMI. A strategy session's commit pins it: the
registry launcher reads `platform.orchestrator` from gnome-strategies' `strategies/pom.xml` at a java session's
`strategy_commit`, or `orchestrator=` / `gnomepy=` from gnomepy-research's `platform.properties` at a python session's
`research_commit`, sets `ORCHESTRATOR_VERSION` (and `GNOMEPY_VERSION` for python), and `start-session.sh`
downloads that jar from GitHub Packages at boot. A release reaches sessions only through a commit that pins it. SSM
`/gnome/orchestrator/latest-version` (written by StorageStack only for non-SNAPSHOT releases) is for gateways and
collectors. Property keys and defaults per release are in
`/gnome/orchestrator/properties/latest` and `/gnome/orchestrator/properties/<version>`. AMIs (`16core`, `32core`,
`48core`, `standard`) rebuild only when `ami/` content changes; `AmiPublisher` writes `/gnome/orchestrator/ami/<variant>`
per region after image tests pass. Roll back an AMI by repointing that parameter (images kept 14 days).
Collectors pull the same jar (latest unless pinned) into the gnome-market-data collector image.

**How a Java strategy reaches a session.** Production Java strategies live in gnome-strategies' `strategies/` and run
by commit, not release.
- With `STRATEGY_COMMIT` set, `start-session.sh` clones gnome-strategies at it and checks that the commit's
  `strategies/pom.xml` pins (`<platform.orchestrator>`) the orchestrator this session fetched; a mismatch fails the
  session before it starts.
- It then compiles `strategies/src/main/java` with `javac --release 17 -parameters` against `app.jar` into
  `STRATEGY_CLASSES` (`/opt/gnome/strategy-classes`; a few seconds; the AMI and the Docker image have JDK 17).
- `SessionStrategy` loads `strategy.class` from there through a `URLClassLoader` whose parent is this jar's
  (`StrategyFactory.create(loader, …)`), so the runtime, OMS and schemas stay the session's own.
- Without `STRATEGY_COMMIT` the class comes from this jar.
- A local session can set `STRATEGY_CLASSES` to a mounted directory of locally compiled classes, which skips the
  clone.
- Failures: `has no platform.orchestrator`, `runs on gnome-orchestrator X; this session fetched Y` (the commit pins a
  different release), a `javac` error (the commit doesn't compile against this release), `STRATEGY_CLASSES … is not a
  directory`.

**Session lifecycle.** SUBMITTED -> STARTING (launcher) -> RUNNING (this process PATCHes after agents start; the
first heartbeat also counts) -> STOPPING -> STOPPED or FAILED (set by the registry's session monitor when the
instance goes away). On boot it runs `StartupRecovery` (inherits positions, and in live open venue orders, from
earlier sessions). A session already stopped before startup exits 0 without reporting.
- Heartbeat: every 5 s to `/api/strategy-sessions/heartbeat` (agents' `stalledMs`/`exited`, per-listing
  `priceUnchangedMs` and `marketDataBadMs` (how long its book has been flagged bad data, the OMS refusing new orders;
  0 trusted), ledger, market data `reconnecting`/`resyncsLast5m`, each order gateway link's `linked`/`unlinkedMs`/
  `workingOrders`, risk, host CPU/mem/disk, `clock` (source `PHC`/`NTP`/`UNSYNCED`/`UNKNOWN`, `errorBoundNanos`,
  `offsetNanos`, `ageMs`), journal backlog). Stops once the registry says the session ended.
- Clock: every host (the AMI's `clock` component; shard and gateway hosts' user data) runs gnome-shared-cdk's host
  clock: `gnome-clock-setup` switches chrony to the Nitro hardware clock (PHC, ~20 µs) when the instance is in its
  region's precision-time placement group (platform network, gnome-shared-infra), otherwise leaves it on Amazon Time
  Sync (~0.1 ms; ~0.5 ms on t3); `gnome-clock-status` rewrites `/run/gnome/clock.status` every 10 s (containers mount
  `/run/gnome` read-only). In the group: low-latency sessions (the launcher), live gateway hosts and on-demand shard
  hosts.
- Journal: segments under `/var/lib/gnome/journal/<sessionId>/`; `gnome-journal-shipper` uploads them to
  `gnome-journals-<stage>/<sessionId>/` and ships open segments after the strategy unit stops. Strategy metrics live
  only in the journal.
- Graceful exit: the strategy requests exit -> `SessionExit` sends `SESSION_EXITED`, PATCHes STOPPING with
  `stoppedBy=strategy`, closes, exits 0.
- Operator stop: the registry terminates the instance -> SIGTERM -> shutdown hook closes agents (60 s
  `TimeoutStopSec`). Errors during shutdown are not reported.
- Shards: ECS tasks started by gnome-market-data's reconciler (`Main shard`, `SHARD_ID`,
  `ORCHESTRATOR_VERSION`). Streams come from the shard's item in `collection-shards` (us-east-1): `generation`,
  `streams` and `draining` (packed int32s, both collected), `accounts`, and `cuts`; each 10 s poll reads only the
  generation and fetches the item when it changed, so streams are added and removed without a restart.
  - Minutes go straight to the final bucket (`OUTPUT_BUCKET`, `gnome-market-data-<stage>`) at
    `{stream}/book/mbp-10/YYYY/MM/DD/hh/mm.zst`, with a conditional put: the first writer keeps a minute, and a 412
    counts as done. There is no raw copy and no merger.
  - `cuts` (packed (stream, minute-since-epoch) pairs) bound which minutes a stream writes, by file, never by a
    record's event time. In `streams`, writing starts at that minute (`Integer.MAX_VALUE` = warming, write nothing);
    in `draining`, writing stops before it. A hand-off therefore cuts at a minute boundary, and each minute is
    written once, complete.
  - Every 15 s, after the heartbeat, the shard overwrites its status at
    `s3://<STATUS_BUCKET>/collection/shards/<shard>/status/<task>.json` (gnome-market-data-metadata-<stage>): task
    id, private IP, version, applied generation, the heartbeat's process line, connections, every stream's state and
    timestamps, and the latest 256 state changes.
  - `/health` on :8080 fails when the agent stops cycling for 30 s or an upload hangs 5 min, and ECS replaces the
    task. A failing market is quarantined and retried, a failing connection keeps retrying; neither stops the process.
  - Raw frames go per connection to `raw/<source>/<shard>/<connection>/...` in `RAW_CAPTURE_BUCKET`.
  - No registry heartbeat: `HEARTBEAT` log lines every 15 s (process, connection, and a stream only when its state
    changed; a `publish` line when a stream held or dropped records for sessions since the last: `held` counts offers
    a full publication refused and retried (a burst, nothing lost), `lapped` the records lost because the ring went
    round first) plus the status above.
- Market-data credentials: a shard signs Kalshi and Polymarket US in with the source's market-data account it is bound
  to by label (its item's `accounts`, source to label; `md-1` when unnamed), looked up in the registry's
  `md.source_account` as each connection opens; its key is `gnome/market-data/<source>/<label>`. A missing or retired
  account fails only that source's markets on the shard ("No market-data account md-1 for KALSHI"), retried like any
  failed start; each connection's heartbeat line names its account. Sessions still use `gnome/market-data/<exchange>`.

**Logs.** Sessions: log group `/gnome/orchestrator/<region>`, stream `ec2/<instanceId>` (orchestrator) and
`ec2/<instanceId>/journal-shipper`; retention 1 month. On-box: `/var/log/gnome/orchestrator.log`. The last lines
before a failure are usually the cause (`FATAL_ERROR_EXITING`). Collectors: `/ecs/collector-<region>`, prefix
`collector` (gnome-market-data).

**Alerts.** A session sends only two, to `ALERTS_API_URL` (launcher sets it from SSM `/gnome/alerting/api-url`;
unset means no alerts; posted through `runtime/AlertsApi`):
- `FATAL_ERROR` - any agent error, gateway that can't reconnect, failed startup, or uncaught exception. Also PATCHes
  `failureReason`, then exits 1. Check the log stream tail and the failure reason.
- `SESSION_EXITED` - INFO on a graceful exit; WARNING when positions were left (too small, rejected, timed out).
  Check the listed positions on the venue. A clean exit is labelled `route: silent` (recorded, not posted to Slack)
  unless the strategy's request set `notifyExit`.
- Everything else about a session is raised by the registry's session monitor from heartbeat fields
  (gnome-registry `cdk/lambda/monitoring/rules/sessions.ts`): `AGENT_STALLED`, `AGENT_EXITED`, `RECONNECTING`,
  `BOOK_RESYNCS`, `MARKET_DATA_BAD`, `MARKET_DATA_BAD_HELD`, `LEDGER_FAILING`, `LEDGER_FENCED`, `RISK_STALE`, `RISK_UNGUARDED`, `POLICY_UNBUILDABLE`,
  `LATCHES_DROPPED`, `HOST_CPU_HIGH`, `HOST_MEMORY_LOW`, `DISK_LOW`, `JOURNAL_BACKLOG`, `CLOCK_UNSYNCED`,
  `SILENT`, `ORDER_GATEWAY_UNLINKED` (live, a minute unlinked from a gateway with orders working there); lifecycle ones (`START_STUCK`, `STOP_STUCK`, `FAILED`) come from other monitor rules.
- An order gateway raises what only it sees (`core/GatewayAlerts`, posted by `IngestGatewayAlerts` to the
  `ALERTS_API_URL` the reconciler puts in its revision), keyed `<TYPE>:<gateway key>[:<account or order>]`:
  `GATEWAY_KILLS_STALE` (kills unread 30 s; resolved by the next read), `GATEWAY_VENUE_RATE_LIMITED` and
  `GATEWAY_ORDERS_UNANSWERED` (per account, a minute with any; resolved by a minute without),
  `GATEWAY_UNATTRIBUTED_ORDERS` (a restart or its startup sweep cancelled an order nobody knew), `GATEWAY_SWEEP_FAILED`
  (the sweep couldn't list an account's orders for 5 minutes) and `GATEWAY_FATAL` (sent,
  waiting up to 2 s, before a failing gateway halts; one per process).
- The gateway reconciler raises, per gateway, on the `gnome-alerts` bus (`cdk/lambda/gateway_reconciler/alerts.ts`,
  firing keys and per-gateway memory on the `service-endpoints` item `order-gateway-reconciler#alerts`):
  `GATEWAY_START_STUCK` (running, not READY or DEGRADED 6 min after it started), `GATEWAY_DOWN` (its endpoint expired
  while it last showed sessions linked or orders working, without being asked to stop; CRITICAL with orders working,
  WARNING with `cleanStop` when its planned stop's last write said it held nothing),
  `GATEWAY_DEGRADED` (2 ticks), `GATEWAY_RECONCILE_FAILED` (3 ticks), `GATEWAY_RESTARTED` (its running task changed
  without the reconciler starting it while orders worked; held 15 minutes, with the old task's stop reason). CloudWatch alarms on the reconciler itself,
  `GatewayReconciler-{TickFailing,NotRunning,Errors,QueueStuck}-Error`, are this repo's only alarms. Catalog and
  runbooks are gnome-registry's. Collectors have no alerting from here.

**Runbooks** (gnome-registry): `docs/runbooks/alerts/<TYPE>.md` for every alert type above;
`docs/runbooks/procedures/inspect-session.md` (controller, logs, instance) and
`docs/runbooks/procedures/stop-or-kill.md` (kill switch vs stop). Prefer a kill switch while the process is alive.
