package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

import dev.edt.gitflow.core.HostingException;

public class GitLabClientTest
{
    @Test
    public void readsCurrentUser() throws Exception
    {
        try (FakeGitLab server = new FakeGitLab())
        {
            server.respond("/api/v4/user", 200, "{\"name\":\"Иван\",\"username\":\"ivan\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("Иван", server.client().currentUser()); //$NON-NLS-1$
            assertEquals("test-token", server.lastToken()); //$NON-NLS-1$
        }
    }

    @Test
    public void mapsUnauthorized() throws Exception
    {
        try (FakeGitLab server = new FakeGitLab())
        {
            server.respond("/api/v4/user", 401, "{\"message\":\"401 Unauthorized\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            try
            {
                server.client().currentUser();
                fail("ожидалось исключение"); //$NON-NLS-1$
            }
            catch (HostingException e)
            {
                assertEquals(HostingException.Kind.UNAUTHORIZED, e.kind());
            }
        }
    }

    @Test
    public void mapsForbidden() throws Exception
    {
        try (FakeGitLab server = new FakeGitLab())
        {
            server.respond("/api/v4/user", 403, "{}"); //$NON-NLS-1$ //$NON-NLS-2$
            try
            {
                server.client().currentUser();
                fail("ожидалось исключение"); //$NON-NLS-1$
            }
            catch (HostingException e)
            {
                assertEquals(HostingException.Kind.FORBIDDEN, e.kind());
            }
        }
    }

    @Test
    public void readsTokenScopes() throws Exception
    {
        try (FakeGitLab server = new FakeGitLab())
        {
            server.respond("/api/v4/personal_access_tokens/self", 200, //$NON-NLS-1$
                "{\"scopes\":[\"api\",\"read_user\"]}"); //$NON-NLS-1$
            assertEquals(List.of("api", "read_user"), server.client().tokenScopes()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void reportsNetworkFailure() throws Exception
    {
        FakeGitLab server = new FakeGitLab();
        var client = server.client();
        server.close();
        try
        {
            client.currentUser();
            fail("ожидалось исключение"); //$NON-NLS-1$
        }
        catch (HostingException e)
        {
            assertEquals(HostingException.Kind.NETWORK, e.kind());
            assertTrue(e.getMessage() != null);
        }
    }
}
