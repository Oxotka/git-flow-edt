package dev.edt.gitflow.ui.handlers;

import java.time.Duration;

import javax.net.ssl.SSLContext;

import org.eclipse.jgit.lib.Repository;

import dev.edt.gitflow.core.GitHubClient;
import dev.edt.gitflow.core.GitHubRemote;
import dev.edt.gitflow.core.GitLabClient;
import dev.edt.gitflow.core.GitLabRemote;
import dev.edt.gitflow.core.HostingProvider;
import dev.edt.gitflow.core.MergeRequestProvider;

/**
 * Выбор хостинга merge request по адресу remote и создание клиента. Панель и
 * форма настроек работают через эту фабрику и не знают, GitLab это или GitHub.
 * <p>
 * Токен уходит только на хост из настроек: чужой хостинг, случайно встретившийся
 * в рабочей области, клиента не получает.
 */
public final class HostingConnection
{
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private HostingConnection()
    {
    }

    /**
     * Клиент для репозитория. Хостинг, токен и сертификат берутся по хосту
     * {@code remote.origin.url}. Возвращает {@code null}, если remote не настроен
     * или не разобран, хост не совпал с настройками, для хоста нет токена или
     * подключение не собралось: панель в этом случае просто не показывает строку.
     * <p>
     * Исключения наружу не выходят: это фоновая задача панели, и любая ошибка
     * настроек означает «строки нет», а не сбой обновления.
     */
    public static MergeRequestProvider create(Repository repository)
    {
        if (repository == null)
            return null;
        try
        {
            String remoteUrl = repository.getConfig()
                .getString("remote", "origin", "url"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            String host = host(remoteUrl);
            if (host == null)
                return null;
            HostingSettings settings = HostingSettings.load();
            if (!settings.matches(host))
                return null;
            String token = settings.token(host);
            if (token.isBlank())
                return null;
            SSLContext ssl = HostingSsl.context(settings);
            HostingProvider provider = settings.provider(host);
            if (provider == HostingProvider.GITHUB)
                return new GitHubClient(GitHubRemote.parse(remoteUrl), token, ssl, TIMEOUT);
            return new GitLabClient(GitLabRemote.parse(remoteUrl), token, ssl, TIMEOUT);
        }
        catch (Exception e)
        {
            // Нет настроек, нет сертификата или адрес не разобран — строки нет.
            return null;
        }
    }

    /**
     * Клиент по значениям формы настроек, без репозитория: адрес сервера и путь
     * проекта приходят из полей. Нужен для проверки подключения из формы.
     * Если путь проекта не задан, клиент строится для корня сервера.
     *
     * @throws IllegalArgumentException если адрес сервера не разобран: это ошибка
     *             в форме, а не сбой хостинга
     */
    public static MergeRequestProvider probe(String serverUrl, HostingProvider provider,
        String projectPath, String token)
    {
        String base = serverUrl == null ? "" : serverUrl.trim(); //$NON-NLS-1$
        while (base.endsWith("/")) //$NON-NLS-1$
            base = base.substring(0, base.length() - 1);
        if (base.isEmpty())
            throw new IllegalArgumentException("Не задан адрес сервера хостинга."); //$NON-NLS-1$
        String path = cleanPath(projectPath);
        String readyToken = token == null ? "" : token.trim(); //$NON-NLS-1$
        HostingProvider readyProvider = provider == null ? HostingProvider.GITLAB : provider;
        if (readyProvider == HostingProvider.GITHUB)
        {
            GitHubRemote remote = path.isEmpty() ? GitHubRemote.forServer(base)
                : GitHubRemote.parse(base + "/" + path); //$NON-NLS-1$
            return new GitHubClient(remote, readyToken, context(), TIMEOUT);
        }
        GitLabRemote remote = path.isEmpty() ? GitLabRemote.forServer(base)
            : GitLabRemote.parse(base + "/" + path); //$NON-NLS-1$
        return new GitLabClient(remote, readyToken, context(), TIMEOUT);
    }

    /**
     * Хост адреса remote. Разбор пути у GitLab и GitHub общий, поэтому хост не
     * зависит от выбранного хостинга.
     */
    private static String host(String remoteUrl)
    {
        try
        {
            return GitLabRemote.parse(remoteUrl).host();
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    /** Путь проекта без обрамляющих слэшей и суффикса {@code .git}. */
    private static String cleanPath(String projectPath)
    {
        String path = projectPath == null ? "" : projectPath.trim(); //$NON-NLS-1$
        while (path.startsWith("/")) //$NON-NLS-1$
            path = path.substring(1);
        while (path.endsWith("/")) //$NON-NLS-1$
            path = path.substring(0, path.length() - 1);
        if (path.endsWith(".git")) //$NON-NLS-1$
            path = path.substring(0, path.length() - 4);
        return path;
    }

    /**
     * Контекст сертификатов из сохраненных настроек. Если его не собрать, остается
     * стандартное доверие JVM: проверка подключения покажет ошибку TLS сама.
     */
    private static SSLContext context()
    {
        try
        {
            return HostingSsl.context(HostingSettings.load());
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
