package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import dev.edt.gitflow.core.CreateMergeRequest;
import dev.edt.gitflow.core.GitHubClient;
import dev.edt.gitflow.core.GitHubRemote;
import dev.edt.gitflow.core.HostingDiagnostics;
import dev.edt.gitflow.core.HostingException;
import dev.edt.gitflow.core.MergeRequestStatus;
import dev.edt.gitflow.core.ProjectMember;

public class GitHubClientTest
{
    private static final String USER = "/api/v3/user"; //$NON-NLS-1$
    private static final String REPO = "/api/v3/repos/owner/repo"; //$NON-NLS-1$
    private static final String COLLABORATORS = REPO + "/collaborators?per_page=100"; //$NON-NLS-1$
    private static final String PULLS = REPO + "/pulls" //$NON-NLS-1$
        + "?head=owner:fix/1&state=all&sort=updated&direction=desc&per_page=10"; //$NON-NLS-1$
    private static final String PULL = REPO + "/pulls/"; //$NON-NLS-1$
    private static final String CREATE_PULL = REPO + "/pulls"; //$NON-NLS-1$
    private static final String REQUESTED_REVIEWERS = PULL + "3/requested_reviewers"; //$NON-NLS-1$
    private static final String PROTECTION = REPO + "/branches/main/protection/" //$NON-NLS-1$
        + "required_pull_request_reviews"; //$NON-NLS-1$
    private static final String CHECKS = REPO + "/commits/abc/check-runs?per_page=100"; //$NON-NLS-1$
    private static final String STATUS = REPO + "/commits/abc/status"; //$NON-NLS-1$
    private static final String OPEN_PULL = "{\"number\":2,\"state\":\"open\",\"title\":\"Fix\"," //$NON-NLS-1$
        + "\"draft\":false,\"html_url\":\"https://github.com/owner/repo/pull/2\"," //$NON-NLS-1$
        + "\"head\":{\"sha\":\"abc\"},\"base\":{\"ref\":\"main\"}," //$NON-NLS-1$
        + "\"mergeable\":true,\"mergeable_state\":\"clean\"}"; //$NON-NLS-1$

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

    @Test
    public void prefersOpenPullRequest() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(PULLS, 200, "[{\"number\":1,\"state\":\"closed\"}," //$NON-NLS-1$
                + OPEN_PULL + "]"); //$NON-NLS-1$
            server.respond(PULL + "2", 200, OPEN_PULL); //$NON-NLS-1$
            respondDetails(server, 2);
            assertEquals(2, readStatus(server).iid());
        }
    }

    @Test
    public void prefersFirstOpenByUpdate() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(PULLS, 200, "[{\"number\":5,\"state\":\"open\"}," //$NON-NLS-1$
                + "{\"number\":6,\"state\":\"open\"}]"); //$NON-NLS-1$
            server.respond(PULL + "5", 200, "{\"number\":5,\"state\":\"open\"," //$NON-NLS-1$
                + "\"title\":\"Fix\",\"head\":{\"sha\":\"abc\"}," //$NON-NLS-1$
                + "\"base\":{\"ref\":\"main\"}}"); //$NON-NLS-1$
            respondDetails(server, 5);
            assertEquals(5, readStatus(server).iid());
        }
    }

    @Test
    public void returnsEmptyWhenNoPullRequest() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(PULLS, 200, "[]"); //$NON-NLS-1$
            assertFalse(server.client().statusForBranch("fix/1").isPresent()); //$NON-NLS-1$
        }
    }

    @Test
    public void readsPullRequestFields() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            respondPullRequest(server, 2, "{\"number\":2,\"state\":\"open\"," //$NON-NLS-1$
                + "\"title\":\"Fix\",\"draft\":true," //$NON-NLS-1$
                + "\"html_url\":\"https://github.com/owner/repo/pull/2\"," //$NON-NLS-1$
                + "\"head\":{\"sha\":\"abc\"},\"base\":{\"ref\":\"main\"}," //$NON-NLS-1$
                + "\"mergeable\":true,\"mergeable_state\":\"clean\"}"); //$NON-NLS-1$
            MergeRequestStatus status = readStatus(server);
            assertEquals("Fix", status.title()); //$NON-NLS-1$
            assertTrue(status.draft());
            assertEquals("https://github.com/owner/repo/pull/2", status.webUrl()); //$NON-NLS-1$
            assertEquals("opened", status.state()); //$NON-NLS-1$
            assertEquals("mergeable", status.mergeStatus()); //$NON-NLS-1$
            assertTrue(status.discussionsResolved());
            assertEquals(0, status.notesCount());
        }
    }

    @Test
    public void keepsApprovalAfterComment() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            respondPullRequest(server, 2, OPEN_PULL);
            server.respond(PULL + "2/reviews?per_page=100", 200, //$NON-NLS-1$ //$NON-NLS-2$
                "[{\"user\":{\"login\":\"ivan\"},\"state\":\"APPROVED\"}," //$NON-NLS-1$
                    + "{\"user\":{\"login\":\"ivan\"},\"state\":\"COMMENTED\"}]"); //$NON-NLS-1$
            assertEquals(1, readStatus(server).approvedCount());
        }
    }

    @Test
    public void countsLatestDecisiveReview() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            respondPullRequest(server, 2, OPEN_PULL);
            server.respond(PULL + "2/reviews?per_page=100", 200, //$NON-NLS-1$ //$NON-NLS-2$
                "[{\"user\":{\"login\":\"ivan\"},\"state\":\"APPROVED\"}," //$NON-NLS-1$
                    + "{\"user\":{\"login\":\"ivan\"},\"state\":\"PENDING\"}," //$NON-NLS-1$
                    + "{\"user\":{\"login\":\"ivan\"},\"state\":\"COMMENTED\"}," //$NON-NLS-1$
                    + "{\"user\":{\"login\":\"petr\"},\"state\":\"APPROVED\"}," //$NON-NLS-1$
                    + "{\"user\":{\"login\":\"petr\"},\"state\":\"CHANGES_REQUESTED\"}," //$NON-NLS-1$
                    + "{\"user\":{\"login\":\"olga\"},\"state\":\"APPROVED\"}," //$NON-NLS-1$
                    + "{\"user\":{\"login\":\"olga\"},\"state\":\"DISMISSED\"}]"); //$NON-NLS-1$
            assertEquals(1, readStatus(server).approvedCount());
        }
    }

    @Test
    public void readsApprovalRule() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            respondPullRequest(server, 2, OPEN_PULL);
            server.respond(PROTECTION, 200, //$NON-NLS-1$
                "{\"required_approving_review_count\":2}"); //$NON-NLS-1$
            MergeRequestStatus status = readStatus(server);
            assertEquals(2, status.approvalsRequired());
            assertTrue(status.approvalsRequiredKnown());
        }
    }

    @Test
    public void protectionForbidden() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            respondPullRequest(server, 2, OPEN_PULL);
            MergeRequestStatus status = readStatus(server);
            assertEquals(0, status.approvalsRequired());
            assertFalse(status.approvalsRequiredKnown());
        }
    }

    @Test
    public void marksMergedAndClosed() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            respondPullRequest(server, 2, "{\"number\":2,\"state\":\"closed\"," //$NON-NLS-1$
                + "\"title\":\"Fix\",\"merged_at\":\"2026-11-16T10:00:00Z\"," //$NON-NLS-1$
                + "\"merged_by\":{\"login\":\"ivan\"}," //$NON-NLS-1$
                + "\"head\":{\"sha\":\"abc\"},\"base\":{\"ref\":\"main\"}}"); //$NON-NLS-1$
            MergeRequestStatus merged = readStatus(server);
            assertEquals("merged", merged.state()); //$NON-NLS-1$
            assertEquals("ivan", merged.mergedBy()); //$NON-NLS-1$
            assertEquals("2026-11-16T10:00:00Z", merged.mergedAt()); //$NON-NLS-1$
        }
        try (FakeGitHub server = new FakeGitHub())
        {
            respondPullRequest(server, 2, "{\"number\":2,\"state\":\"closed\"," //$NON-NLS-1$
                + "\"title\":\"Fix\",\"head\":{\"sha\":\"abc\"}," //$NON-NLS-1$
                + "\"base\":{\"ref\":\"main\"}}"); //$NON-NLS-1$
            MergeRequestStatus closed = readStatus(server);
            assertEquals("closed", closed.state()); //$NON-NLS-1$
            assertNull(closed.mergedAt());
            assertNull(closed.mergedBy());
        }
    }

    @Test
    public void ignoresUnknownMergeable() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            respondPullRequest(server, 2, "{\"number\":2,\"state\":\"open\"," //$NON-NLS-1$
                + "\"title\":\"Fix\",\"mergeable\":null," //$NON-NLS-1$
                + "\"mergeable_state\":\"unknown\"," //$NON-NLS-1$
                + "\"head\":{\"sha\":\"abc\"},\"base\":{\"ref\":\"main\"}}"); //$NON-NLS-1$
            MergeRequestStatus status = readStatus(server);
            assertFalse(status.hasConflicts());
            assertNull(status.mergeStatus());
        }
    }

    @Test
    public void detectsConflictsAndReady() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            respondPullRequest(server, 2, "{\"number\":2,\"state\":\"open\"," //$NON-NLS-1$
                + "\"title\":\"Fix\",\"mergeable\":false," //$NON-NLS-1$
                + "\"mergeable_state\":\"dirty\"," //$NON-NLS-1$
                + "\"head\":{\"sha\":\"abc\"},\"base\":{\"ref\":\"main\"}}"); //$NON-NLS-1$
            MergeRequestStatus conflicts = readStatus(server);
            assertTrue(conflicts.hasConflicts());
            assertNull(conflicts.mergeStatus());
        }
        try (FakeGitHub server = new FakeGitHub())
        {
            respondPullRequest(server, 2, OPEN_PULL);
            MergeRequestStatus ready = readStatus(server);
            assertFalse(ready.hasConflicts());
            assertEquals("mergeable", ready.mergeStatus()); //$NON-NLS-1$
        }
    }

    @Test
    public void mapsCheckConclusions() throws Exception
    {
        assertEquals("failed", readStatusWithCheckRuns( //$NON-NLS-1$
            "[{\"status\":\"completed\",\"conclusion\":\"failure\"}]", 1).pipelineStatus()); //$NON-NLS-1$
        assertEquals("failed", readStatusWithCheckRuns( //$NON-NLS-1$
            "[{\"status\":\"completed\",\"conclusion\":\"cancelled\"}," //$NON-NLS-1$
                + "{\"status\":\"in_progress\",\"conclusion\":null}]", 2).pipelineStatus()); //$NON-NLS-1$
        assertEquals("running", readStatusWithCheckRuns( //$NON-NLS-1$
            "[{\"status\":\"completed\",\"conclusion\":\"neutral\"}," //$NON-NLS-1$
                + "{\"status\":\"completed\",\"conclusion\":\"skipped\"}," //$NON-NLS-1$
                + "{\"status\":\"in_progress\",\"conclusion\":null}]", 3).pipelineStatus()); //$NON-NLS-1$
        assertEquals("success", readStatusWithCheckRuns( //$NON-NLS-1$
            "[{\"status\":\"completed\",\"conclusion\":\"neutral\"}]", 1).pipelineStatus()); //$NON-NLS-1$
    }

    @Test
    public void fallsBackToCombinedStatus() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            respondPullRequest(server, 2, OPEN_PULL);
            server.respond(CHECKS, 200, "{\"total_count\":0,\"check_runs\":[]}"); //$NON-NLS-1$
            server.respond(STATUS, 200, "{\"state\":\"success\"}"); //$NON-NLS-1$
            assertEquals("success", readStatus(server).pipelineStatus()); //$NON-NLS-1$
        }
    }

    @Test
    public void mapsCombinedStatusStates() throws Exception
    {
        assertEquals("failed", readStatusWithCombinedStatus("failure").pipelineStatus()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("failed", readStatusWithCombinedStatus("error").pipelineStatus()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("running", readStatusWithCombinedStatus("pending").pipelineStatus()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void hidesChecksWhenForbidden() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            respondPullRequest(server, 2, OPEN_PULL);
            server.respond(CHECKS, 403, "{}"); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull(readStatus(server).pipelineStatus());
        }
    }

    @Test
    public void createsDraftWithHeadAndBase() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(CREATE_PULL, 201, "{\"number\":3,\"state\":\"open\",\"draft\":true," //$NON-NLS-1$
                + "\"html_url\":\"https://github.com/owner/repo/pull/3\",\"title\":\"Fix\"}"); //$NON-NLS-1$
            MergeRequestStatus created = server.client().createMergeRequest(
                new CreateMergeRequest("fix/1", "main", "Fix").draft(true)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertEquals(3, created.iid());
            assertEquals("opened", created.state()); //$NON-NLS-1$
            assertEquals("Fix", created.title()); //$NON-NLS-1$
            assertTrue(created.draft());
            assertEquals("https://github.com/owner/repo/pull/3", created.webUrl()); //$NON-NLS-1$
            assertTrue(created.approvalsRequiredKnown());
            assertEquals("POST", server.lastMethod()); //$NON-NLS-1$
            assertEquals("Bearer test-token", server.lastAuthorization()); //$NON-NLS-1$
            String body = server.lastBody();
            assertTrue(body, body.contains("\"head\":\"owner:fix/1\"")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(body, body.contains("\"base\":\"main\"")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(body, body.contains("\"title\":\"Fix\"")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(body, body.contains("\"draft\":true")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(body, body.contains("\"body\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void sendsDescriptionAsBody() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(CREATE_PULL, 201, "{\"number\":3,\"state\":\"open\",\"title\":\"Fix\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            server.client().createMergeRequest(new CreateMergeRequest("fix/1", "main", "Fix") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .description("Подробности")); //$NON-NLS-1$
            assertTrue(server.lastBody(), server.lastBody().contains("\"body\":\"Подробности\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void omitsBlankDescription() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(CREATE_PULL, 201, "{\"number\":3,\"state\":\"open\",\"title\":\"Fix\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            server.client().createMergeRequest(new CreateMergeRequest("fix/1", "main", "Fix") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .description("   ")); //$NON-NLS-1$
            assertFalse(server.lastBody(), server.lastBody().contains("\"body\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void assignsReviewerAfterCreate() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(CREATE_PULL, 201, "{\"number\":3,\"state\":\"open\",\"title\":\"Fix\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            server.respond(REQUESTED_REVIEWERS, 201, "{\"number\":3,\"state\":\"open\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            MergeRequestStatus created = server.client().createMergeRequest(
                new CreateMergeRequest("fix/1", "main", "Fix") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .reviewer(new ProjectMember(7, null, "ivan"))); //$NON-NLS-1$
            assertEquals(3, created.iid());
            assertEquals("POST", server.lastMethod()); //$NON-NLS-1$
            assertEquals(REQUESTED_REVIEWERS, server.lastPath());
            assertTrue(server.lastBody(), server.lastBody().contains("\"reviewers\":[\"ivan\"]")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void keepsCreatedPullRequestWhenReviewerFails() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            String marker = "reviewer-" + UUID.randomUUID(); //$NON-NLS-1$
            server.respond(CREATE_PULL, 201, "{\"number\":3,\"state\":\"open\",\"title\":\"Fix\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            server.respond(REQUESTED_REVIEWERS, 422, //$NON-NLS-1$
                "{\"message\":\"Reviews may only be requested from collaborators. " //$NON-NLS-1$
                    + marker + "\"}"); //$NON-NLS-1$
            MergeRequestStatus created = server.client().createMergeRequest(
                new CreateMergeRequest("fix/1", "main", "Fix") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .reviewer(new ProjectMember(7, null, "ivan"))); //$NON-NLS-1$
            assertEquals(3, created.iid());
            assertEquals(REQUESTED_REVIEWERS, server.lastPath());
            String log = Files.readString(HostingDiagnostics.file());
            assertTrue(log, log.contains("reviewer assignment failed")); //$NON-NLS-1$
            assertTrue(log, log.contains(marker));
        }
    }

    @Test
    public void keepsCreatedPullRequestWhenReviewerForbidden() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            String marker = "reviewer-" + UUID.randomUUID(); //$NON-NLS-1$
            server.respond(CREATE_PULL, 201, "{\"number\":3,\"state\":\"open\",\"title\":\"Fix\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            server.respond(REQUESTED_REVIEWERS, 403, //$NON-NLS-1$
                "{\"message\":\"" + marker + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            MergeRequestStatus created = server.client().createMergeRequest(
                new CreateMergeRequest("fix/1", "main", "Fix") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .reviewer(new ProjectMember(7, null, "ivan"))); //$NON-NLS-1$
            assertEquals(3, created.iid());
            String log = Files.readString(HostingDiagnostics.file());
            assertTrue(log, log.contains("reviewer assignment failed")); //$NON-NLS-1$
            assertTrue(log, log.contains(marker));
            assertTrue(log, log.contains("FORBIDDEN")); //$NON-NLS-1$
        }
    }

    @Test
    public void skipsReviewerWithoutUsername() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(CREATE_PULL, 201, "{\"number\":3,\"state\":\"open\",\"title\":\"Fix\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            MergeRequestStatus created = server.client().createMergeRequest(
                new CreateMergeRequest("fix/1", "main", "Fix") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .reviewer(new ProjectMember(7, null, null)));
            assertEquals(3, created.iid());
            // Назначение не выполнялось: последним остался запрос создания.
            assertEquals(CREATE_PULL, server.lastPath());
        }
    }

    @Test
    public void mapsExistingPullRequestToConflict() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(CREATE_PULL, 422, //$NON-NLS-1$
                "{\"errors\":[{\"message\":\"A pull request already exists for owner:fix/1.\"}]}"); //$NON-NLS-1$
            assertCreateFailure(server, HostingException.Kind.CONFLICT);
        }
    }

    @Test
    public void mapsMissingCommitsToBadRequest() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(CREATE_PULL, 422, //$NON-NLS-1$
                "{\"message\":\"Validation Failed\",\"errors\":[{\"message\":\"No commits between main and fix/1\"}]}"); //$NON-NLS-1$
            assertCreateFailure(server, HostingException.Kind.BAD_REQUEST);
        }
    }

    @Test
    public void keepsOtherCreateFailures() throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            server.respond(CREATE_PULL, 500, "{\"message\":\"Server Error\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            assertCreateFailure(server, HostingException.Kind.SERVER);
        }
    }

    private static void respondPullRequest(FakeGitHub server, int number, String pull)
    {
        server.respond(PULLS, 200, "[" + pull + "]"); //$NON-NLS-1$ //$NON-NLS-2$
        server.respond(PULL + number, 200, pull);
        respondDetails(server, number);
    }

    private static void respondDetails(FakeGitHub server, int number)
    {
        server.respond(PULL + number + "/reviews?per_page=100", 200, "[]"); //$NON-NLS-1$ //$NON-NLS-2$
        server.respond(PROTECTION, 403, "{}"); //$NON-NLS-1$ //$NON-NLS-2$
        server.respond(CHECKS, 200, "{\"total_count\":0,\"check_runs\":[]}"); //$NON-NLS-1$
        server.respond(STATUS, 200, "{\"state\":\"success\"}"); //$NON-NLS-1$
    }

    private static MergeRequestStatus readStatus(FakeGitHub server) throws Exception
    {
        return server.client().statusForBranch("fix/1").orElseThrow(); //$NON-NLS-1$
    }

    private static MergeRequestStatus readStatusWithCheckRuns(String runs, int total) throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            respondPullRequest(server, 2, OPEN_PULL);
            server.respond(CHECKS, 200, "{\"total_count\":" + total //$NON-NLS-1$
                + ",\"check_runs\":" + runs + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            return readStatus(server);
        }
    }

    private static MergeRequestStatus readStatusWithCombinedStatus(String state) throws Exception
    {
        try (FakeGitHub server = new FakeGitHub())
        {
            respondPullRequest(server, 2, OPEN_PULL);
            server.respond(STATUS, 200, "{\"state\":\"" + state + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            return readStatus(server);
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

    private static void assertCreateFailure(FakeGitHub server, HostingException.Kind kind) throws Exception
    {
        try
        {
            server.client().createMergeRequest(new CreateMergeRequest("fix/1", "main", "Fix")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("ожидалось исключение"); //$NON-NLS-1$
        }
        catch (HostingException e)
        {
            assertEquals(kind, e.kind());
        }
    }
}
