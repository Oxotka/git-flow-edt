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

    /** Короткое имя провайдера: MR или PR. */
    public static String describe(MergeRequestStatus status, Function<String, String> messages,
        String shortName)
    {
        return describe(status, messages, shortName, false);
    }

    /** Вариант для SWT Link: номер merge request — кликабельная ссылка. */
    public static String describeLink(MergeRequestStatus status, Function<String, String> messages,
        String shortName)
    {
        return describe(status, messages, shortName, true);
    }

    /** Ссылка для создания merge request. */
    public static String createLink(Function<String, String> messages, String shortName)
    {
        return "<a href=\"create\">" //$NON-NLS-1$
            + String.format(messages.apply("mergeRequestCreateLink"), shortName) + "</a>"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String describe(MergeRequestStatus status, Function<String, String> messages,
        String shortName, boolean links)
    {
        List<String> parts = new ArrayList<>();
        String number = shortName + "-" + status.iid(); //$NON-NLS-1$
        parts.add(links ? "<a href=\"open\">" + number + "</a>" : number); //$NON-NLS-1$ //$NON-NLS-2$
        if ("merged".equals(status.state())) //$NON-NLS-1$
        {
            parts.add(messages.apply("mergeRequestMerged")); //$NON-NLS-1$
            if (status.mergedBy() != null && !status.mergedBy().isBlank())
                parts.add(status.mergedBy());
            String mergedAt = formatMergedAt(status.mergedAt());
            if (mergedAt != null)
                parts.add(mergedAt);
            return String.join(" · ", parts); //$NON-NLS-1$
        }
        if (status.draft())
            parts.add(messages.apply("mergeRequestDraft")); //$NON-NLS-1$
        if ("closed".equals(status.state())) //$NON-NLS-1$
            parts.add(messages.apply("mergeRequestClosed")); //$NON-NLS-1$
        if (status.approvalsRequiredKnown() && status.approvalsRequired() > 0)
            parts.add(String.format(messages.apply("mergeRequestApprovals"), //$NON-NLS-1$
                status.approvedCount(), status.approvalsRequired()));
        else if (status.approvedCount() > 0)
            parts.add(String.format(messages.apply("mergeRequestApproved"), //$NON-NLS-1$
                status.approvedCount()));
        String pipeline = pipelineText(status.pipelineStatus());
        if (pipeline != null)
            parts.add(messages.apply(pipeline));
        if (status.hasConflicts())
            parts.add(messages.apply("mergeRequestConflicts")); //$NON-NLS-1$
        if (!status.discussionsResolved() && "opened".equals(status.state())) //$NON-NLS-1$
            parts.add(messages.apply("mergeRequestDiscussions")); //$NON-NLS-1$
        if ("opened".equals(status.state()) && !status.draft() && "mergeable".equals(status.mergeStatus())) //$NON-NLS-1$ //$NON-NLS-2$
            parts.add(messages.apply("mergeRequestReadyToMerge")); //$NON-NLS-1$
        if (links && status.closedWithoutMerge())
            parts.add("<a href=\"create\">" + String.format(messages.apply("mergeRequestCreateNew"), shortName) + "</a>");
        return String.join(" · ", parts); //$NON-NLS-1$
    }

    /** Дата слияния в местном времени: 11.11.2026 12:30. */
    static String formatMergedAt(String mergedAt)
    {
        if (mergedAt == null || mergedAt.isBlank())
            return null;
        try
        {
            return java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm") //$NON-NLS-1$
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.OffsetDateTime.parse(mergedAt));
        }
        catch (java.time.format.DateTimeParseException e)
        {
            return mergedAt;
        }
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
