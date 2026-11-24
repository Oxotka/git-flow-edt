package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;

import dev.edt.gitflow.core.BranchOperations;
import dev.edt.gitflow.core.OperationResult;
import dev.edt.gitflow.core.WorkingChanges;

public class BranchOperationsTest
{
    @Test
    public void removesMergedLocalBranchesAndPreservesServerRefs() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-cleanup-");
        try (Git git = TestRepository.init(work.toFile(), "main"))
        {
            commit(git, work, "base");
            var repository = git.getRepository();
            String current = repository.getBranch();
            git.branchCreate().setName("gone").call();
            git.tag().setName("gone").call();
            git.branchCreate().setName("local").call();
            git.branchCreate().setName("tracked").call();
            repository.updateRef("refs/remotes/origin/tracked").link("refs/heads/tracked");
            git.checkout().setCreateBranch(true).setName("unmerged").call();
            commit(git, work, "unique");
            git.checkout().setName(current).call();
            git.checkout().setCreateBranch(true).setName("merged").call();
            commit(git, work, "merged in develop only");
            var target = repository.updateRef("refs/remotes/origin/release/2026");
            target.setNewObjectId(repository.resolve("HEAD"));
            target.update();
            repository.updateRef("refs/remotes/origin/HEAD").link("refs/remotes/origin/release/2026");
            ObjectId mergedTip = repository.resolve("HEAD");
            var tracking = repository.updateRef("refs/remotes/origin/merged");
            tracking.setNewObjectId(mergedTip);
            tracking.update();
            git.checkout().setName(current).call();

            var config = repository.getConfig();
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            for (String name : List.of("gone", "tracked", "unmerged", "merged", current))
            {
                config.setString("branch", name, "remote", "origin");
                config.setString("branch", name, "merge", "refs/heads/" + name);
            }
            assertTrue(BranchOperations.isUpstreamGone(repository, "gone"));
            assertFalse(BranchOperations.isUpstreamGone(repository, "local"));
            assertFalse(BranchOperations.isUpstreamGone(repository, "tracked"));
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(repository))
            {
                var branch = repository.exactRef("refs/heads/merged");
                assertEquals("refs/remotes/origin/release/2026", BranchOperations.mergedTarget(walk, branch,
                    BranchOperations.mergeTargets(repository, null)).getName());
                assertEquals("refs/remotes/origin/release/2026", BranchOperations.mergedTarget(walk, branch,
                    BranchOperations.mergeTargets(repository, "refs/remotes/origin/release/2026")).getName());
                assertNull(BranchOperations.mergedTarget(walk, branch,
                    BranchOperations.mergeTargets(repository, "refs/heads/" + current)));
            }


            var result = BranchOperations.deleteInactiveBranches(repository,
                List.of("gone", "unmerged", "merged", current), null, new NullProgressMonitor());
            assertEquals(OperationResult.Kind.ERROR, result.kind());
            assertNull(repository.exactRef("refs/heads/gone"));
            assertNull(repository.exactRef("refs/heads/merged"));
            assertEquals(mergedTip, repository.resolve("refs/remotes/origin/merged"));
            assertNotNull(repository.exactRef("refs/tags/gone"));
            for (String name : List.of("local", "tracked", "unmerged", current))
                assertNotNull(name, repository.exactRef("refs/heads/" + name));
        }
    }

    @Test
    public void checkoutMergesNonoverlappingUnstagedEditsInSameFile() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-checkout-same-file-"); //$NON-NLS-1$
        try (Git git = TestRepository.init(work.toFile()))
        {
            String base = "first\nsecond\nthird\nfourth\nfifth\nsixth\n"; //$NON-NLS-1$
            commit(git, work, base);
            String original = git.getRepository().getBranch();
            git.checkout().setCreateBranch(true).setName("target").call(); //$NON-NLS-1$
            commit(git, work, base.replace("sixth", "target sixth")); //$NON-NLS-1$ //$NON-NLS-2$
            git.checkout().setName(original).call();
            Files.writeString(work.resolve("file.txt"), base.replace("first", "local first")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            OperationResult result = BranchOperations.checkout(git.getRepository(), "target", false, //$NON-NLS-1$
                new NullProgressMonitor());

            assertTrue(result.toString(), result.succeeded());
            assertEquals("target", git.getRepository().getBranch()); //$NON-NLS-1$
            assertEquals(base.replace("first", "local first").replace("sixth", "target sixth"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                Files.readString(work.resolve("file.txt"))); //$NON-NLS-1$
            assertTrue(git.status().call().getChanged().isEmpty());
            assertTrue(git.status().call().getModified().contains("file.txt")); //$NON-NLS-1$
            assertTrue(git.stashList().call().isEmpty());
        }
    }

    @Test
    public void checkoutConflictRestoresLocalFilesAndIndex() throws Exception
    {
        for (boolean prepareFile : new boolean[] {false, true})
        {
            Path work = Files.createTempDirectory("gitflow-checkout-mixed-conflict-"); //$NON-NLS-1$
            try (Git git = TestRepository.init(work.toFile()))
            {
                commit(git, work, "base\n"); //$NON-NLS-1$
                String original = git.getRepository().getBranch();
                ObjectId originalHead = git.getRepository().resolve("HEAD"); //$NON-NLS-1$
                git.checkout().setCreateBranch(true).setName("target").call(); //$NON-NLS-1$
                commit(git, work, "target\n"); //$NON-NLS-1$
                ObjectId targetHead = git.getRepository().resolve("HEAD"); //$NON-NLS-1$
                git.checkout().setName(original).call();
                if (prepareFile)
                {
                    Files.writeString(work.resolve("file.txt"), "staged\n"); //$NON-NLS-1$ //$NON-NLS-2$
                    git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
                }
                ObjectId staged = git.getRepository().readDirCache().getEntry("file.txt").getObjectId(); //$NON-NLS-1$
                Files.writeString(work.resolve("file.txt"), "unstaged\n"); //$NON-NLS-1$ //$NON-NLS-2$
                Files.writeString(work.resolve("note.txt"), "new local file\n"); //$NON-NLS-1$ //$NON-NLS-2$
                WorkingChanges before = WorkingChanges.read(git.getRepository());

                OperationResult result = BranchOperations.checkout(git.getRepository(), "target", false, //$NON-NLS-1$
                    new NullProgressMonitor());

                assertEquals(result.toString(), OperationResult.Kind.NEEDS_CHECKOUT_CLEANUP, result.kind());
                assertEquals(original, git.getRepository().getBranch());
                assertEquals(originalHead, git.getRepository().resolve("HEAD")); //$NON-NLS-1$
                assertEquals(targetHead, git.getRepository().resolve("refs/heads/target")); //$NON-NLS-1$
                assertEquals("unstaged\n", Files.readString(work.resolve("file.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals("new local file\n", Files.readString(work.resolve("note.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(staged, git.getRepository().readDirCache().getEntry("file.txt").getObjectId()); //$NON-NLS-1$
                assertEquals(before, WorkingChanges.read(git.getRepository()));
                assertTrue(git.stashList().call().isEmpty());
            }
        }
    }

    @Test
    public void cleanCheckoutDoesNotCreateStash() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-clean-checkout-"); //$NON-NLS-1$
        try (Git git = TestRepository.init(work.toFile()))
        {
            Files.writeString(work.resolve("file.txt"), "base"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern(".").call(); //$NON-NLS-1$
            git.commit().setMessage("base").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            git.branchCreate().setName("feature").call(); //$NON-NLS-1$
            assertTrue(BranchOperations.checkout(git.getRepository(), "feature", false, //$NON-NLS-1$
                new NullProgressMonitor()).succeeded());
            assertEquals("feature", git.getRepository().getBranch()); //$NON-NLS-1$
            assertTrue(git.stashList().call().isEmpty());
            assertTrue(git.status().call().isClean());
        }
    }

    @Test
    public void checkoutKeepsStagedAndUnstagedFilesInTheirGroups() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-switch-index-"); //$NON-NLS-1$
        try (Git git = TestRepository.init(work.toFile()))
        {
            commit(git, work, "base\n"); //$NON-NLS-1$
            Files.writeString(work.resolve("file.txt"), "staged\n"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
            Files.writeString(work.resolve("untracked.txt"), "new\n"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(BranchOperations.checkout(git.getRepository(), "feature", true, //$NON-NLS-1$
                new NullProgressMonitor()).succeeded());
            WorkingChanges changes = WorkingChanges.read(git.getRepository());
            assertEquals("file.txt", changes.staged().get(0).path()); //$NON-NLS-1$
            assertEquals("untracked.txt", changes.unstaged().get(0).path()); //$NON-NLS-1$
            assertTrue(git.stashList().call().isEmpty());
        }
    }

    @Test
    public void checkoutTagDetachesHeadAndRestoresDirtyTree() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-tag-checkout-"); //$NON-NLS-1$
        try (Git git = TestRepository.init(directory.toFile()))
        {
            commit(git, directory, "base\n"); //$NON-NLS-1$
            git.tag().setName("v1.0").call(); //$NON-NLS-1$
            ObjectId tagCommit = git.getRepository().resolve("refs/tags/v1.0^{commit}"); //$NON-NLS-1$
            Files.writeString(directory.resolve("file.txt"), "dirty\n"); //$NON-NLS-1$ //$NON-NLS-2$

            OperationResult result = BranchOperations.checkout(git.getRepository(), "refs/tags/v1.0", false, //$NON-NLS-1$
                new NullProgressMonitor());

            assertTrue(result.toString(), result.succeeded());
            assertEquals(tagCommit, git.getRepository().resolve("HEAD")); //$NON-NLS-1$
            assertFalse(git.getRepository().getFullBranch().startsWith("refs/heads/")); //$NON-NLS-1$
            assertEquals("dirty\n", Files.readString(directory.resolve("file.txt"))); //$NON-NLS-1$
            assertTrue(git.stashList().call().isEmpty());
        }
    }

    @Test
    public void checkoutRestoresDirtyTree() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-branch-"); //$NON-NLS-1$
        try (Git git = TestRepository.init(directory.toFile()))
        {
            commit(git, directory, "base\n"); //$NON-NLS-1$
            String original = git.getRepository().getBranch();
            Files.writeString(directory.resolve("file.txt"), "dirty\n"); //$NON-NLS-1$ //$NON-NLS-2$
            OperationResult switched = BranchOperations.checkout(git.getRepository(), "feature", true, //$NON-NLS-1$
                new NullProgressMonitor());
            assertTrue(switched.toString(), switched.succeeded());
            assertEquals("dirty\n", Files.readString(directory.resolve("file.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(git.stashList().call().isEmpty());
            assertTrue(BranchOperations.checkout(git.getRepository(), original, false,
                new NullProgressMonitor()).succeeded());
        }
    }

    @Test
    public void checkoutStashConflictRestoresOriginalStateBeforeCleanupPrompt() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-checkout-conflict-"); //$NON-NLS-1$
        try (Git git = TestRepository.init(directory.toFile()))
        {
            commit(git, directory, "base\n"); //$NON-NLS-1$
            Files.writeString(directory.resolve("other.txt"), "base\n"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("other.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("other base").call(); //$NON-NLS-1$
            String original = git.getRepository().getBranch();
            git.checkout().setCreateBranch(true).setName("other").call(); //$NON-NLS-1$
            commit(git, directory, "other\n"); //$NON-NLS-1$
            git.checkout().setName(original).call();
            Files.writeString(directory.resolve("file.txt"), "local\n"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(directory.resolve("other.txt"), "local other\n"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").addFilepattern("other.txt").call(); //$NON-NLS-1$ //$NON-NLS-2$
            OperationResult result = BranchOperations.checkout(git.getRepository(), "other", false, //$NON-NLS-1$
                new NullProgressMonitor());
            assertEquals(OperationResult.Kind.NEEDS_CHECKOUT_CLEANUP, result.kind());
            assertEquals(original, git.getRepository().getBranch());
            assertEquals("local\n", Files.readString(directory.resolve("file.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("local other\n", Files.readString(directory.resolve("other.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(2, WorkingChanges.read(git.getRepository()).staged().size());
            assertTrue(git.stashList().call().isEmpty());
            assertTrue(result.affectedPaths().contains("file.txt")); //$NON-NLS-1$
            assertFalse(result.affectedPaths().contains("other.txt")); //$NON-NLS-1$
        }
    }

    @Test
    public void checkoutReportsBothFilesWhenBothConflict() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-checkout-two-conflicts-"); //$NON-NLS-1$
        try (Git git = TestRepository.init(directory.toFile()))
        {
            commit(git, directory, "base\n"); //$NON-NLS-1$
            Files.writeString(directory.resolve("other.txt"), "base\n"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("other.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("other base").call(); //$NON-NLS-1$
            String original = git.getRepository().getBranch();
            git.checkout().setCreateBranch(true).setName("other").call(); //$NON-NLS-1$
            Files.writeString(directory.resolve("file.txt"), "target\n"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(directory.resolve("other.txt"), "target other\n"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").addFilepattern("other.txt").call(); //$NON-NLS-1$ //$NON-NLS-2$
            git.commit().setMessage("target").call(); //$NON-NLS-1$
            git.checkout().setName(original).call();
            Files.writeString(directory.resolve("file.txt"), "local\n"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(directory.resolve("other.txt"), "local other\n"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").addFilepattern("other.txt").call(); //$NON-NLS-1$ //$NON-NLS-2$

            OperationResult result = BranchOperations.checkout(git.getRepository(), "other", false, //$NON-NLS-1$
                new NullProgressMonitor());

            assertEquals(OperationResult.Kind.NEEDS_CHECKOUT_CLEANUP, result.kind());
            assertEquals(original, git.getRepository().getBranch());
            assertEquals("local\n", Files.readString(directory.resolve("file.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("local other\n", Files.readString(directory.resolve("other.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(2, WorkingChanges.read(git.getRepository()).staged().size());
            assertTrue(result.affectedPaths().contains("file.txt")); //$NON-NLS-1$
            assertTrue(result.affectedPaths().contains("other.txt")); //$NON-NLS-1$
        }
    }

    @Test
    public void remoteSelectionCreatesTrackingBranchAndRestoresDirtyTree() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-remote-checkout-"); //$NON-NLS-1$
        Path bare = directory.resolve("origin.git"); //$NON-NLS-1$
        Path sourceDir = directory.resolve("source"); //$NON-NLS-1$
        Path work = directory.resolve("work"); //$NON-NLS-1$
        try (Git origin = Git.init().setBare(true).setDirectory(bare.toFile()).call();
             Git source = TestRepository.init(sourceDir.toFile()))
        {
            commit(source, sourceDir, "base\n"); //$NON-NLS-1$
            source.remoteAdd().setName("origin").setUri(new URIish(bare.toUri().toString())).call(); //$NON-NLS-1$
            source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
            source.checkout().setCreateBranch(true).setName("feature/topic").call(); //$NON-NLS-1$
            commit(source, sourceDir, "feature\n"); //$NON-NLS-1$
            source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
            try (Git local = Git.cloneRepository().setURI(bare.toUri().toString())
                .setDirectory(work.toFile()).call())
            {
                Files.writeString(work.resolve("note.txt"), "dirty"); //$NON-NLS-1$ //$NON-NLS-2$
                OperationResult result = BranchOperations.checkout(local.getRepository(), "feature/topic", true, //$NON-NLS-1$
                    "refs/remotes/origin/feature/topic", new NullProgressMonitor()); //$NON-NLS-1$
                assertTrue(result.toString(), result.succeeded());
                assertEquals("feature/topic", local.getRepository().getBranch()); //$NON-NLS-1$
                assertEquals("origin", local.getRepository().getConfig().getString( //$NON-NLS-1$
                    "branch", "feature/topic", "remote")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertEquals("dirty", Files.readString(work.resolve("note.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue(local.stashList().call().isEmpty());
            }
        }
    }

    @Test
    public void checkoutReportsChangedPathsForTargetedRefresh() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-checkout-paths-"); //$NON-NLS-1$
        try (Git git = TestRepository.init(work.toFile()))
        {
            commit(git, work, "base\n"); //$NON-NLS-1$
            String original = git.getRepository().getBranch();
            git.checkout().setCreateBranch(true).setName("target").call(); //$NON-NLS-1$
            commit(git, work, "target\n"); //$NON-NLS-1$
            Files.writeString(work.resolve("extra.txt"), "extra\n"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("extra.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("extra").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            git.checkout().setName(original).call();

            OperationResult result = BranchOperations.checkout(git.getRepository(), "target", false, //$NON-NLS-1$
                new NullProgressMonitor());

            assertTrue(result.toString(), result.succeeded());
            assertTrue(result.affectedPaths().toString(), result.affectedPaths().contains("file.txt")); //$NON-NLS-1$
            assertTrue(result.affectedPaths().toString(), result.affectedPaths().contains("extra.txt")); //$NON-NLS-1$
        }
    }

    private static void commit(Git git, Path directory, String contents) throws Exception
    {
        Files.writeString(directory.resolve("file.txt"), contents); //$NON-NLS-1$
        git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
        git.commit().setMessage(contents).setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$
            .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
