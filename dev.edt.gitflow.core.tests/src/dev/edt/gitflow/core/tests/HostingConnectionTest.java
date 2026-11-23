package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import dev.edt.gitflow.core.HostingProvider;
import dev.edt.gitflow.core.HostingException;
import dev.edt.gitflow.ui.handlers.HostingConnection;
import dev.edt.gitflow.ui.handlers.HostingSettings;

public class HostingConnectionTest
{
    private static Path directory;
    private static Path certificate;
    private static HttpsServer server;
    private static String serverUrl;

    @BeforeClass
    public static void startSelfSignedServer() throws Exception
    {
        directory = Files.createTempDirectory("hosting-connection-test-"); //$NON-NLS-1$
        Path storeFile = directory.resolve("server.p12"); //$NON-NLS-1$
        String password = "test-password"; //$NON-NLS-1$
        String keytool = Path.of(System.getProperty("java.home"), "bin", //$NON-NLS-1$ //$NON-NLS-2$
            System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "server", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "-keystore", storeFile.toString(), "-storetype", "PKCS12", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "-storepass", password, "-keypass", password, "-dname", "CN=localhost", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-validity", "2", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "-keyalg", "RSA", "-keysize", "2048").redirectErrorStream(true).start(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.waitFor());
        KeyStore store = KeyStore.getInstance("PKCS12"); //$NON-NLS-1$
        try (var input = Files.newInputStream(storeFile))
        {
            store.load(input, password.toCharArray());
        }
        certificate = directory.resolve("server.cer"); //$NON-NLS-1$
        Files.write(certificate, store.getCertificate("server").getEncoded()); //$NON-NLS-1$
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password.toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS"); //$NON-NLS-1$
        ssl.init(keys.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0); //$NON-NLS-1$
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.createContext("/", exchange -> //$NON-NLS-1$
        {
            byte[] response = "{\"login\":\"test-user\",\"name\":\"test-user\"}".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
            exchange.sendResponseHeaders(200, response.length);
            try (var outputStream = exchange.getResponseBody())
            {
                outputStream.write(response);
            }
        });
        server.start();
        serverUrl = "https://127.0.0.1:" + server.getAddress().getPort(); //$NON-NLS-1$
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        if (server != null)
            server.stop(0);
        if (directory != null)
        {
            try (var files = Files.list(directory))
            {
                for (Path file : files.toList())
                    Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void trustsSelectedCertificateWithoutSavingSettings() throws Exception
    {
        var selected = HostingSettings.forTesting(serverUrl, "127.0.0.1", //$NON-NLS-1$
            HostingSettings.CertMode.FILE, certificate.toString());
        for (HostingProvider provider : HostingProvider.values())
        {
            var client = HostingConnection.probe(serverUrl, provider, "owner/repo", "test-token", selected); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("test-user", client.currentUser()); //$NON-NLS-1$
        }
    }

    @Test
    public void reportsUnreadableSelectedFileInsteadOfUsingDefaultTrust()
    {
        var selected = HostingSettings.forTesting(serverUrl, "127.0.0.1", //$NON-NLS-1$
            HostingSettings.CertMode.FILE, directory.resolve("missing.cer").toString()); //$NON-NLS-1$
        for (HostingProvider provider : HostingProvider.values())
        {
            HostingException error = assertThrows(HostingException.class, () -> HostingConnection.probe(
                serverUrl, provider, "owner/repo", "test-token", selected)); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(HostingException.Kind.TLS, error.kind());
            assertTrue(error.getCause() instanceof NoSuchFileException);
        }
    }
}
