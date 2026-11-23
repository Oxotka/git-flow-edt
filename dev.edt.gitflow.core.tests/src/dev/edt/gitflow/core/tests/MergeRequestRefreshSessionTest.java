package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.edt.gitflow.ui.views.MergeRequestRefreshSession;

public class MergeRequestRefreshSessionTest
{
    @Test
    public void switchesBranchWhilePreviousRequestIsRunning()
    {
        var session = new MergeRequestRefreshSession();
        assertTrue(session.select("repo|fix/one"));
        long first = session.begin();
        assertTrue(session.select("repo|fix/two"));
        long second = session.begin();
        assertFalse(session.isCurrent(first));
        assertTrue(session.isCurrent(second));
        assertFalse(session.select("repo|fix/two"));
    }

    @Test
    public void switchesRepositoryWithSameBranchName()
    {
        var session = new MergeRequestRefreshSession();
        session.select("one|fix/task");
        long first = session.begin();
        assertTrue(session.select("two|fix/task"));
        assertFalse(session.isCurrent(first));
        assertTrue(session.isCurrent(session.begin()));
    }

    @Test
    public void hidesPendingResultAndReloadsSameContextWhenReopened()
    {
        var session = new MergeRequestRefreshSession();
        session.select("repo|fix/task");
        long request = session.begin();
        session.clear();
        assertFalse(session.isCurrent(request));
        assertTrue(session.select("repo|fix/task"));
    }

    @Test
    public void latestManualRefreshWinsEvenWhenOlderRequestFinishesLater()
    {
        var session = new MergeRequestRefreshSession();
        session.select("repo|fix/task");
        long first = session.begin();
        long second = session.begin();
        assertTrue(session.isCurrent(second));
        assertFalse(session.isCurrent(first));
    }
}
