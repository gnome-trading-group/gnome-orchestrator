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
  profile): `docker compose up --build strategy` (a paper session; starts `collector` too) or `collector` alone.
  `docker compose stop strategy` sends the same SIGTERM an instance stop does. The `collector` service is shard
  `local`: put its item in the dev `collection-shards` table first (`shard_id` `local`, numeric `generation`,
  `streams` and `publish` as packed big-endian int32s, see `DynamoAssignment.pack`). `strategy` has no lease: its
  `MARKET_DATA_LOCAL_SHARD=collector:40000` stands in for one, so its listings' book streams must be in both lists.
- CDK: `cd cdk && npm ci && npx cdk synth` (needs an npm token for `@gnome-trading-group` packages). Deploys only
  happen through the pipeline; do not `cdk deploy` by hand.

## Layout

Everything is under `group.gnometrading.orchestrator`, laid out by process: `session/` and `shard/` are separate
programs that share only the layers below them (`aeron/`, `runtime/`, `aws/`, `credentials/`, `di/`), so neither
uses the other and the shared layers use neither. `LayoutTest` (ArchUnit) enforces it.

- `Main` - the only entry point anything outside this repo starts: `Main <role>` (`session`, `shard`, `gateway`;
  also the jar's `Main-Class`), or `Main.start(role, args)` from gnomepy's runner. Callers
  name a role, never a class, so classes can move; a new process is a new `Main.Role`. `start-session.sh` takes the
  role from `ORCHESTRATOR_ROLE` (default `session`).
- `session/SessionOrchestrator` - the session process: listings, the OMS, positions, ledger (with startup recovery)
  and risk sync, wired from the pieces beside it: `SessionBuffers` (every ring buffer on the session sequence and its
  journal source), `SessionStrategy` (python or java strategy), `SessionAgents` (start order, close order, heartbeat),
  `marketdata/SessionMarketData`, `orders/SessionOrders` (simulated exchange or venue gateway per listing, the router,
  rate limiters, bound venue accounts) and `journal/SessionJournal`.
- `session/marketdata/`: every session reads its streams from shards, live ones from on-demand PRODUCTION shards,
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
- `session/orders/`: paper sessions trade on `PaperTradingOutboundGateway` (one per listing, `ListingRouter` with
  several). Live sessions trade only through order gateways, and hold no trading key: one `GatewayLink` per venue
  (`live:<exchange>:main`, found in `service-endpoints`, or `order.gateway.local=<ip>:<port>`), opened by
  `GatewayLinks` on a control thread, and one `GatewayClient` agent reading the OMS's orders and writing its reports
  (no router). A NEW on a link not ready is rejected `GATEWAY_UNAVAILABLE`; cancels wait for the link; a gateway's
  `OrderStatus` becomes the reports the OMS lacks. The client starts, and every link is welcomed (within
  `order.gateway.start.timeout.seconds`), before startup recovery, which asks the venue through it
  (`GatewayVenueOrderQuery`: `VenueQuery` for open orders and lookups, `CancelEnded` to cancel what an ended session
  of the strategy left; a query a dropped link lost is sent again). The slot is the registry's `session_seq`. The
  session's `AeronRuntime` is launched by `SessionOrchestrator` (shared by market data and the links) and closed
  last.
- `session/recovery/`: `StartupRecovery`.
- `shard/` - `ShardOrchestrator` (a shard: `SHARD_ID`), `ShardRuntime` (applies the assignment every 10 s,
  records state changes in `StreamTransitions`, opens an Aeron publication for each stream in the item's `publish`),
  `ShardStream` + `MarketDataCollectorAgent` (minute files), `StreamPublisherAgent` (drains every ring a second time,
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
  holding that venue's accounts (registry `venue-accounts/gateway`) for every live session. Layered, and `LayoutTest`
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
    Aeron session id (core orders carry no session), turns orders into lane commands or refuses them straight back
    (`GatewayRejects`); `GatewayEgress` numbers each session's reports into its `ReplayRing` (`gateway.replay.reports`)
    and sends them with the number as the frame's reserved value, at that session's pace, plus a 100 ms heartbeat;
    `GatewayControl` checks hellos against the registry (`SessionRegistry`, a 10-minute fallback cache), loads the
    listings they name, opens reply publications, sends the welcome and replay, cancels a silent or deaf session's
    orders (lane `CANCEL_SESSION`) and asks it to hello again, evicts a session that sends something unreadable
    (refused 5 min), keeps the state (STARTING/READY/DEGRADED) and writes `order-gateway#<key>` to `service-endpoints`
    every 15 s. SIGTERM is a planned stop: every session's orders cancelled and a goodbye sent before anything closes.
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
    no progress for `gateway.watchdog.stall.millis` (3000). `/health` on :8080 fails when a lane stops.
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
  - Tests: `GatewayFixture` (test scope, from gnome-gateways' test jar) runs a whole gateway in process on a fake
    venue; `session/orders/GatewayInProcessTest` drives sessions through it, restart included.
  - Idle stop: `gateway.idle.minutes` with no session and no working order, and no `wanted_at` since, exits into the
    planned stop. 0 (the default) never stops, until something also keeps ECS from starting it again.
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
  `AgentUtils`, `LatencyHistogram`, `SchedulingLatencyProbe`.
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
- Close order matters: a producer blocks on a full ring whose reader has closed. Keep `SessionAgents.close` ordered
  market data -> strategy -> OMS -> gateways -> ledger -> journal -> heartbeat.
- Every process starts with `java -jar` and takes its module access from the jar's manifest (`Add-Opens`/`Add-Exports`
  in the shade plugin, kept equal to gnomepy's `DEFAULT_JVM_ARGS` minus jdk.compiler, which the Python runner passes to
  JPype instead). Sessions' other JVM options live only in gnomepy (`STRATEGY_JVM_OPTIONS`), which `start-session.sh`
  reads; the gateway's GC choice is in `gateway-start.sh`, a shard's in gnome-market-data's `shard-start.sh`. Add no
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
scaling from zero (capacity provider `order-gateway-hosts`), base task definition `order-gateway` (a Java 17 runtime and
`cdk/lib/stacks/gateway-docker/gateway-start.sh`, which runs `java -jar <release> gateway`: the jar's manifest carries
the module access gnomepy's JVM args give sessions), state on a host volume, logs in
`/gnome/order-gateway/<region>`. The registry's `POST /gateways/ensure?gatewayKey=` starts a gateway in its row's
region as service `order-gateway-<key with : as ->` from its own revision (key and release filled in) and allocates its
Elastic IP (tag `gnome:order-gateway`), which `gateway-start.sh` attaches at boot; the launcher calls it for each live
venue and a waiting session every 10 s. A gateway idle `gateway.idle.minutes` (30) sets its service to zero and
stops),
and `<Stage>-OrchestratorEmergencyCancelStack-<region>`: Lambda `EmergencyCancel` (event names the registry's
exchange code; run in the exchange's region, `sm.exchange.region`, where its gateway runs; reads only that region's
`gnome/accounts/*/trading`; 5-minute timeout, never retried, dropped unless started within a minute). Started from the controller's Treasury
(registry `POST /venue-accounts/emergency-cancel`, async) or by `aws lambda invoke` (registry runbook
`stop-or-kill.md`).

**How a version reaches sessions.** The jar is not baked into the AMI. The launcher pins `ORCHESTRATOR_VERSION`
(default: SSM `/gnome/orchestrator/latest-version`, written by StorageStack only for non-SNAPSHOT releases) and
`start-session.sh` downloads that jar from GitHub Packages at boot. Property keys and defaults per release are in
`/gnome/orchestrator/properties/latest` and `/gnome/orchestrator/properties/<version>`. AMIs (`16core`, `32core`,
`48core`, `standard`) rebuild only when `ami/` content changes; `AmiPublisher` writes `/gnome/orchestrator/ami/<variant>`
per region after image tests pass. Roll back an AMI by repointing that parameter (images kept 14 days).
Collectors pull the same jar (latest unless pinned) into the gnome-market-data collector image.

**Session lifecycle.** SUBMITTED -> STARTING (launcher) -> RUNNING (this process PATCHes after agents start; the
first heartbeat also counts) -> STOPPING -> STOPPED or FAILED (set by the registry's session monitor when the
instance goes away). On boot it runs `StartupRecovery` (inherits positions, and in live open venue orders, from
earlier sessions). A session already stopped before startup exits 0 without reporting.
- Heartbeat: every 5 s to `/api/strategy-sessions/heartbeat` (agents' `stalledMs`/`exited`, per-listing
  `priceUnchangedMs` and `marketDataBadMs` (how long its book has been flagged bad data, the OMS refusing new orders;
  0 trusted), ledger, market data `reconnecting`/`resyncsLast5m`, risk, host CPU/mem/disk,
  journal backlog). Stops once the registry says the session ended.
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

**Alerts.** This process sends only two, to `ALERTS_API_URL` (launcher sets it from SSM `/gnome/alerting/api-url`;
unset means no alerts):
- `FATAL_ERROR` - any agent error, gateway that can't reconnect, failed startup, or uncaught exception. Also PATCHes
  `failureReason`, then exits 1. Check the log stream tail and the failure reason.
- `SESSION_EXITED` - INFO on a graceful exit; WARNING when positions were left (too small, rejected, timed out).
  Check the listed positions on the venue.
- Everything else about a session is raised by the registry's session monitor from heartbeat fields
  (gnome-registry `cdk/lambda/monitoring/rules/sessions.ts`): `AGENT_STALLED`, `AGENT_EXITED`, `RECONNECTING`,
  `BOOK_RESYNCS`, `MARKET_DATA_BAD`, `MARKET_DATA_BAD_HELD`, `LEDGER_FAILING`, `LEDGER_FENCED`, `RISK_STALE`, `RISK_UNGUARDED`, `POLICY_UNBUILDABLE`,
  `LATCHES_DROPPED`, `HOST_CPU_HIGH`, `HOST_MEMORY_LOW`, `DISK_LOW`, `JOURNAL_BACKLOG`,
  `SILENT`; lifecycle ones (`START_STUCK`, `STOP_STUCK`, `FAILED`) come from other monitor rules.
- This repo defines no CloudWatch alarms. Collectors have no alerting from here either.

**Runbooks** (gnome-registry): `docs/runbooks/alerts/<TYPE>.md` for every alert type above;
`docs/runbooks/procedures/inspect-session.md` (controller, logs, instance) and
`docs/runbooks/procedures/stop-or-kill.md` (kill switch vs stop). Prefer a kill switch while the process is alive.
