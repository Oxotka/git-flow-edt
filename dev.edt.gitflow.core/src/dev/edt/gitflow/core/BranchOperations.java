package dev.edt.gitflow.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NotMergedException;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;

import dev.edt.gitflow.core.OperationResult.Kind;

public final class BranchOperations
{
    public record RemoteRename(String remote, String oldName, String newName)
    {
    }

    private BranchOperations()
    {
    }

    public static boolean isValidBranchName(String name)
    {
        return name != null && org.eclipse.jgit.api.CreateBranchCommand.isValidBranchName(name);
    }

    public static OperationResult rename(Repository repository, String oldName, String newName,
        IProgressMonitor monitor)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        if (!isValidBranchName(newName))
            return new OperationResult(Kind.ERROR, "Некорректное имя ветки."); //$NON-NLS-1$
        if (oldName.equals(newName))
            return new OperationResult(Kind.NO_CHANGE, "Имя ветки не изменилось."); //$NON-NLS-1$
        monitor.beginTask("Переименование ветки", 1); //$NON-NLS-1$
        try
        {
            Git.wrap(repository).branchRename().setOldName(oldName).setNewName(newName).call();
            return new OperationResult(Kind.SUCCESS, "Ветка переименована: " + newName, true); //$NON-NLS-1$
        }
        catch (GitAPIException e)
        {
            return new OperationResult(Kind.ERROR, "Не удалось переименовать ветку: " + e.getMessage()); //$NON-NLS-1$
        }
        finally
        {
            monitor.done();
        }
    }

    public static OperationResult validateRename(Repository repository, String oldName, String newName)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        if (!isValidBranchName(newName))
            return new OperationResult(Kind.ERROR, "Некорректное имя ветки."); //$NON-NLS-1$
        if (oldName.equals(newName))
            return new OperationResult(Kind.NO_CHANGE, "Имя ветки не изменилось."); //$NON-NLS-1$
        try
        {
            if (repository.exactRef(Constants.R_HEADS + oldName) == null)
                return new OperationResult(Kind.ERROR, "Локальная ветка не найдена: " + oldName); //$NON-NLS-1$
            if (repository.exactRef(Constants.R_HEADS + newName) != null)
                return new OperationResult(Kind.ERROR, "Локальная ветка уже существует: " + newName); //$NON-NLS-1$
            return new OperationResult(Kind.SUCCESS, "Переименование локальной ветки возможно."); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            return new OperationResult(Kind.ERROR, "Не удалось проверить локальное переименование: " //$NON-NLS-1$
                + e.getMessage());
        }
    }

    public static OperationResult renameWithRemote(Repository repository, String oldName, String newName,
        RemoteRename target, IProgressMonitor monitor)
    {
        OperationResult validation = validateRename(repository, oldName, newName);
        if (!validation.succeeded() || validation.kind() == Kind.NO_CHANGE)
            return validation;
        OperationResult remoteResult = renameRemote(repository, oldName, target, monitor);
        if (!remoteResult.succeeded())
            return remoteResult;
        OperationResult localResult = rename(repository, oldName, newName, monitor);
        if (!localResult.succeeded())
            return new OperationResult(Kind.ERROR, "Удалённая ветка переименована, но локальную переименовать " //$NON-NLS-1$
                + "не удалось: " + localResult.message(), true); //$NON-NLS-1$
        String message = "Локальная и удалённая ветки переименованы: " + newName; //$NON-NLS-1$
        try
        {
            if (repository.exactRef(Constants.R_REMOTES + target.remote() + "/" + target.oldName()) != null) //$NON-NLS-1$
                message += ". Старая удалённая ссылка всё ещё может отображаться в списке веток."; //$NON-NLS-1$
        }
        catch (IOException e)
        {
            message += ". Не удалось проверить список удалённых веток: " + e.getMessage(); //$NON-NLS-1$
        }
        return new OperationResult(Kind.SUCCESS, message, true);
    }

    public static RemoteRename remoteRenameTarget(Repository repository, String localName, String newLocalName)
    {
        BranchConfig branch = new BranchConfig(repository.getConfig(), localName);
        String remote = branch.getRemote();
        String merge = branch.getMerge();
        if (remote == null || ".".equals(remote) || merge == null //$NON-NLS-1$
            || !merge.startsWith(Constants.R_HEADS))
            return null;
        String remoteBranch = merge.substring(Constants.R_HEADS.length());
        if (localName.equals(newLocalName))
            return null;
        return remoteBranch.equals(newLocalName) ? null
            : new RemoteRename(remote, remoteBranch, newLocalName);
    }

    public static OperationResult renameRemote(Repository repository, String localName, RemoteRename target,
        IProgressMonitor monitor)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        String oldTrackingRef = Constants.R_REMOTES + target.remote() + "/" + target.oldName(); //$NON-NLS-1$
        String newTrackingRef = Constants.R_REMOTES + target.remote() + "/" + target.newName(); //$NON-NLS-1$
        String newRemoteRef = Constants.R_HEADS + target.newName();
        String oldRemoteRef = Constants.R_HEADS + target.oldName();
        monitor.beginTask("Переименование удалённой ветки", 3); //$NON-NLS-1$
        try
        {
            Git git = Git.wrap(repository);
            git.fetch().setRemote(target.remote()).call();
            monitor.worked(1);
            ObjectId oldRemoteId = repository.resolve(oldTrackingRef);
            ObjectId existingNewRemote = repository.resolve(newTrackingRef);
            if (oldRemoteId == null)
                return new OperationResult(Kind.ERROR, "Удалённая ветка не найдена: " //$NON-NLS-1$
                    + target.remote() + "/" + target.oldName()); //$NON-NLS-1$
            if (existingNewRemote != null)
                return new OperationResult(Kind.ERROR, "На сервере уже существует ветка " //$NON-NLS-1$
                    + target.remote() + "/" + target.newName()); //$NON-NLS-1$
            ObjectId localId = repository.resolve(Constants.R_HEADS + localName);
            if (localId == null)
                return new OperationResult(Kind.ERROR, "Локальная ветка не найдена: " + localName); //$NON-NLS-1$
            try (RevWalk walk = new RevWalk(repository))
            {
                if (!walk.isMergedInto(walk.parseCommit(oldRemoteId), walk.parseCommit(localId)))
                    return new OperationResult(Kind.ERROR,
                        "В удалённой ветке есть коммиты, которых нет в локальной. Сначала синхронизируйте ветки."); //$NON-NLS-1$
            }
            monitor.worked(1);
            OperationResult published = pushRef(git, target.remote(),
                new RefSpec(Constants.R_HEADS + localName + ":" + newRemoteRef), newRemoteRef, false); //$NON-NLS-1$
            if (!published.succeeded())
                return new OperationResult(Kind.ERROR, "Не удалось отправить новую удалённую ветку: " //$NON-NLS-1$
                    + published.message());
            try
            {
                repository.getConfig().setString("branch", localName, "remote", target.remote()); //$NON-NLS-1$ //$NON-NLS-2$
                repository.getConfig().setString("branch", localName, "merge", newRemoteRef); //$NON-NLS-1$ //$NON-NLS-2$
                repository.getConfig().save();
            }
            catch (IOException e)
            {
                return new OperationResult(Kind.ERROR, "Новая ветка отправлена, но upstream не обновлён; " //$NON-NLS-1$
                    + "старая удалённая ветка сохранена: " + e.getMessage()); //$NON-NLS-1$
            }
            OperationResult deleted = pushRef(git, target.remote(), new RefSpec(":" + oldRemoteRef), //$NON-NLS-1$
                oldRemoteRef, true);
            monitor.worked(1);
            if (!deleted.succeeded())
                return new OperationResult(Kind.ERROR, "Новая ветка отправлена и настроена как upstream, " //$NON-NLS-1$
                    + "но старую ветку удалить не удалось. На сервере остались обе ветки: " //$NON-NLS-1$
                    + target.remote() + "/" + target.newName() + " и " + target.remote() + "/" //$NON-NLS-1$ //$NON-NLS-2$
                    + target.oldName() + ". " + deleted.message()); //$NON-NLS-1$
            try
            {
                git.fetch().setRemote(target.remote()).setRemoveDeletedRefs(true).call();
            }
            catch (GitAPIException ignored)
            {
                // The server rename is complete; local tracking cleanup is retried below.
            }
            RefUpdate staleTrackingRef = repository.updateRef(oldTrackingRef);
            staleTrackingRef.setExpectedOldObjectId(oldRemoteId);
            RefUpdate.Result trackingDelete = staleTrackingRef.delete();
            return new OperationResult(Kind.SUCCESS, "Удалённая ветка переименована: " //$NON-NLS-1$
                + target.remote() + "/" + target.oldName() + " → " + target.remote() + "/" //$NON-NLS-1$ //$NON-NLS-2$
                + target.newName() + (trackingDelete == RefUpdate.Result.FORCED //$NON-NLS-1$
                    || trackingDelete == RefUpdate.Result.NEW || trackingDelete == RefUpdate.Result.NO_CHANGE
                        ? "" : "; локальная ссылка " + target.remote() + "/" + target.oldName() //$NON-NLS-1$ //$NON-NLS-2$
                            + " осталась в списке веток (" + trackingDelete + ")"), true); //$NON-NLS-1$
        }
        catch (GitAPIException | IOException e)
        {
            return new OperationResult(Kind.ERROR, "Не удалось переименовать удалённую ветку: " + e.getMessage()); //$NON-NLS-1$
        }
        finally
        {
            monitor.done();
        }
    }

    public static OperationResult renameRemoteReference(Repository repository, String remote, String oldName,
        String newName, IProgressMonitor monitor)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        if (!isValidBranchName(newName))
            return new OperationResult(Kind.ERROR, "Некорректное имя ветки."); //$NON-NLS-1$
        if (oldName.equals(newName))
            return new OperationResult(Kind.NO_CHANGE, "Имя ветки не изменилось."); //$NON-NLS-1$
        String oldTrackingRef = Constants.R_REMOTES + remote + "/" + oldName; //$NON-NLS-1$
        String newTrackingRef = Constants.R_REMOTES + remote + "/" + newName; //$NON-NLS-1$
        String oldRemoteRef = Constants.R_HEADS + oldName;
        String newRemoteRef = Constants.R_HEADS + newName;
        List<String> localBranches = trackingBranches(repository, remote, oldRemoteRef);
        String localBranch = localBranches.contains(oldName) ? oldName
            : localBranches.size() == 1 ? localBranches.get(0) : null;
        if (localBranches.size() > 1 && localBranch == null)
            return new OperationResult(Kind.ERROR,
                "Несколько локальных веток отслеживают эту ветку. Сначала переименуйте нужную локальную ветку отдельно."); //$NON-NLS-1$
        if (localBranch != null)
        {
            OperationResult validation = validateRename(repository, localBranch, newName);
            if (!validation.succeeded() || validation.kind() == Kind.NO_CHANGE)
                return validation;
        }
        monitor.beginTask("Переименование удалённой ветки", 4); //$NON-NLS-1$
        try
        {
            Git git = Git.wrap(repository);
            git.fetch().setRemote(remote).call();
            monitor.worked(1);
            ObjectId oldId = repository.resolve(oldTrackingRef);
            if (oldId == null)
                return new OperationResult(Kind.ERROR, "Удалённая ветка не найдена: " + remote + "/" + oldName); //$NON-NLS-1$
            if (repository.resolve(newTrackingRef) != null)
                return new OperationResult(Kind.ERROR, "Удалённая ветка уже существует: " + remote + "/" + newName); //$NON-NLS-1$
            OperationResult published = pushRef(git, remote, new RefSpec(oldTrackingRef + ":" + newRemoteRef), //$NON-NLS-1$
                newRemoteRef, false);
            if (!published.succeeded())
                return new OperationResult(Kind.ERROR, "Не удалось создать новую удалённую ветку: " //$NON-NLS-1$
                    + published.message());
            monitor.worked(1);
            OperationResult deleted = pushRef(git, remote, new RefSpec(":" + oldRemoteRef), oldRemoteRef, true); //$NON-NLS-1$
            if (!deleted.succeeded())
                return new OperationResult(Kind.ERROR, "Новая ветка отправлена, но старую удалить не удалось. " //$NON-NLS-1$
                    + "На сервере остались обе ветки: " + remote + "/" + newName + " и " + remote + "/" //$NON-NLS-1$ //$NON-NLS-2$
                    + oldName + ". " + deleted.message()); //$NON-NLS-1$
            monitor.worked(1);
            if (localBranch != null)
            {
                OperationResult localRename = rename(repository, localBranch, newName, monitor);
                if (!localRename.succeeded())
                    return new OperationResult(Kind.ERROR, "Удалённая ветка переименована, но локальную ветку " //$NON-NLS-1$
                        + "не удалось переименовать: " + localRename.message(), true); //$NON-NLS-1$
            }
            boolean upstreamsUpdated = updateUpstreams(repository, remote, oldRemoteRef, newRemoteRef);
            try
            {
                git.fetch().setRemote(remote).setRemoveDeletedRefs(true).call();
            }
            catch (GitAPIException ignored)
            {
                // The remote rename succeeded; the stale tracking ref is removed below when possible.
            }
            removeTrackingRef(repository, oldTrackingRef, oldId);
            monitor.worked(1);
            String message = localBranch == null ? "Удалённая ветка переименована: " //$NON-NLS-1$
                : "Локальная и удалённая ветки переименованы: "; //$NON-NLS-1$
            message += remote + "/" //$NON-NLS-1$
                + oldName + " → " + remote + "/" + newName //$NON-NLS-1$
                + (upstreamsUpdated ? "" : ". Не удалось обновить связи локальных веток с upstream."); //$NON-NLS-1$
            if (repository.exactRef(oldTrackingRef) != null)
                message += ". Старая ссылка может остаться в списке веток."; //$NON-NLS-1$
            return new OperationResult(Kind.SUCCESS, message, true);
        }
        catch (GitAPIException | IOException e)
        {
            return new OperationResult(Kind.ERROR, "Не удалось переименовать удалённую ветку: " + e.getMessage()); //$NON-NLS-1$
        }
        finally
        {
            monitor.done();
        }
    }

    private static boolean updateUpstreams(Repository repository, String remote, String oldMerge, String newMerge)
        throws IOException
    {
        boolean changed = false;
        for (String branch : repository.getConfig().getSubsections("branch")) //$NON-NLS-1$
        {
            if (remote.equals(repository.getConfig().getString("branch", branch, "remote")) //$NON-NLS-1$ //$NON-NLS-2$
                && oldMerge.equals(repository.getConfig().getString("branch", branch, "merge"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                repository.getConfig().setString("branch", branch, "merge", newMerge); //$NON-NLS-1$ //$NON-NLS-2$
                changed = true;
            }
        }
        if (changed)
            repository.getConfig().save();
        return true;
    }

    private static List<String> trackingBranches(Repository repository, String remote, String merge)
    {
        return repository.getConfig().getSubsections("branch").stream() //$NON-NLS-1$
            .filter(branch -> remote.equals(repository.getConfig().getString("branch", branch, "remote")) //$NON-NLS-1$ //$NON-NLS-2$
                && merge.equals(repository.getConfig().getString("branch", branch, "merge"))) //$NON-NLS-1$ //$NON-NLS-2$
            .toList();
    }

    private static void removeTrackingRef(Repository repository, String trackingRef, ObjectId expectedId)
        throws IOException
    {
        if (repository.exactRef(trackingRef) == null)
            return;
        RefUpdate update = repository.updateRef(trackingRef);
        update.setExpectedOldObjectId(expectedId);
        update.delete();
    }

    private static OperationResult pushRef(Git git, String remote, RefSpec spec, String destination,
        boolean allowMissing)
    {
        boolean found = false;
        try
        {
            for (PushResult result : git.push().setRemote(remote).setRefSpecs(spec).call())
            {
                RemoteRefUpdate update = result.getRemoteUpdate(destination);
                if (update == null)
                    continue;
                found = true;
                RemoteRefUpdate.Status status = update.getStatus();
                if (status == RemoteRefUpdate.Status.OK || status == RemoteRefUpdate.Status.UP_TO_DATE
                    || allowMissing && status == RemoteRefUpdate.Status.NON_EXISTING)
                    continue;
                return new OperationResult(Kind.ERROR, update.getMessage() == null
                    ? status.toString() : update.getMessage());
            }
        }
        catch (GitAPIException e)
        {
            return new OperationResult(Kind.ERROR, e.getMessage());
        }
        return found ? new OperationResult(Kind.SUCCESS, "Ветка отправлена.") //$NON-NLS-1$
            : new OperationResult(Kind.ERROR, "Сервер не сообщил результат операции с веткой " + destination); //$NON-NLS-1$
    }

    public static OperationResult delete(Repository repository, String name, IProgressMonitor monitor)
    {
        return delete(repository, name, false, monitor);
    }

    public static OperationResult delete(Repository repository, String name, boolean force,
        IProgressMonitor monitor)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        try
        {
            if (name.equals(repository.getBranch()))
                return new OperationResult(Kind.ERROR, "Нельзя удалить текущую ветку."); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            return new OperationResult(Kind.ERROR, "Не удалось определить текущую ветку: " + e.getMessage()); //$NON-NLS-1$
        }
        monitor.beginTask("Удаление ветки", 1); //$NON-NLS-1$
        try
        {
            Git.wrap(repository).branchDelete().setBranchNames(name).setForce(force).call();
            return new OperationResult(Kind.SUCCESS, "Ветка удалена: " + name, true); //$NON-NLS-1$
        }
        catch (NotMergedException e)
        {
            return new OperationResult(Kind.NEEDS_CONFIRMATION,
                "Ветка «" + name + "» содержит коммиты, не слитые в текущую ветку. Удалить её принудительно?"); //$NON-NLS-1$
        }
        catch (GitAPIException e)
        {
            return new OperationResult(Kind.ERROR, "Не удалось удалить ветку: " + e.getMessage()); //$NON-NLS-1$
        }
        finally
        {
            monitor.done();
        }
    }

    public static OperationResult checkout(Repository repository, String target, boolean create,
        IProgressMonitor monitor)
    {
        return checkout(repository, target, create, null, monitor);
    }

    public static OperationResult checkout(Repository repository, String target, boolean create,
        String startPoint, IProgressMonitor monitor)
    {
        return checkout(repository, target, create, startPoint, monitor, BranchOperations::checkoutDirect);
    }

    @FunctionalInterface
    public interface CheckoutExecutor
    {
        void checkout(Repository repository, String target, boolean create, String startPoint,
            IProgressMonitor monitor) throws GitAPIException, IOException;
    }

    public static void checkoutDirect(Repository repository, String target, boolean create,
        String startPoint, IProgressMonitor monitor) throws GitAPIException
    {
        var command = Git.wrap(repository).checkout().setName(target).setCreateBranch(create);
        if (startPoint != null)
            command.setStartPoint(startPoint).setUpstreamMode(SetupUpstreamMode.TRACK);
        command.call();
    }

    public static OperationResult checkout(Repository repository, String target, boolean create,
        String startPoint, IProgressMonitor monitor, CheckoutExecutor executor)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        boolean tagTarget = target != null && target.startsWith(Constants.R_TAGS);
        if (tagTarget ? !Repository.isValidRefName(target) || create || startPoint != null
            : !isValidBranchName(target))
            return new OperationResult(Kind.ERROR, "Некорректное имя ветки."); //$NON-NLS-1$
        String stashId = null;
        String original;
        monitor.beginTask("Переключение ветки", 3); //$NON-NLS-1$
        try
        {
            Git git = Git.wrap(repository);
            original = repository.getBranch();
            if (target.equals(original))
                return new OperationResult(Kind.NO_CHANGE, "Это уже текущая ветка."); //$NON-NLS-1$
            ObjectId existing = repository.resolve(tagTarget ? target : "refs/heads/" + target); //$NON-NLS-1$
            if (create && existing != null)
                return new OperationResult(Kind.ERROR, "Ветка уже существует: " + target); //$NON-NLS-1$
            if (!create && existing == null)
                return new OperationResult(Kind.ERROR, "Ветка не найдена: " + target); //$NON-NLS-1$
            if (startPoint != null && (!create || !startPoint.startsWith("refs/remotes/") //$NON-NLS-1$
                || repository.resolve(startPoint) == null))
                return new OperationResult(Kind.ERROR, "Удаленная ветка не найдена: " + startPoint); //$NON-NLS-1$
            monitor.subTask("Сохранение локальных изменений"); //$NON-NLS-1$
            RevCommit stash = git.stashCreate().setIncludeUntracked(true).call();
            if (stash != null)
                stashId = stash.getId().name();
            monitor.worked(1);
            try
            {
                monitor.subTask("Переключение на " + target); //$NON-NLS-1$
                executor.checkout(repository, target, create, startPoint, monitor);
            }
            catch (GitAPIException | IOException e)
            {
                if (stashId != null && original.equals(repository.getBranch()) && RepositorySupport.isSafe(repository))
                {
                    StashOperations.Result restored = StashOperations.applyAndDrop(repository, stashId);
                    if (restored.outcome() != StashOperations.Outcome.APPLIED)
                        return new OperationResult(Kind.CONFLICT,
                            "Переключение не удалось; исходные изменения остались в стеше: " //$NON-NLS-1$
                                + restored.detail(), true);
                    stashId = null;
                }
                return new OperationResult(Kind.ERROR, "Переключение не удалось: " + e.getMessage()
                    + (stashId == null ? "" : " Изменения сохранены в стеше " + stashId),
                    !original.equals(repository.getBranch())); //$NON-NLS-1$
            }
            monitor.worked(1);
            if (stashId != null)
            {
                monitor.subTask("Восстановление локальных изменений"); //$NON-NLS-1$
                StashOperations.Result restored = StashOperations.applyAndDrop(repository, stashId);
                if (restored.outcome() != StashOperations.Outcome.APPLIED)
                {
                    if (restored.outcome() == StashOperations.Outcome.CONFLICTS)
                    {
                        var status = git.status().call();
                        List<String> conflicts = new ArrayList<>(status.getConflicting());
                        if (conflicts.isEmpty())
                            conflicts.addAll(status.getModified());
                        try
                        {
                            git.reset().setMode(ResetType.HARD).call();
                            executor.checkout(repository, original, false, null, monitor);
                            StashOperations.Result returned = StashOperations.applyAndDrop(repository, stashId);
                            if (returned.outcome() == StashOperations.Outcome.APPLIED)
                                return new OperationResult(Kind.NEEDS_CHECKOUT_CLEANUP,
                                    "Локальные изменения восстановлены в исходной ветке.", true, false, conflicts); //$NON-NLS-1$
                            return new OperationResult(Kind.CONFLICT,
                                "Конфликт при переключении отменен, но stash не удалось восстановить: " //$NON-NLS-1$
                                    + returned.detail() + " Stash сохранен.", true); //$NON-NLS-1$
                        }
                        catch (GitAPIException rollbackError)
                        {
                            return new OperationResult(Kind.CONFLICT,
                                "Не удалось завершить переключение после конфликта stash: " //$NON-NLS-1$
                                    + rollbackError.getMessage() + " Stash сохранен: " + stashId, true); //$NON-NLS-1$
                        }
                    }
                    return new OperationResult(Kind.CONFLICT,
                        "Ветка переключена, но изменения остались в стеше: " + restored.detail(), true); //$NON-NLS-1$
                }
            }
            monitor.worked(1);
            return new OperationResult(Kind.SUCCESS, "Текущая ветка: " + target, true); //$NON-NLS-1$
        }
        catch (GitAPIException | IOException e)
        {
            return new OperationResult(Kind.ERROR,
                e.getMessage() + (stashId == null ? "" : " Изменения сохранены в стеше " + stashId), //$NON-NLS-1$ //$NON-NLS-2$
                true);
        }
        finally
        {
            monitor.done();
        }
    }

}
