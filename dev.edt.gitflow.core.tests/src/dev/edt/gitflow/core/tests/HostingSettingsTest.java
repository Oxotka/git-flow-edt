package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.edt.gitflow.core.HostingProvider;
import dev.edt.gitflow.ui.handlers.HostingSettings;

public class HostingSettingsTest
{
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
    public void exposesConfiguredValues()
    {
        var settings = HostingSettings.forTesting("https://akeso.dept07", //$NON-NLS-1$
            "akeso.dept07", HostingProvider.GITLAB); //$NON-NLS-1$
        assertEquals("https://akeso.dept07", settings.baseUrl()); //$NON-NLS-1$
        assertEquals(HostingSettings.CertMode.WINDOWS, settings.certMode());
        assertEquals("akeso.dept07", settings.serverHost()); //$NON-NLS-1$
    }
}
