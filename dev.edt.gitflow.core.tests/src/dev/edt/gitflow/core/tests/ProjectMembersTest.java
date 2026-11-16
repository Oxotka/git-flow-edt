package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.edt.gitflow.core.CreateMergeRequest;
import dev.edt.gitflow.core.ProjectMember;

public class ProjectMembersTest
{
    @Test
    public void readsProjectMembers() throws Exception
    {
        try (FakeGitLab server = new FakeGitLab())
        {
            server.respond("/api/v4/projects/group%2Fproject/members/all?per_page=100", 200, //$NON-NLS-1$
                "[{\"id\":5,\"name\":\"Иван Иванов\",\"username\":\"ivan\"}," //$NON-NLS-1$
                    + "{\"id\":6,\"username\":\"petr\"}]"); //$NON-NLS-1$
            var members = server.client().projectMembers();
            assertEquals(2, members.size());
            assertEquals(5, members.get(0).id());
            assertEquals("ivan", members.get(0).username()); //$NON-NLS-1$
            assertEquals("Иван Иванов", members.get(0).name()); //$NON-NLS-1$
            assertEquals("petr", members.get(1).displayName()); //$NON-NLS-1$
        }
    }

    @Test
    public void sendsReviewerIdWhenCreating() throws Exception
    {
        try (FakeGitLab server = new FakeGitLab())
        {
            server.respond("/api/v4/projects/group%2Fproject/merge_requests", 201, //$NON-NLS-1$
                "{\"iid\":12,\"state\":\"opened\",\"title\":\"Fix\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            server.client().createMergeRequest(new CreateMergeRequest("fix/1", "master", "Fix") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .reviewer(new ProjectMember(5, "Иван", "ivan"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(server.lastBody(), server.lastBody().contains("\"reviewer_ids\":[5]")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
