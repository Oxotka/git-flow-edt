package dev.edt.gitflow.core;

import java.util.List;
import java.util.Optional;

/**
 * Запросы на слияние в хостинге: merge request в GitLab, pull request в GitHub.
 * Панель и диалоги работают через этот интерфейс и не зависят от хостинга.
 */
public interface MergeRequestProvider
{
    /** Состояние запроса по ветке, если он уже создан. */
    Optional<MergeRequestStatus> statusForBranch(String branch) throws GitLabException;

    /** Создает запрос на слияние. */
    MergeRequestStatus createMergeRequest(CreateMergeRequest request) throws GitLabException;

    /** Участники проекта для выбора проверяющего. */
    List<ProjectMember> projectMembers() throws GitLabException;

    /** Хост хостинга, он же ключ токена в настройках. */
    String host();

    /** Путь проекта: group/project или owner/repo. */
    String projectPath();

    /** Название хостинга для интерфейса. */
    String providerName();
}
