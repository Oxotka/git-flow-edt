package dev.edt.gitflow.core;

import java.net.URI;

/**
 * Адрес GitHub, выведенный из remote.origin.url: хост, адрес сайта, owner/repo
 * и база REST API. Для github.com API живет на отдельном хосте api.github.com,
 * у GitHub Enterprise Server — на том же хосте с префиксом /api/v3. Порт SSH
 * отбрасывается, как в {@link GitLabRemote}: он не имеет отношения к https.
 */
public final class GitHubRemote
{
    private static final String GITHUB_COM = "github.com"; //$NON-NLS-1$

    private final String host;
    private final String webBase;
    private final String projectPath;
    private final String apiBase;

    private GitHubRemote(String host, String webBase, String projectPath, String apiBase)
    {
        this.host = host;
        this.webBase = webBase;
        this.projectPath = projectPath;
        this.apiBase = apiBase;
    }

    public static GitHubRemote parse(String remoteUrl)
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
        String origin = uri.getScheme() + "://" + uri.getRawAuthority(); //$NON-NLS-1$
        return new GitHubRemote(uri.getHost(), origin + "/" + path, path, apiBase(uri, origin)); //$NON-NLS-1$
    }

    /**
     * Адрес сервера без проекта: используется формой настроек, где известен
     * только корень GitHub.
     */
    public static GitHubRemote forServer(String serverUrl)
    {
        if (serverUrl == null || serverUrl.isBlank())
            throw new IllegalArgumentException("Не задан адрес сервера GitHub."); //$NON-NLS-1$
        String root = serverUrl.trim();
        while (root.endsWith("/")) //$NON-NLS-1$
            root = root.substring(0, root.length() - 1);
        URI uri = URI.create(root);
        if (uri.getHost() == null || uri.getScheme() == null)
            throw new IllegalArgumentException("Некорректный адрес сервера GitHub."); //$NON-NLS-1$
        return new GitHubRemote(uri.getHost(), root, "", apiBase(uri, root)); //$NON-NLS-1$
    }

    private static String apiBase(URI uri, String base)
    {
        if (GITHUB_COM.equalsIgnoreCase(uri.getHost()))
            return "https://api.github.com"; //$NON-NLS-1$
        return base + "/api/v3"; //$NON-NLS-1$
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

    /**
     * Владелец проекта: часть пути до последнего слэша. У корня сервера пустая.
     */
    public String owner()
    {
        int slash = projectPath.lastIndexOf('/');
        return slash < 0 ? "" : projectPath.substring(0, slash); //$NON-NLS-1$
    }

    public String apiBase()
    {
        return apiBase;
    }

    public String tokenCreationUrl(String description)
    {
        URI uri = URI.create(webBase);
        return uri.getScheme() + "://" + uri.getRawAuthority() //$NON-NLS-1$
            + "/settings/personal-access-tokens/new?name=EDT%20Git%20Flow&expires_in=365&contents=read&pull_requests=write&description="
            + java.net.URLEncoder.encode(description, java.nio.charset.StandardCharsets.UTF_8); //$NON-NLS-1$
    }
}
