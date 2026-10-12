package dev.edt.gitflow.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.revplot.PlotCommit;
import org.eclipse.jgit.revplot.PlotCommitList;
import org.eclipse.jgit.revplot.PlotLane;
import org.eclipse.jgit.revplot.PlotWalk;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

public final class RecentHistory
{
    public record Page(List<Entry> entries, boolean hasMore)
    {
    }

    public record Entry(PlotCommit<PlotLane> plot, String hash, String subject,
        String message, String author, int commitTime, List<String> labels)
    {
    }

    private RecentHistory()
    {
    }

    public static Reader open(Repository repository) throws IOException
    {
        return new Reader(repository);
    }

    public static SearchReader openSearch(Repository repository) throws IOException
    {
        return new SearchReader(repository, repository.resolve(Constants.HEAD));
    }

    public static SearchReader openSearch(Repository repository, ObjectId head) throws IOException
    {
        return new SearchReader(repository, head);
    }

    public static final class SearchReader implements AutoCloseable
    {
        private final ObjectId head;
        private final CancellableRevWalk walk;
        private boolean closed;

        private SearchReader(Repository repository, ObjectId head) throws IOException
        {
            this.head = head;
            walk = new CancellableRevWalk(repository);
            try
            {
                resetWalk();
            }
            catch (IOException | RuntimeException e)
            {
                walk.close();
                throw e;
            }
        }

        public synchronized String findNext(String query, IProgressMonitor monitor) throws IOException
        {
            if (closed || head == null || query == null || query.isBlank())
                return null;
            String normalized = query.strip().toLowerCase(Locale.ROOT);
            walk.setMonitor(monitor == null ? new NullProgressMonitor() : monitor);
            try
            {
                org.eclipse.jgit.revwalk.RevCommit commit;
                while ((commit = walk.next()) != null)
                {
                    walk.parseBody(commit);
                    if (matches(commit, normalized))
                        return commit.name();
                }
                resetWalk();
                return null;
            }
            catch (OperationCanceledException e)
            {
                resetWalk();
                throw e;
            }
            finally
            {
                walk.setMonitor(new NullProgressMonitor());
            }
        }

        public synchronized void restart() throws IOException
        {
            if (!closed)
                resetWalk();
        }

        private void resetWalk() throws IOException
        {
            walk.reset();
            walk.sort(RevSort.TOPO, true);
            if (head != null)
                walk.markStart(walk.parseCommit(head));
        }

        private static boolean matches(org.eclipse.jgit.revwalk.RevCommit commit, String query)
        {
            String message = commit.getFullMessage();
            String author = commit.getAuthorIdent() == null ? "" : commit.getAuthorIdent().getName(); //$NON-NLS-1$
            return commit.name().startsWith(query)
                || message.toLowerCase(Locale.ROOT).contains(query)
                || author.toLowerCase(Locale.ROOT).contains(query);
        }

        @Override
        public synchronized void close()
        {
            if (!closed)
            {
                closed = true;
                walk.close();
            }
        }
    }

    public static final class Reader implements AutoCloseable
    {
        private final CancellablePlotWalk walk;
        private final PlotCommitList<PlotLane> commits = new PlotCommitList<>();
        private final Map<String, List<String>> labelsByCommit = new HashMap<>();
        private int position;
        private boolean closed;
        private boolean exhausted;

        private Reader(Repository repository) throws IOException
        {
            walk = new CancellablePlotWalk(repository);
            try
            {
                walk.sort(RevSort.TOPO, true);
                List<Ref> refs = importantRefs(repository);
                walk.addAdditionalRefs(refs);
                ObjectId head = repository.resolve(Constants.HEAD);
                if (head != null)
                {
                    walk.markStart(walk.parseCommit(head));
                    commits.source(walk);
                }
                for (Ref ref : refs)
                {
                    Ref peeled = repository.getRefDatabase().peel(ref);
                    ObjectId id = peeled.getPeeledObjectId() == null
                        ? peeled.getObjectId() : peeled.getPeeledObjectId();
                    if (id != null)
                        labelsByCommit.computeIfAbsent(id.name(), key -> new ArrayList<>())
                            .add(Repository.shortenRefName(ref.getName()));
                }
                exhausted = head == null;
            }
            catch (IOException | RuntimeException e)
            {
                walk.close();
                throw e;
            }
        }

        public synchronized Page readNext(int limit) throws IOException
        {
            return readNext(limit, new NullProgressMonitor());
        }

        public synchronized Page readNext(int limit, IProgressMonitor monitor) throws IOException
        {
            if (closed || exhausted || limit <= 0)
                return new Page(List.of(), false);
            walk.setMonitor(monitor);
            try
            {
                commits.fillTo(position + limit);
                int available = commits.size() - position;
                int count = Math.min(limit, Math.max(0, available));
                List<Entry> page = new ArrayList<>(count);
                for (int i = 0; i < count; i++)
                {
                    walk.checkCanceled();
                    page.add(entry(commits.get(position + i), walk, labelsByCommit));
                }
                position += count;
                boolean hasMore = available > limit;
                if (!hasMore)
                {
                    exhausted = true;
                    close();
                }
                return new Page(List.copyOf(page), hasMore);
            }
            finally
            {
                walk.setMonitor(new NullProgressMonitor());
            }
        }

        @Override
        public synchronized void close()
        {
            if (!closed)
            {
                closed = true;
                walk.close();
            }
        }
    }

    private static final class CancellablePlotWalk extends PlotWalk
    {
        private IProgressMonitor monitor = new NullProgressMonitor();

        CancellablePlotWalk(Repository repository)
        {
            super(repository);
        }

        void setMonitor(IProgressMonitor monitor)
        {
            this.monitor = monitor;
        }

        void checkCanceled()
        {
            if (monitor.isCanceled())
                throw new OperationCanceledException();
        }

        @Override
        public org.eclipse.jgit.revwalk.RevCommit next() throws IOException
        {
            checkCanceled();
            return super.next();
        }
    }

    private static final class CancellableRevWalk extends RevWalk
    {
        private IProgressMonitor monitor = new NullProgressMonitor();

        CancellableRevWalk(Repository repository)
        {
            super(repository);
        }

        void setMonitor(IProgressMonitor monitor)
        {
            this.monitor = monitor;
        }

        @Override
        public org.eclipse.jgit.revwalk.RevCommit next() throws IOException
        {
            if (monitor.isCanceled())
                throw new OperationCanceledException();
            return super.next();
        }
    }

    public static List<Entry> read(Repository repository, int limit) throws IOException
    {
        return read(repository, 0, limit);
    }

    public static List<Entry> read(Repository repository, int offset, int limit) throws IOException
    {
        if (offset < 0 || limit <= 0)
            return List.of();
        try (PlotWalk walk = new PlotWalk(repository))
        {
            walk.sort(RevSort.TOPO, true);
            List<Ref> refs = importantRefs(repository);
            walk.addAdditionalRefs(refs);
            ObjectId head = repository.resolve(Constants.HEAD);
            if (head == null)
                return List.of();
            walk.markStart(walk.parseCommit(head));
            PlotCommitList<PlotLane> commits = new PlotCommitList<>();
            commits.source(walk);
            commits.fillTo(offset + limit - 1);
            Map<String, List<String>> labelsByCommit = new HashMap<>();
            for (Ref ref : refs)
            {
                Ref peeled = repository.getRefDatabase().peel(ref);
                ObjectId id = peeled.getPeeledObjectId() == null ? peeled.getObjectId() : peeled.getPeeledObjectId();
                if (id != null)
                    labelsByCommit.computeIfAbsent(id.name(), key -> new ArrayList<>())
                        .add(Repository.shortenRefName(ref.getName()));
            }
            List<Entry> result = new ArrayList<>(Math.min(limit, Math.max(0, commits.size() - offset)));
            for (int i = offset; i < commits.size() && result.size() < limit; i++)
            {
                result.add(entry(commits.get(i), walk, labelsByCommit));
            }
            return List.copyOf(result);
        }
    }

    private static Entry entry(PlotCommit<PlotLane> commit, PlotWalk walk,
        Map<String, List<String>> labelsByCommit) throws IOException
    {
        walk.parseBody(commit);
        return new Entry(commit, commit.name(), commit.getShortMessage(),
            commit.getFullMessage().trim(), commit.getAuthorIdent().getName(),
            commit.getCommitTime(), labelsByCommit.getOrDefault(commit.name(), List.of()));
    }

    public static String stateKey(Repository repository) throws IOException
    {
        List<String> refs = new ArrayList<>();
        refs.add("HEAD=" + repository.getFullBranch()); //$NON-NLS-1$
        for (Ref ref : historyRefs(repository))
        {
            ObjectId id = ref.getObjectId();
            refs.add(ref.getName() + "=" + (id == null ? "" : id.name())); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return String.join("\n", refs); //$NON-NLS-1$
    }

    private static List<Ref> historyRefs(Repository repository) throws IOException
    {
        List<Ref> refs = new ArrayList<>();
        repository.getRefDatabase().getRefs(RefDatabase.ALL).values().stream()
            .filter(ref -> ref.getName().startsWith(Constants.R_HEADS)
                || ref.getName().startsWith(Constants.R_REMOTES)
                || ref.getName().startsWith(Constants.R_TAGS))
            .sorted(java.util.Comparator.comparing(Ref::getName)).forEach(refs::add);
        Ref head = repository.exactRef(Constants.HEAD);
        if (head != null)
            refs.add(head);
        return refs;
    }

    private static List<Ref> importantRefs(Repository repository) throws IOException
    {
        return historyRefs(repository).stream().filter(ref -> !ref.isSymbolic()).toList();
    }
}
