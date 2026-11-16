package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import org.junit.Test;

import dev.edt.gitflow.core.GitHubClient;
import dev.edt.gitflow.core.GitHubRemote;
import dev.edt.gitflow.core.HostingException;
import dev.edt.gitflow.core.ProjectMember;

public class GitHubClientTest
{
    private static final String USER = "/api/v3/user"; //$NON-NLS-1$
    private static final String REPO = "/api/v3/repos/owner/repo"; //$NON-NLS-1$
    private static final String COLLABORATORS = REPO + "/collaborators?per_page=100"; //$NON-NLS-1$

    @Test
    public void readsLoginAndSendsBearer() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(USER, 200, "{\"login\":\"ivan\",\"name\":null}"); //$NON-NLS-1$
            assertEquals("ivan", server.client().currentUser()); //$NON-NLS-1$
            assertEquals("Bearer test-token", server.lastAuthorization()); //$NON-NLS-1$
        }
    }

    @Test
    public void sendsGitHubHeaders() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(USER, 200, "{\"login\":\"ivan\"}"); //$NON-NLS-1$
            server.client().currentUser();
            assertEquals("application/vnd.github+json", server.lastAccept()); //$NON-NLS-1$
            assertEquals("2022-11-28", server.lastApiVersion()); //$NON-NLS-1$
        }
    }

    @Test
    public void mapsRateLimit() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(USER, 403, "{\"message\":\"API rate limit exceeded\"}", //$NON-NLS-1$ //$NON-NLS-2$
                "X-RateLimit-Remaining", "0"); //$NON-NLS-1$ //$NON-NLS-2$
            try
            {
                server.client().currentUser();
                fail("ожидалось исключение"); //$NON-NLS-1$
            }
            catch (HostingException e)
            {
                assertEquals(HostingException.Kind.RATE_LIMIT, e.kind());
            }
        }
    }

    @Test
    public void mapsTooManyRequests() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(USER, 429, "{\"message\":\"Too Many Requests\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            assertFailure(server, HostingException.Kind.RATE_LIMIT);
        }
    }

    @Test
    public void keepsPlainForbidden() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(USER, 403, "{}"); //$NON-NLS-1$ //$NON-NLS-2$
            assertFailure(server, HostingException.Kind.FORBIDDEN);
        }
    }

    @Test
    public void mapsUnauthorized() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(USER, 401, "{\"message\":\"Bad credentials\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            assertFailure(server, HostingException.Kind.UNAUTHORIZED);
        }
    }

    @Test
    public void mapsNotFound() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(REPO, 404, "{\"message\":\"Not Found\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            try
            {
                server.client().defaultBranch();
                fail("ожидалось исключение"); //$NON-NLS-1$
            }
            catch (HostingException e)
            {
                assertEquals(HostingException.Kind.NOT_FOUND, e.kind());
            }
        }
    }

    @Test
    public void mapsServerError() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(USER, 500, "{\"message\":\"Server Error\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            assertFailure(server, HostingException.Kind.SERVER);
        }
    }

    @Test
    public void mapsUnprocessableAndConflict() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(USER, 422, "{}"); //$NON-NLS-1$ //$NON-NLS-2$
            assertFailure(server, HostingException.Kind.BAD_REQUEST);
            server.respond(USER, 409, "{}"); //$NON-NLS-1$ //$NON-NLS-2$
            assertFailure(server, HostingException.Kind.CONFLICT);
        }
    }

    @Test
    public void readsDefaultBranch() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(REPO, 200, "{\"default_branch\":\"main\"}"); //$NON-NLS-1$
            assertEquals("main", server.client().defaultBranch()); //$NON-NLS-1$
        }
    }

    @Test
    public void detectsPushPermission() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(REPO, 200, "{\"permissions\":{\"push\":true}}"); //$NON-NLS-1$
            assertTrue(server.client().canCreateRequests());
        }
    }

    @Test
    public void detectsReadOnlyPermission() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(REPO, 200, "{\"permissions\":{\"pull\":true}}"); //$NON-NLS-1$
            assertFalse(server.client().canCreateRequests());
        }
    }

    @Test
    public void reportsMissingPermissions() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(REPO, 200, "{}"); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(server.client().canCreateRequests());
        }
    }

    @Test
    public void readsCollaborators() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(COLLABORATORS, 200, //$NON-NLS-1$
                "[{\"id\":7,\"login\":\"ivan\"},{\"id\":8,\"login\":\"petr\"}]"); //$NON-NLS-1$
            List<ProjectMember> members = server.client().projectMembers();
            assertEquals(2, members.size());
            assertEquals(7, members.get(0).id());
            assertEquals("ivan", members.get(0).username()); //$NON-NLS-1$
            assertEquals("ivan", members.get(0).displayName()); //$NON-NLS-1$
            assertEquals("petr", members.get(1).username()); //$NON-NLS-1$
        }
    }

    @Test
    public void namesRequestInProviderTerms() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            GitHubClient client = server.client();
            assertEquals("GitHub", client.providerName()); //$NON-NLS-1$
            assertEquals("PR", client.requestShortName()); //$NON-NLS-1$
            assertEquals("pull request", client.requestFullName()); //$NON-NLS-1$
            assertFalse(client.supportsDeleteSourceBranch());
            assertEquals("127.0.0.1", client.host()); //$NON-NLS-1$
            assertEquals("owner/repo", client.projectPath()); //$NON-NLS-1$
        }
    }

    @Test
    public void mapsTimeoutToNetwork() throws Exception
    {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) //$NON-NLS-1$
        {
            GitHubRemote remote = GitHubRemote.parse(
                "http://127.0.0.1:" + socket.getLocalPort() + "/owner/repo.git"); //$NON-NLS-1$ //$NON-NLS-2$
            GitHubClient client = new GitHubClient(remote, "test-token", null, //$NON-NLS-1$
                Duration.ofMillis(300));
            try
            {
                client.currentUser();
                fail("ожидалось исключение"); //$NON-NLS-1$
            }
            catch (HostingException e)
            {
                assertEquals(HostingException.Kind.NETWORK, e.kind());
            }
        }
    }

    @Test
    public void reportsNetworkFailure() throws Exception
    {
        FakeGitHub server = new FakeGitHub();
        GitHubClient client = server.client();
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

    private static void assertFailure(FakeGitHub server, HostingException.Kind kind) throws Exception
    {
        try
        {
            server.client().currentUser();
            fail("ожидалось исключение"); //$NON-NLS-1$
        }
        catch (HostingException e)
        {
            assertEquals(kind, e.kind());
        }
    }
}
