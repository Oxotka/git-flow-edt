package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.edt.gitflow.ui.handlers.GitLabSettings;

public class GitLabSettingsTest
{
    @Test
    public void matchesOnlyTheConfiguredHost()
    {
        var settings = GitLabSettings.forTesting("https://akeso.dept07", "akeso.dept07"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(settings.matches("akeso.dept07")); //$NON-NLS-1$
        assertTrue(settings.matches("AKESO.DEPT07")); //$NON-NLS-1$
        assertFalse(settings.matches("github.com")); //$NON-NLS-1$
        assertFalse(settings.matches("helius")); //$NON-NLS-1$
        assertFalse(settings.matches(null));
    }

    @Test
    public void exposesConfiguredValues()
    {
        var settings = GitLabSettings.forTesting("https://akeso.dept07", "akeso.dept07"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("https://akeso.dept07", settings.baseUrl()); //$NON-NLS-1$
        assertEquals(GitLabSettings.CertMode.WINDOWS, settings.certMode());
    }
}
