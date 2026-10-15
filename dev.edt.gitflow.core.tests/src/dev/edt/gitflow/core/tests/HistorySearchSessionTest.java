package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.Test;

import dev.edt.gitflow.ui.views.HistorySearchSession;

public class HistorySearchSessionTest
{
    @Test
    public void backgroundJobsContinueTheSearchAndWrapAfterExhaustion() throws Exception
    {
        try (Git git = repository())
        {
            String older = commit(git, "Needle older"); //$NON-NLS-1$
            commit(git, "Other"); //$NON-NLS-1$
            String newer = commit(git, "Needle newer"); //$NON-NLS-1$
            var session = session(git, "needle"); //$NON-NLS-1$
            try
            {
                assertEquals(newer, run(session));
                assertEquals(older, run(session));
                assertNull(run(session));
                assertEquals(newer, run(session));
            }
            finally
            {
                session.closeReader();
            }
        }
    }

    @Test
    public void contextMatchesAnotherHandleForTheSameRepository() throws Exception
    {
        try (Git git = repository();
            var otherHandle = new FileRepositoryBuilder().setGitDir(git.getRepository().getDirectory()).build();
            Git unrelated = repository())
        {
            commit(git, "Base"); //$NON-NLS-1$
            var session = session(git, "base"); //$NON-NLS-1$
            assertTrue(session.matches(git.getRepository()));
            assertTrue(session.matches(otherHandle));
            assertFalse(session.matches(unrelated.getRepository()));
            assertFalse(session.matches(null));
        }
    }

    @Test
    public void switchingBranchesInvalidatesTheSessionEvenWhenHeadIsUnchanged() throws Exception
    {
        try (Git git = repository())
        {
            commit(git, "Base"); //$NON-NLS-1$
            var session = session(git, "base"); //$NON-NLS-1$
            git.checkout().setCreateBranch(true).setName("other").call(); //$NON-NLS-1$
            assertFalse(session.matches(git.getRepository()));
        }
    }

    @Test
    public void movingHeadInvalidatesTheSessionButItsSearchKeepsTheOriginalSnapshot() throws Exception
    {
        try (Git git = repository())
        {
            String original = commit(git, "Needle original"); //$NON-NLS-1$
            var session = session(git, "needle"); //$NON-NLS-1$
            commit(git, "Needle later"); //$NON-NLS-1$
            try
            {
                assertFalse(session.matches(git.getRepository()));
                assertEquals(original, run(session));
                assertNull(run(session));
            }
            finally
            {
                session.closeReader();
            }
        }
    }

    @Test
    public void closingReaderAllowsANewSearchFromTheSameSnapshot() throws Exception
    {
        try (Git git = repository())
        {
            String head = commit(git, "Needle"); //$NON-NLS-1$
            var session = session(git, "needle"); //$NON-NLS-1$
            try
            {
                assertEquals(head, run(session));
                session.closeReader();
                assertEquals(head, run(session));
            }
            finally
            {
                session.closeReader();
            }
        }
    }

    @Test
    public void cancellationBeforeReadingDoesNotAdvanceTheSearch() throws Exception
    {
        try (Git git = repository())
        {
            String head = commit(git, "Needle"); //$NON-NLS-1$
            var session = session(git, "needle"); //$NON-NLS-1$
            var canceled = new NullProgressMonitor();
            canceled.setCanceled(true);
            try
            {
                assertThrows(OperationCanceledException.class, () -> session.findNext(canceled));
                assertEquals(head, run(session));
            }
            finally
            {
                session.closeReader();
            }
        }
    }

    @Test
    public void backgroundFailureIsReportedWithoutProducingAMatch() throws Exception
    {
        try (Git git = repository())
        {
            var session = new HistorySearchSession(git.getRepository(), git.getRepository().getBranch(),
                ObjectId.fromString("1111111111111111111111111111111111111111"), "needle"); //$NON-NLS-1$ //$NON-NLS-2$
            var job = session.newJob();
            job.schedule();
            job.join();
            assertEquals(IStatus.ERROR, job.getResult().getSeverity());
            assertNotNull(job.failure());
            assertNull(job.match());
            session.closeReader();
        }
    }

    private static HistorySearchSession session(Git git, String query) throws Exception
    {
        var repository = git.getRepository();
        return new HistorySearchSession(repository, repository.getBranch(), repository.resolve(Constants.HEAD), query);
    }

    private static String run(HistorySearchSession session) throws Exception
    {
        var job = session.newJob();
        job.schedule();
        job.join();
        assertTrue(job.getResult().toString(), job.getResult().isOK());
        assertNull(job.failure());
        return job.match();
    }

    private static Git repository() throws Exception
    {
        return Git.init().setDirectory(Files.createTempDirectory("gitflow-search-session-").toFile()).call(); //$NON-NLS-1$
    }

    private static String commit(Git git, String message) throws Exception
    {
        return git.commit().setAllowEmpty(true).setMessage(message).setAuthor("Author", "author@example.org") //$NON-NLS-1$ //$NON-NLS-2$
            .setCommitter("Author", "author@example.org").call().name(); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
