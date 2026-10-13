package dev.edt.gitflow.core;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.StashApplyFailureException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

public final class StashOperations
{
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"); //$NON-NLS-1$

    public enum Outcome { CREATED, NO_CHANGES, APPLIED, NOT_FOUND, CONFLICTS, ERROR }

    public record Result(Outcome outcome, String detail, String stashId)
    {
        public Result(Outcome outcome, String detail)
        {
            this(outcome, detail, null);
        }
    }

    private StashOperations()
    {
    }

    public static boolean hasStash(Repository repository) throws IOException
    {
        return repository.exactRef(Constants.R_STASH) != null;
    }

    public static Result quickStash(Repository repository, boolean includeUntracked, IProgressMonitor monitor)
    {
        monitor.beginTask("Создание стеша", 2); //$NON-NLS-1$
        try
        {
            Git git = Git.wrap(repository); // EGit owns the repository; do not close it here.
            monitor.worked(1);
            String name = "WIP @ " + repository.getBranch() + " " + DATE_FORMAT.format(LocalDateTime.now()); //$NON-NLS-1$ //$NON-NLS-2$
            RevCommit stash = git.stashCreate().setIncludeUntracked(includeUntracked)
                .setWorkingDirectoryMessage(name).call();
            monitor.worked(1);
            return stash == null ? new Result(Outcome.NO_CHANGES, null)
                : new Result(Outcome.CREATED, name, stash.getId().name());
        }
        catch (GitAPIException | java.io.IOException e)
        {
            return new Result(Outcome.ERROR, e.getMessage());
        }
        finally
        {
            monitor.done();
        }
    }

    public static Result quickPop(Repository repository, IProgressMonitor monitor)
    {
        monitor.beginTask("Восстановление стеша", 3); //$NON-NLS-1$
        try
        {
            Ref stash = repository.exactRef(Constants.R_STASH);
            if (stash == null || stash.getObjectId() == null)
                return new Result(Outcome.NOT_FOUND, null);
            String stashId = stash.getObjectId().name();
            monitor.worked(1);
            Result result = applyAndDrop(repository, stashId);
            monitor.worked(2);
            return result;
        }
        catch (IOException e)
        {
            return new Result(Outcome.ERROR, e.getMessage());
        }
        finally
        {
            monitor.done();
        }
    }

    public static Result applyAndDrop(Repository repository, String stashId)
    {
        try
        {
            Git git = Git.wrap(repository);
            if (!isTopStash(repository, stashId))
                return new Result(Outcome.ERROR, "Список стешей изменился. Стеш не применен."); //$NON-NLS-1$
            try
            {
                git.stashApply().setStashRef(stashId).setRestoreIndex(true)
                    .setRestoreUntracked(true).call();
            }
            catch (StashApplyFailureException e)
            {
                return new Result(Outcome.CONFLICTS, e.getMessage());
            }
            // The stash may have changed outside this Job; never drop a different entry.
            if (!isTopStash(repository, stashId))
                return new Result(Outcome.ERROR, "Изменения восстановлены, но список стешей изменился. Стеш сохранен."); //$NON-NLS-1$
            git.stashDrop().setStashRef(0).call();
            return new Result(Outcome.APPLIED, null);
        }
        catch (GitAPIException | IOException e)
        {
            return new Result(Outcome.ERROR, e.getMessage());
        }
    }

    private static boolean isTopStash(Repository repository, String stashId) throws IOException
    {
        Ref stash = repository.exactRef(Constants.R_STASH);
        return stash != null && stash.getObjectId() != null && stashId.equals(stash.getObjectId().name());
    }
}
