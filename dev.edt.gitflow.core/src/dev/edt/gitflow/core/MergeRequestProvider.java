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
    Optional<MergeRequestStatus> statusForBranch(String branch) throws HostingException;

    /** Создает запрос на слияние. */
    MergeRequestStatus createMergeRequest(CreateMergeRequest request) throws HostingException;

    /** Участники проекта для выбора проверяющего. */
    List<ProjectMember> projectMembers() throws HostingException;

    /** Имя текущего пользователя хостинга. */
    String currentUser() throws HostingException;

    /** Ветка по умолчанию: цель нового запроса. */
    String defaultBranch() throws HostingException;

    /**
     * Есть ли у токена право создавать запросы. Справочный признак: фактическое
     * право проверяет ответ API.
     */
    boolean canCreateRequests() throws HostingException;

    /** Хост хостинга, он же ключ токена в настройках. */
    String host();

    /** Путь проекта: group/project или owner/repo. */
    String projectPath();

    /** Название хостинга для интерфейса. */
    String providerName();

    /** Сокращение запроса для строки состояния: MR или PR. */
    String requestShortName();

    /** Название запроса для интерфейса: merge request или pull request. */
    String requestFullName();

    /** Умеет ли хостинг удалять исходную ветку после слияния. */
    boolean supportsDeleteSourceBranch();

    /**
     * Обязателен ли проверяющий при создании запроса. GitLab ждет
     * reviewer_ids, в GitHub проверяющего назначают после создания и он
     * необязателен.
     */
    boolean requiresReviewer();
}
