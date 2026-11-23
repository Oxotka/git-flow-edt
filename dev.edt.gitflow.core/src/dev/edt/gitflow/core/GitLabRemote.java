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

    /**
     * Адрес сервера без проекта: используется формой настроек, где известен
     * только корень GitLab.
     */
    public static GitLabRemote forServer(String serverUrl)
    {
        if (serverUrl == null || serverUrl.isBlank())
            throw new IllegalArgumentException("Не задан адрес сервера GitLab."); //$NON-NLS-1$
        String root = serverUrl.trim();
        while (root.endsWith("/")) //$NON-NLS-1$
            root = root.substring(0, root.length() - 1);
        URI uri = URI.create(root);
        if (uri.getHost() == null || uri.getScheme() == null)
            throw new IllegalArgumentException("Некорректный адрес сервера GitLab."); //$NON-NLS-1$
        String api = root + "/api/v4"; //$NON-NLS-1$
        return new GitLabRemote(uri.getHost(), root, "", api); //$NON-NLS-1$
    }

    public String tokenCreationUrl(String description)
    {
        URI uri = URI.create(webBase);
        return uri.getScheme() + "://" + uri.getRawAuthority()
            + "/-/user_settings/personal_access_tokens?name=EDT%20Git%20Flow&scopes=api&description="
            + java.net.URLEncoder.encode(description, java.nio.charset.StandardCharsets.UTF_8);
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
