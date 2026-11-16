package dev.edt.gitflow.core.tests;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.edt.gitflow.core.GitHubClient;
import dev.edt.gitflow.core.GitHubRemote;

/**
 * Фейковый сервер GitHub для тестов клиента: отдает заранее заданные ответы по
 * абсолютному пути запроса и запоминает заголовки Authorization, Accept, версию
 * API и последнее тело.
 */
final class FakeGitHub implements AutoCloseable
{
    private static final class Response
    {
        private final int status;
        private final String body;
        private final Map<String, String> headers = new LinkedHashMap<>();

        private Response(int status, String body)
        {
            this.status = status;
            this.body = body;
        }
    }

    private final HttpServer server;
    private final Map<String, Response> responses = new HashMap<>();
    private String lastBody = ""; //$NON-NLS-1$
    private String lastAuthorization;
    private String lastAccept;
    private String lastApiVersion;

    FakeGitHub() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); //$NON-NLS-1$
        server.createContext("/", this::handle); //$NON-NLS-1$
        server.start();
    }

    void respond(String path, int status, String body)
    {
        responses.put(path, new Response(status, body));
    }

    void respond(String path, int status, String body, String headerName, String headerValue)
    {
        Response response = new Response(status, body);
        response.headers.put(headerName, headerValue);
        responses.put(path, response);
    }

    GitHubClient client()
    {
        GitHubRemote remote = GitHubRemote.parse(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/owner/repo.git"); //$NON-NLS-1$ //$NON-NLS-2$
        return new GitHubClient(remote, "test-token", null, Duration.ofSeconds(5)); //$NON-NLS-1$
    }

    String lastBody()
    {
        return lastBody;
    }

    String lastAuthorization()
    {
        return lastAuthorization;
    }

    String lastAccept()
    {
        return lastAccept;
    }

    String lastApiVersion()
    {
        return lastApiVersion;
    }

    private Response lookup(String rawPath, String rawQuery)
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
            Response response = responses.get(candidate);
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
        lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization"); //$NON-NLS-1$
        lastAccept = exchange.getRequestHeaders().getFirst("Accept"); //$NON-NLS-1$
        lastApiVersion = exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version"); //$NON-NLS-1$
        Response response = lookup(rawPath, rawQuery);
        if (response == null)
            response = new Response(404, "{\"message\":\"Not Found\"}"); //$NON-NLS-1$
        byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
        for (Map.Entry<String, String> header : response.headers.entrySet())
            exchange.getResponseHeaders().add(header.getKey(), header.getValue());
        exchange.sendResponseHeaders(response.status, bytes.length);
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
