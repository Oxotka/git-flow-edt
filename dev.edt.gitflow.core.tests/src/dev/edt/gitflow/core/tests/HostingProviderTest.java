package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import dev.edt.gitflow.core.HostingProvider;

public class HostingProviderTest
{
    @Test
    public void detectsGithubCom()
    {
        assertEquals(HostingProvider.GITHUB, HostingProvider.detect("github.com")); //$NON-NLS-1$
    }

    @Test
    public void detectsGithubEnterpriseByWordInHost()
    {
        assertEquals(HostingProvider.GITHUB, HostingProvider.detect("GHE.Corp.GitHub.Ru")); //$NON-NLS-1$
    }

    @Test
    public void detectsGitlabByWordInHost()
    {
        assertEquals(HostingProvider.GITLAB, HostingProvider.detect("gitlab.akeso.dept07")); //$NON-NLS-1$
    }

    @Test
    public void returnsNullForUnknownHost()
    {
        assertNull(HostingProvider.detect("akeso.dept07")); //$NON-NLS-1$
    }

    @Test
    public void returnsNullForMissingHost()
    {
        assertNull(HostingProvider.detect(null));
        assertNull(HostingProvider.detect("")); //$NON-NLS-1$
    }
}
