package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.edt.gitflow.core.MergeRequestStatus;

public class MergeRequestStatusTest
{
    private static final String LIST = "/api/v4/projects/group%2Fproject/merge_requests?source_branch=fix%2F1&state=all&order_by=updated_at"; //$NON-NLS-1$

    @Test
    public void buildsStatusFromMergeRequestAndApprovals() throws Exception
    {
        try (FakeGitLab server = new FakeGitLab())
        {
            server.respond(LIST, 200,
                "[{\"iid\":7,\"state\":\"opened\",\"title\":\"Fix\",\"draft\":false," //$NON-NLS-1$
                    + "\"web_url\":\"https://git/group/project/-/merge_requests/7\"," //$NON-NLS-1$
                    + "\"has_conflicts\":false,\"blocking_discussions_resolved\":true," //$NON-NLS-1$
                    + "\"user_notes_count\":3,\"detailed_merge_status\":\"mergeable\"," //$NON-NLS-1$
                    + "\"head_pipeline\":{\"status\":\"success\"}}]"); //$NON-NLS-1$
            server.respond("/api/v4/projects/group%2Fproject/merge_requests/7/approvals", 200, //$NON-NLS-1$
                "{\"approved_by\":[{\"user\":{\"id\":1}}],\"approvals_required\":2}"); //$NON-NLS-1$

            MergeRequestStatus status = server.client().statusForBranch("fix/1").orElseThrow(); //$NON-NLS-1$
            assertEquals(7, status.iid());
            assertEquals(1, status.approvedCount());
            assertEquals(2, status.approvalsRequired());
            assertEquals("success", status.pipelineStatus()); //$NON-NLS-1$
            assertEquals("mergeable", status.mergeStatus()); //$NON-NLS-1$
            assertTrue(status.discussionsResolved());
        }
    }

    @Test
    public void fallsBackWhenDetailedStatusAndApprovalsMissing() throws Exception
    {
        try (FakeGitLab server = new FakeGitLab())
        {
            server.respond(LIST, 200,
                "[{\"iid\":9,\"state\":\"opened\",\"title\":\"WIP: Fix\"," //$NON-NLS-1$
                    + "\"merge_status\":\"cannot_be_merged\",\"has_conflicts\":true}]"); //$NON-NLS-1$
            server.respond("/api/v4/projects/group%2Fproject/merge_requests/9/approvals", 403, "{}"); //$NON-NLS-1$ //$NON-NLS-2$

            MergeRequestStatus status = server.client().statusForBranch("fix/1").orElseThrow(); //$NON-NLS-1$
            assertEquals("cannot_be_merged", status.mergeStatus()); //$NON-NLS-1$
            assertTrue(status.draft());
            assertTrue(status.hasConflicts());
            assertEquals(0, status.approvedCount());
            assertEquals(0, status.approvalsRequired());
        }
    }

    @Test
    public void hidesPipelineWhenItIsAbsent() throws Exception
    {
        try (FakeGitLab server = new FakeGitLab())
        {
            server.respond(LIST, 200, "[{\"iid\":10,\"state\":\"opened\",\"title\":\"Fix\"}]"); //$NON-NLS-1$ //$NON-NLS-2$
            server.respond("/api/v4/projects/group%2Fproject/merge_requests/10/approvals", 200, //$NON-NLS-1$
                "{\"approved_by\":[],\"approvals_required\":0}"); //$NON-NLS-1$

            assertNull(server.client().statusForBranch("fix/1").orElseThrow().pipelineStatus()); //$NON-NLS-1$
        }
    }

    @Test
    public void prefersTheOpenMergeRequest() throws Exception
    {
        try (FakeGitLab server = new FakeGitLab())
        {
            server.respond(LIST, 200,
                "[{\"iid\":11,\"state\":\"merged\",\"title\":\"Old\"}," //$NON-NLS-1$
                    + "{\"iid\":12,\"state\":\"opened\",\"title\":\"New\"}]"); //$NON-NLS-1$ //$NON-NLS-2$
            server.respond("/api/v4/projects/group%2Fproject/merge_requests/12/approvals", 200, "{}"); //$NON-NLS-1$ //$NON-NLS-2$

            assertEquals(12, server.client().statusForBranch("fix/1").orElseThrow().iid()); //$NON-NLS-1$
        }
    }
}
