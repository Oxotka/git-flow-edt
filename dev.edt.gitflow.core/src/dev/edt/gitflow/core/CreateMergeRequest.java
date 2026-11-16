package dev.edt.gitflow.core;

/**
 * Запрос на создание merge request. Черновик передается префиксом `Draft: ` в
 * заголовке: так его понимают все версии GitLab. Тело запроса собирает клиент
 * хостинга: у GitLab и GitHub разные форматы.
 */
public final class CreateMergeRequest
{
    private final String sourceBranch;
    private final String targetBranch;
    private final String title;
    private String description;
    private boolean draft;
    private boolean removeSourceBranch;
    private ProjectMember reviewer;

    public CreateMergeRequest(String sourceBranch, String targetBranch, String title)
    {
        this.sourceBranch = sourceBranch;
        this.targetBranch = targetBranch;
        this.title = title;
    }

    public CreateMergeRequest description(String value)
    {
        this.description = value;
        return this;
    }

    public CreateMergeRequest draft(boolean value)
    {
        this.draft = value;
        return this;
    }

    public CreateMergeRequest removeSourceBranch(boolean value)
    {
        this.removeSourceBranch = value;
        return this;
    }

    public CreateMergeRequest reviewer(ProjectMember value)
    {
        this.reviewer = value;
        return this;
    }

    public String sourceBranch()
    {
        return sourceBranch;
    }

    public String targetBranch()
    {
        return targetBranch;
    }

    public String title()
    {
        return title;
    }

    public String description()
    {
        return description;
    }

    public boolean draft()
    {
        return draft;
    }

    public boolean removeSourceBranch()
    {
        return removeSourceBranch;
    }

    /** Проверяющий, если выбран. */
    public ProjectMember reviewer()
    {
        return reviewer;
    }

    /** Заголовок с учетом черновика. */
    public String effectiveTitle()
    {
        if (!draft || title.startsWith("Draft:")) //$NON-NLS-1$
            return title;
        return "Draft: " + title; //$NON-NLS-1$
    }
}
