package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertNull;

import org.junit.Assume;
import org.junit.Test;

import dev.edt.gitflow.core.HostingProvider;
import dev.edt.gitflow.ui.handlers.HostingSettings;
import dev.edt.gitflow.ui.handlers.HostingSsl;

public class HostingSslTest
{
    @Test
    public void usesJvmTrustOnNonWindowsWithoutCertificateFile() throws Exception
    {
        Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        HostingSsl.reset();
        assertNull(HostingSsl.context(HostingSettings.forTesting("https://github.com",
            "github.com", HostingProvider.GITHUB)));
    }
}
