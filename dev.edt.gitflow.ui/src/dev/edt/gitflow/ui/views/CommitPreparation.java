package dev.edt.gitflow.ui.views;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.egit.core.internal.job.RuleUtil;
import org.eclipse.egit.ui.UIUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.StatusCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import dev.edt.gitflow.core.OperationResult;
import dev.edt.gitflow.core.OperationResult.Kind;

final class CommitPreparation
{
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$

    private CommitPreparation()
    {
    }

    static void prepareAsync(Repository repository, Shell shell, Set<String> stagedPaths,
        Consumer<OperationResult> completion)
    {
        Set<String> paths = Set.copyOf(stagedPaths);
        OperationResult[] result = new OperationResult[1];
        GitFlowView.started(repository);
        Job job = new Job("Подготовка коммита") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    var before = paths.isEmpty() ? null : status(repository, paths);
                    boolean[] saved = {false};
                    Display.getDefault().syncExec(() ->
                    {
                        if (!shell.isDisposed())
                            saved[0] = UIUtils.saveAllEditors(repository);
                    });
                    if (!saved[0])
                    {
                        result[0] = new OperationResult(Kind.CANCELLED,
                            "Сохранение редакторов отменено"); //$NON-NLS-1$
                        return Status.CANCEL_STATUS;
                    }
                    if (before != null)
                        stageSavedChanges(repository, paths, before, status(repository, paths));
                    result[0] = new OperationResult(Kind.SUCCESS, "Подготовка коммита завершена"); //$NON-NLS-1$
                    return Status.OK_STATUS;
                }
                catch (GitAPIException | RuntimeException e)
                {
                    result[0] = new OperationResult(Kind.ERROR,
                        "Не удалось подготовить сохраненные файлы: " + e.getMessage()); //$NON-NLS-1$
                    return new Status(IStatus.ERROR, PLUGIN_ID, result[0].message(), e);
                }
            }
        };
        job.setRule(RuleUtil.getRule(repository));
        job.addJobChangeListener(new org.eclipse.core.runtime.jobs.JobChangeAdapter()
        {
            @Override
            public void done(org.eclipse.core.runtime.jobs.IJobChangeEvent event)
            {
                OperationResult outcome = result[0];
                if (outcome == null)
                    outcome = new OperationResult(event.getResult().getSeverity() == IStatus.CANCEL
                        ? Kind.CANCELLED : Kind.ERROR, event.getResult().getMessage());
                OperationResult completed = outcome;
                Display.getDefault().asyncExec(() ->
                {
                    GitFlowView.finished(repository);
                    completion.accept(completed);
                });
            }
        });
        job.schedule();
    }

    private static org.eclipse.jgit.api.Status status(Repository repository, Set<String> paths)
        throws GitAPIException
    {
        StatusCommand command = Git.wrap(repository).status();
        paths.forEach(command::addPath);
        return command.call();
    }

    private static void stageSavedChanges(Repository repository, Set<String> paths,
        org.eclipse.jgit.api.Status before,
        org.eclipse.jgit.api.Status after) throws GitAPIException
    {
        Set<String> modified = new HashSet<>();
        Set<String> missing = new HashSet<>();
        for (String path : paths)
        {
            if (!after.getAdded().contains(path) && !after.getChanged().contains(path)
                && !after.getRemoved().contains(path))
                continue;
            if (after.getModified().contains(path) && !before.getModified().contains(path))
                modified.add(path);
            else if (after.getMissing().contains(path) && !before.getMissing().contains(path))
                missing.add(path);
        }
        Git git = Git.wrap(repository);
        if (!modified.isEmpty())
        {
            var add = git.add();
            modified.forEach(add::addFilepattern);
            add.call();
        }
        if (!missing.isEmpty())
        {
            var add = git.add().setUpdate(true);
            missing.forEach(add::addFilepattern);
            add.call();
        }
    }
}
