package dev.edt.gitflow.core.tests;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.edt.gitflow.core.GitLabClient;
import dev.edt.gitflow.core.GitLabRemote;

/**
 * Фейковый сервер GitLab для тестов клиента: отдает заранее заданные ответы по
 * пути запроса и запоминает последнее тело.
 */
final class FakeGitLab implements AutoCloseable
{
    private final HttpServer server;
    private final Map<String, String[]> responses = new HashMap<>();
    private String lastBody = ""; //$NON-NLS-1$
    private String lastToken;

    FakeGitLab() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); //$NON-NLS-1$
        server.createContext("/", this::handle); //$NON-NLS-1$
        server.start();
    }

    void respond(String path, int status, String body)
    {
        responses.put(path, new String[] {Integer.toString(status), body});
    }

    GitLabClient client()
    {
        GitLabRemote remote = GitLabRemote.parse(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/group/project.git"); //$NON-NLS-1$ //$NON-NLS-2$
        return new GitLabClient(remote, "test-token", null, Duration.ofSeconds(5)); //$NON-NLS-1$
    }

    String lastBody()
    {
        return lastBody;
    }

    String lastToken()
    {
        return lastToken;
    }

    private String[] lookup(String rawPath, String rawQuery)
    {
        String singleEncodedPath = rawPath.replace("%25", "%"); //$NON-NLS-1$ //$NON-NLS-2$
        String singleEncodedQuery = rawQuery == null ? null : rawQuery.replace("%25", "%"); //$NON-NLS-1$ //$NON-NLS-2$
        String[] candidates = {
            rawPath + query(rawQuery),
            singleEncodedPath + query(singleEncodedQuery),
            decode(rawPath) + query(decode(rawQuery)),
            rawPath,
            singleEncodedPath,
            decode(rawPath)
        };
        for (String candidate : candidates)
        {
            String[] response = responses.get(candidate);
            if (response != null)
                return response;
        }
        return null;
    }

    private static String query(String value)
    {
        return value == null ? "" : "?" + value; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String decode(String value)
    {
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private void handle(HttpExchange exchange) throws IOException
    {
        URI uri = exchange.getRequestURI();
        String rawPath = uri.getRawPath();
        String rawQuery = uri.getRawQuery();
        String[] response = lookup(rawPath, rawQuery);
        lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastToken = exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"); //$NON-NLS-1$
        if (response == null)
            response = new String[] {"404", "{\"message\":\"404 Not Found: " //$NON-NLS-1$ //$NON-NLS-2$
                + rawPath + "?" + rawQuery + "\"}"}; //$NON-NLS-1$ //$NON-NLS-2$
        byte[] bytes = response[1].getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.sendResponseHeaders(Integer.parseInt(response[0]), bytes.length);
        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(bytes);
        }
    }

    @Override
    public void close()
    {
        server.stop(0);
    }
}
