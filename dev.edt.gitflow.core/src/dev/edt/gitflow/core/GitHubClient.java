package dev.edt.gitflow.core;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Клиент GitHub REST API. Сетевые вызовы синхронные: их запускает фоновая
 * задача, а не поток интерфейса.
 */
public final class GitHubClient implements MergeRequestProvider
{
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final String ACCEPT = "application/vnd.github+json"; //$NON-NLS-1$
    private static final String API_VERSION = "2022-11-28"; //$NON-NLS-1$

    private final GitHubRemote remote;
    private final String token;
    private final HttpClient http;
    private final Duration timeout;

    public GitHubClient(GitHubRemote remote, String token, SSLContext ssl, Duration timeout)
    {
        this.remote = remote;
        this.token = token;
        this.timeout = timeout;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
            .proxy(proxySelector());
        if (ssl != null)
            builder.sslContext(ssl);
        this.http = builder.build();
    }

    /**
     * Системный прокси, как у остальных сетевых вызовов, но loopback-адреса
     * идут напрямую: иначе тесты на 127.0.0.1 могут уйти в корпоративный прокси.
     */
    private static ProxySelector proxySelector()
    {
        ProxySelector system = ProxySelector.getDefault();
        return new ProxySelector()
        {
            @Override
            public List<Proxy> select(URI uri)
            {
                if (isLoopback(uri.getHost()))
                    return List.of(Proxy.NO_PROXY);
                return system == null ? List.of(Proxy.NO_PROXY) : system.select(uri);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException e)
            {
                if (system != null)
                    system.connectFailed(uri, address, e);
            }
        };
    }

    private static boolean isLoopback(String host)
    {
        if (host == null)
            return false;
        String value = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(value) || "::1".equals(value) || "[::1]".equals(value) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            || value.startsWith("127."); //$NON-NLS-1$
    }

    public GitHubRemote remote()
    {
        return remote;
    }

    @Override
    public String host()
    {
        return remote.host();
    }

    @Override
    public String projectPath()
    {
        return remote.projectPath();
    }

    @Override
    public String providerName()
    {
        return "GitHub"; //$NON-NLS-1$
    }

    @Override
    public String requestShortName()
    {
        return "PR"; //$NON-NLS-1$
    }

    @Override
    public String requestFullName()
    {
        return "pull request"; //$NON-NLS-1$
    }

    @Override
    public boolean supportsDeleteSourceBranch()
    {
        return false;
    }

    @Override
    public String currentUser() throws HostingException
    {
        return string(getObject("user"), "login"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public String defaultBranch() throws HostingException
    {
        return string(getObject(repoPath()), "default_branch"); //$NON-NLS-1$
    }

    @Override
    public boolean canCreateRequests() throws HostingException
    {
        JsonObject project = getObject(repoPath());
        if (!project.has("permissions") || !project.get("permissions").isJsonObject()) //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        return booleanValue(project.getAsJsonObject("permissions"), "push"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Участники проекта для выбора проверяющего. GitHub отдает логин в поле
     * login, имени пользователя в ответе нет: в подписи будет логин.
     */
    @Override
    public List<ProjectMember> projectMembers() throws HostingException
    {
        JsonArray members = JsonParser.parseString(send(request(
            repoPath() + "/collaborators?per_page=100"))).getAsJsonArray(); //$NON-NLS-1$
        List<ProjectMember> result = new ArrayList<>();
        for (JsonElement element : members)
        {
            JsonObject member = element.getAsJsonObject();
            result.add(new ProjectMember(member.get("id").getAsInt(), null, //$NON-NLS-1$
                string(member, "login"))); //$NON-NLS-1$
        }
        return result;
    }

    /**
     * Состояние pull request ветки. Из нескольких запросов выбирается открытый,
     * иначе первый по времени обновления. Проверки, одобрения и готовность
     * к слиянию приводятся к значениям, которые показывает строка панели.
     */
    @Override
    public Optional<MergeRequestStatus> statusForBranch(String branch) throws HostingException
    {
        JsonArray list = JsonParser.parseString(send(request(repoPath() + "/pulls?head=" //$NON-NLS-1$
            + encode(remote.owner() + ":" + branch) //$NON-NLS-1$
            + "&state=all&sort=updated&direction=desc&per_page=10"))).getAsJsonArray(); //$NON-NLS-1$
        JsonElement chosen = null;
        for (JsonElement element : list)
        {
            if (chosen == null)
                chosen = element;
            if ("open".equals(string(element.getAsJsonObject(), "state"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                chosen = element;
                break;
            }
        }
        if (chosen == null)
            return Optional.empty();
        int number = chosen.getAsJsonObject().get("number").getAsInt(); //$NON-NLS-1$
        // В списке нет mergeable, проверок и сведений о слиянии: они приходят
        // только в деталях pull request.
        JsonObject pull = getObject(repoPath() + "/pulls/" + number); //$NON-NLS-1$
        JsonArray reviews = JsonParser.parseString(send(request(
            repoPath() + "/pulls/" + number + "/reviews?per_page=100"))).getAsJsonArray(); //$NON-NLS-1$ //$NON-NLS-2$
        int required = 0;
        boolean approvalsRequiredKnown = false;
        String base = baseRef(pull);
        if (base != null)
        {
            try
            {
                JsonObject rule = getObject(repoPath() + "/branches/" + encode(base) //$NON-NLS-1$
                    + "/protection/required_pull_request_reviews"); //$NON-NLS-1$
                if (rule.has("required_approving_review_count")) //$NON-NLS-1$
                    required = rule.get("required_approving_review_count").getAsInt(); //$NON-NLS-1$
                approvalsRequiredKnown = true;
            }
            catch (HostingException e)
            {
                // Правило видят только администраторы репозитория: без него
                // показывается число одобрений без «из скольких».
                if (e.kind() != HostingException.Kind.FORBIDDEN
                    && e.kind() != HostingException.Kind.NOT_FOUND)
                    throw e;
            }
        }
        return Optional.of(new MergeRequestStatus(number, string(pull, "title"), //$NON-NLS-1$
            state(pull), booleanValue(pull, "draft"), string(pull, "html_url"), //$NON-NLS-1$ //$NON-NLS-2$
            approvedCount(reviews), required, pipelineStatus(headSha(pull)), //$NON-NLS-1$
            hasConflicts(pull), true, 0, mergeStatus(pull), string(pull, "merged_at"), //$NON-NLS-1$ //$NON-NLS-2$
            mergedBy(pull), approvalsRequiredKnown));
    }

    /**
     * Создание pull request. Пока не реализовано.
     */
    @Override
    public MergeRequestStatus createMergeRequest(CreateMergeRequest request) throws HostingException
    {
        throw new UnsupportedOperationException();
    }

    /**
     * Состояние в терминах строки: слитый запрос важнее закрытого, открытый
     * приводится к «opened».
     */
    private static String state(JsonObject pull)
    {
        if (string(pull, "merged_at") != null) //$NON-NLS-1$
            return "merged"; //$NON-NLS-1$
        String state = string(pull, "state"); //$NON-NLS-1$
        if ("open".equals(state)) //$NON-NLS-1$
            return "opened"; //$NON-NLS-1$
        return state;
    }

    private static String headSha(JsonObject pull)
    {
        return nested(pull, "head", "sha"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String baseRef(JsonObject pull)
    {
        return nested(pull, "base", "ref"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String mergedBy(JsonObject pull)
    {
        return nested(pull, "merged_by", "login"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String nested(JsonObject object, String field, String name)
    {
        if (!object.has(field) || !object.get(field).isJsonObject())
            return null;
        return string(object.getAsJsonObject(field), name);
    }

    /**
     * Конфликты видны только когда GitHub уже посчитал слияние. Пустой
     * mergeable означает «еще считается»: ни конфликтов, ни готовности.
     */
    private static boolean hasConflicts(JsonObject pull)
    {
        Boolean mergeable = optionalBoolean(pull, "mergeable"); //$NON-NLS-1$
        if (mergeable == null)
            return false;
        return !mergeable.booleanValue() || "dirty".equals(string(pull, "mergeable_state")); //$NON-NLS-1$
    }

    private static String mergeStatus(JsonObject pull)
    {
        Boolean mergeable = optionalBoolean(pull, "mergeable"); //$NON-NLS-1$
        if (mergeable == null || !mergeable.booleanValue())
            return null;
        return "clean".equals(string(pull, "mergeable_state")) ? "mergeable" : null; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Boolean optionalBoolean(JsonObject object, String name)
    {
        if (!object.has(name) || object.get(name).isJsonNull())
            return null;
        return object.get(name).getAsBoolean();
    }

    /**
     * Число одобрений: решающим считается последнее ревью каждого пользователя.
     * COMMENTED и PENDING одобрение не меняют, CHANGES_REQUESTED и DISMISSED
     * снимают; ревью приходят в хронологическом порядке.
     */
    private static int approvedCount(JsonArray reviews)
    {
        Map<String, Boolean> latest = new LinkedHashMap<>();
        for (JsonElement element : reviews)
        {
            JsonObject review = element.getAsJsonObject();
            String login = nested(review, "user", "login"); //$NON-NLS-1$ //$NON-NLS-2$
            String status = string(review, "state"); //$NON-NLS-1$
            if (login == null || status == null)
                continue;
            switch (status)
            {
                case "APPROVED" -> latest.put(login, Boolean.TRUE); //$NON-NLS-1$
                case "CHANGES_REQUESTED", "DISMISSED" -> latest.put(login, Boolean.FALSE); //$NON-NLS-1$ //$NON-NLS-2$
                default -> { } // COMMENTED и PENDING ничего не меняют.
            }
        }
        int approved = 0;
        for (Boolean value : latest.values())
            if (value.booleanValue())
                approved++;
        return approved;
    }

    /**
     * Итог проверок коммита: check-runs, а при их отсутствии — combined status.
     * Недоступные проверки не скрывают строку, а оставляют статус пустым.
     */
    private String pipelineStatus(String sha) throws HostingException
    {
        if (sha == null)
            return null;
        JsonObject runs;
        try
        {
            runs = getObject(repoPath() + "/commits/" + encode(sha) + "/check-runs?per_page=100"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (HostingException e)
        {
            if (e.kind() == HostingException.Kind.FORBIDDEN
                || e.kind() == HostingException.Kind.NOT_FOUND)
                return null;
            throw e;
        }
        if (runs.has("total_count") && runs.get("total_count").getAsInt() > 0) //$NON-NLS-1$ //$NON-NLS-2$
            return checkRunsStatus(runs.has("check_runs") //$NON-NLS-1$
                ? runs.getAsJsonArray("check_runs") : new JsonArray()); //$NON-NLS-1$
        try
        {
            return combinedStatus(string(getObject(
                repoPath() + "/commits/" + encode(sha) + "/status"), "state")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        catch (HostingException e)
        {
            if (e.kind() == HostingException.Kind.FORBIDDEN
                || e.kind() == HostingException.Kind.NOT_FOUND)
                return null;
            throw e;
        }
    }

    private static String checkRunsStatus(JsonArray runs)
    {
        boolean running = false;
        for (JsonElement element : runs)
        {
            JsonObject run = element.getAsJsonObject();
            if (!"completed".equals(string(run, "status"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                running = true;
                continue;
            }
            if (isFailedConclusion(string(run, "conclusion"))) //$NON-NLS-1$
                return "failed"; //$NON-NLS-1$
        }
        return running ? "running" : "success"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isFailedConclusion(String conclusion)
    {
        if (conclusion == null)
            return false;
        return switch (conclusion)
        {
            case "failure", "timed_out", "cancelled", "action_required", "startup_failure" -> true; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            default -> false;
        };
    }

    private static String combinedStatus(String state)
    {
        if (state == null)
            return null;
        return switch (state)
        {
            case "failure", "error" -> "failed"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "pending" -> "running"; //$NON-NLS-1$ //$NON-NLS-2$
            case "success" -> "success"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> null;
        };
    }

    private String repoPath()
    {
        return "repos/" + remote.projectPath(); //$NON-NLS-1$
    }

    private JsonObject getObject(String path) throws HostingException
    {
        return JsonParser.parseString(send(request(path))).getAsJsonObject();
    }

    private HttpRequest request(String path)
    {
        return HttpRequest.newBuilder(URI.create(remote.apiBase() + "/" + path)) //$NON-NLS-1$
            .timeout(timeout)
            .header("Authorization", "Bearer " + (token == null ? "" : token)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .header("Accept", ACCEPT) //$NON-NLS-1$
            .header("X-GitHub-Api-Version", API_VERSION) //$NON-NLS-1$
            .GET()
            .build();
    }

    private String send(HttpRequest request) throws HostingException
    {
        HttpResponse<String> response;
        try
        {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (SSLException e)
        {
            throw new HostingException(HostingException.Kind.TLS,
                "Сертификат сервера не доверен", e); //$NON-NLS-1$
        }
        catch (HttpTimeoutException e)
        {
            throw new HostingException(HostingException.Kind.NETWORK, "Сервер не отвечает", e); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            throw new HostingException(HostingException.Kind.NETWORK,
                e.getMessage() == null ? "Сервер не отвечает" : e.getMessage(), e); //$NON-NLS-1$
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new HostingException(HostingException.Kind.NETWORK, "Запрос прерван", e); //$NON-NLS-1$
        }
        if (response.statusCode() >= 200 && response.statusCode() < 300)
            return response.body();
        throw failure(response.statusCode(), response.body(),
            response.headers().firstValue("X-RateLimit-Remaining").orElse(null)); //$NON-NLS-1$
    }

    private static HostingException failure(int status, String body, String rateLimitRemaining)
    {
        HostingException.Kind kind;
        if (status == 429 || (status == 403 && "0".equals(trim(rateLimitRemaining)))) //$NON-NLS-1$
            kind = HostingException.Kind.RATE_LIMIT;
        else
            kind = switch (status)
            {
                case 400, 422 -> HostingException.Kind.BAD_REQUEST;
                case 401 -> HostingException.Kind.UNAUTHORIZED;
                case 403 -> HostingException.Kind.FORBIDDEN;
                case 404 -> HostingException.Kind.NOT_FOUND;
                case 409 -> HostingException.Kind.CONFLICT;
                default -> HostingException.Kind.SERVER;
            };
        return new HostingException(kind, "GitHub ответил " + status + ": " + body); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String trim(String value)
    {
        return value == null ? null : value.trim();
    }

    private static String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String string(JsonObject object, String name)
    {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : null;
    }

    private static boolean booleanValue(JsonObject object, String name)
    {
        return object.has(name) && object.get(name).isJsonPrimitive() && object.get(name).getAsBoolean();
    }
}
