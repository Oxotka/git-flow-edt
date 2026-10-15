package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.util.List;
import java.util.stream.IntStream;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.junit.Test;

import dev.edt.gitflow.core.RecentHistory;
import dev.edt.gitflow.ui.views.HistoryPages;
import dev.edt.gitflow.ui.views.HistoryPages.RevealAction;

public class HistoryPagesTest
{
    @Test
    public void pagesLoadInOrderAndConcurrentLoadingIsBlocked() throws Exception
    {
        try (Git git = repository(); HistoryPages pages = new HistoryPages())
        {
            for (int i = 0; i < 5; i++)
                commit(git, "Commit " + i); //$NON-NLS-1$
            initialize(pages, git, 2);
            assertEquals(List.of("Commit 4", "Commit 3"), subjects(pages)); //$NON-NLS-1$ //$NON-NLS-2$
            var load = pages.beginLoad(git.getRepository(), 2);
            assertNotNull(load);
            assertNull(pages.beginLoad(git.getRepository(), 2));
            load.schedule();
            load.join();
            assertTrue(load.getResult().toString(), load.getResult().isOK());
            assertTrue(pages.apply(load));
            assertEquals(List.of("Commit 4", "Commit 3", "Commit 2", "Commit 1"), subjects(pages)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertEquals(2, load.page().entries().size());
            var last = pages.beginLoad(git.getRepository(), 2);
            last.schedule();
            last.join();
            assertTrue(pages.apply(last));
            assertEquals(5, pages.entries().size());
            assertFalse(pages.hasMore());
            assertNull(pages.beginLoad(git.getRepository(), 2));
        }
    }

    @Test
    public void changedHeadRejectsAPageWithoutAppendingIt() throws Exception
    {
        try (Git git = repository(); HistoryPages pages = new HistoryPages())
        {
            commit(git, "Base"); //$NON-NLS-1$
            commit(git, "Latest"); //$NON-NLS-1$
            initialize(pages, git, 1);
            var load = pages.beginLoad(git.getRepository(), 1);
            commit(git, "New head"); //$NON-NLS-1$
            load.schedule();
            load.join();
            assertFalse(pages.apply(load));
            assertEquals(List.of("Latest"), subjects(pages)); //$NON-NLS-1$
        }
    }

    @Test
    public void changedLabelsRejectAPageEvenWhenHeadIsUnchanged() throws Exception
    {
        try (Git git = repository(); HistoryPages pages = new HistoryPages())
        {
            commit(git, "Base"); //$NON-NLS-1$
            commit(git, "Latest"); //$NON-NLS-1$
            initialize(pages, git, 1);
            var load = pages.beginLoad(git.getRepository(), 1);
            git.tag().setName("new-label").setAnnotated(false).call(); //$NON-NLS-1$
            load.schedule();
            load.join();
            assertFalse(pages.apply(load));
            assertEquals(List.of("Latest"), subjects(pages)); //$NON-NLS-1$
        }
    }

    @Test
    public void replacingDisplayedHistoryRejectsAnAlreadyLoadedPage() throws Exception
    {
        try (Git git = repository(); HistoryPages pages = new HistoryPages())
        {
            commit(git, "Base"); //$NON-NLS-1$
            commit(git, "Latest"); //$NON-NLS-1$
            initialize(pages, git, 1);
            var load = pages.beginLoad(git.getRepository(), 1);
            load.schedule();
            load.join();
            commit(git, "New head"); //$NON-NLS-1$
            initialize(pages, git, 1);
            assertFalse(pages.apply(load));
            assertEquals(List.of("New head"), subjects(pages)); //$NON-NLS-1$
        }
    }

    @Test
    public void finishingAnAbandonedLoadAllowsRetryWithoutLosingCommits() throws Exception
    {
        try (Git git = repository(); HistoryPages pages = new HistoryPages())
        {
            commit(git, "Base"); //$NON-NLS-1$
            commit(git, "Latest"); //$NON-NLS-1$
            initialize(pages, git, 1);
            assertNotNull(pages.beginLoad(git.getRepository(), 1));
            pages.finishLoad();
            var retry = pages.beginLoad(git.getRepository(), 1);
            retry.schedule();
            retry.join();
            assertTrue(pages.apply(retry));
            assertEquals(List.of("Latest", "Base"), subjects(pages)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void revealWaitsForFiveFollowingCommitsOrTheEndOfHistory()
    {
        try (HistoryPages pages = new HistoryPages())
        {
            pages.replace(null, "state", null, page(6, true)); //$NON-NLS-1$
            pages.requestReveal("hash-0"); //$NON-NLS-1$
            assertEquals(RevealAction.SELECT, pages.nextReveal(null));
            assertNull(pages.revealHash());
            pages.requestReveal("hash-1"); //$NON-NLS-1$
            assertEquals(RevealAction.LOAD_MORE, pages.nextReveal(null));
            assertEquals("hash-1", pages.revealHash()); //$NON-NLS-1$
            pages.replace(null, "state", null, page(6, false)); //$NON-NLS-1$
            assertEquals(RevealAction.SELECT, pages.nextReveal(null));
            assertNull(pages.revealHash());
        }
    }

    @Test
    public void missingCommitLoadsMoreAndClearsTheRequestAtTheEnd()
    {
        try (HistoryPages pages = new HistoryPages())
        {
            pages.replace(null, "state", null, page(1, true)); //$NON-NLS-1$
            pages.requestReveal("missing"); //$NON-NLS-1$
            assertEquals(RevealAction.LOAD_MORE, pages.nextReveal(null));
            pages.replace(null, "state", null, page(1, false)); //$NON-NLS-1$
            assertEquals(RevealAction.NONE, pages.nextReveal(null));
            assertNull(pages.revealHash());
        }
    }

    @Test
    public void revealWithAnotherSearchHeadWaitsWithoutSelectingOrLoading() throws Exception
    {
        try (Git git = repository(); HistoryPages pages = new HistoryPages())
        {
            String original = commit(git, "Base"); //$NON-NLS-1$
            var originalHead = git.getRepository().resolve(Constants.HEAD);
            commit(git, "Latest"); //$NON-NLS-1$
            initialize(pages, git, 2);
            pages.requestReveal(original);
            assertEquals(RevealAction.NONE, pages.nextReveal(originalHead));
            assertEquals(original, pages.revealHash());
        }
    }

    @Test
    public void clearResetsPagesLoadingAndPendingReveal() throws Exception
    {
        try (Git git = repository(); HistoryPages pages = new HistoryPages())
        {
            commit(git, "Base"); //$NON-NLS-1$
            commit(git, "Latest"); //$NON-NLS-1$
            initialize(pages, git, 1);
            assertNotNull(pages.beginLoad(git.getRepository(), 1));
            pages.requestReveal("pending"); //$NON-NLS-1$
            pages.clear();
            assertNull(pages.head());
            assertNull(pages.stateKey());
            assertTrue(pages.entries().isEmpty());
            assertFalse(pages.hasMore());
            assertNull(pages.revealHash());
            assertNull(pages.beginLoad(git.getRepository(), 1));
            initialize(pages, git, 1);
            assertNotNull(pages.beginLoad(git.getRepository(), 1));
        }
    }

    private static RecentHistory.Page page(int count, boolean hasMore)
    {
        return new RecentHistory.Page(IntStream.range(0, count).mapToObj(i ->
            new RecentHistory.Entry(null, "hash-" + i, "subject", "message", "author", 0, List.of())).toList(), hasMore); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static void initialize(HistoryPages pages, Git git, int count) throws Exception
    {
        var repository = git.getRepository();
        var reader = RecentHistory.open(repository);
        pages.replace(repository.resolve(Constants.HEAD), RecentHistory.stateKey(repository), reader,
            reader.readNext(count, new NullProgressMonitor()));
    }

    private static List<String> subjects(HistoryPages pages)
    {
        return pages.entries().stream().map(RecentHistory.Entry::subject).toList();
    }

    private static Git repository() throws Exception
    {
        return Git.init().setDirectory(Files.createTempDirectory("gitflow-history-pages-").toFile()).call(); //$NON-NLS-1$
    }

    private static String commit(Git git, String message) throws Exception
    {
        return git.commit().setAllowEmpty(true).setMessage(message).setAuthor("Author", "author@example.org") //$NON-NLS-1$ //$NON-NLS-2$
            .setCommitter("Author", "author@example.org").call().name(); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
