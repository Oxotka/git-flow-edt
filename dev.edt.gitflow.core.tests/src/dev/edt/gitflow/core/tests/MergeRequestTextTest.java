package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import dev.edt.gitflow.core.MergeRequestStatus;
import dev.edt.gitflow.ui.views.MergeRequestText;

public class MergeRequestTextTest
{
    private static String message(String key)
    {
        return switch (key)
        {
            case "mergeRequestDraft" -> "Черновик"; //$NON-NLS-1$
            case "mergeRequestMerged" -> "Слит"; //$NON-NLS-1$
            case "mergeRequestApprovals" -> "Одобрено %s из %s"; //$NON-NLS-1$
            case "mergeRequestPipelineSuccess" -> "Сборка прошла"; //$NON-NLS-1$
            case "mergeRequestPipelineRunning" -> "Сборка идет"; //$NON-NLS-1$
            case "mergeRequestPipelineFailed" -> "Сборка упала"; //$NON-NLS-1$
            case "mergeRequestConflicts" -> "Есть конфликты"; //$NON-NLS-1$
            case "mergeRequestDiscussions" -> "Есть незакрытые обсуждения"; //$NON-NLS-1$
            default -> key;
        };
    }

    private static MergeRequestStatus status(String state, boolean draft, int approved, int required,
        String pipeline, boolean conflicts, boolean discussionsResolved)
    {
        return new MergeRequestStatus(7, "Fix", state, draft, "url", approved, required, //$NON-NLS-1$ //$NON-NLS-2$
            pipeline, conflicts, discussionsResolved, 0, null);
    }

    @Test
    public void describesApprovalsAndPipeline()
    {
        assertEquals("!7 · Одобрено 1 из 2 · Сборка идет", MergeRequestText.describe( //$NON-NLS-1$
            status("opened", false, 1, 2, "running", false, true), MergeRequestTextTest::message)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void describesMergedAndDraft()
    {
        assertEquals("!7 · Слит", MergeRequestText.describe( //$NON-NLS-1$
            status("merged", false, 0, 0, null, false, true), MergeRequestTextTest::message)); //$NON-NLS-1$
        assertEquals("!7 · Черновик", MergeRequestText.describe( //$NON-NLS-1$
            status("opened", true, 0, 0, null, false, true), MergeRequestTextTest::message)); //$NON-NLS-1$
    }

    @Test
    public void omitsMissingPipelineAndApprovals()
    {
        assertEquals("!7", MergeRequestText.describe( //$NON-NLS-1$
            status("opened", false, 0, 0, null, false, true), MergeRequestTextTest::message)); //$NON-NLS-1$
    }

    @Test
    public void describesConflictsAndDiscussions()
    {
        assertEquals("!7 · Есть конфликты · Есть незакрытые обсуждения", //$NON-NLS-1$
            MergeRequestText.describe(status("opened", false, 0, 0, null, true, false), //$NON-NLS-1$
                MergeRequestTextTest::message));
    }
}
