package dev.edt.gitflow.core;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
     * Состояние pull request ветки. Пока не реализовано.
     */
    @Override
    public Optional<MergeRequestStatus> statusForBranch(String branch) throws HostingException
    {
        throw new UnsupportedOperationException();
    }

    /**
     * Создание pull request. Пока не реализовано.
     */
    @Override
    public MergeRequestStatus createMergeRequest(CreateMergeRequest request) throws HostingException
    {
        throw new UnsupportedOperationException();
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

    private static String string(JsonObject object, String name)
    {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : null;
    }

    private static boolean booleanValue(JsonObject object, String name)
    {
        return object.has(name) && object.get(name).isJsonPrimitive() && object.get(name).getAsBoolean();
    }
}
