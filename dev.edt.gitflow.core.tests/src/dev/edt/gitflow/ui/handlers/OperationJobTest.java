package dev.edt.gitflow.ui.handlers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.edt.gitflow.core.OperationScheduleGuard;

public class OperationJobTest
{
    @Test
    public void cleanupRetryMayStartWhileChangesRefreshIsPending() throws Exception
    {
        assertFalse(shouldBlockSchedule(false, true, true));
    }

    @Test
    public void newOperationWaitsForPendingChangesRefresh() throws Exception
    {
        assertTrue(shouldBlockSchedule(false, true, false));
    }

    @Test
    public void runningRepositoryAlwaysBlocksRetry() throws Exception
    {
        assertTrue(shouldBlockSchedule(true, true, true));
    }

    private static boolean shouldBlockSchedule(boolean running, boolean pending, boolean retry)
        throws Exception
    {
        return OperationScheduleGuard.shouldBlockSchedule(running, pending, retry);
    }
}
