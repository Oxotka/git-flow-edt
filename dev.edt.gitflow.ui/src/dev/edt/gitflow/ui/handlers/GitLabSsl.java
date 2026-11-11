package dev.edt.gitflow.ui.handlers;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Доверие серверу GitLab. На Windows берется системное хранилище, на остальных
 * платформах — файл сертификата из настроек. Проверка сертификата не отключается
 * никогда.
 */
public final class GitLabSsl
{
    private static String cachedKey;
    private static SSLContext cachedContext;

    private GitLabSsl()
    {
    }

    /**
     * Контекст строится один раз на режим сертификата: чтение хранилища Windows
     * заметно дорого, а настройки меняются редко.
     */
    public static synchronized SSLContext context(GitLabSettings settings) throws Exception
    {
        String key = settings.certMode() + "|" //$NON-NLS-1$
            + (settings.certFile() == null ? "" : settings.certFile()); //$NON-NLS-1$
        if (key.equals(cachedKey) && cachedContext != null)
            return cachedContext;
        SSLContext built = build(settings);
        cachedKey = key;
        cachedContext = built;
        return built;
    }

    /** Сбрасывает кеш: вызывается после сохранения настроек подключения. */
    public static synchronized void reset()
    {
        cachedKey = null;
        cachedContext = null;
    }

    private static SSLContext build(GitLabSettings settings) throws Exception
    {
        if (settings.certMode() == GitLabSettings.CertMode.FILE
            && settings.certFile() != null && !settings.certFile().isBlank())
            return fromFile(Path.of(settings.certFile()));
        if (settings.certMode() == GitLabSettings.CertMode.WINDOWS)
            return fromWindowsStore();
        return null;
    }

    private static SSLContext fromWindowsStore() throws Exception
    {
        KeyStore store = KeyStore.getInstance("Windows-ROOT", "SunMSCAPI"); //$NON-NLS-1$ //$NON-NLS-2$
        store.load(null, null);
        return contextFor(store);
    }

    private static SSLContext fromFile(Path file) throws Exception
    {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        try (InputStream in = Files.newInputStream(file))
        {
            int index = 0;
            for (Certificate certificate : CertificateFactory.getInstance("X.509") //$NON-NLS-1$
                .generateCertificates(in))
                store.setCertificateEntry("gitlab-" + index++, certificate); //$NON-NLS-1$
        }
        return contextFor(store);
    }

    private static SSLContext contextFor(KeyStore store) throws Exception
    {
        TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX"); //$NON-NLS-1$
        factory.init(store);
        SSLContext context = SSLContext.getInstance("TLS"); //$NON-NLS-1$
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }
}
