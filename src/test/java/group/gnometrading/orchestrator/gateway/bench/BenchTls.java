package group.gnometrading.orchestrator.gateway.bench;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * A throwaway certificate authority and a server certificate it signed for the bench venue's addresses, made with the
 * JDK's {@code keytool}. The gateway's native TLS checks the server's name against the certificate's SANs, so every
 * address the gateway dials must be listed; it trusts the CA through the PEM file {@link #caFile()} names.
 */
public final class BenchTls {

    private static final String PASSWORD = "bench-password";

    private final Path directory;
    private final Path caFile;
    private final Path serverStore;

    private BenchTls(final Path directory) {
        // keytool runs in the directory, so every path it is given must stand on its own.
        this.directory = directory.toAbsolutePath();
        this.caFile = this.directory.resolve("ca.pem");
        this.serverStore = this.directory.resolve("server.p12");
    }

    /** Makes the CA and a server certificate valid for each of {@code addresses} (IPs or DNS names). */
    public static BenchTls create(final Path directory, final List<String> addresses) throws IOException {
        Files.createDirectories(directory);
        final BenchTls tls = new BenchTls(directory);
        final Path caStore = tls.directory.resolve("ca.p12");
        final Path request = tls.directory.resolve("server.csr");
        final Path signed = tls.directory.resolve("server.pem");
        tls.keytool(
                "-genkeypair",
                "-alias",
                "ca",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                "CN=gnome bench CA",
                "-ext",
                "bc:c",
                "-validity",
                "30",
                "-keystore",
                caStore.toString());
        tls.keytool(
                "-exportcert", "-rfc", "-alias", "ca", "-keystore", caStore.toString(), "-file", tls.caFile.toString());
        tls.keytool(
                "-genkeypair",
                "-alias",
                "server",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                "CN=gnome bench venue",
                "-validity",
                "30",
                "-keystore",
                tls.serverStore.toString());
        tls.keytool(
                "-certreq", "-alias", "server", "-keystore", tls.serverStore.toString(), "-file", request.toString());
        tls.keytool(
                "-gencert",
                "-alias",
                "ca",
                "-keystore",
                caStore.toString(),
                "-infile",
                request.toString(),
                "-outfile",
                signed.toString(),
                "-rfc",
                "-validity",
                "30",
                "-ext",
                "san=" + sans(addresses));
        // The chain goes in under the server's key: the CA first, so keytool can link the reply to it.
        tls.keytool(
                "-importcert",
                "-noprompt",
                "-alias",
                "ca",
                "-keystore",
                tls.serverStore.toString(),
                "-file",
                tls.caFile.toString());
        tls.keytool(
                "-importcert", "-alias", "server", "-keystore", tls.serverStore.toString(), "-file", signed.toString());
        return tls;
    }

    /** The CA and server certificate {@link #create} made earlier in {@code directory}, as another host copied them. */
    public static BenchTls open(final Path directory) {
        return new BenchTls(directory);
    }

    private static String sans(final List<String> addresses) {
        final List<String> entries = new ArrayList<>();
        for (final String address : addresses) {
            entries.add((Character.isDigit(address.charAt(0)) ? "ip:" : "dns:") + address);
        }
        return String.join(",", entries);
    }

    /** The CA certificate, PEM, for {@code gnome.ssl.caFile}. */
    public Path caFile() {
        return this.caFile;
    }

    /** The server's TLS context: its key and the chain to the CA. */
    public SSLContext serverContext() throws Exception {
        final KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(this.serverStore)) {
            store.load(in, PASSWORD.toCharArray());
        }
        final KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD.toCharArray());
        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    private void keytool(final String... args) throws IOException {
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        command.addAll(List.of("-storepass", PASSWORD, "-keypass", PASSWORD, "-storetype", "PKCS12"));
        final Process process = new ProcessBuilder(command)
                .directory(this.directory.toFile())
                .redirectErrorStream(true)
                .start();
        try {
            final String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("keytool " + args[0] + " failed: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted making the bench certificates", e);
        }
    }
}
