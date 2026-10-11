package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.Test;

import dev.edt.gitflow.core.RecentHistory;

public class RecentHistoryTest
{
    @Test
    public void readsRecentCommitsWithAuthorAndLimit() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-history-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(directory.toFile()).call())
        {
            assertTrue(RecentHistory.read(git.getRepository(), 30).isEmpty());
            for (int i = 0; i < 3; i++)
            {
                Files.writeString(directory.resolve("file.txt"), Integer.toString(i)); //$NON-NLS-1$
                git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
                git.commit().setMessage("Commit " + i).setAuthor("Developer", "dev@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .setCommitter("Developer", "dev@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String currentBranch = git.getRepository().getBranch();
            git.branchCreate().setName("older").setStartPoint("HEAD~1").call(); //$NON-NLS-1$ //$NON-NLS-2$
            git.checkout().setName("older").call(); //$NON-NLS-1$
            Files.writeString(directory.resolve("other.txt"), "other"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("other.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("Other branch commit").call(); //$NON-NLS-1$
            git.checkout().setName(currentBranch).call();
            assertTrue(RecentHistory.stateKey(git.getRepository())
                .contains(Constants.R_HEADS + "older=")); //$NON-NLS-1$
            var branchHistory = RecentHistory.read(git.getRepository(), 10);
            assertEquals(3, branchHistory.size());
            assertTrue(branchHistory.get(0).labels().contains(currentBranch));
            assertFalse(branchHistory.get(0).labels().contains("HEAD")); //$NON-NLS-1$
            assertTrue(branchHistory.get(1).labels().isEmpty());
            var remote = git.getRepository().updateRef(Constants.R_REMOTES + "origin/vendor"); //$NON-NLS-1$
            remote.setNewObjectId(git.getRepository().resolve("HEAD~1")); //$NON-NLS-1$
            remote.update();
            git.branchCreate().setName("vendor").setStartPoint("HEAD~1").call(); //$NON-NLS-1$ //$NON-NLS-2$
            git.tag().setName("release").setObjectId(git.getRepository().parseCommit(
                git.getRepository().resolve("HEAD~1"))).setAnnotated(true).setMessage("Release").call();
            var decorated = RecentHistory.read(git.getRepository(), 10);
            assertEquals(3, decorated.size());
            assertTrue(decorated.get(1).labels().contains("origin/vendor")); //$NON-NLS-1$
            assertTrue(decorated.get(1).labels().contains("release"));
            assertTrue(decorated.get(1).labels().contains("vendor")); //$NON-NLS-1$
            var entries = RecentHistory.read(git.getRepository(), 2);
            assertEquals(2, entries.size());
            assertEquals("Commit 2", entries.get(0).subject()); //$NON-NLS-1$
            assertEquals("Developer", entries.get(0).author()); //$NON-NLS-1$
            assertEquals("Commit 1", entries.get(1).subject()); //$NON-NLS-1$
            var nextPage = RecentHistory.read(git.getRepository(), 2, 2);
            assertEquals(1, nextPage.size());
            assertEquals("Commit 0", nextPage.get(0).subject()); //$NON-NLS-1$
            assertTrue(RecentHistory.read(git.getRepository(), 3, 2).isEmpty());
            assertTrue(RecentHistory.read(git.getRepository(), 0).isEmpty());
        }
    }

    @Test
    public void readsSequentialPagesWithoutRepeatingCommits() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-history-pages-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(directory.toFile()).call())
        {
            for (int i = 0; i < 5; i++)
            {
                Files.writeString(directory.resolve("file.txt"), Integer.toString(i)); //$NON-NLS-1$
                git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
                git.commit().setMessage("Commit " + i).call(); //$NON-NLS-1$
            }
            try (RecentHistory.Reader reader = RecentHistory.open(git.getRepository()))
            {
                RecentHistory.Page first = reader.readNext(2);
                RecentHistory.Page second = reader.readNext(2);
                RecentHistory.Page last = reader.readNext(2);

                assertEquals(List.of("Commit 4", "Commit 3"),
                    first.entries().stream().map(RecentHistory.Entry::subject).toList()); //$NON-NLS-1$
                assertEquals(List.of("Commit 2", "Commit 1"),
                    second.entries().stream().map(RecentHistory.Entry::subject).toList()); //$NON-NLS-1$
                assertEquals(List.of("Commit 0"),
                    last.entries().stream().map(RecentHistory.Entry::subject).toList()); //$NON-NLS-1$
                assertTrue(first.hasMore());
                assertTrue(second.hasMore());
                assertFalse(last.hasMore());
            }
        }
    }

    @Test
    public void canceledPageReadCanBeRetried() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-history-cancel-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(directory.toFile()).call())
        {
            Files.writeString(directory.resolve("file.txt"), "content"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("Commit").call(); //$NON-NLS-1$
            try (RecentHistory.Reader reader = RecentHistory.open(git.getRepository()))
            {
                NullProgressMonitor canceled = new NullProgressMonitor();
                canceled.setCanceled(true);
                boolean canceledRead = false;
                try
                {
                    reader.readNext(1, canceled);
                }
                catch (OperationCanceledException expected)
                {
                    canceledRead = true;
                }
                assertTrue(canceledRead);
                assertEquals("Commit", reader.readNext(1).entries().get(0).subject()); //$NON-NLS-1$
            }
        }
    }
}
