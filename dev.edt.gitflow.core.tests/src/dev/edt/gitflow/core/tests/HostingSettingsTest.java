package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.Test;
import org.osgi.service.prefs.Preferences;

import dev.edt.gitflow.core.HostingProvider;
import dev.edt.gitflow.ui.handlers.HostingSettings;

public class HostingSettingsTest
{
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$

    @Test
    public void matchesOnlyConfiguredHost()
    {
        var settings = HostingSettings.forTesting("https://akeso.dept07", //$NON-NLS-1$
            "akeso.dept07", HostingProvider.GITLAB); //$NON-NLS-1$
        assertTrue(settings.matches("akeso.dept07")); //$NON-NLS-1$
        assertTrue(settings.matches("AKESO.DEPT07")); //$NON-NLS-1$
        assertFalse(settings.matches("github.com")); //$NON-NLS-1$
        assertFalse(settings.matches("helius")); //$NON-NLS-1$
        assertFalse(settings.matches(null));
    }

    @Test
    public void exposesProvider()
    {
        var settings = HostingSettings.forTesting("https://github.com", //$NON-NLS-1$
            "github.com", HostingProvider.GITHUB); //$NON-NLS-1$
        assertEquals(HostingProvider.GITHUB, settings.provider("github.com")); //$NON-NLS-1$
    }

    @Test
    public void exposesConfiguredProviderForConfiguredHost()
    {
        // Хост не распознается по имени: тип задан настройками.
        var settings = HostingSettings.forTesting("https://akeso.dept07", //$NON-NLS-1$
            "akeso.dept07", HostingProvider.GITHUB); //$NON-NLS-1$
        assertEquals(HostingProvider.GITHUB, settings.provider("akeso.dept07")); //$NON-NLS-1$
    }

    @Test
    public void fallsBackToGitLabForForeignHost()
    {
        // Настроен GitHub, но чужой нераспознанный хост — это GitLab по умолчанию.
        var settings = HostingSettings.forTesting("https://github.com", //$NON-NLS-1$
            "github.com", HostingProvider.GITHUB); //$NON-NLS-1$
        assertEquals(HostingProvider.GITLAB, settings.provider("scm.local")); //$NON-NLS-1$
    }

    @Test
    public void readsStoredProviderForHost()
    {
        Preferences node = preferences("hosting"); //$NON-NLS-1$
        String key = "provider.scm.local"; //$NON-NLS-1$
        String previous = node.get(key, null);
        node.put(key, HostingProvider.GITHUB.name());
        try
        {
            var settings = HostingSettings.forTesting("https://akeso.dept07", //$NON-NLS-1$
                "akeso.dept07", HostingProvider.GITLAB); //$NON-NLS-1$
            assertEquals(HostingProvider.GITHUB, settings.provider("scm.local")); //$NON-NLS-1$
        }
        finally
        {
            restore(node, key, previous);
        }
    }

    @Test
    public void matchesHostWithOwnStoredConfiguration()
    {
        // У хоста может быть только тип или только адрес: обоих достаточно,
        // чтобы считать хост настроенным, хотя общий serverHost другой.
        Preferences node = preferences("hosting"); //$NON-NLS-1$
        String providerKey = "provider.scm.local"; //$NON-NLS-1$
        String baseKey = "baseUrl.docs.local"; //$NON-NLS-1$
        String previousProvider = node.get(providerKey, null);
        String previousBase = node.get(baseKey, null);
        node.put(providerKey, HostingProvider.GITHUB.name());
        node.put(baseKey, "https://docs.local"); //$NON-NLS-1$
        try
        {
            var settings = HostingSettings.forTesting("https://akeso.dept07", //$NON-NLS-1$
                "akeso.dept07", HostingProvider.GITLAB); //$NON-NLS-1$
            assertTrue(settings.matches("scm.local")); //$NON-NLS-1$
            assertTrue(settings.matches("SCM.LOCAL")); //$NON-NLS-1$
            assertTrue(settings.matches("docs.local")); //$NON-NLS-1$
            assertEquals("https://akeso.dept07", settings.baseUrl("scm.local")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("https://docs.local", settings.baseUrl("docs.local")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(settings.matches("empty.local")); //$NON-NLS-1$
        }
        finally
        {
            restore(node, providerKey, previousProvider);
            restore(node, baseKey, previousBase);
        }
    }

    @Test
    public void storedBaseUrlDoesNotFallBackToGlobal()
    {
        // Форма настроек не должна показывать адрес другого хоста: у хоста без
        // своего ключа сохраненного адреса нет, даже если общий адрес задан.
        Preferences node = preferences("hosting"); //$NON-NLS-1$
        String key = "baseUrl.scm.local"; //$NON-NLS-1$
        String previous = node.get(key, null);
        node.remove(key);
        try
        {
            var settings = HostingSettings.forTesting("https://akeso.dept07", //$NON-NLS-1$
                "akeso.dept07", HostingProvider.GITLAB); //$NON-NLS-1$
            assertNull(settings.storedBaseUrl("scm.local")); //$NON-NLS-1$
            assertEquals("https://akeso.dept07", settings.baseUrl("scm.local")); //$NON-NLS-1$ //$NON-NLS-2$
            node.put(key, "https://scm.local"); //$NON-NLS-1$
            assertEquals("https://scm.local", settings.storedBaseUrl("scm.local")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("https://scm.local", settings.storedBaseUrl("SCM.LOCAL")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            restore(node, key, previous);
        }
    }

    @Test
    public void readsLegacyBranchPrefixesWhenNewKeyAbsent()
    {
        Preferences hosting = preferences("hosting"); //$NON-NLS-1$
        Preferences legacy = preferences("gitlab"); //$NON-NLS-1$
        String oldHosting = hosting.get("branchPrefixes", null); //$NON-NLS-1$
        String oldLegacy = legacy.get("branchPrefixes", null); //$NON-NLS-1$
        hosting.remove("branchPrefixes"); //$NON-NLS-1$
        legacy.put("branchPrefixes", "release/"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            var settings = HostingSettings.forTesting("https://akeso.dept07", //$NON-NLS-1$
                "akeso.dept07", HostingProvider.GITLAB); //$NON-NLS-1$
            assertEquals(List.of("release/"), settings.branchPrefixes()); //$NON-NLS-1$
        }
        finally
        {
            restore(hosting, "branchPrefixes", oldHosting); //$NON-NLS-1$
            restore(legacy, "branchPrefixes", oldLegacy); //$NON-NLS-1$
        }
    }

    @Test
    public void doesNotRestoreLegacyBranchPrefixesAfterClearing()
    {
        Preferences hosting = preferences("hosting"); //$NON-NLS-1$
        Preferences legacy = preferences("gitlab"); //$NON-NLS-1$
        String oldHosting = hosting.get("branchPrefixes", null); //$NON-NLS-1$
        String oldLegacy = legacy.get("branchPrefixes", null); //$NON-NLS-1$
        hosting.put("branchPrefixes", ""); //$NON-NLS-1$ //$NON-NLS-2$
        legacy.put("branchPrefixes", "release/"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            var settings = HostingSettings.forTesting("https://akeso.dept07", //$NON-NLS-1$
                "akeso.dept07", HostingProvider.GITLAB); //$NON-NLS-1$
            assertTrue(settings.branchPrefixes().isEmpty());
            assertTrue(settings.matchesBranch("main"));
            hosting.put("branchPrefixes", "*");
            assertTrue(settings.matchesBranch("release/next"));
            hosting.put("branchPrefixes", "fix/, feat/");
            assertTrue(settings.matchesBranch("feat/example"));
            assertFalse(settings.matchesBranch("release/next"));
        }
        finally
        {
            restore(hosting, "branchPrefixes", oldHosting); //$NON-NLS-1$
            restore(legacy, "branchPrefixes", oldLegacy); //$NON-NLS-1$
        }
    }

    @Test
    public void exposesConfiguredValues()
    {
        var settings = HostingSettings.forTesting("https://akeso.dept07", //$NON-NLS-1$
            "akeso.dept07", HostingProvider.GITLAB); //$NON-NLS-1$
        assertEquals("https://akeso.dept07", settings.baseUrl()); //$NON-NLS-1$
        assertEquals(HostingSettings.CertMode.WINDOWS, settings.certMode());
        assertEquals("akeso.dept07", settings.serverHost()); //$NON-NLS-1$
    }

    private static Preferences preferences(String node)
    {
        return InstanceScope.INSTANCE.getNode(PLUGIN_ID).node(node);
    }

    private static void restore(Preferences node, String key, String previous)
    {
        if (previous == null)
            node.remove(key);
        else
            node.put(key, previous);
    }
}
