package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Test;

import dev.edt.gitflow.ui.views.OperationProgressMonitor;

public class OperationProgressMonitorTest
{
    @Test
    public void taskNamesAreDisplayedWithoutGuessingTheirMeaning()
    {
        List<String> updates = new ArrayList<>();
        var monitor = monitor(updates);

        monitor.beginTask("Upload changes", 4); //$NON-NLS-1$
        monitor.worked(1);

        assertEquals(List.of("Upload changes", "Upload changes (25%)"), updates); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void subTaskPreservesFileNamesAndReportsTheCurrentPhase()
    {
        List<String> updates = new ArrayList<>();
        List<String> tasks = new ArrayList<>();
        var monitor = new OperationProgressMonitor(new NullProgressMonitor(), "Stage", updates::add, //$NON-NLS-1$
            (name, phase) -> tasks.add(name + ": " + phase)); //$NON-NLS-1$

        monitor.beginTask("Stage", 2); //$NON-NLS-1$
        monitor.subTask("src/push-handler.java"); //$NON-NLS-1$
        monitor.worked(1);

        assertEquals(List.of("src/push-handler.java: src/push-handler.java"), tasks); //$NON-NLS-1$
        assertEquals(List.of("Stage", "src/push-handler.java (50%)"), updates); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void blankTaskNamesKeepTheLastUsefulPhase()
    {
        List<String> updates = new ArrayList<>();
        var monitor = monitor(updates);

        monitor.beginTask(null, 2);
        monitor.subTask(" "); //$NON-NLS-1$
        monitor.worked(1);
        monitor.beginTask("Fetch", 2); //$NON-NLS-1$
        monitor.subTask(null);
        monitor.worked(1);

        assertEquals(List.of("Synchronize", "Synchronize (50%)", "Fetch", "Fetch (50%)"), updates); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void newTaskResetsWorkAndPercentageSuppression()
    {
        List<String> updates = new ArrayList<>();
        var monitor = monitor(updates);

        monitor.beginTask("Fetch", 2); //$NON-NLS-1$
        monitor.worked(1);
        monitor.beginTask("Push", 4); //$NON-NLS-1$
        monitor.worked(2);

        assertEquals(List.of("Fetch", "Fetch (50%)", "Push", "Push (50%)"), updates); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void unchangedPercentagesAreNotPublishedAgainAndNeverExceedOneHundred()
    {
        List<String> updates = new ArrayList<>();
        var monitor = monitor(updates);

        monitor.beginTask("Fetch", 1000); //$NON-NLS-1$
        monitor.worked(1);
        monitor.worked(1);
        monitor.worked(2000);
        monitor.worked(1);

        assertEquals(List.of("Fetch", "Fetch (0%)", "Fetch (100%)"), updates); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void unknownAndZeroWorkDoNotProducePercentages()
    {
        List<String> updates = new ArrayList<>();
        var monitor = monitor(updates);

        monitor.beginTask("Fetch", -1); //$NON-NLS-1$
        monitor.worked(1);
        monitor.beginTask("Push", 0); //$NON-NLS-1$
        monitor.worked(1);

        assertEquals(List.of("Fetch", "Push"), updates); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void cancellationStillReachesTheWrappedMonitor()
    {
        var delegate = new NullProgressMonitor();
        var monitor = new OperationProgressMonitor(delegate, "Fetch", text -> {}, (name, phase) -> {}); //$NON-NLS-1$

        delegate.setCanceled(true);
        assertTrue(monitor.isCanceled());
        delegate.setCanceled(false);
        monitor.setCanceled(true);
        assertTrue(delegate.isCanceled());
    }

    private static OperationProgressMonitor monitor(List<String> updates)
    {
        return new OperationProgressMonitor(new NullProgressMonitor(), "Synchronize", updates::add, //$NON-NLS-1$
            (name, phase) -> {});
    }
}
