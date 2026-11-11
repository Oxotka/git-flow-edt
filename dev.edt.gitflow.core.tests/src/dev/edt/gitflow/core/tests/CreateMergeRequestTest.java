package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.edt.gitflow.core.CreateMergeRequest;
import dev.edt.gitflow.core.MergeRequestStatus;

public class CreateMergeRequestTest
{
    private static final String CREATE = "/api/v4/projects/group%2Fproject/merge_requests"; //$NON-NLS-1$

    @Test
    public void createsDraftMergeRequestWithBranchRemoval() throws Exception
    {
        try (FakeGitLab server = new FakeGitLab())
        {
            server.respond(CREATE, 201,
                "{\"iid\":8,\"state\":\"opened\",\"title\":\"Draft: Fix\",\"draft\":true," //$NON-NLS-1$
                    + "\"web_url\":\"https://git/group/project/-/merge_requests/8\"}"); //$NON-NLS-1$

            MergeRequestStatus created = server.client().createMergeRequest(
                new CreateMergeRequest("fix/1", "master", "Fix") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .draft(true).removeSourceBranch(true));

            assertEquals(8, created.iid());
            assertTrue(created.draft());
            assertEquals("https://git/group/project/-/merge_requests/8", created.webUrl()); //$NON-NLS-1$
            String body = server.lastBody();
            assertTrue(body, body.contains("\"source_branch\":\"fix/1\"")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(body, body.contains("\"target_branch\":\"master\"")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(body, body.contains("\"remove_source_branch\":true")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(body, body.contains("\"title\":\"Draft: Fix\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void keepsPlainTitleWhenNotDraft() throws Exception
    {
        try (FakeGitLab server = new FakeGitLab())
        {
            server.respond(CREATE, 201, "{\"iid\":9,\"state\":\"opened\",\"title\":\"Fix\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            server.client().createMergeRequest(new CreateMergeRequest("fix/2", "master", "Fix")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(server.lastBody().contains("\"title\":\"Fix\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
