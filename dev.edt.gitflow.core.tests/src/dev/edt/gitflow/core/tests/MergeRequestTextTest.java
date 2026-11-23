package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
            case "mergeRequestClosed" -> "Закрыт"; //$NON-NLS-1$
            case "mergeRequestApprovals" -> "Одобрено %s из %s"; //$NON-NLS-1$
            case "mergeRequestApproved" -> "Одобрено %s"; //$NON-NLS-1$
            case "mergeRequestPipelineSuccess" -> "Проверки прошли"; //$NON-NLS-1$ //$NON-NLS-2$
            case "mergeRequestPipelineRunning" -> "Проверки идут"; //$NON-NLS-1$ //$NON-NLS-2$
            case "mergeRequestPipelineFailed" -> "Проверки упали"; //$NON-NLS-1$ //$NON-NLS-2$
            case "mergeRequestConflicts" -> "Есть конфликты"; //$NON-NLS-1$ //$NON-NLS-2$
            case "mergeRequestDiscussions" -> "Есть незакрытые обсуждения"; //$NON-NLS-1$ //$NON-NLS-2$
            case "mergeRequestReadyToMerge" -> "Готов к слиянию"; //$NON-NLS-1$ //$NON-NLS-2$
            case "mergeRequestCreateLink" -> "Создать %s"; //$NON-NLS-1$
            case "mergeRequestCreateNew" -> "Создать новый %s";
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
    public void offersAnotherRequestOnlyAfterClosingWithoutMerge()
    {
        for (String name : new String[] {"MR", "PR"})
        {
            var closed = status("closed", false, 0, 0, null, false, true);
            assertEquals("<a href=\"open\">" + name + "-7</a> · Закрыт · <a href=\"create\">Создать новый "
                + name + "</a>", MergeRequestText.describeLink(closed, MergeRequestTextTest::message, name));
            for (String state : new String[] {"opened", "merged"})
                org.junit.Assert.assertFalse(MergeRequestText.describeLink(
                    status(state, false, 0, 0, null, false, true), MergeRequestTextTest::message, name)
                    .contains("href=\"create\""));
        }
    }

    @Test
    public void describesApprovalsAndPipeline()
    {
        assertEquals("MR-7 · Одобрено 1 из 2 · Проверки идут", MergeRequestText.describe( //$NON-NLS-1$ //$NON-NLS-2$
            status("opened", false, 1, 2, "running", false, true), MergeRequestTextTest::message, "MR")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void marksReadyToMerge()
    {
        var ready = new MergeRequestStatus(7, "Fix", "opened", false, "url", 2, 2, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "success", false, true, 0, "mergeable"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("MR-7 · Одобрено 2 из 2 · Проверки прошли · Готов к слиянию", //$NON-NLS-1$
            MergeRequestText.describe(ready, MergeRequestTextTest::message, "MR")); //$NON-NLS-1$
    }

    @Test
    public void showsWhoAndWhenMerged()
    {
        var merged = new MergeRequestStatus(7, "Fix", "merged", false, "url", 0, 0, null, false, //$NON-NLS-1$ //$NON-NLS-2$
            true, 0, null, "2026-11-11T09:30:00Z", "Иван Иванов"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(MergeRequestText.describe(merged, MergeRequestTextTest::message, "MR")
            .startsWith("MR-7 · Слит · Иван Иванов · ")); //$NON-NLS-1$
    }

    @Test
    public void describesMergedAndDraft()
    {
        assertEquals("MR-7 · Слит", MergeRequestText.describe( //$NON-NLS-1$
            status("merged", false, 0, 0, null, false, true), MergeRequestTextTest::message, "MR")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("MR-7 · Черновик", MergeRequestText.describe( //$NON-NLS-1$
            status("opened", true, 0, 0, null, false, true), MergeRequestTextTest::message, "MR")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void omitsMissingPipelineAndApprovals()
    {
        assertEquals("MR-7", MergeRequestText.describe( //$NON-NLS-1$
            status("opened", false, 0, 0, null, false, true), MergeRequestTextTest::message, "MR")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void describesConflictsAndDiscussions()
    {
        assertEquals("MR-7 · Есть конфликты · Есть незакрытые обсуждения", //$NON-NLS-1$
            MergeRequestText.describe(status("opened", false, 0, 0, null, true, false), //$NON-NLS-1$
                MergeRequestTextTest::message, "MR"));
    }

    @Test
    public void usesProviderShortName()
    {
        assertEquals("PR-7 · Черновик", MergeRequestText.describe(
            status("opened", true, 0, 0, null, false, true), MergeRequestTextTest::message, "PR"));
    }

    @Test
    public void showsClosedState()
    {
        assertEquals("PR-7 · Закрыт", MergeRequestText.describe(
            status("closed", false, 0, 0, null, false, true), MergeRequestTextTest::message, "PR"));
    }

    @Test
    public void showsApprovedWithoutRequiredRule()
    {
        var noRule = new MergeRequestStatus(7, "Fix", "opened", false, "url", 1, 0,
            null, false, true, 0, null, null, null, false);
        assertEquals("PR-7 · Одобрено 1", MergeRequestText.describe(
            noRule, MergeRequestTextTest::message, "PR"));
    }

    @Test
    public void createLinkUsesProviderShortName()
    {
        assertEquals("<a href=\"create\">Создать PR</a>", //$NON-NLS-1$
            MergeRequestText.createLink(MergeRequestTextTest::message, "PR"));
    }
}
