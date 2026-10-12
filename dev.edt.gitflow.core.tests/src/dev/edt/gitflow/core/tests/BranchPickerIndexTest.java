package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

import dev.edt.gitflow.core.BranchPickerIndex;
import dev.edt.gitflow.core.BranchPickerIndex.Group;
import dev.edt.gitflow.core.BranchPickerIndex.Ref;

public class BranchPickerIndexTest
{
    @Test
    public void filtersPreorderedRefsAndOnlyMatchingFolders()
    {
        BranchPickerIndex index = new BranchPickerIndex();
        index.addAll(List.of(
            new Ref("feature/z-fix", "refs/heads/feature/z-fix", Group.LOCAL, 20), //$NON-NLS-1$ //$NON-NLS-2$
            new Ref("feature/a-bug", "refs/heads/feature/a-bug", Group.LOCAL, 30), //$NON-NLS-1$ //$NON-NLS-2$
            new Ref("hotfix/bug-1", "refs/heads/hotfix/bug-1", Group.LOCAL, 10))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of("refs/heads/feature/a-bug", "refs/heads/hotfix/bug-1"), //$NON-NLS-1$
            index.filter(Group.LOCAL, "BUG").stream().map(Ref::ref).toList()); //$NON-NLS-1$
        assertEquals(List.of("feature", "hotfix"), //$NON-NLS-1$
            index.folderPaths(Group.LOCAL, "bug", null)); //$NON-NLS-1$
    }

    @Test
    public void groupsRemoteRefsAndKeepsPinnedFolderOrder()
    {
        BranchPickerIndex index = new BranchPickerIndex();
        index.addAll(List.of(
            new Ref("vendor/topic", "refs/remotes/vendor/topic", Group.REMOTE, 40), //$NON-NLS-1$ //$NON-NLS-2$
            new Ref("origin/team/bugfix", "refs/remotes/origin/team/bugfix", Group.REMOTE, 20), //$NON-NLS-1$ //$NON-NLS-2$
            new Ref("origin/main", "refs/remotes/origin/main", Group.REMOTE, 30))); //$NON-NLS-1$ //$NON-NLS-2$
        index.setPinnedFolders(List.of("origin/team")); //$NON-NLS-1$

        assertEquals(List.of("origin", "vendor"), index.remoteNames("")); //$NON-NLS-1$
        assertEquals(List.of("origin/team"), index.folderPaths(Group.REMOTE, "bug", "origin")); //$NON-NLS-1$
        assertEquals(List.of("refs/remotes/origin/team/bugfix"), //$NON-NLS-1$
            index.filterRemote("bug", "origin").stream().map(Ref::ref).toList()); //$NON-NLS-1$
    }

    @Test
    public void keepsOrderAndFoldersWhenRefsArriveInLaterBatches()
    {
        BranchPickerIndex index = new BranchPickerIndex();
        index.addAll(List.of(new Ref("feature/z", "refs/heads/feature/z", Group.LOCAL, 10))); //$NON-NLS-1$ //$NON-NLS-2$
        index.addAll(List.of(new Ref("feature/a", "refs/heads/feature/a", Group.LOCAL, 20))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of("refs/heads/feature/a", "refs/heads/feature/z"), //$NON-NLS-1$
            index.filter(Group.LOCAL, "").stream().map(Ref::ref).toList()); //$NON-NLS-1$
        assertEquals(List.of("feature"), index.folderPaths(Group.LOCAL, "", null)); //$NON-NLS-1$
    }
}
