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
  `local`: write `s3://gnome-market-data-raw-dev/shards/local.json` (`{"generation": 1, "streams": [...]}`) first.
- CDK: `cd cdk && npm ci && npx cdk synth` (needs an npm token for `@gnome-trading-group` packages). Deploys only
  happen through the pipeline; do not `cdk deploy` by hand.

## Layout

- `trading/TradingOrchestrator` - the session process: listings, buffers, OMS, strategy, gateways, ledger, risk
  sync, journal, heartbeat, and the close order of all of them (`closeAgents`).
- `trading/` also: `SessionFeeds` (the session's market data on the same feeds as a shard), `SessionExit` (graceful
  exit), `SessionFatalHandler` (every error exit), `SessionHeartbeatAgent`, `SessionStatusReporter` (STARTING ->
  RUNNING), `SessionReports` (alerts + registry PATCH), `JournalManagerAgent` and `JournalSegmentSealer`,
  `AgentRuntimeInstaller` (latency profile / CPU pinning), `PaperTradingOutboundGateway`, `MarketDataMultiplexer` /
  `ListingRouter` (multi-listing only), `recovery/StartupRecovery`.
- `collectors/` - `ShardOrchestrator` (a shard: `SHARD_ID`), `ShardRuntime` (applies the assignment every 10 s),
  `ShardStream` + `MarketDataCollectorAgent` (minute files), `CollectorHeartbeat` log lines, `CollectorHealth`.
- `feeds/` - `FeedVenues` (per `md.source.adapter`: the venue's `FEED` from gnome-gateways `venues/`, and its
  protocol built with the credentials this process loads, and its REST probe), `FeedSources` (adds a registry source,
  sized by its capacity columns), `WebSocketFeedOpener`, `Assignment` / `S3Assignment`
  (`s3://<OUTPUT_BUCKET>/shards/<shard>.json`).
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
  `priceUnchangedMs`, ledger, gateways `reconnecting`/`resyncsLast5m`, risk, rate limiters, host CPU/mem/disk,
  journal backlog). Stops once the registry says the session ended.
- Journal: segments under `/var/lib/gnome/journal/<sessionId>/`; `gnome-journal-shipper` uploads them to
  `gnome-journals-<stage>/<sessionId>/` and ships open segments after the strategy unit stops. Strategy metrics live
  only in the journal.
- Graceful exit: the strategy requests exit -> `SessionExit` sends `SESSION_EXITED`, PATCHes STOPPING with
  `stoppedBy=strategy`, closes, exits 0.
- Operator stop: the registry terminates the instance -> SIGTERM -> shutdown hook closes agents (60 s
  `TimeoutStopSec`). Errors during shutdown are not reported.
- Shards: ECS Fargate tasks from gnome-market-data `CollectorRegionalStack` (`MAIN_CLASS=...ShardOrchestrator`,
  `SHARD_ID`). Streams come from the shard's assignment file, re-read every 10 s, so streams are added and removed
  without a restart. `/health` on :8080 fails when the agent stops cycling for 30 s or an upload hangs 5 min, and ECS
  replaces the task. A failing market is quarantined and retried, a failing connection keeps retrying; neither stops
  the process. Raw frames go per connection to `raw/<source>/<shard>/<connection>/...` in `RAW_CAPTURE_BUCKET`. No
  registry heartbeat: state is in `HEARTBEAT` log lines every 15 s (process, connection, stream).
- Market-data credentials: a shard signs Kalshi and Polymarket US in with the source's market-data account it is bound
  to by label (`SOURCE_ACCOUNTS`, JSON like `{"KALSHI":"md-2"}`; `md-1` when unnamed), looked up in the registry's
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
  `BOOK_RESYNCS`, `LEDGER_FAILING`, `LEDGER_FENCED`, `RISK_STALE`, `RISK_UNGUARDED`, `POLICY_UNBUILDABLE`,
  `LATCHES_DROPPED`, `RATE_LIMIT_THROTTLING`, `HOST_CPU_HIGH`, `HOST_MEMORY_LOW`, `DISK_LOW`, `JOURNAL_BACKLOG`,
  `SILENT`; lifecycle ones (`START_STUCK`, `STOP_STUCK`, `FAILED`) come from other monitor rules.
- This repo defines no CloudWatch alarms. Collectors have no alerting from here either.

**Runbooks** (gnome-registry): `docs/runbooks/alerts/<TYPE>.md` for every alert type above;
`docs/runbooks/procedures/inspect-session.md` (controller, logs, instance) and
`docs/runbooks/procedures/stop-or-kill.md` (kill switch vs stop). Prefer a kill switch while the process is alive.
