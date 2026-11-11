package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/**
 * Настройки подключения к GitLab. Обычные значения лежат в настройках EDT,
 * токен — в шифрованном хранилище, отдельным ключом на каждый сервер.
 */
public final class GitLabSettings
{
    public enum CertMode
    {
        WINDOWS, FILE
    }

    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$
    private static final String NODE = "gitlab"; //$NON-NLS-1$
    private static final String BASE_URL = "baseUrl"; //$NON-NLS-1$
    private static final String SERVER_HOST = "serverHost"; //$NON-NLS-1$
    private static final String CERT_MODE = "certMode"; //$NON-NLS-1$
    private static final String CERT_FILE = "certFile"; //$NON-NLS-1$
    private static final String TOKEN_PREFIX = "gitlab.token."; //$NON-NLS-1$

    private final String baseUrl;
    private final String serverHost;
    private final CertMode certMode;
    private final String certFile;

    private GitLabSettings(String baseUrl, String serverHost, CertMode certMode, String certFile)
    {
        this.baseUrl = baseUrl;
        this.serverHost = serverHost;
        this.certMode = certMode;
        this.certFile = certFile;
    }

    public static GitLabSettings forTesting(String baseUrl, String serverHost)
    {
        return new GitLabSettings(baseUrl, serverHost, CertMode.WINDOWS, null);
    }

    public static GitLabSettings load()
    {
        Preferences node = InstanceScope.INSTANCE.getNode(PLUGIN_ID).node(NODE);
        String mode = node.get(CERT_MODE, CertMode.WINDOWS.name());
        CertMode certMode;
        try
        {
            certMode = CertMode.valueOf(mode);
        }
        catch (IllegalArgumentException e)
        {
            certMode = CertMode.WINDOWS;
        }
        return new GitLabSettings(node.get(BASE_URL, ""), node.get(SERVER_HOST, ""), //$NON-NLS-1$ //$NON-NLS-2$
            certMode, node.get(CERT_FILE, null));
    }

    public void save(String newBaseUrl, String newServerHost, CertMode newCertMode, String newCertFile)
    {
        Preferences node = InstanceScope.INSTANCE.getNode(PLUGIN_ID).node(NODE);
        node.put(BASE_URL, newBaseUrl == null ? "" : newBaseUrl.trim()); //$NON-NLS-1$
        node.put(SERVER_HOST, newServerHost == null ? "" : newServerHost.trim()); //$NON-NLS-1$
        node.put(CERT_MODE, newCertMode.name());
        node.put(CERT_FILE, newCertFile == null ? "" : newCertFile.trim()); //$NON-NLS-1$
        try
        {
            node.flush();
        }
        catch (BackingStoreException e)
        {
            throw new IllegalStateException("Не удалось сохранить настройки GitLab.", e); //$NON-NLS-1$
        }
    }

    public String baseUrl()
    {
        return baseUrl;
    }

    public String serverHost()
    {
        return serverHost;
    }

    public CertMode certMode()
    {
        return certMode;
    }

    public String certFile()
    {
        return certFile;
    }

    /**
     * Токен можно отправлять только на свой сервер: иначе он уйдет на GitHub или
     * другой хостинг, который встретится в рабочей области.
     */
    public boolean matches(String host)
    {
        if (host == null || serverHost == null)
            return false;
        String expected = serverHost.trim();
        return !expected.isEmpty() && expected.equalsIgnoreCase(host.trim());
    }

    public String token(String host)
    {
        try
        {
            ISecurePreferences node = SecurePreferencesFactory.getDefault().node(PLUGIN_ID);
            return node.get(TOKEN_PREFIX + host.trim().toLowerCase(), ""); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    public void saveToken(String host, String token)
    {
        try
        {
            ISecurePreferences node = SecurePreferencesFactory.getDefault().node(PLUGIN_ID);
            node.put(TOKEN_PREFIX + host.trim().toLowerCase(), token == null ? "" : token.trim(), true); //$NON-NLS-1$
            node.flush();
        }
        catch (Exception e)
        {
            throw new IllegalStateException(
                "Шифрованное хранилище EDT недоступно, токен не сохранен.", e); //$NON-NLS-1$
        }
    }
}
