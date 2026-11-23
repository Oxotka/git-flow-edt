package dev.edt.gitflow.core;

import java.util.Locale;

/**
 * Тип хостинга, определенный по имени хоста. {@code null} из {@link #detect(String)}
 * означает, что хост не распознан: решение о запасном варианте принимают
 * настройки, а не этот перечень.
 */
public enum HostingProvider
{
    GITLAB, GITHUB;

    /**
     * Определяет хостинг по имени хоста: {@code github.com} и любой хост со словом
     * github — GitHub, хост со словом gitlab — GitLab.
     */
    public static HostingProvider detect(String host)
    {
        if (host == null || host.isBlank())
            return null;
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.contains("github")) //$NON-NLS-1$
            return GITHUB;
        if (normalized.contains("gitlab")) //$NON-NLS-1$
            return GITLAB;
        return null;
    }
}
