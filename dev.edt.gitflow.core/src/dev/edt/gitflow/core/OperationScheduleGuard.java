package dev.edt.gitflow.core;

public final class OperationScheduleGuard
{
    private OperationScheduleGuard()
    {
    }

    public static boolean shouldBlockSchedule(boolean repositoryRunning, boolean indexChangePending,
        boolean allowPendingIndexChanges)
    {
        return repositoryRunning || indexChangePending && !allowPendingIndexChanges;
    }
}
