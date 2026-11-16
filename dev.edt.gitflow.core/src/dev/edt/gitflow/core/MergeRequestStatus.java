package dev.edt.gitflow.core;

/**
 * Снимок merge request для строки состояния в панели. Поля, которых сервер не
 * прислал, остаются пустыми или нулевыми: интерфейс просто не показывает их.
 */
public final class MergeRequestStatus
{
    private final int iid;
    private final String title;
    private final String state;
    private final boolean draft;
    private final String webUrl;
    private final int approvedCount;
    private final int approvalsRequired;
    private final String pipelineStatus;
    private final boolean hasConflicts;
    private final boolean discussionsResolved;
    private final int notesCount;
    private final String mergeStatus;
    private final String mergedAt;
    private final String mergedBy;
    private final boolean approvalsRequiredKnown;

    public MergeRequestStatus(int iid, String title, String state, boolean draft, String webUrl,
        int approvedCount, int approvalsRequired, String pipelineStatus, boolean hasConflicts,
        boolean discussionsResolved, int notesCount, String mergeStatus)
    {
        this(iid, title, state, draft, webUrl, approvedCount, approvalsRequired, pipelineStatus,
            hasConflicts, discussionsResolved, notesCount, mergeStatus, null, null);
    }

    public MergeRequestStatus(int iid, String title, String state, boolean draft, String webUrl,
        int approvedCount, int approvalsRequired, String pipelineStatus, boolean hasConflicts,
        boolean discussionsResolved, int notesCount, String mergeStatus, String mergedAt,
        String mergedBy)
    {
        this(iid, title, state, draft, webUrl, approvedCount, approvalsRequired, pipelineStatus,
            hasConflicts, discussionsResolved, notesCount, mergeStatus, mergedAt, mergedBy, true);
    }

    public MergeRequestStatus(int iid, String title, String state, boolean draft, String webUrl,
        int approvedCount, int approvalsRequired, String pipelineStatus, boolean hasConflicts,
        boolean discussionsResolved, int notesCount, String mergeStatus, String mergedAt,
        String mergedBy, boolean approvalsRequiredKnown)
    {
        this.mergedAt = mergedAt;
        this.mergedBy = mergedBy;
        this.approvalsRequiredKnown = approvalsRequiredKnown;
        this.iid = iid;
        this.title = title;
        this.state = state;
        this.draft = draft;
        this.webUrl = webUrl;
        this.approvedCount = approvedCount;
        this.approvalsRequired = approvalsRequired;
        this.pipelineStatus = pipelineStatus;
        this.hasConflicts = hasConflicts;
        this.discussionsResolved = discussionsResolved;
        this.notesCount = notesCount;
        this.mergeStatus = mergeStatus;
    }

    public int iid()
    {
        return iid;
    }

    public String title()
    {
        return title;
    }

    public String state()
    {
        return state;
    }

    public boolean draft()
    {
        return draft;
    }

    public String webUrl()
    {
        return webUrl;
    }

    public int approvedCount()
    {
        return approvedCount;
    }

    public int approvalsRequired()
    {
        return approvalsRequired;
    }

    /**
     * Известно ли правило одобрений. Ложь, когда сервер не отдал его: тогда
     * нельзя утверждать, что одобрений не требуется.
     */
    public boolean approvalsRequiredKnown()
    {
        return approvalsRequiredKnown;
    }

    public String pipelineStatus()
    {
        return pipelineStatus;
    }

    public boolean hasConflicts()
    {
        return hasConflicts;
    }

    public boolean discussionsResolved()
    {
        return discussionsResolved;
    }

    public int notesCount()
    {
        return notesCount;
    }

    public String mergeStatus()
    {
        return mergeStatus;
    }

    /** Когда слит, в формате ISO-8601; пусто, пока запрос открыт. */
    public String mergedAt()
    {
        return mergedAt;
    }

    /** Кто слил запрос. */
    public String mergedBy()
    {
        return mergedBy;
    }
}
