package dev.edt.gitflow.ui.views;

import java.io.IOException;
import java.util.Objects;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import dev.edt.gitflow.core.RecentHistory;
import dev.edt.gitflow.ui.handlers.Messages;

public final class HistorySearchSession
{
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$
    private final Repository repository;
    private final String branch;
    private final ObjectId head;
    private final String query;
    private RecentHistory.SearchReader reader;

    public HistorySearchSession(Repository repository, String branch, ObjectId head, String query)
    {
        this.repository = repository;
        this.branch = branch;
        this.head = head;
        this.query = query;
    }

    public Repository repository()
    {
        return repository;
    }

    public ObjectId head()
    {
        return head;
    }

    public String query()
    {
        return query;
    }

    public boolean matches(Repository selected)
    {
        try
        {
            return selected != null && selected.getDirectory().equals(repository.getDirectory())
                && Objects.equals(branch, repository.getBranch())
                && Objects.equals(head, repository.resolve(Constants.HEAD));
        }
        catch (IOException e)
        {
            return false;
        }
    }

    public String findNext(IProgressMonitor monitor) throws IOException
    {
        if (monitor.isCanceled())
            throw new OperationCanceledException();
        if (reader == null)
            reader = RecentHistory.openSearch(repository, head);
        return reader.findNext(query, monitor);
    }

    public SearchJob newJob()
    {
        return new SearchJob();
    }

    public void closeReader()
    {
        RecentHistory.SearchReader opened = reader;
        reader = null;
        if (opened == null)
            return;
        Job closeJob = new Job("Закрыть поиск по истории коммитов") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                opened.close();
                return Status.OK_STATUS;
            }
        };
        closeJob.setSystem(true);
        closeJob.schedule();
    }

    public final class SearchJob extends Job
    {
        private String match;
        private Exception failure;

        private SearchJob()
        {
            super(Messages.get("historySearchRunning")); //$NON-NLS-1$
            setSystem(true);
        }

        public String match()
        {
            return match;
        }

        public Exception failure()
        {
            return failure;
        }

        @Override
        protected IStatus run(IProgressMonitor monitor)
        {
            try
            {
                match = findNext(monitor);
                return Status.OK_STATUS;
            }
            catch (OperationCanceledException e)
            {
                return Status.CANCEL_STATUS;
            }
            catch (Exception e)
            {
                failure = e;
                return new Status(IStatus.ERROR, PLUGIN_ID, e.getMessage(), e);
            }
        }
    }
}
