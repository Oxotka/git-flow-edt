package dev.edt.gitflow.core;

import com.google.gson.JsonObject;

/**
 * Запрос на создание merge request. Черновик передается префиксом `Draft: ` в
 * заголовке: так его понимают все версии GitLab.
 */
public final class CreateMergeRequest
{
    private final String sourceBranch;
    private final String targetBranch;
    private final String title;
    private String description;
    private boolean draft;
    private boolean removeSourceBranch;
    private int reviewerId;

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

    public CreateMergeRequest reviewer(int memberId)
    {
        this.reviewerId = memberId;
        return this;
    }

    public String effectiveTitle()
    {
        if (!draft || title.startsWith("Draft:")) //$NON-NLS-1$
            return title;
        return "Draft: " + title; //$NON-NLS-1$
    }

    JsonObject toJson()
    {
        JsonObject json = new JsonObject();
        json.addProperty("source_branch", sourceBranch); //$NON-NLS-1$
        json.addProperty("target_branch", targetBranch); //$NON-NLS-1$
        json.addProperty("title", effectiveTitle()); //$NON-NLS-1$
        if (description != null && !description.isBlank())
            json.addProperty("description", description); //$NON-NLS-1$
        json.addProperty("remove_source_branch", removeSourceBranch); //$NON-NLS-1$
        if (reviewerId > 0)
        {
            com.google.gson.JsonArray reviewers = new com.google.gson.JsonArray();
            reviewers.add(reviewerId);
            json.add("reviewer_ids", reviewers); //$NON-NLS-1$
        }
        return json;
    }
}
