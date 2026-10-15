package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Test;

import dev.edt.gitflow.core.OperationResult;
import dev.edt.gitflow.core.OperationResult.Kind;
import dev.edt.gitflow.core.WorkingChanges;
import dev.edt.gitflow.core.WorkingChanges.FileChange;

/** Opt-in benchmark: mvn verify -Dtest=WorkingChangesBenchmark. Timings are not assertions. */
public class WorkingChangesBenchmark
{
    @Test
    public void compareBatchSizesWithTheSameIndexAndWorkingTree() throws Exception
    {
        int[] sizes = {32, 128, 512, Integer.MAX_VALUE};
        try (Fixture warmup = new Fixture(120))
        {
            for (int size : sizes)
                measure(warmup, size);
        }
        System.out.println("STAGE_ENV,java=" + System.getProperty("java.version") + ",os=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + System.getProperty("os.name") + ",arch=" + System.getProperty("os.arch")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (int count : new int[] {800, 1000, 5000})
        {
            try (Fixture fixture = new Fixture(count))
            {
                // Rotate the order so each strategy runs first in one of the rounds.
                for (int round = 0; round < 4; round++)
                {
                    for (int step = 0; step < sizes.length; step++)
                    {
                        int size = sizes[(round + step) % sizes.length];
                        long millis = measure(fixture, size);
                        System.out.printf("STAGE_BENCH,%d,%s,%d,%d%n", count, //$NON-NLS-1$
                            size == Integer.MAX_VALUE ? "current" : Integer.toString(size), round + 1, millis); //$NON-NLS-1$
                    }
                }
            }
        }
    }

    @Test
    public void currentPreparationFinishesSelectionBeforeStoppingAndKeepsOtherChanges() throws Exception
    {
        try (Fixture fixture = new Fixture(1000))
        {
            fixture.restoreIndex();
            NullProgressMonitor monitor = new NullProgressMonitor()
            {
                @Override
                public void subTask(String name)
                {
                    setCanceled(true);
                }
            };
            assertEquals(Kind.CANCELLED,
                WorkingChanges.stage(fixture.git.getRepository(), fixture.selection, monitor).kind());
            fixture.checkPrepared();
            System.out.println("STAGE_CANCEL,selected=1000,prepared=1000,partial-and-untracked-preserved=true"); //$NON-NLS-1$
        }
    }

    private static long measure(Fixture fixture, int batchSize) throws Exception
    {
        fixture.restoreIndex();
        long start = System.nanoTime();
        OperationResult result;
        if (batchSize == Integer.MAX_VALUE)
            result = WorkingChanges.stage(fixture.git.getRepository(), fixture.selection, new NullProgressMonitor());
        else
        {
            result = null;
            for (int offset = 0; offset < fixture.selection.size(); offset += batchSize)
            {
                int end = (int) Math.min((long) offset + batchSize, fixture.selection.size());
                result = WorkingChanges.stage(fixture.git.getRepository(), fixture.selection.subList(offset, end));
                if (!result.succeeded())
                    break;
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(result.message(), result.succeeded());
        fixture.checkPrepared();
        return millis;
    }

    private static final class Fixture implements AutoCloseable
    {
        private final Path work;
        private final Git git;
        private final List<FileChange> selection = new ArrayList<>();
        private final byte[] originalIndex;
        private final ObjectId head;

        Fixture(int count) throws Exception
        {
            work = Files.createTempDirectory("gitflow-stage-bench-"); //$NON-NLS-1$
            git = Git.init().setDirectory(work.toFile()).call();
            for (int i = 0; i < count; i++)
            {
                String path = "src/CommonModules/Module" + String.format("%05d", i) + "/Ext/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                String state = switch (i % 3) { case 0 -> "M"; case 1 -> "D"; default -> "U"; }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                selection.add(new FileChange(path, state));
                if (!"U".equals(state)) //$NON-NLS-1$
                {
                    Path file = work.resolve(path);
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, "// Base module " + i + "\n" + "// data\n".repeat(250)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            }
            Files.writeString(work.resolve("partial.txt"), "base\n"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern(".").call(); //$NON-NLS-1$
            head = git.commit().setMessage("Base").setAuthor("Author", "author@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .setCommitter("Author", "author@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(work.resolve("partial.txt"), "staged\n"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("partial.txt").call(); //$NON-NLS-1$
            Files.writeString(work.resolve("partial.txt"), "unstaged\n"); //$NON-NLS-1$ //$NON-NLS-2$
            for (FileChange change : selection)
            {
                Path file = work.resolve(change.path());
                if ("D".equals(change.state())) //$NON-NLS-1$
                    Files.delete(file);
                else
                {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, "// Changed module\n" + "// data\n".repeat(250)); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            Files.writeString(work.resolve("later.txt"), "not selected\n"); //$NON-NLS-1$ //$NON-NLS-2$
            originalIndex = Files.readAllBytes(git.getRepository().getIndexFile().toPath());
        }

        void restoreIndex() throws Exception
        {
            Files.write(git.getRepository().getIndexFile().toPath(), originalIndex);
        }

        void checkPrepared() throws Exception
        {
            var status = git.status().call();
            for (String state : List.of("M", "D", "U")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                Set<String> expected = new HashSet<>();
                selection.stream().filter(file -> state.equals(file.state())).map(FileChange::path).forEach(expected::add);
                if ("M".equals(state)) //$NON-NLS-1$
                    expected.add("partial.txt"); //$NON-NLS-1$
                assertEquals(expected, switch (state) {
                    case "M" -> status.getChanged(); //$NON-NLS-1$
                    case "D" -> status.getRemoved(); //$NON-NLS-1$
                    default -> status.getAdded();
                });
            }
            assertEquals(Set.of("partial.txt"), status.getModified()); //$NON-NLS-1$
            assertEquals(Set.of("later.txt"), status.getUntracked()); //$NON-NLS-1$
            assertTrue(status.getMissing().isEmpty());
            assertTrue(status.getConflicting().isEmpty());
            checkPreservedChanges();
        }

        void checkPreservedChanges() throws Exception
        {
            var repository = git.getRepository();
            var status = git.status().call();
            assertTrue(status.getUntracked().contains("later.txt")); //$NON-NLS-1$
            assertEquals("unstaged\n", Files.readString(work.resolve("partial.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("staged\n", new String(repository.open(repository.readDirCache() //$NON-NLS-1$
                .getEntry("partial.txt").getObjectId()).getBytes(), StandardCharsets.UTF_8)); //$NON-NLS-1$
            assertEquals(head, repository.resolve(Constants.HEAD));
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
