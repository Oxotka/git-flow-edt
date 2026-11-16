package dev.edt.gitflow.ui.handlers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import dev.edt.gitflow.core.HostingProvider;

/**
 * Настройки подключения к хостингу merge request: GitLab или GitHub. Обычные
 * значения лежат в настройках EDT, токен — в шифрованном хранилище, отдельным
 * ключом на каждый сервер.
 * <p>
 * Новые ключи хранятся в узле {@code hosting}; при их отсутствии читаются
 * прежние ключи GitLab. Старые значения не удаляются: миграция только на чтение.
 */
public final class HostingSettings
{
    public enum CertMode
    {
        WINDOWS, FILE
    }

    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$
    private static final String NODE = "hosting"; //$NON-NLS-1$
    private static final String LEGACY_NODE = "gitlab"; //$NON-NLS-1$
    private static final String BASE_URL = "baseUrl"; //$NON-NLS-1$
    private static final String SERVER_HOST = "serverHost"; //$NON-NLS-1$
    private static final String CERT_MODE = "certMode"; //$NON-NLS-1$
    private static final String CERT_FILE = "certFile"; //$NON-NLS-1$
    private static final String BRANCH_PREFIXES = "branchPrefixes"; //$NON-NLS-1$
    private static final String HOST_BASE_URL_PREFIX = "baseUrl."; //$NON-NLS-1$
    private static final String PROVIDER_PREFIX = "provider."; //$NON-NLS-1$
    private static final String TOKEN_PREFIX = "hosting.token."; //$NON-NLS-1$
    private static final String LEGACY_TOKEN_PREFIX = "gitlab.token."; //$NON-NLS-1$

    private static volatile HostingSettings cached;
    private static final java.util.Map<String, String> TOKENS = new java.util.concurrent.ConcurrentHashMap<>();

    private final String baseUrl;
    private final String serverHost;
    private final HostingProvider provider;
    private final CertMode certMode;
    private final String certFile;

    private HostingSettings(String baseUrl, String serverHost, HostingProvider provider,
        CertMode certMode, String certFile)
    {
        this.baseUrl = baseUrl;
        this.serverHost = serverHost;
        this.provider = provider;
        this.certMode = certMode;
        this.certFile = certFile;
    }

    public static HostingSettings forTesting(String baseUrl, String serverHost,
        HostingProvider provider)
    {
        return new HostingSettings(baseUrl, serverHost,
            provider == null ? HostingProvider.GITLAB : provider, CertMode.WINDOWS, null);
    }

    public static HostingSettings forTesting(String baseUrl, String serverHost, CertMode certMode,
        String certFile)
    {
        HostingProvider provider = HostingProvider.detect(serverHost);
        return new HostingSettings(baseUrl, serverHost,
            provider == null ? HostingProvider.GITLAB : provider, certMode, certFile);
    }

    public static HostingSettings load()
    {
        HostingSettings ready = cached;
        if (ready != null)
            return ready;
        Preferences node = hostingNode();
        Preferences legacy = legacyNode();
        String baseUrl = node.get(BASE_URL, ""); //$NON-NLS-1$
        if (baseUrl.isBlank())
            baseUrl = legacy.get(BASE_URL, ""); //$NON-NLS-1$
        String serverHost = node.get(SERVER_HOST, ""); //$NON-NLS-1$
        if (serverHost.isBlank())
            serverHost = legacy.get(SERVER_HOST, ""); //$NON-NLS-1$
        String mode = node.get(CERT_MODE, null);
        if (mode == null)
            mode = legacy.get(CERT_MODE, CertMode.WINDOWS.name());
        String certFile = node.get(CERT_FILE, null);
        if (certFile == null)
            certFile = legacy.get(CERT_FILE, null);
        HostingProvider provider = storedProvider(serverHost);
        if (provider == null)
            provider = HostingProvider.detect(serverHost);
        if (provider == null)
            provider = HostingProvider.GITLAB;
        HostingSettings loaded = new HostingSettings(baseUrl, serverHost, provider,
            parseCertMode(mode), certFile);
        cached = loaded;
        return loaded;
    }

    public void save(String newBaseUrl, String newServerHost, HostingProvider newProvider,
        CertMode newCertMode, String newCertFile)
    {
        String base = clean(newBaseUrl);
        String host = clean(newServerHost);
        HostingProvider readyProvider = newProvider == null ? HostingProvider.GITLAB : newProvider;
        String certificate = clean(newCertFile);
        Preferences node = hostingNode();
        node.put(BASE_URL, base);
        node.put(SERVER_HOST, host);
        node.put(CERT_MODE, newCertMode.name());
        node.put(CERT_FILE, certificate);
        if (!host.isEmpty())
        {
            node.put(HOST_BASE_URL_PREFIX + normalize(host), base);
            node.put(PROVIDER_PREFIX + normalize(host), readyProvider.name());
        }
        try
        {
            node.flush();
        }
        catch (BackingStoreException e)
        {
            throw new IllegalStateException("Не удалось сохранить настройки хостинга.", e); //$NON-NLS-1$
        }
        HostingSsl.reset();
        cached = new HostingSettings(base, host, readyProvider, newCertMode, certificate);
    }

    public static synchronized List<String> defaultBranchPrefixes()
    {
        return List.of("fix/", "feat/"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Префиксы веток, для которых предлагается создать merge request. */
    public List<String> branchPrefixes()
    {
        String stored = hostingNode().get(BRANCH_PREFIXES, ""); //$NON-NLS-1$
        if (stored.isBlank())
            stored = legacyNode().get(BRANCH_PREFIXES, ""); //$NON-NLS-1$
        if (stored.isBlank())
            return defaultBranchPrefixes();
        List<String> result = new ArrayList<>();
        for (String part : stored.split(",")) //$NON-NLS-1$
        {
            String prefix = part.trim();
            if (!prefix.isEmpty())
                result.add(prefix);
        }
        return result.isEmpty() ? defaultBranchPrefixes() : result;
    }

    public void saveBranchPrefixes(String prefixes)
    {
        Preferences node = hostingNode();
        node.put(BRANCH_PREFIXES, prefixes == null ? "" : prefixes.trim()); //$NON-NLS-1$
        try
        {
            node.flush();
        }
        catch (BackingStoreException e)
        {
            throw new IllegalStateException("Не удалось сохранить список веток.", e); //$NON-NLS-1$
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
        String expected = normalize(serverHost);
        return !expected.isEmpty() && expected.equals(normalize(host));
    }

    /**
     * Тип хостинга для адреса: сохраненный для этого хоста, затем распознанный
     * по хосту, затем по настроенному серверу. Если ничего не распознано,
     * остается тип, с которым созданы настройки.
     */
    public HostingProvider provider(String host)
    {
        HostingProvider stored = storedProvider(host);
        if (stored != null)
            return stored;
        HostingProvider detected = HostingProvider.detect(host);
        if (detected != null)
            return detected;
        detected = HostingProvider.detect(serverHost);
        if (detected != null)
            return detected;
        return provider;
    }

    public String token(String host)
    {
        String key = normalize(host);
        String known = TOKENS.get(key);
        if (known != null)
            return known;
        try
        {
            ISecurePreferences node = secureNode();
            String value = node.get(TOKEN_PREFIX + key, ""); //$NON-NLS-1$
            if (value.isEmpty())
            {
                // Токен, сохраненный прежней версией плагина для GitLab.
                value = node.get(LEGACY_TOKEN_PREFIX + key, ""); //$NON-NLS-1$
            }
            TOKENS.put(key, value);
            return value;
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
            ISecurePreferences node = secureNode();
            String clean = token == null ? "" : token.trim(); //$NON-NLS-1$
            node.put(TOKEN_PREFIX + normalize(host), clean, true);
            node.flush();
            TOKENS.put(normalize(host), clean);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(
                "Шифрованное хранилище EDT недоступно, токен не сохранен.", e); //$NON-NLS-1$
        }
    }

    /** Сохраненный тип хостинга для хоста; {@code null}, если его еще нет. */
    private static HostingProvider storedProvider(String host)
    {
        if (host == null || host.isBlank())
            return null;
        String value = hostingNode().get(PROVIDER_PREFIX + normalize(host), ""); //$NON-NLS-1$
        if (value.isBlank())
            return null;
        try
        {
            return HostingProvider.valueOf(value);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private static CertMode parseCertMode(String mode)
    {
        if (mode == null)
            return CertMode.WINDOWS;
        try
        {
            return CertMode.valueOf(mode);
        }
        catch (IllegalArgumentException e)
        {
            return CertMode.WINDOWS;
        }
    }

    private static String clean(String value)
    {
        return value == null ? "" : value.trim(); //$NON-NLS-1$
    }

    private static String normalize(String host)
    {
        return host == null ? "" : host.trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$
    }

    private static Preferences hostingNode()
    {
        return InstanceScope.INSTANCE.getNode(PLUGIN_ID).node(NODE);
    }

    private static Preferences legacyNode()
    {
        return InstanceScope.INSTANCE.getNode(PLUGIN_ID).node(LEGACY_NODE);
    }

    private static ISecurePreferences secureNode()
    {
        return SecurePreferencesFactory.getDefault().node(PLUGIN_ID);
    }
}
