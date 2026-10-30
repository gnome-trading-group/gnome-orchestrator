# CLAUDE.md

gnome-orchestrator is the Java process a strategy session runs on its own EC2 instance: one JVM that wires the
session's market data gateways, the strategy, gnome-oms and the venue order gateways (gnome-gateways) over
sequenced ring buffers. The same jar also runs the market data shards (`collectors/ShardOrchestrator`) on ECS.
Nobody starts a session by hand: the registry's `strategy-sessions/launch` launcher starts the EC2 instance, whose user
data writes `/etc/gnome/session.json` and starts the `gnome-strategy` systemd unit.

## Commands

- Build with Maven, never Gradle. Needs GitHub Packages credentials in `~/.m2/settings.xml` (internal deps).
- `mvn clean package` builds the shaded jar `target/gnome-orchestrator-<version>.jar`.
- `mvn test` runs the unit tests. One class: `mvn test -Dtest=group.gnometrading.trading.SessionExitTest` (always
  the full class name).
- `mvn spotless:apply` fixes formatting. Spotless (palantir-java-format) and checkstyle both run at `validate` and
  fail the build, so run it before committing.
- `*LiveSmoke`, `*Probe`, `*LiveCheck`, `*CrossCheck` under `src/test` are manual checks against real venues (run
  by hand with `-D` flags documented in each class). Surefire skips them; never wire them into `mvn test`.
- Local runs (Docker, same install script as the AMI; needs `~/.m2/settings.xml` and `~/.aws` with a `dev`
  profile): `docker compose up --build strategy` (paper session), `collector`, or `testing`.
  `docker compose stop strategy` sends the same SIGTERM an instance stop does. The `collector` service is shard
  `local`: put its item in the dev `collection-shards` table first (`shard_id` `local`, numeric `generation`,
  `streams` as packed big-endian int32s, see `DynamoAssignment.pack`).
- CDK: `cd cdk && npm ci && npx cdk synth` (needs an npm token for `@gnome-trading-group` packages). Deploys only
  happen through the pipeline; do not `cdk deploy` by hand.

## Layout

- `orchestrator/Main` - the only entry point anything outside this repo starts: `Main <role>` (`session`, `shard`,
  `market-data-writer`; also the jar's `Main-Class`), or `Main.start(role, args)` from gnomepy's runner. Callers
  name a role, never a class, so classes can move; a new process is a new `Main.Role`. `start-session.sh` takes the
  role from `ORCHESTRATOR_ROLE` (default `session`).
- `trading/TradingOrchestrator` - the session process: listings, buffers, OMS, strategy, gateways, ledger, risk
  sync, journal, heartbeat, and the close order of all of them (`closeAgents`).
- `trading/` market data: a paper session the launcher leased streams for (`MARKET_DATA_API_URL` set) reads them from
  shards. `ShardFeedAgent` (HOT_PATH) is the only writer of the journaled strategy buffer and of which subscription
  each stream reads; `ShardMarketData`'s control thread makes and closes subscriptions (they block on the driver),
  waits up to `market.data.start.timeout.seconds` for the lease to be live, polls it every 10 s, follows moves (switch
  on the new shard's first clean record; a move that doesn't deliver in 60 s, or the lease stops naming, is given up)
  and re-subscribes a stalled stream after closing the old subscription (so the new one joins at the live position).
  A stream is marked bad (its last record repeated with `badData`) when its shard's status stops for 5 s, nothing
  arrives for 15 s (also never), its image goes, the shard reports it reconnecting or reports records it couldn't send
  (`ShardStatus.dropped` rising), or it stalls behind the publication position (same Aeron session id) for 1 s. Each
  bad spell is logged with its reason ("Market data: stream N marked bad: ..."; for drops, the shard's total). A
  shard's re-send is dropped only as an exact byte repeat (venues often have no sequence and millisecond stamps).
  The market-data path's threads (shard publisher and driver, session driver and `ShardFeedAgent`, i.e. the
  `MarketDataPathAgent`s and both `AeronRuntime`s) park at most `market.data.idle.max.park.micros` when idle
  (`MarketDataIdle`; dev 50, prod 1000): each park delays a record that arrives during it, so on a quiet stream the
  parks add up along the path.
  `MarketDataLeases` reads `GET /collection/leases/{sessionId}` with the `gnome/api-keys/market-data` key (read again
  on 401/403). `market.data.compare: true` (shard sessions only) also opens the session's own venue connections for
  its listings, which nothing trades on, and `FeedComparator` times the shard path against them: records are paired by
  their bytes less `timestampRecv` and flags (k-th with k-th), shard arrivals are copied by `ShardArrivals` on the
  ingress thread (monotonic stamp: the wall clock's `Instant` wasn't always optimized away there), and each minute it
  logs `FEED_COMPARE` lines (`feed_compare` per stream: matched, one-sided after 10 s, bad, hop p50–max in µs, which
  includes the venue's per-connection jitter; transit = arrival less the shard's receive stamp, carried in
  `timestampSent`, i.e. the shard pipeline alone, across two hosts' clocks; venue lag per path; `feed_compare_host`:
  lapped, scheduling lateness) and `feed_compare_total` at close. Live sessions and local runs still open venue
  connections themselves:
- `trading/` also: `SessionFeeds` (the session's market data on the same feeds as a shard), `SessionExit` (graceful
  exit), `SessionFatalHandler` (every error exit), `SessionHeartbeatAgent`, `SessionStatusReporter` (STARTING ->
  RUNNING), `SessionReports` (alerts + registry PATCH), `JournalManagerAgent` and `JournalSegmentSealer`,
  `AgentRuntimeInstaller` (latency profile / CPU pinning), `PaperTradingOutboundGateway`, `MarketDataMultiplexer` /
  `ListingRouter` (multi-listing only), `recovery/StartupRecovery`.
- `collectors/` - `ShardOrchestrator` (a shard: `SHARD_ID`), `ShardRuntime` (applies the assignment every 10 s,
  records state changes in `StreamTransitions`, opens an Aeron publication for each stream in the item's `publish`),
  `ShardStream` + `MarketDataCollectorAgent` (minute files), `StreamPublisherAgent` (drains every ring a second time,
  offers leased streams' records, re-sends a quiet stream's last record every 5 s, sends the core SBE `ShardStatus`
  every 1 s; reads the rings through a `LappingPoller`, so it never gates collection: a record it was lapped past or a
  back-pressured offer is dropped and counted, and sessions see the count),
  `CollectorHeartbeat` log lines, `ShardStatusWriter` (status for the reconciler), `ShardIdentity`, `ShardAccounts`,
  `CollectorHealth`.
- `feeds/` - `FeedVenues` (per `md.source.adapter`: the venue's `FEED` from gnome-gateways `venues/`, and its
  protocol built with the credentials this process loads, and its REST probe), `FeedSources` (adds a registry source,
  sized by its capacity columns), `WebSocketFeedOpener`, `Assignment` / `DynamoAssignment` (the shard's item in the
  reconciler's `collection-shards` table, us-east-1).
- `aeron/` - the Aeron layer shards, sessions and order gateways share:
  `AeronRuntime` (embedded media driver + client per process, `AeronProfile` per host kind), `AeronChannels`
  (shard MDC publications with `fc=max`, per-shard tagged wildcard receive ports), `AeronPorts` (split of UDP
  40000-40999; mirrors gnome-shared-cdk `AERON_PORT_MAP`), `AeronStreamIds` (market data = `stream_id`, >= 2^30
  reserved), `AeronIngressAgent` (one thread polls every subscription into sinks, zero allocation; a sink with no room
  leaves the message in Aeron; removal takes effect next pass and the caller closes, since closing blocks), `ServiceEndpoints` (the `service-endpoints` table, us-east-1), `HostAddress`.
  A shard's 1 s status is core SBE `ShardStatus` (gnome-schemas; per published stream its state and publication
  position), never journaled. `AeronTransportTest`
  pins the Aeron behaviour the design relies on: re-run it on every Aeron upgrade (a lagging subscriber stalls
  silently rather than losing its image; a fixed receive port can't serve two shards).
- `gateways/` - live outbound orchestrators (Kalshi, Polymarket Intl, Polymarket US), credentials,
  `GatewayErrorHandler`, `GatewayRunners` (order gateways), `GatewayHealth`.
- `shared/` - DI modules: `PropertiesModule`, `AwsModule`, `RiskModule`, `SecurityMasterModule`, `SecretCache`.
- `di/` - the in-house DI container (`Orchestrator`, `@Provides`, `@Singleton`, `@Named`, child orchestrators).
- `testing/MarketDataWriterOrchestrator` - local tool that writes a listing's market data to a file.
- `src/test/.../feeds/FeedLiveCheck` - manual check of live markets through the feeds (`-Dmarkets`, `-Draw`, `-Ddrop`).
- `src/main/resources/orchestrator.{dev,prod}.properties` - per-stage defaults.
- `ami/` - Image Builder inputs: `scripts/install-runtime.sh`, `scripts/tune-kernel.sh`, `files/run-strategy.sh`
  (EC2 wrapper), `files/start-session.sh` (shared with Docker), systemd units, `files/journal-shipper.py` (+ test).
- `cdk/` - `OrchestratorPipelineStack`, `lib/stacks/{storage,network,ec2,ami}-stack.ts`. Ignore stale local
  `ecs-stack.js` / `ecr-stack.js` (untracked build output, no source).

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
  session `GlobalSequence`, and every buffer on it must be journaled. `verifyJournalCoverage` fails startup for a
  buffer with no `JournalSource`, even with the journal off. Create pollers before the producers start.
- Close order matters: a producer blocks on a full ring whose reader has closed. Keep `closeAgents` ordered
  market data -> strategy -> OMS -> gateways -> ledger -> journal -> heartbeat.
- JVM flags live only in gnomepy (`gnomepy/java/_jvm.py`, `STRATEGY_JVM_ARGS`); `start-session.sh` reads them from
  there. Never add JVM flags to scripts, units or Dockerfiles here.
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
(launch templates `gnome-orchestrator` and `gnome-orchestrator-live`; only live roles can read `gnome/accounts/*`).

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
  0 trusted), ledger, gateways `reconnecting`/`resyncsLast5m`, risk, rate limiters, host CPU/mem/disk,
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
    changed; a `publish` line when a stream dropped records for sessions since the last, with totals split into
    `back_pressured` (a session not reading) and `lapped` (the shard's publisher behind)) plus the status above.
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
  `LATCHES_DROPPED`, `RATE_LIMIT_THROTTLING`, `HOST_CPU_HIGH`, `HOST_MEMORY_LOW`, `DISK_LOW`, `JOURNAL_BACKLOG`,
  `SILENT`; lifecycle ones (`START_STUCK`, `STOP_STUCK`, `FAILED`) come from other monitor rules.
- This repo defines no CloudWatch alarms. Collectors have no alerting from here either.

**Runbooks** (gnome-registry): `docs/runbooks/alerts/<TYPE>.md` for every alert type above;
`docs/runbooks/procedures/inspect-session.md` (controller, logs, instance) and
`docs/runbooks/procedures/stop-or-kill.md` (kill switch vs stop). Prefer a kill switch while the process is alive.
