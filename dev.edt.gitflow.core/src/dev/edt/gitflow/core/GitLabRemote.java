package dev.edt.gitflow.core;

import java.net.URI;

/**
 * Адрес GitLab, выведенный из remote.origin.url: хост, адрес сайта, путь проекта
 * и база REST API. Порт веб-адреса сохраняется, порт SSH отбрасывается: он не
 * имеет отношения к https.
 */
public final class GitLabRemote
{
    private final String host;
    private final String webBase;
    private final String projectPath;
    private final String apiBase;

    private GitLabRemote(String host, String webBase, String projectPath, String apiBase)
    {
        this.host = host;
        this.webBase = webBase;
        this.projectPath = projectPath;
        this.apiBase = apiBase;
    }

    public static GitLabRemote parse(String remoteUrl)
    {
        if (remoteUrl == null || remoteUrl.isBlank())
            throw new IllegalArgumentException("У репозитория не настроен remote.origin.url."); //$NON-NLS-1$
        URI uri = URI.create(GitLinks.baseUrl(remoteUrl));
        String path = uri.getPath() == null ? "" : uri.getPath(); //$NON-NLS-1$
        while (path.startsWith("/")) //$NON-NLS-1$
            path = path.substring(1);
        while (path.endsWith("/")) //$NON-NLS-1$
            path = path.substring(0, path.length() - 1);
        if (path.endsWith(".git")) //$NON-NLS-1$
            path = path.substring(0, path.length() - 4);
        if (path.isEmpty())
            throw new IllegalArgumentException("В адресе remote нет пути проекта."); //$NON-NLS-1$
        String authority = uri.getRawAuthority();
        String web = uri.getScheme() + "://" + authority + "/" + path; //$NON-NLS-1$ //$NON-NLS-2$
        String api = uri.getScheme() + "://" + authority + "/api/v4"; //$NON-NLS-1$ //$NON-NLS-2$
        return new GitLabRemote(uri.getHost(), web, path, api);
    }

    public String host()
    {
        return host;
    }

    public String webBase()
    {
        return webBase;
    }

    public String projectPath()
    {
        return projectPath;
    }

    public String apiBase()
    {
        return apiBase;
    }
}
