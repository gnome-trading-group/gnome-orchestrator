package group.gnometrading.orchestrator.aeron;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * This host's private IP on the platform network, where other hosts reach it over Aeron: read from the instance
 * metadata service (IMDSv2), as both EC2 sessions and host-networked shard tasks see the instance's. Off EC2 (a local
 * run) the local host's address stands in, unless it is a loopback address, which no other host could reach.
 */
public final class HostAddress {

    private static final String IMDS = "http://169.254.169.254/latest";
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private HostAddress() {}

    /** Never throws; null when no address other hosts could reach is found. */
    public static String privateIp() {
        final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        try {
            final String token = send(
                    http,
                    HttpRequest.newBuilder(URI.create(IMDS + "/api/token"))
                            .header("X-aws-ec2-metadata-token-ttl-seconds", "60")
                            .PUT(HttpRequest.BodyPublishers.noBody()));
            return send(
                    http,
                    HttpRequest.newBuilder(URI.create(IMDS + "/meta-data/local-ipv4"))
                            .header("X-aws-ec2-metadata-token", token)
                            .GET());
        } catch (Exception e) {
            try {
                final InetAddress local = InetAddress.getLocalHost();
                return local.isLoopbackAddress() ? null : local.getHostAddress();
            } catch (Exception unknown) {
                return null;
            }
        }
    }

    /** An IPv4 address as one int, its first octet in the high byte, as a session's hello carries its address. */
    public static int pack(final String ipv4) {
        final String[] octets = ipv4.split("\\.");
        if (octets.length != 4) {
            throw new IllegalArgumentException("Not an IPv4 address: " + ipv4);
        }
        int packed = 0;
        for (final String octet : octets) {
            final int value = Integer.parseInt(octet);
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException("Not an IPv4 address: " + ipv4);
            }
            packed = (packed << 8) | value;
        }
        return packed;
    }

    public static String unpack(final int packed) {
        return ((packed >>> 24) & 0xFF) + "." + ((packed >>> 16) & 0xFF) + "." + ((packed >>> 8) & 0xFF) + "."
                + (packed & 0xFF);
    }

    private static String send(final HttpClient http, final HttpRequest.Builder request) throws Exception {
        final HttpResponse<String> response =
                http.send(request.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body().trim();
    }
}
