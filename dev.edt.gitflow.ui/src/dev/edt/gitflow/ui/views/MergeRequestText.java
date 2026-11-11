package dev.edt.gitflow.ui.views;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import dev.edt.gitflow.core.MergeRequestStatus;

/**
 * Текст строки состояния merge request. Без SWT: проверяется обычным тестом,
 * сообщения передаются функцией.
 */
public final class MergeRequestText
{
    private MergeRequestText()
    {
    }

    public static String describe(MergeRequestStatus status, Function<String, String> messages)
    {
        List<String> parts = new ArrayList<>();
        parts.add("!" + status.iid()); //$NON-NLS-1$
        if ("merged".equals(status.state())) //$NON-NLS-1$
        {
            parts.add(messages.apply("mergeRequestMerged")); //$NON-NLS-1$
            return String.join(" · ", parts); //$NON-NLS-1$
        }
        if (status.draft())
            parts.add(messages.apply("mergeRequestDraft")); //$NON-NLS-1$
        if (status.approvalsRequired() > 0)
            parts.add(String.format(messages.apply("mergeRequestApprovals"), //$NON-NLS-1$
                status.approvedCount(), status.approvalsRequired()));
        String pipeline = pipelineText(status.pipelineStatus());
        if (pipeline != null)
            parts.add(messages.apply(pipeline));
        if (status.hasConflicts())
            parts.add(messages.apply("mergeRequestConflicts")); //$NON-NLS-1$
        if (!status.discussionsResolved() && "opened".equals(status.state())) //$NON-NLS-1$
            parts.add(messages.apply("mergeRequestDiscussions")); //$NON-NLS-1$
        return String.join(" · ", parts); //$NON-NLS-1$
    }

    private static String pipelineText(String status)
    {
        if (status == null)
            return null;
        return switch (status)
        {
            case "success" -> "mergeRequestPipelineSuccess"; //$NON-NLS-1$ //$NON-NLS-2$
            case "failed", "canceled" -> "mergeRequestPipelineFailed"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "running", "pending", "created", "preparing", "waiting_for_resource" -> //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "mergeRequestPipelineRunning"; //$NON-NLS-1$
            case "skipped", "manual" -> null; //$NON-NLS-1$ //$NON-NLS-2$
            default -> "mergeRequestPipelineRunning"; //$NON-NLS-1$
        };
    }
}
