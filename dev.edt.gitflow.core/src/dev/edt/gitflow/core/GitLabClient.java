package dev.edt.gitflow.core;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Клиент GitLab REST API. Сетевые вызовы синхронные: их запускает фоновая
 * задача, а не поток интерфейса.
 */
public final class GitLabClient
{
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final GitLabRemote remote;
    private final String token;
    private final HttpClient http;
    private final Duration timeout;

    public GitLabClient(GitLabRemote remote, String token, SSLContext ssl, Duration timeout)
    {
        this.remote = remote;
        this.token = token;
        this.timeout = timeout;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT);
        if (ssl != null)
            builder.sslContext(ssl);
        this.http = builder.build();
    }

    public String currentUser() throws GitLabException
    {
        return get("user").get("name").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public List<String> tokenScopes() throws GitLabException
    {
        JsonArray scopes = get("personal_access_tokens/self").getAsJsonArray("scopes"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> result = new ArrayList<>();
        for (JsonElement scope : scopes)
            result.add(scope.getAsString());
        return result;
    }

    private JsonObject get(String path) throws GitLabException
    {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
            .timeout(timeout)
            .header("PRIVATE-TOKEN", token == null ? "" : token) //$NON-NLS-1$ //$NON-NLS-2$
            .GET()
            .build();
        return send(request);
    }

    private java.net.URI uri(String path)
    {
        return java.net.URI.create(remote.apiBase() + "/" + path); //$NON-NLS-1$
    }

    private JsonObject send(HttpRequest request) throws GitLabException
    {
        HttpResponse<String> response;
        try
        {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (SSLException e)
        {
            throw new GitLabException(GitLabException.Kind.TLS,
                "Сертификат сервера не доверен", e); //$NON-NLS-1$
        }
        catch (HttpTimeoutException e)
        {
            throw new GitLabException(GitLabException.Kind.NETWORK, "Сервер не отвечает", e); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            throw new GitLabException(GitLabException.Kind.NETWORK,
                e.getMessage() == null ? "Сервер не отвечает" : e.getMessage(), e); //$NON-NLS-1$
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new GitLabException(GitLabException.Kind.NETWORK, "Запрос прерван", e); //$NON-NLS-1$
        }
        if (response.statusCode() >= 200 && response.statusCode() < 300)
            return JsonParser.parseString(response.body()).getAsJsonObject();
        throw failure(response.statusCode(), response.body());
    }

    private static GitLabException failure(int status, String body)
    {
        GitLabException.Kind kind = switch (status)
        {
            case 400 -> GitLabException.Kind.BAD_REQUEST;
            case 401 -> GitLabException.Kind.UNAUTHORIZED;
            case 403 -> GitLabException.Kind.FORBIDDEN;
            case 404 -> GitLabException.Kind.NOT_FOUND;
            case 409 -> GitLabException.Kind.CONFLICT;
            default -> GitLabException.Kind.SERVER;
        };
        return new GitLabException(kind, "GitLab ответил " + status + ": " + body); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
