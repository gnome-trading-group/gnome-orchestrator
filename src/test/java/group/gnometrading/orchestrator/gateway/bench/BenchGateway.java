package group.gnometrading.orchestrator.gateway.bench;

import group.gnometrading.account.VenueAccount;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlOrderSigner;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.logging.Logger;
import group.gnometrading.orchestrator.aeron.AeronPathIdle;
import group.gnometrading.orchestrator.gateway.GatewayFixture;
import group.gnometrading.orchestrator.gateway.accounts.GatewayAccount;
import group.gnometrading.orchestrator.gateway.accounts.GatewaySettings;
import group.gnometrading.orchestrator.gateway.accounts.GatewayVenue;
import group.gnometrading.orchestrator.gateway.accounts.VenueAccounts;
import group.gnometrading.orchestrator.gateway.core.CrossKeys;
import group.gnometrading.orchestrator.runtime.AgentRuntimeInstaller;
import group.gnometrading.orchestrator.runtime.ProcessExit;
import group.gnometrading.resources.Properties;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.utils.SampledEpochNanoClock;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.EdECPrivateKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import org.agrona.concurrent.SystemEpochClock;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;

/**
 * An order gateway for the bench, built as a deployed one is: production properties, the standard latency profile
 * with the gateway's Aeron-path parks, and its account from {@link VenueAccounts} on a throwaway trading secret, so the
 * lane signs, encodes and sends over TLS exactly as live, and the reader reads the venue's real websocket. Only the
 * control plane (registry, alerts, kills, endpoints) is the fixture's.
 */
public final class BenchGateway implements AutoCloseable {

    public static final int ACCOUNT_ID = 900;
    public static final int STRATEGY_ID = 31;
    public static final int FIRST_LISTING_ID = 9_000;
    public static final long FIRST_SECURITY_ID = 9_000;

    /**
     * One venue's bench account: what the gateway is keyed and configured as, its trading secret, the listings it
     * trades, and the stand-in venue that answers for them.
     */
    public record Account(
            GatewayVenue venue,
            String gatewayKey,
            int exchangeId,
            String exchangeCode,
            String secret,
            String funderAddress,
            List<String> exchangeSecurityIds,
            List<String> urlProperties,
            Supplier<BenchVenue> model) {

        /** The {@code --key=value} overrides pointing every venue URL the gateway uses at {@code host}. */
        List<String> urlOverrides(final String host) {
            final List<String> overrides = new ArrayList<>();
            for (final String property : this.urlProperties) {
                final String scheme = property.endsWith(".ws.url") ? "wss://" : "https://";
                overrides.add("--" + property + "=" + scheme + host + wsPath(property));
            }
            return overrides;
        }

        private String wsPath(final String property) {
            if (!property.endsWith(".ws.url")) {
                return "";
            }
            return switch (this.venue) {
                case KALSHI -> "/trade-api/ws/v2";
                case POLYMARKET_US -> "/v1/ws/private";
                case POLYMARKET_INTL -> "/ws/user";
            };
        }

        /**
         * A Polymarket International account: a signing key, deposit wallet and API key, one token a market, all from
         * {@code seed}, so the gateway's host and the venue's agree on them.
         */
        public static Account polymarketIntl(final int listings, final long seed) {
            final Random random = new Random(seed);
            final byte[] key = new byte[32];
            random.nextBytes(key);
            key[0] &= 0x7F;
            key[31] |= 1;
            final byte[] funder = new byte[20];
            random.nextBytes(funder);
            final String funderAddress = "0x" + HexFormat.of().formatHex(funder);
            final String apiKey = "bench-" + Long.toHexString(random.nextLong());
            final String secret = "{\"apiKey\":\"" + apiKey + "\",\"secret\":\"YmVuY2gtc2VjcmV0\","
                    + "\"passphrase\":\"bench\",\"ethereumPrivateKey\":\"0x"
                    + HexFormat.of().formatHex(key)
                    + "\",\"signerAddress\":\"" + addressOf(key) + "\",\"funderAddress\":\"" + funderAddress
                    + "\",\"signatureType\":\"" + PolymarketIntlOrderSigner.SIGNATURE_TYPE_POLY_1271 + "\"}";
            final Map<String, String> tokenByCondition = new LinkedHashMap<>();
            final List<String> ids = new ArrayList<>();
            for (int i = 0; i < listings; i++) {
                final String token =
                        new BigInteger(250, random).add(BigInteger.ONE).toString();
                final byte[] condition = new byte[32];
                random.nextBytes(condition);
                final String conditionId = "0x" + HexFormat.of().formatHex(condition);
                tokenByCondition.put(conditionId, token);
                ids.add(conditionId + ":" + token);
            }
            return new Account(
                    GatewayVenue.POLYMARKET_INTL,
                    "live:polymarket_intl:bench",
                    4,
                    "POLYMARKET_INTL",
                    secret,
                    funderAddress,
                    ids,
                    List.of("polymarket.intl.clob.url", "polymarket.intl.user.ws.url"),
                    () -> new PolymarketIntlBenchVenue(
                            PolymarketIntlBenchVenue.Timing.live().overridden(), tokenByCondition, apiKey));
        }

        /** A Kalshi account: a fresh Ed25519 key, one ticker a listing. */
        public static Account kalshi(final int listings) {
            final List<String> tickers = new ArrayList<>();
            for (int i = 0; i < listings; i++) {
                tickers.add("KXBENCH-26NOV18-B" + i);
            }
            try {
                final byte[] key = KeyPairGenerator.getInstance("Ed25519")
                        .generateKeyPair()
                        .getPrivate()
                        .getEncoded();
                final String secret = "{\"apiKey\":\"bench-key\",\"privateKey\":\"-----BEGIN PRIVATE KEY-----\\n"
                        + Base64.getEncoder().encodeToString(key) + "\\n-----END PRIVATE KEY-----\"}";
                return new Account(
                        GatewayVenue.KALSHI,
                        "live:kalshi:bench",
                        5,
                        "KALSHI",
                        secret,
                        null,
                        tickers,
                        List.of("kalshi.api.url", "kalshi.user.ws.url"),
                        () -> new KalshiBenchVenue(KalshiBenchVenue.liveTiming().overridden()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        /** A Polymarket US account: a fresh Ed25519 key, the YES side of one market a listing. */
        public static Account polymarketUs(final int listings) {
            final List<String> sides = new ArrayList<>();
            for (int i = 0; i < listings; i++) {
                sides.add("tec-bench-2026-11-18-b" + i + ":long");
            }
            try {
                final byte[] seed = ((EdECPrivateKey) KeyPairGenerator.getInstance("Ed25519")
                                .generateKeyPair()
                                .getPrivate())
                        .getBytes()
                        .orElseThrow();
                final String secret = "{\"apiKey\":\"bench-key\",\"secret\":\""
                        + Base64.getEncoder().encodeToString(seed) + "\"}";
                return new Account(
                        GatewayVenue.POLYMARKET_US,
                        "live:polymarket_us:bench",
                        6,
                        "POLYMARKET_US",
                        secret,
                        null,
                        sides,
                        List.of("polymarket.us.api.url", "polymarket.us.gateway.url", "polymarket.us.private.ws.url"),
                        () -> new PolymarketUsBenchVenue(
                                PolymarketUsBenchVenue.liveTiming().overridden()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * By the venue's gateway-key code: {@code polymarket_intl}, {@code kalshi} or {@code polymarket_us}. Kalshi's
         * and Polymarket US's venues need nothing of the account, so only Polymarket International's uses the seed.
         */
        public static Account of(final String venue, final int listings, final long seed) {
            return switch (venue) {
                case "kalshi" -> kalshi(listings);
                case "polymarket_us" -> polymarketUs(listings);
                default -> polymarketIntl(listings, seed);
            };
        }
    }

    private final GatewayFixture fixture;
    private final Account account;
    private final List<Listing> listings;
    private final GatewayFixture.Registry registry;
    private final GatewayHops hops;

    private BenchGateway(
            final GatewayFixture fixture,
            final Account account,
            final List<Listing> listings,
            final GatewayFixture.Registry registry,
            final GatewayHops hops) {
        this.fixture = fixture;
        this.account = account;
        this.listings = listings;
        this.registry = registry;
        this.hops = hops;
    }

    /**
     * Starts a gateway on {@code account}'s bench venue at {@code venueHost} (443), taking sessions on
     * {@code ingressHost:ingressPort}. {@code overrides} are {@code --key=value} property overrides, applied over
     * production's, as a deployed gateway's environment would be.
     */
    public static BenchGateway start(
            final Account account,
            final String venueHost,
            final String ingressHost,
            final int ingressPort,
            final Path state,
            final String... overrides)
            throws Exception {
        final List<String> args = new ArrayList<>();
        args.add("--gateway.key=" + account.gatewayKey());
        args.addAll(account.urlOverrides(venueHost));
        args.addAll(Arrays.asList(overrides));
        final Properties properties = new Properties("orchestrator.prod.properties", args.toArray(String[]::new));
        final Logger logger = new ConsoleLogger(SampledEpochNanoClock.INSTANCE);
        AgentRuntimeInstaller.installStandard(logger, AeronPathIdle.forGateway(properties));
        final VenueAccounts accounts = new VenueAccounts(new VenueAccounts.Context(
                logger,
                properties,
                Map.of("bench-secret", account.secret())::get,
                GatewaySettings.of(properties),
                SystemEpochClock.INSTANCE,
                SampledEpochNanoClock.INSTANCE,
                ProcessExit.SYSTEM,
                new CrossKeys()));
        final GatewayAccount built = accounts.build(
                account.venue(),
                new VenueAccount(
                        ACCOUNT_ID, account.exchangeId(), "bench-account", "bench-secret", account.funderAddress()));
        final Exchange exchange =
                new Exchange(account.exchangeId(), account.exchangeCode(), account.exchangeCode(), "bench");
        final List<Listing> listings = new ArrayList<>();
        for (int i = 0; i < account.exchangeSecurityIds().size(); i++) {
            final long securityId = FIRST_SECURITY_ID + i;
            listings.add(new Listing(
                    FIRST_LISTING_ID + i,
                    exchange,
                    new Security(
                            (int) securityId,
                            "B" + i,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            false,
                            false,
                            0L,
                            0L,
                            true,
                            0),
                    account.exchangeSecurityIds().get(i),
                    "B" + i));
        }
        final GatewayFixture.Registry registry = new GatewayFixture.Registry();
        final GatewayHops hops = new GatewayHops();
        final GatewayFixture fixture = GatewayFixture.start(
                "bench-gateway-" + ingressPort,
                ingressHost,
                ingressPort,
                new GatewayFixture.Setup(List.of(built), listings, registry, state, null, 0, false, hops),
                AeronPathIdle.forGateway(properties));
        return new BenchGateway(fixture, account, listings, registry, hops);
    }

    /** Lets sessions {@code bench-0} onwards, on slots from {@code slotBase}, trade every listing. */
    public void registerSessions(final int count, final long slotBase) {
        for (int i = 0; i < count; i++) {
            register("bench-" + i, slotBase + i);
        }
    }

    /** Lets a session on {@code slot} trade every listing through the account. */
    public void register(final String sessionId, final long slot) {
        final Set<Integer> listingIds = new HashSet<>();
        for (final Listing listing : this.listings) {
            listingIds.add(listing.listingId());
        }
        this.registry.register(sessionId, slot, STRATEGY_ID, this.account.exchangeId(), listingIds, ACCOUNT_ID);
    }

    public Account account() {
        return this.account;
    }

    /** The lane's hops for every request so far. */
    public GatewayHops hops() {
        return this.hops;
    }

    @Override
    public void close() throws Exception {
        this.fixture.close();
    }

    /** The Ethereum address a private key signs as. */
    static String addressOf(final byte[] privateKey) {
        final X9ECParameters curve = CustomNamedCurves.getByName("secp256k1");
        final byte[] encoded =
                curve.getG().multiply(new BigInteger(1, privateKey)).normalize().getEncoded(false);
        final byte[] hash = PolymarketIntlOrderSigner.keccak256(Arrays.copyOfRange(encoded, 1, 65));
        return "0x" + HexFormat.of().formatHex(Arrays.copyOfRange(hash, 12, 32));
    }
}
