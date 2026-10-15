package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.junit.Test;

import dev.edt.gitflow.core.OperationResult.Kind;
import dev.edt.gitflow.core.WorkingChanges;
import dev.edt.gitflow.core.WorkingChanges.FileChange;

public class WorkingChangesBulkTest
{
    @Test
    public void preparesAllEightHundredSelectedFilesWithoutIncludingOtherChanges() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            assertTrue(WorkingChanges.stage(fixture.git.getRepository(), fixture.selection,
                new NullProgressMonitor()).succeeded());
            fixture.checkPrepared();
        }
    }

    @Test
    public void cancellationDuringPreparationFinishesSelectionBeforeStopping() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            assertEquals(Kind.CANCELLED,
                WorkingChanges.stage(fixture.git.getRepository(), fixture.selection, cancelDuringPreparation()).kind());
            fixture.checkPrepared();
        }
    }

    @Test
    public void lateCancellationDoesNotHideAnIndexLockError() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            Files.writeString(fixture.work.resolve(".git/index.lock"), "locked"); //$NON-NLS-1$ //$NON-NLS-2$
            assertThrows(JGitInternalException.class,
                () -> WorkingChanges.stage(fixture.git.getRepository(), fixture.selection, cancelDuringPreparation()));
            assertEquals(List.of(new FileChange("partial.txt", "M")), //$NON-NLS-1$ //$NON-NLS-2$
                WorkingChanges.read(fixture.git.getRepository()).staged());
        }
    }

    private static NullProgressMonitor cancelDuringPreparation()
    {
        return new NullProgressMonitor()
        {
            @Override
            public void subTask(String name)
            {
                setCanceled(true);
            }
        };
    }

    private static final class Fixture implements AutoCloseable
    {
        private final Path work = Files.createTempDirectory("gitflow-bulk-stage-"); //$NON-NLS-1$
        private final Git git = Git.init().setDirectory(work.toFile()).call();
        private final List<FileChange> selection;

        Fixture() throws Exception
        {
            for (String name : List.of("modified.txt", "deleted.txt", "partial.txt")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Files.writeString(work.resolve(name), "base\n"); //$NON-NLS-1$
            git.add().addFilepattern(".").call(); //$NON-NLS-1$
            git.commit().setMessage("Base").setAuthor("Author", "author@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .setCommitter("Author", "author@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(work.resolve("partial.txt"), "staged\n"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("partial.txt").call(); //$NON-NLS-1$
            Files.writeString(work.resolve("partial.txt"), "unstaged\n"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(work.resolve("modified.txt"), "changed\n"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.delete(work.resolve("deleted.txt")); //$NON-NLS-1$
            for (int i = 0; i < 798; i++)
                Files.writeString(work.resolve("module-" + i + ".bsl"), "// New module\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            selection = WorkingChanges.read(git.getRepository()).unstaged().stream()
                .filter(file -> !"partial.txt".equals(file.path())).toList(); //$NON-NLS-1$
            assertEquals(800, selection.size());
            Files.writeString(work.resolve("later.txt"), "outside selection\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        void checkPrepared() throws Exception
        {
            var repository = git.getRepository();
            var status = git.status().call();
            assertEquals(Set.copyOf(selection.stream().filter(file -> "U".equals(file.state())) //$NON-NLS-1$
                .map(FileChange::path).toList()), status.getAdded());
            assertEquals(Set.of("modified.txt", "partial.txt"), status.getChanged()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(Set.of("deleted.txt"), status.getRemoved()); //$NON-NLS-1$
            assertEquals(Set.of("partial.txt"), status.getModified()); //$NON-NLS-1$
            assertEquals(Set.of("later.txt"), status.getUntracked()); //$NON-NLS-1$
            assertEquals("staged\n", new String(repository.open(repository.readDirCache() //$NON-NLS-1$
                .getEntry("partial.txt").getObjectId()).getBytes(), StandardCharsets.UTF_8)); //$NON-NLS-1$
            assertEquals("unstaged\n", Files.readString(work.resolve("partial.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public void close() throws Exception
        {
            git.close();
            try (var paths = Files.walk(work))
            {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }
}
