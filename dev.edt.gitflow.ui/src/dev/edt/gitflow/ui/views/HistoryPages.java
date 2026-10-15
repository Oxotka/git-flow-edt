package dev.edt.gitflow.ui.views;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

/** Displayed history state. Accessed on the UI thread; PageJob reads in the background. */
public final class HistoryPages implements AutoCloseable
{
    public enum RevealAction { NONE, LOAD_MORE, SELECT }

    private ObjectId head;
    private String stateKey;
    private RecentHistory.Reader reader;
    private final List<RecentHistory.Entry> entries = new ArrayList<>();
    private boolean hasMore;
    private boolean loading;
    private String revealHash;

    public ObjectId head()
    {
        return head;
    }

    public String stateKey()
    {
        return stateKey;
    }

    public List<RecentHistory.Entry> entries()
    {
        return Collections.unmodifiableList(entries);
    }

    public boolean hasMore()
    {
        return hasMore;
    }

    public void replace(ObjectId head, String stateKey, RecentHistory.Reader reader, RecentHistory.Page first)
    {
        close();
        this.head = head;
        this.stateKey = stateKey;
        this.reader = reader;
        entries.clear();
        entries.addAll(first.entries());
        hasMore = first.hasMore();
        loading = false;
    }

    public void clear()
    {
        replace(null, null, null, new RecentHistory.Page(List.of(), false));
        cancelReveal();
    }

    public PageJob beginLoad(Repository repository, int limit)
    {
        if (repository == null || head == null || reader == null || loading || !hasMore)
            return null;
        loading = true;
        return new PageJob(repository, reader, head, stateKey, limit);
    }

    public boolean apply(PageJob load)
    {
        loading = false;
        if (!Objects.equals(head, load.expectedHead) || !Objects.equals(load.currentHead, load.expectedHead)
            || !Objects.equals(stateKey, load.expectedState) || !Objects.equals(load.currentState, load.expectedState))
            return false;
        entries.addAll(load.page.entries());
        hasMore = load.page.hasMore();
        return true;
    }

    public void finishLoad()
    {
        loading = false;
    }

    public String revealHash()
    {
        return revealHash;
    }

    public void requestReveal(String hash)
    {
        revealHash = hash;
    }

    public void cancelReveal()
    {
        revealHash = null;
    }

    public RevealAction nextReveal(ObjectId searchHead)
    {
        if (revealHash == null || !Objects.equals(head, searchHead))
            return RevealAction.NONE;
        int index = -1;
        for (int i = 0; i < entries.size(); i++)
        {
            if (entries.get(i).hash().equals(revealHash))
            {
                index = i;
                break;
            }
        }
        if (index >= 0 && (entries.size() - index - 1 >= 5 || !hasMore))
        {
            cancelReveal();
            return RevealAction.SELECT;
        }
        if (hasMore)
            return RevealAction.LOAD_MORE;
        if (index < 0)
            cancelReveal();
        return RevealAction.NONE;
    }

    @Override
    public void close()
    {
        closeReader(reader);
        reader = null;
    }

    public static void closeReader(RecentHistory.Reader reader)
    {
        if (reader == null)
            return;
        Job closeJob = new Job("Закрыть историю коммитов") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                reader.close();
                return Status.OK_STATUS;
            }
        };
        closeJob.setSystem(true);
        closeJob.schedule();
    }

    public static final class PageJob extends Job
    {
        private final Repository repository;
        private final RecentHistory.Reader reader;
        private final ObjectId expectedHead;
        private final String expectedState;
        private final int limit;
        private RecentHistory.Page page;
        private ObjectId currentHead;
        private String currentState;
        private Exception failure;

        private PageJob(Repository repository, RecentHistory.Reader reader, ObjectId head, String state, int limit)
        {
            super("Загрузить следующие коммиты"); //$NON-NLS-1$
            this.repository = repository;
            this.reader = reader;
            expectedHead = head;
            expectedState = state;
            this.limit = limit;
        }

        public RecentHistory.Page page()
        {
            return page;
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
                page = reader.readNext(limit, monitor);
                currentHead = repository.resolve(Constants.HEAD);
                currentState = RecentHistory.stateKey(repository);
                return Status.OK_STATUS;
            }
            catch (OperationCanceledException e)
            {
                return Status.CANCEL_STATUS;
            }
            catch (Exception e)
            {
                failure = e;
                return new Status(IStatus.ERROR, "dev.edt.gitflow.ui", e.getMessage(), e); //$NON-NLS-1$
            }
        }
    }
}
