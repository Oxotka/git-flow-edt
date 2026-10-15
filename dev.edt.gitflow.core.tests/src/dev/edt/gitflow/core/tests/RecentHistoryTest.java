package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.Test;

import dev.edt.gitflow.core.RecentHistory;

public class RecentHistoryTest
{
    @Test
    public void searchesPastFirstPageByMessageAuthorAndHashPrefix() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-history-search-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(directory.toFile()).call())
        {
            String oldCommit = null;
            String authorCommit = null;
            String hashCommit = null;
            for (int i = 0; i < 40; i++)
            {
                String message = "Commit " + i; //$NON-NLS-1$
                String author = "Developer"; //$NON-NLS-1$
                if (i == 0)
                    message = "Old commit\n\nneedle is in the body"; //$NON-NLS-1$
                else if (i == 13)
                {
                    message = "Alice authored this commit"; //$NON-NLS-1$
                    author = "Alice Example"; //$NON-NLS-1$
                }
                else if (i == 17)
                    message = "Commit selected by hash"; //$NON-NLS-1$
                String hash = commit(git, directory, i, message, author);
                if (i == 0)
                    oldCommit = hash;
                else if (i == 13)
                    authorCommit = hash;
                else if (i == 17)
                    hashCommit = hash;
            }

            try (RecentHistory.SearchReader search = RecentHistory.openSearch(git.getRepository()))
            {
                assertEquals(oldCommit, search.findNext("NEEDLE", new NullProgressMonitor())); //$NON-NLS-1$
                search.restart();
                assertEquals(authorCommit, search.findNext("ALICE", new NullProgressMonitor())); //$NON-NLS-1$
                assertNull(search.findNext("alice", new NullProgressMonitor())); //$NON-NLS-1$
                assertEquals(authorCommit, search.findNext("alice", new NullProgressMonitor())); //$NON-NLS-1$
                search.restart();
                assertEquals(hashCommit, search.findNext(hashCommit.substring(0, 8).toUpperCase(), //$NON-NLS-1$
                    new NullProgressMonitor()));
            }
        }
    }

    @Test
    public void findsEachMatchingCommitOnceThenWrapsAfterExhaustion() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-history-search-wrap-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(directory.toFile()).call())
        {
            String olderMatch = commit(git, directory, 0, "Needle older", "Developer"); //$NON-NLS-1$ //$NON-NLS-2$
            commit(git, directory, 1, "No match", "Developer"); //$NON-NLS-1$ //$NON-NLS-2$
            String newerMatch = commit(git, directory, 2, "Needle newer", "Developer"); //$NON-NLS-1$ //$NON-NLS-2$

            try (RecentHistory.SearchReader search = RecentHistory.openSearch(git.getRepository()))
            {
                assertEquals(newerMatch, search.findNext("needle", new NullProgressMonitor())); //$NON-NLS-1$
                assertEquals(olderMatch, search.findNext("needle", new NullProgressMonitor())); //$NON-NLS-1$
                assertNull(search.findNext("needle", new NullProgressMonitor())); //$NON-NLS-1$
                assertEquals(newerMatch, search.findNext("needle", new NullProgressMonitor())); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void searchesOnlyCommitsReachableFromCurrentBranch() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-history-search-branch-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(directory.toFile()).call())
        {
            commit(git, directory, 0, "Base", "Developer"); //$NON-NLS-1$ //$NON-NLS-2$
            String currentBranch = git.getRepository().getBranch();
            git.branchCreate().setName("other").call(); //$NON-NLS-1$
            git.checkout().setName("other").call(); //$NON-NLS-1$
            commit(git, directory, 1, "Unique side commit", "Developer"); //$NON-NLS-1$ //$NON-NLS-2$
            git.checkout().setName(currentBranch).call();

            try (RecentHistory.SearchReader search = RecentHistory.openSearch(git.getRepository()))
            {
                assertNull(search.findNext("Unique side commit", new NullProgressMonitor())); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void canceledSearchCanBeRetried() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-history-search-cancel-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(directory.toFile()).call())
        {
            String hash = commit(git, directory, 0, "Search target", "Developer"); //$NON-NLS-1$ //$NON-NLS-2$
            try (RecentHistory.SearchReader search = RecentHistory.openSearch(git.getRepository()))
            {
                NullProgressMonitor canceled = new NullProgressMonitor();
                canceled.setCanceled(true);
                boolean wasCanceled = false;
                try
                {
                    search.findNext("target", canceled); //$NON-NLS-1$
                }
                catch (OperationCanceledException expected)
                {
                    wasCanceled = true;
                }
                assertTrue(wasCanceled);
                search.restart();
                assertEquals(hash, search.findNext("target", new NullProgressMonitor())); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void bothReadersDecorateCommitsWithBranchesAndPeeledTags() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-history-labels-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(directory.toFile()).call())
        {
            String base = commit(git, directory, 0, "Base\n\nDescription", "Author"); //$NON-NLS-1$ //$NON-NLS-2$
            String branch = git.getRepository().getBranch();
            git.branchCreate().setName("older").call(); //$NON-NLS-1$
            var remote = git.getRepository().updateRef(Constants.R_REMOTES + "origin/vendor"); //$NON-NLS-1$
            remote.setNewObjectId(ObjectId.fromString(base));
            remote.update();
            git.getRepository().updateRef(Constants.R_REMOTES + "origin/HEAD") //$NON-NLS-1$
                .link(Constants.R_REMOTES + "origin/vendor"); //$NON-NLS-1$
            git.tag().setName("lightweight").setAnnotated(false).call(); //$NON-NLS-1$
            git.tag().setName("release").setAnnotated(true).setMessage("Release") //$NON-NLS-1$ //$NON-NLS-2$
                .setTagger(new org.eclipse.jgit.lib.PersonIdent("Tagger", "tagger@example.org")).call(); //$NON-NLS-1$ //$NON-NLS-2$
            String head = commit(git, directory, 1, "Latest", "Developer"); //$NON-NLS-1$ //$NON-NLS-2$
            git.checkout().setName("older").call(); //$NON-NLS-1$
            commit(git, directory, 2, "Unrelated side commit", "Developer"); //$NON-NLS-1$ //$NON-NLS-2$
            git.checkout().setName(branch).call();

            for (List<RecentHistory.Entry> entries : readBoth(git, 10))
            {
                assertEquals(List.of(head, base), entries.stream().map(RecentHistory.Entry::hash).toList());
                assertEquals(List.of(branch), entries.get(0).labels());
                assertEquals(List.of("origin/vendor", "lightweight", "release"), entries.get(1).labels()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertEquals("Base", entries.get(1).subject()); //$NON-NLS-1$
                assertEquals("Base\n\nDescription", entries.get(1).message()); //$NON-NLS-1$
                assertEquals("Author", entries.get(1).author()); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void bothReadersLabelDetachedHead() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-history-detached-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(directory.toFile()).call())
        {
            String head = commit(git, directory, 0, "Detached", "Developer"); //$NON-NLS-1$ //$NON-NLS-2$
            String branch = git.getRepository().getBranch();
            git.checkout().setName(head).call();

            for (List<RecentHistory.Entry> entries : readBoth(git, 10))
            {
                assertEquals(1, entries.size());
                assertEquals(head, entries.get(0).hash());
                assertEquals(List.of(branch, "HEAD"), entries.get(0).labels()); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void bothReadersKeepMergeParentsAndTopologicalOrder() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-history-merge-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(directory.toFile()).call())
        {
            String base = commit(git, directory, 0, "Base", "Developer"); //$NON-NLS-1$ //$NON-NLS-2$
            String branch = git.getRepository().getBranch();
            git.branchCreate().setName("side").call(); //$NON-NLS-1$
            String main = commit(git, directory, 1, "Main", "Developer"); //$NON-NLS-1$ //$NON-NLS-2$
            git.checkout().setName("side").call(); //$NON-NLS-1$
            Files.writeString(directory.resolve("side.txt"), "side"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("side.txt").call(); //$NON-NLS-1$
            String side = git.commit().setMessage("Side").setAuthor("Developer", "dev@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .setCommitter("Developer", "dev@example.org").call().name(); //$NON-NLS-1$ //$NON-NLS-2$
            git.checkout().setName(branch).call();
            String merge = git.merge().include(ObjectId.fromString(side)).setMessage("Merge side").call() //$NON-NLS-1$
                .getNewHead().name();

            for (List<RecentHistory.Entry> entries : readBoth(git, 10))
            {
                List<String> hashes = entries.stream().map(RecentHistory.Entry::hash).toList();
                assertEquals(4, hashes.size());
                assertEquals(merge, hashes.get(0));
                assertEquals(base, hashes.get(3));
                assertTrue(hashes.contains(main));
                assertTrue(hashes.contains(side));
                var plot = entries.get(0).plot();
                assertEquals(2, plot.getParentCount());
                assertEquals(main, plot.getParent(0).name());
                assertEquals(side, plot.getParent(1).name());
            }
        }
    }

    @Test
    public void emptyAndExactPageBoundariesDoNotLoseOrRepeatCommits() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-history-boundaries-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(directory.toFile()).call())
        {
            try (RecentHistory.Reader reader = RecentHistory.open(git.getRepository()))
            {
                assertEquals(new RecentHistory.Page(List.of(), false), reader.readNext(1));
            }
            String base = commit(git, directory, 0, "Base", "Developer"); //$NON-NLS-1$ //$NON-NLS-2$
            String head = commit(git, directory, 1, "Latest", "Developer"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(RecentHistory.read(git.getRepository(), -1, 2).isEmpty());
            assertTrue(RecentHistory.read(git.getRepository(), 0, -1).isEmpty());
            assertTrue(RecentHistory.read(git.getRepository(), 0, 0).isEmpty());
            assertTrue(RecentHistory.read(git.getRepository(), 2, 1).isEmpty());
            assertEquals(List.of(base), RecentHistory.read(git.getRepository(), 1, 2).stream()
                .map(RecentHistory.Entry::hash).toList());
            try (RecentHistory.Reader reader = RecentHistory.open(git.getRepository()))
            {
                assertTrue(reader.readNext(0).entries().isEmpty());
                RecentHistory.Page first = reader.readNext(1);
                RecentHistory.Page last = reader.readNext(1);
                assertEquals(List.of(head), first.entries().stream().map(RecentHistory.Entry::hash).toList());
                assertTrue(first.hasMore());
                assertEquals(List.of(base), last.entries().stream().map(RecentHistory.Entry::hash).toList());
                assertFalse(last.hasMore());
                assertEquals(new RecentHistory.Page(List.of(), false), reader.readNext(1));
            }
        }
    }

    private static List<List<RecentHistory.Entry>> readBoth(Git git, int limit) throws Exception
    {
        try (RecentHistory.Reader reader = RecentHistory.open(git.getRepository()))
        {
            return List.of(RecentHistory.read(git.getRepository(), limit), reader.readNext(limit).entries());
        }
    }

    private static String commit(Git git, Path directory, int sequence, String message, String author)
        throws Exception
    {
        Files.writeString(directory.resolve("file.txt"), Integer.toString(sequence)); //$NON-NLS-1$
        git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
        ObjectId id = git.commit().setMessage(message)
            .setAuthor(author, "author@example.org") //$NON-NLS-1$
            .setCommitter("Developer", "dev@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
        return id.name();
    }

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
