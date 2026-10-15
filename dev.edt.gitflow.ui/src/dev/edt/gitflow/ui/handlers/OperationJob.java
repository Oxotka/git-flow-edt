package dev.edt.gitflow.ui.handlers;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.ArrayList;

import org.eclipse.egit.core.internal.job.RuleUtil;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.egit.ui.internal.UIRepositoryUtils;
import org.eclipse.egit.ui.internal.UIText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import dev.edt.gitflow.core.OperationResult;
import dev.edt.gitflow.core.OperationScheduleGuard;
import dev.edt.gitflow.ui.views.GitFlowView;
import dev.edt.gitflow.ui.views.OperationProgressMonitor;

public final class OperationJob
{
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$

    private OperationJob()
    {
    }

    public static void schedule(Repository repository, Shell shell, String title,
        BiFunction<Repository, IProgressMonitor, OperationResult> operation,
        BiFunction<Repository, IProgressMonitor, OperationResult> confirmedOperation)
    {
        schedule(repository, shell, title, operation, confirmedOperation, null);
    }

    public static void schedule(Repository repository, Shell shell, String title,
        BiFunction<Repository, IProgressMonitor, OperationResult> operation,
        BiFunction<Repository, IProgressMonitor, OperationResult> confirmedOperation,
        Consumer<OperationResult> completionAction)
    {
        schedule(repository, shell, title, operation, confirmedOperation, completionAction, false);
    }

    public static void scheduleAfterPreparation(Repository repository, Shell shell, String title,
        BiFunction<Repository, IProgressMonitor, OperationResult> operation,
        Consumer<OperationResult> completionAction)
    {
        schedule(repository, shell, title, operation, null, completionAction, false, true);
    }

    public static void schedule(Repository repository, Shell shell, String title,
        BiFunction<Repository, IProgressMonitor, OperationResult> operation,
        BiFunction<Repository, IProgressMonitor, OperationResult> confirmedOperation,
        Consumer<OperationResult> completionAction, boolean keepChangesEnabled)
    {
        schedule(repository, shell, title, operation, confirmedOperation, completionAction,
            keepChangesEnabled, false);
    }

    private static void schedule(Repository repository, Shell shell, String title,
        BiFunction<Repository, IProgressMonitor, OperationResult> operation,
        BiFunction<Repository, IProgressMonitor, OperationResult> confirmedOperation,
        Consumer<OperationResult> completionAction, boolean keepChangesEnabled,
        boolean allowPendingIndexChanges)
    {
        if (OperationScheduleGuard.shouldBlockSchedule(GitFlowView.isRunning(repository),
            !keepChangesEnabled && GitFlowView.isIndexChangePending(repository), allowPendingIndexChanges))
        {
            GitFlowView.publish(Messages.get("operationAlreadyRunning")); //$NON-NLS-1$
            return;
        }
        OperationResult[] completion = new OperationResult[1];
        Job job = new Job(title)
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                OperationResult result = operation.apply(repository,
                    new OperationProgressMonitor(monitor, title, GitFlowView::updateProgress,
                        (name, phase) -> GitFlowView.publishStatus(title + ": " + name, phase))); //$NON-NLS-1$
                completion[0] = result;
                if (result.kind() == OperationResult.Kind.CANCELLED)
                    return Status.CANCEL_STATUS;
                if (!result.succeeded() && result.kind() != OperationResult.Kind.NEEDS_CONFIRMATION
                    && result.kind() != OperationResult.Kind.NEEDS_NATIVE_MERGE
                    && result.kind() != OperationResult.Kind.NEEDS_CHECKOUT_CLEANUP)
                    return new Status(IStatus.ERROR, PLUGIN_ID, completion[0].message());
                return Status.OK_STATUS;
            }
        };
        job.setRule(RuleUtil.getRule(repository));
        job.addJobChangeListener(new JobChangeAdapter()
        {
            @Override
            public void done(IJobChangeEvent changeEvent)
            {
                if (completion[0] == null)
                {
                    boolean cancelled = changeEvent.getResult().getSeverity() == IStatus.CANCEL;
                    completion[0] = new OperationResult(cancelled ? OperationResult.Kind.CANCELLED
                        : OperationResult.Kind.ERROR, cancelled ? Messages.get("operationCancelledShort")
                            : changeEvent.getResult().getMessage()); //$NON-NLS-1$
                }
                GitFlowView.finished(repository, keepChangesEnabled);
                if (completion[0] != null)
                    Display.getDefault().asyncExec(() ->
                    {
                        if (completion[0].kind() == OperationResult.Kind.NEEDS_CHECKOUT_CLEANUP)
                        {
                            boolean retry = UIRepositoryUtils.showCleanupDialog(repository,
                                new ArrayList<>(completion[0].affectedPaths()),
                                UIText.BranchResultDialog_CheckoutConflictsTitle, shell);
                            if (retry)
                                schedule(repository, shell, title, operation, confirmedOperation,
                                    completionAction, keepChangesEnabled, true);
                            return;
                        }
                        if (completion[0].kind() == OperationResult.Kind.NEEDS_CONFIRMATION
                            && confirmedOperation != null)
                        {
                            if (shell != null && !shell.isDisposed()
                                && MessageDialog.openQuestion(shell, title, completion[0].message()))
                                schedule(repository, shell, title, confirmedOperation, null,
                                    completionAction, keepChangesEnabled, true);
                            else
                                GitFlowView.publishStatus(title + Messages.get("operationCancelled"),
                                    Messages.get("operationCancelledShort")); //$NON-NLS-1$
                        }
                        else
                        {
                            if (completionAction != null)
                                completionAction.accept(completion[0]);
                            if (completion[0].kind() == OperationResult.Kind.CANCELLED)
                            {
                                GitFlowView.publishStatus(title + ": " + completion[0].message(), //$NON-NLS-1$
                                    Messages.get("operationCancelledShort")); //$NON-NLS-1$
                                return;
                            }
                            if (completion[0].kind() == OperationResult.Kind.NEEDS_NATIVE_MERGE)
                            {
                                NativePullLauncher.start(repository, title);
                                return;
                            }
                            boolean succeeded = completion[0].succeeded();
                            GitFlowView.publishStatus(title + ": " + completion[0].message(), //$NON-NLS-1$
                                succeeded ? Messages.get("allDone") : Messages.get("operationFailedShort"));
                        }
                    });
            }
        });
        GitFlowView.started(repository, keepChangesEnabled);
        GitFlowView.useRepository(repository);
        GitFlowView.publishStatus(title + Messages.get("operationStarted"), title); //$NON-NLS-1$
        job.schedule();
    }

}
