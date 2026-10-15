package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.egit.core.internal.indexdiff.IndexDiffData;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.IndexDiff;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.junit.Test;

import dev.edt.gitflow.core.WorkingChanges;
import dev.edt.gitflow.core.WorkingChanges.FileChange;

public class WorkingChangesReadTest
{
    @Test
    public void separatesAndSortsAllFileStatesWithoutIncludingIgnoredFiles() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-read-states-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(work.toFile()).call())
        {
            for (String name : List.of("a-removed", "b-missing", "c-changed", "d-modified", "e-both")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                Files.writeString(work.resolve(name), "base\n"); //$NON-NLS-1$
            Files.writeString(work.resolve(".gitignore"), "ignored\n"); //$NON-NLS-1$ //$NON-NLS-2$
            commitAll(git);
            git.rm().addFilepattern("a-removed").call(); //$NON-NLS-1$
            Files.delete(work.resolve("b-missing")); //$NON-NLS-1$
            Files.writeString(work.resolve("c-changed"), "staged\n"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(work.resolve("e-both"), "staged\n"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(work.resolve("x-added"), "new\n"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("c-changed").addFilepattern("e-both").addFilepattern("x-added").call(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Files.writeString(work.resolve("d-modified"), "unstaged\n"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(work.resolve("e-both"), "unstaged\n"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(work.resolve("y-untracked"), "new\n"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(work.resolve("ignored"), "ignored\n"); //$NON-NLS-1$ //$NON-NLS-2$

            assertChanges(git,
                List.of(new FileChange("a-removed", "D"), new FileChange("c-changed", "M"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    new FileChange("e-both", "M"), new FileChange("x-added", "A")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(new FileChange("b-missing", "D"), new FileChange("d-modified", "M"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    new FileChange("e-both", "M"), new FileChange("y-untracked", "U"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
    }

    @Test
    public void showsConflictsOnlyInUnstagedChanges() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-read-conflict-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(work.toFile()).call())
        {
            Files.writeString(work.resolve("conflict.txt"), "base\n"); //$NON-NLS-1$ //$NON-NLS-2$
            commitAll(git);
            String branch = git.getRepository().getBranch();
            git.checkout().setCreateBranch(true).setName("feature").call(); //$NON-NLS-1$
            Files.writeString(work.resolve("conflict.txt"), "feature\n"); //$NON-NLS-1$ //$NON-NLS-2$
            commitAll(git);
            var feature = git.getRepository().resolve("HEAD"); //$NON-NLS-1$
            git.checkout().setName(branch).call();
            Files.writeString(work.resolve("conflict.txt"), "main\n"); //$NON-NLS-1$ //$NON-NLS-2$
            commitAll(git);
            git.merge().include(feature).call();

            assertChanges(git, List.of(), List.of(new FileChange("conflict.txt", "C"))); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void handlesEmptyRepositoryAndFirstStagedFile() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-read-empty-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(work.toFile()).call())
        {
            assertChanges(git, List.of(), List.of());
            Files.writeString(work.resolve("new.txt"), "new\n"); //$NON-NLS-1$ //$NON-NLS-2$
            assertChanges(git, List.of(), List.of(new FileChange("new.txt", "U"))); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("new.txt").call(); //$NON-NLS-1$
            assertChanges(git, List.of(new FileChange("new.txt", "A")), List.of()); //$NON-NLS-1$ //$NON-NLS-2$
            commitAll(git);
            assertChanges(git, List.of(), List.of());
        }
    }

    @Test
    public void returnedListsCannotBeModifiedAndKeepTheirSnapshot() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-read-snapshot-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(work.toFile()).call())
        {
            Files.writeString(work.resolve("new.txt"), "new\n"); //$NON-NLS-1$ //$NON-NLS-2$
            WorkingChanges snapshot = WorkingChanges.read(git.getRepository());
            WorkingChanges cachedSnapshot = readIndexDiff(git);
            assertThrows(UnsupportedOperationException.class,
                () -> snapshot.staged().add(new FileChange("other", "A"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertThrows(UnsupportedOperationException.class, () -> snapshot.unstaged().clear());
            assertThrows(UnsupportedOperationException.class,
                () -> cachedSnapshot.staged().add(new FileChange("other", "A"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertThrows(UnsupportedOperationException.class, () -> cachedSnapshot.unstaged().clear());
            git.add().addFilepattern("new.txt").call(); //$NON-NLS-1$
            assertChanges(git, List.of(new FileChange("new.txt", "A")), List.of()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(cachedSnapshot, snapshot);
            assertEquals(List.of(), snapshot.staged());
            assertEquals(List.of(new FileChange("new.txt", "U")), snapshot.unstaged()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void assertChanges(Git git, List<FileChange> staged, List<FileChange> unstaged) throws Exception
    {
        WorkingChanges changes = WorkingChanges.read(git.getRepository());
        assertEquals(staged, changes.staged());
        assertEquals(unstaged, changes.unstaged());
        WorkingChanges cached = readIndexDiff(git);
        assertEquals(staged, cached.staged());
        assertEquals(unstaged, cached.unstaged());
    }

    private static WorkingChanges readIndexDiff(Git git) throws Exception
    {
        var repository = git.getRepository();
        IndexDiff diff = new IndexDiff(repository, Constants.HEAD, new FileTreeIterator(repository));
        diff.diff();
        return WorkingChanges.fromIndexDiff(new IndexDiffData(diff));
    }

    private static void commitAll(Git git) throws Exception
    {
        git.add().addFilepattern(".").call(); //$NON-NLS-1$
        git.commit().setMessage("test").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
