package dev.edt.gitflow.core;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        return getObject("user").get("name").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public List<String> tokenScopes() throws GitLabException
    {
        JsonArray scopes = getObject("personal_access_tokens/self").getAsJsonArray("scopes"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> result = new ArrayList<>();
        for (JsonElement scope : scopes)
            result.add(scope.getAsString());
        return result;
    }

    /**
     * Состояние merge request указанной ветки. Если по ветке несколько запросов,
     * выбирается открытый, иначе первый по времени обновления.
     */
    public Optional<MergeRequestStatus> statusForBranch(String branch) throws GitLabException
    {
        String base = "projects/" + encode(remote.projectPath()) + "/merge_requests"; //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray list = JsonParser.parseString(send(request(base + "?source_branch=" //$NON-NLS-1$
            + encode(branch) + "&state=all&order_by=updated_at"))).getAsJsonArray(); //$NON-NLS-1$
        JsonObject chosen = null;
        for (JsonElement element : list)
        {
            JsonObject candidate = element.getAsJsonObject();
            if (chosen == null)
                chosen = candidate;
            if ("opened".equals(string(candidate, "state"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                chosen = candidate;
                break;
            }
        }
        if (chosen == null)
            return Optional.empty();
        int iid = chosen.get("iid").getAsInt(); //$NON-NLS-1$

        int approved = 0;
        int required = 0;
        try
        {
            JsonObject approvals = getObject(base + "/" + iid + "/approvals"); //$NON-NLS-1$ //$NON-NLS-2$
            if (approvals.has("approved_by")) //$NON-NLS-1$
                approved = approvals.getAsJsonArray("approved_by").size(); //$NON-NLS-1$
            if (approvals.has("approvals_required")) //$NON-NLS-1$
                required = approvals.get("approvals_required").getAsInt(); //$NON-NLS-1$
        }
        catch (GitLabException e)
        {
            // Младшая редакция GitLab не отдает правила апрувов: счетчик не нужен.
            if (e.kind() != GitLabException.Kind.FORBIDDEN && e.kind() != GitLabException.Kind.NOT_FOUND)
                throw e;
        }

        String title = string(chosen, "title"); //$NON-NLS-1$
        String mergeStatus = string(chosen, "detailed_merge_status"); //$NON-NLS-1$
        if (mergeStatus == null)
            mergeStatus = string(chosen, "merge_status"); //$NON-NLS-1$
        boolean conflicts = booleanValue(chosen, "has_conflicts") //$NON-NLS-1$
            || "cannot_be_merged".equals(mergeStatus); //$NON-NLS-1$
        return Optional.of(new MergeRequestStatus(iid, title, string(chosen, "state"), //$NON-NLS-1$
            isDraft(chosen, title), string(chosen, "web_url"), approved, required, //$NON-NLS-1$
            pipelineStatus(chosen), conflicts, booleanValue(chosen, "blocking_discussions_resolved"), //$NON-NLS-1$
            chosen.has("user_notes_count") ? chosen.get("user_notes_count").getAsInt() : 0, mergeStatus)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isDraft(JsonObject mergeRequest, String title)
    {
        if (mergeRequest.has("draft")) //$NON-NLS-1$
            return mergeRequest.get("draft").getAsBoolean(); //$NON-NLS-1$
        return title != null && (title.startsWith("Draft:") || title.startsWith("WIP:")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String pipelineStatus(JsonObject mergeRequest)
    {
        if (!mergeRequest.has("head_pipeline") || !mergeRequest.get("head_pipeline").isJsonObject()) //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        return string(mergeRequest.getAsJsonObject("head_pipeline"), "status"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String string(JsonObject object, String name)
    {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : null;
    }

    private static boolean booleanValue(JsonObject object, String name)
    {
        return object.has(name) && object.get(name).isJsonPrimitive() && object.get(name).getAsBoolean();
    }

    private static String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private JsonObject getObject(String path) throws GitLabException
    {
        return JsonParser.parseString(send(request(path))).getAsJsonObject();
    }

    private HttpRequest request(String path)
    {
        return HttpRequest.newBuilder(java.net.URI.create(remote.apiBase() + "/" + path)) //$NON-NLS-1$
            .timeout(timeout)
            .header("PRIVATE-TOKEN", token == null ? "" : token) //$NON-NLS-1$ //$NON-NLS-2$
            .GET()
            .build();
    }

    private String send(HttpRequest request) throws GitLabException
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
            return response.body();
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
