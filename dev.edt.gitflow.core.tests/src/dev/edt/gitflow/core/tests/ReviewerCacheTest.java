package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.After;
import org.junit.Test;
import org.osgi.service.prefs.Preferences;

import dev.edt.gitflow.core.ProjectMember;
import dev.edt.gitflow.ui.handlers.ReviewerCache;

public class ReviewerCacheTest
{
    private final String project = "reviewer-cache-test/" + UUID.randomUUID(); //$NON-NLS-1$

    @Test
    public void distinguishesReviewersWithIdenticalNames()
    {
        assertEquals("Alex (@one)", new ProjectMember(1, "Alex", "one").reviewerLabel());
        assertEquals("Alex (@two)", new ProjectMember(2, "Alex", "two").reviewerLabel());
        assertEquals("one", new ProjectMember(1, null, "one").reviewerLabel());
    }

    @After
    public void removeTestPreferences() throws Exception
    {
        for (String name : new String[] {"hosting", "gitlab"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Preferences node = node(name);
            for (String key : node.keys())
                if (key.endsWith(project))
                    node.remove(key);
            node.flush();
        }
    }

    @Test
    public void separatesIdenticalProjectPathsAndUserIdsAcrossHosts()
    {
        ReviewerCache.remember("first.example", project, new ProjectMember(7, "First", "first")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ReviewerCache.remember("second.example", project, new ProjectMember(7, "Second", "second")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("first", ReviewerCache.recent("first.example", project).get(0).username()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("second", ReviewerCache.recent("second.example", project).get(0).username()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(ReviewerCache.recent("unknown.example", project).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void treatsHostNamesCaseInsensitively()
    {
        ReviewerCache.remember("FIRST.EXAMPLE", project, new ProjectMember(7, "First", "first")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("first", ReviewerCache.recent("first.example", project).get(0).username()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void doesNotReuseOldEntriesWithoutKnownHost()
    {
        node("hosting").put("reviewers." + project, "7|Old|old"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        node("hosting").put("reviewers.first.example/" + project, "9|Nested|nested"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        node("gitlab").put("reviewers." + project, "8|Legacy|legacy"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(ReviewerCache.recent("first.example", project).isEmpty()); //$NON-NLS-1$
    }

    private static Preferences node(String name)
    {
        return InstanceScope.INSTANCE.getNode("dev.edt.gitflow.ui").node(name); //$NON-NLS-1$
    }
}
