# Поддержка GitHub pull request — план реализации

> **Для агентов:** обязательный под-навык: `superpowers:subagent-driven-development`
> (рекомендуется) или `superpowers:executing-plans`. Шаги отмечены чекбоксами
> (`- [ ]`).

**Цель:** показать в панели Git Flow pull request текущей ветки GitHub и создать
его оттуда же, не уходя в браузер.

**Архитектура:** клиент GitHub REST API живет в `dev.edt.gitflow.core` без SWT и
реализует уже существующий `MergeRequestProvider`; UI выбирает провайдера по
хосту remote и работаете через интерфейс. Настройки становятся общими для
GitLab и GitHub: адрес, тип сервера и токен хранятся по хосту.

**Стек:** Java 17, Gson (уже в target-платформе), JUnit 4, SWT/JFace, Tycho.

**Spec:** [docs/superpowers/specs/2026-11-16-github-pull-request-design.md](../specs/2026-11-16-github-pull-request-design.md)

## Global Constraints

- Java 17; новых зависимостей не добавлять. JSON — через Gson из target.
- `dev.edt.gitflow.core` без SWT и Eclipse UI; тесты — обычный JUnit 4.
- Русские тексты писать с «е» вместо «е».
- Новые ключи сообщений — и в `messages.properties` (en), и в
  `messages_ru.properties`.
- Коммиты только локальные, без push. Сообщения по-русски, без `Co-Authored-By`.
- `releng/edt-2026-1.target` содержит macOS-путь. Для локальной сборки на этой
  машине он временно заменяется на
  `C:\Program Files (x86)\1C\1CE\components\1c-edt-2026.1.3+25-x86_64\plugins`
  и НЕ коммитится. Проверять перед каждым коммитом: `git status --porcelain`.
- Полная проверка: `./.tools/bin/mvn.cmd -B verify` (Windows, JDK 17, Maven 3.9.16).
  Ожидается `BUILD SUCCESS`.
- Версия не меняется: обе фичи (GitLab MR и GitHub PR) входят в один выпуск,
  номер определится при выпуске.

## Review Focus

Входы и ситуации, которые спека подразумевает, но отдельные тесты задач должны
закрепить:

1. Токен сохраняется для одного хоста — запрос к другому хосту не должен
   уходить: тест `HostingSettingsTest.matchesOnlyConfiguredHost` и проверка
   `HostingConnection` (Task 7, Task 9).
2. 403 с исчерпанным лимитом GitHub — это `RATE_LIMIT`, а не «нет прав»:
   тест `GitHubClientTest.mapsRateLimit` (Task 4).
3. Одобрение не снимается комментарием после него: тест
   `GitHubClientTest.keepsApprovalAfterComment` (Task 5).
4. Отмененные, нейтральные и пропущенные проверки не означают «упали»: тест
   `GitHubClientTest.mapsCheckConclusions` (Task 5).
5. Пока GitHub считает слияние (`mergeable` null), строка не показывает ни
   конфликты, ни готовность: тест `GitHubClientTest.ignoresUnknownMergeable`
   (Task 5).

---
### Task 1: Общее исключение хостинга

**Files:**
- Rename: `dev.edt.gitflow.core/src/dev/edt/gitflow/core/GitLabException.java` →
  `HostingException.java`
- Modify: `GitLabClient.java`, `MergeRequestProvider.java`
- Modify: `dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/views/MergeRequestPane.java`,
  `.../handlers/GitLabSettingsDialog.java`
- Modify: `dev.edt.gitflow.core.tests/src/dev/edt/gitflow/core/tests/GitLabClientTest.java`,
  `CreateMergeRequestTest.java`

**Interfaces:**
- Produces:
  `public final class HostingException extends Exception`,
  `public enum Kind { UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, BAD_REQUEST, NETWORK, TLS, SERVER, RATE_LIMIT }`,
  конструкторы `(Kind, String)`, `(Kind, String, Throwable)`, `Kind kind()`.

- [ ] **Step 1: Подготовить локальную target-платформу и проверить базу**

Заменить путь в `releng/edt-2026-1.target` на
`C:\Program Files (x86)\1C\1CE\components\1c-edt-2026.1.3+25-x86_64\plugins`.
Файл не коммитить.

Run: `./.tools/bin/mvn.cmd -B verify -DskipTests`
Expected: `BUILD SUCCESS` (в тестовом бандле допускаются предупреждения Eclipse).

- [ ] **Step 2: Переименовать класс и добавить `RATE_LIMIT`**

Переименовать файл и класс; во всех ссылках заменить `GitLabException` на
`HostingException`. В `GitLabClient.failure` оставить прежнее соответствие кодов;
`429` и `403` с `X-RateLimit-Remaining: 0` пока не добавлять — это Task 4.

- [ ] **Step 3: Проверить компиляцию и тесты**

Run: `./.tools/bin/mvn.cmd -B verify -Dtest=GitLabClientTest`
Expected: PASS.

- [ ] **Step 4: Коммит**

```bash
git add dev.edt.gitflow.core dev.edt.gitflow.ui dev.edt.gitflow.core.tests
git commit -m "refactor: общее исключение хостинга вместо GitLabException"
```

---
### Task 2: Общий контракт провайдера

**Files:**
- Modify: `MergeRequestProvider.java`, `CreateMergeRequest.java`,
  `MergeRequestStatus.java`, `GitLabClient.java`
- Test: `CreateMergeRequestTest.java`, `GitLabClientTest.java`

**Interfaces:**
- `MergeRequestProvider` дополняется:
  `String currentUser()`, `String defaultBranch()`, `boolean canCreateRequests()`,
  `String requestShortName()`, `String requestFullName()`,
  `boolean supportsDeleteSourceBranch()` — все `throws HostingException`, кроме
  последних трех.
- `CreateMergeRequest`:
  `reviewer(ProjectMember)`, геттеры `sourceBranch()`, `targetBranch()`,
  `title()`, `description()`, `draft()`, `removeSourceBranch()`,
  `reviewer()`. Метод `toJson()` удаляется: тело собирает клиент.
- `MergeRequestStatus`: новый конструктор с `boolean approvalsRequiredKnown`
  последним аргументом; прежний длинный конструктор делегирует со значением
  `true`; геттер `approvalsRequiredKnown()`.

- [ ] **Step 1: Написать тесты**

В `CreateMergeRequestTest` добавить:

```java
@Test
public void sendsReviewerId() throws Exception {
    try (FakeGitLab server = new FakeGitLab()) {
        server.respond(CREATE, 201, "{\"iid\":10,\"state\":\"opened\",\"title\":\"Fix\"}");
        server.client().createMergeRequest(new CreateMergeRequest("fix/1", "master", "Fix")
            .reviewer(new ProjectMember(42, "Иван", "ivan")));
        assertTrue(server.lastBody().contains("\"reviewer_ids\":[42]"));
    }
}
```

В `GitLabClientTest` добавить проверку ветки по умолчанию и права записи:

```java
server.respond("/api/v4/projects/group%2Fproject", 200,
    "{\"default_branch\":\"master\"}");
assertEquals("master", server.client().defaultBranch());

server.respond("/api/v4/personal_access_tokens/self", 200, "{\"scopes\":[\"read_api\"]}");
assertFalse(server.client().canCreateRequests());
```

- [ ] **Step 2: Прогнать тесты — ожидать падение компиляции**

Run: `./.tools/bin/mvn.cmd -B verify -Dtest='CreateMergeRequestTest,GitLabClientTest'`
Expected: ошибка компиляции из-за отсутствующих методов.

- [ ] **Step 3: Реализовать контракт**

`GitLabClient` собирает JSON в приватном `createPayload(CreateMergeRequest)`:
`source_branch`, `target_branch`, `title` (`effectiveTitle()`), `description`,
`remove_source_branch`, `reviewer_ids` из `reviewer().id()`.
`defaultBranch()` — `GET projects/{path}` поле `default_branch`;
`canCreateRequests()` — `tokenScopes().contains("api")`;
`requestShortName()` — `"MR"`, `requestFullName()` — `"merge request"`,
`supportsDeleteSourceBranch()` — `true`.
`GitLabClient` реализует все новые методы интерфейса, иначе бандл не соберется.
При успешном `/approvals` передавать `approvalsRequiredKnown=true`, при
`FORBIDDEN`/`NOT_FOUND` — `false`.

- [ ] **Step 4: Прогнать тесты**

Run: `./.tools/bin/mvn.cmd -B verify -Dtest='CreateMergeRequestTest,GitLabClientTest'`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add dev.edt.gitflow.core dev.edt.gitflow.core.tests
git commit -m "refactor: общий контракт провайдера запросов"
```

---
### Task 3: Адрес GitHub и тип хостинга

**Files:**
- Create: `dev.edt.gitflow.core/src/dev/edt/gitflow/core/HostingProvider.java`
- Create: `dev.edt.gitflow.core/src/dev/edt/gitflow/core/GitHubRemote.java`
- Test: `dev.edt.gitflow.core.tests/src/dev/edt/gitflow/core/tests/GitHubRemoteTest.java`

**Interfaces:**
- `HostingProvider { GITLAB, GITHUB }`; `static HostingProvider detect(String host)`:
  `github.com` или хост содержит `github` → `GITHUB`; содержит `gitlab` → `GITLAB`;
  иначе `null` (решение о fallback — у настроек).
- `GitHubRemote`:
  `static GitHubRemote parse(String remoteUrl)`,
  `static GitHubRemote forServer(String serverUrl)`;
  `String host()`, `webBase()`, `projectPath()`, `owner()`, `apiBase()`.
  `apiBase()` — `https://api.github.com` для `github.com`, иначе
  `<scheme>://<authority>/api/v3`. `webBase()` — `scheme://authority/owner/repo`.
  `owner()` — часть `projectPath` до последнего `/`.

- [ ] **Step 1: Написать тесты**

```java
@Test
public void parsesGithubHttps() {
    var remote = GitHubRemote.parse("https://github.com/Oxotka/git-flow-edt.git");
    assertEquals("github.com", remote.host());
    assertEquals("Oxotka/git-flow-edt", remote.projectPath());
    assertEquals("Oxotka", remote.owner());
    assertEquals("https://api.github.com", remote.apiBase());
    assertEquals("https://github.com/Oxotka/git-flow-edt", remote.webBase());
}

@Test
public void buildsEnterpriseApiBase() {
    var remote = GitHubRemote.parse("ssh://git@ghe.corp:2222/team/repo.git");
    assertEquals("ghe.corp", remote.host());
    assertEquals("team/repo", remote.projectPath());
    assertEquals("https://ghe.corp/api/v3", remote.apiBase());
}

@Test
public void buildsFromServerRoot() {
    var remote = GitHubRemote.forServer("https://ghe.corp/");
    assertEquals("ghe.corp", remote.host());
    assertEquals("https://ghe.corp/api/v3", remote.apiBase());
}
```

- [ ] **Step 2: Прогнать — ожидать падение**

Run: `./.tools/bin/mvn.cmd -B verify -Dtest=GitHubRemoteTest`
Expected: ошибка компиляции.

- [ ] **Step 3: Реализовать**

Разбор пути — как в `GitLabRemote` (переиспользовать `GitLinks.baseUrl`, обрезать
`/` и `.git`). Для `github.com` не добавлять `/api/v3`.

- [ ] **Step 4: Прогнать тесты**

Run: `./.tools/bin/mvn.cmd -B verify -Dtest='GitHubRemoteTest,GitLabRemoteTest'`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add dev.edt.gitflow.core dev.edt.gitflow.core.tests
git commit -m "feat: разбор адреса GitHub и тип хостинга"
```

---
### Task 4: Клиент GitHub — подключение и ошибки

**Files:**
- Create: `dev.edt.gitflow.core/src/dev/edt/gitflow/core/GitHubClient.java`
- Create: `dev.edt.gitflow.core.tests/src/dev/edt/gitflow/core/tests/FakeGitHub.java`
- Test: `dev.edt.gitflow.core.tests/src/dev/edt/gitflow/core/tests/GitHubClientTest.java`

**Interfaces:**
- `GitHubClient(GitHubRemote remote, String token, SSLContext ssl, Duration timeout)`
  implements `MergeRequestProvider`.
- Методы этого шага: `currentUser()`, `defaultBranch()`, `canCreateRequests()`,
  `projectMembers()`, `host()`, `projectPath()`, `providerName()` → `"GitHub"`,
  `requestShortName()` → `"PR"`, `requestFullName()` → `"pull request"`,
  `supportsDeleteSourceBranch()` → `false`.
- `FakeGitHub`: как `FakeGitLab`, но клиент собирается из remote
  `http://127.0.0.1:<port>/owner/repo.git`; запоминает заголовок
  `Authorization` и последнее тело; ответы по абсолютному пути (`/api/v3/user`).
  Методы: `respond(path, status, body)`,
  `respond(path, status, body, headerName, headerValue)`, `client()`,
  `lastAuthorization()`, `lastBody()`.

- [ ] **Step 1: Написать тесты**

```java
@Test
public void readsLoginAndSendsBearer() throws Exception {
    try (FakeGitHub server = new FakeGitHub()) {
        server.respond("/api/v3/user", 200, "{\"login\":\"ivan\",\"name\":null}");
        assertEquals("ivan", server.client().currentUser());
        assertEquals("Bearer test-token", server.lastAuthorization());
    }
}

@Test
public void mapsRateLimit() throws Exception {
    try (FakeGitHub server = new FakeGitHub()) {
        server.respond("/api/v3/user", 403, "{\"message\":\"API rate limit exceeded\"}",
            "X-RateLimit-Remaining", "0");
        try { server.client().currentUser(); fail(); }
        catch (HostingException e) { assertEquals(HostingException.Kind.RATE_LIMIT, e.kind()); }
    }
}
```

Дополнительно: `defaultBranch()` из `GET /api/v3/repos/owner/repo`
(`default_branch`), `canCreateRequests()` из `permissions.push` (false при
отсутствии поля), `projectMembers()` из `/collaborators` (логин в `username`),
401 → `UNAUTHORIZED`, 404 → `NOT_FOUND`, 500 → `SERVER`, таймаут → `NETWORK`.
Заголовок `Accept: application/vnd.github+json`.

- [ ] **Step 2: Прогнать — ожидать падение**

Run: `./.tools/bin/mvn.cmd -B verify -Dtest=GitHubClientTest`
Expected: ошибка компиляции.

- [ ] **Step 3: Реализовать**

`HttpClient` с системным прокси (`ProxySelector.getDefault()`), но для
loopback-адресов — `NO_PROXY`, иначе тесты на `127.0.0.1` могут уйти в прокси.
Заголовки `Authorization: Bearer`, `Accept`, `X-GitHub-Api-Version: 2022-11-28`,
таймауты из конструктора. `Map` кодов: 401→UNAUTHORIZED, 403 с
`X-RateLimit-Remaining: 0` или 429→RATE_LIMIT, 403→FORBIDDEN, 404→NOT_FOUND,
422→BAD_REQUEST, 409→CONFLICT, 5xx→SERVER. `SSLException`→TLS,
`HttpTimeoutException`/`IOException`→NETWORK.
Методы `statusForBranch` и `createMergeRequest` пока реализуются заглушками
(`throw new UnsupportedOperationException()`), чтобы класс компилировался до
Task 5 и Task 6.

- [ ] **Step 4: Прогнать тесты**

Run: `./.tools/bin/mvn.cmd -B verify -Dtest='GitHubClientTest,GitLabClientTest'`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add dev.edt.gitflow.core dev.edt.gitflow.core.tests
git commit -m "feat: клиент GitHub API и разбор ошибок"
```

---
### Task 5: Состояние pull request

**Files:**
- Modify: `GitHubClient.java`
- Test: `GitHubClientTest.java`

**Interfaces:**
- `Optional<MergeRequestStatus> statusForBranch(String branch)`.

Запросы: список `GET /api/v3/repos/{o}/{r}/pulls?head={o}:{branch}&state=all&sort=updated&direction=desc&per_page=10`;
выбрать `state=open`, иначе первый. Детали `GET .../pulls/{number}`.
Ревью `GET .../pulls/{number}/reviews?per_page=100`.
Правило `GET .../branches/{base}/protection/required_pull_request_reviews`
(403/404 — правило неизвестно).
Проверки `GET .../commits/{sha}/check-runs?per_page=100`, при `total_count=0` —
`GET .../commits/{sha}/status`.

- [ ] **Step 1: Написать тесты**

```java
private static final String PULLS = "/api/v3/repos/owner/repo/pulls"
    + "?head=owner:fix/1&state=all&sort=updated&direction=desc&per_page=10";
private static final String PULL = "/api/v3/repos/owner/repo/pulls/";

@Test
public void prefersOpenPullRequest() throws Exception {
    try (FakeGitHub server = new FakeGitHub()) {
        server.respond(PULLS, 200, "[{\"number\":1,\"state\":\"closed\"},"
            + "{\"number\":2,\"state\":\"open\",\"title\":\"Fix\",\"draft\":false,"
            + "\"html_url\":\"https://github.com/owner/repo/pull/2\","
            + "\"head\":{\"sha\":\"abc\"},\"base\":{\"ref\":\"main\"},"
            + "\"mergeable\":true,\"mergeable_state\":\"clean\"}]");
        server.respond(PULL + "2", 200, "{\"number\":2,\"state\":\"open\","
            + "\"title\":\"Fix\",\"draft\":false,\"mergeable\":true,"
            + "\"mergeable_state\":\"clean\",\"head\":{\"sha\":\"abc\"},"
            + "\"base\":{\"ref\":\"main\"}}");
        server.respond(PULL + "2/reviews?per_page=100", 200, "[]");
        server.respond("/api/v3/repos/owner/repo/branches/main/protection/"
            + "required_pull_request_reviews", 403, "{}");
        server.respond("/api/v3/repos/owner/repo/commits/abc/check-runs?per_page=100",
            200, "{\"total_count\":0,\"check_runs\":[]}");
        server.respond("/api/v3/repos/owner/repo/commits/abc/status", 200,
            "{\"state\":\"success\"}");
        assertEquals(2, server.client().statusForBranch("fix/1").get().iid());
    }
}

@Test
public void keepsApprovalAfterComment() throws Exception { ... }

@Test
public void ignoresUnknownMergeable() throws Exception { ... }
```

Остальные тесты этого шага пишутся по тому же образцу с собственными ответами.

Обязательные проверки в этом шаге:

- `mapsCheckConclusions`: `failure` → `failed`; `cancelled` + `in_progress` →
  `failed` (приоритет упавших); `neutral`/`skipped` + `in_progress` → `running`;
  все `neutral` → `success`.
- `fallsBackToCombinedStatus`: `total_count: 0`, затем `{"state":"success"}`.
- `countsLatestDecisiveReview`: `APPROVED`, затем `COMMENTED` того же
  пользователя — все равно одобрено; `CHANGES_REQUESTED` или `DISMISSED`
  снимают.
- `readsApprovalRule`: `required_approving_review_count: 2`;
  `protectionForbidden`: 403 → `approvalsRequiredKnown() == false`.
- `marksMergedAndClosed`: `merged_at` + `merged_by.login` → `state merged`,
  `mergedBy`; `state closed` без `merged_at` → `closed`.
- `ignoresUnknownMergeable`: `mergeable: null` → `hasConflicts() == false`,
  `mergeStatus() == null`.
- `detectsConflictsAndReady`: `mergeable:false`/`dirty` → `hasConflicts`;
  `mergeable_state:"clean"` → `mergeStatus == "mergeable"`.
- `prefersOpenPullRequest`, тест «несколько открытых — первый по обновлению».

- [ ] **Step 2: Прогнать — ожидать падение**

Run: `./.tools/bin/mvn.cmd -B verify -Dtest=GitHubClientTest`
Expected: FAIL по новым тестам.

- [ ] **Step 3: Реализовать**

Состояние: `merged_at != null` → `"merged"`, иначе `state` (`open` → `"opened"`).
`discussionsResolved=true`, `notesCount=0` (обсуждения вне первой итерации).
`approvalsRequiredKnown=false` при 403/404 от branch protection.
Проверки: упали, если есть завершенная с `failure`, `timed_out`, `cancelled`,
`action_required`, `startup_failure`; идут, если нет упавших и есть не
`completed`; иначе прошли.

- [ ] **Step 4: Прогнать тесты**

Run: `./.tools/bin/mvn.cmd -B verify -Dtest=GitHubClientTest`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add dev.edt.gitflow.core dev.edt.gitflow.core.tests
git commit -m "feat: состояние pull request текущей ветки"
```

---
### Task 6: Создание pull request

**Files:**
- Modify: `GitHubClient.java`
- Test: `GitHubClientTest.java`

**Interfaces:**
- `MergeRequestStatus createMergeRequest(CreateMergeRequest request)`.

- [ ] **Step 1: Написать тесты**

```java
@Test
public void createsDraftWithHeadAndBase() throws Exception {
    try (FakeGitHub server = new FakeGitHub()) {
        server.respond(PULLS, 201, "{\"number\":3,\"state\":\"open\",\"draft\":true,"
            + "\"html_url\":\"https://github.com/owner/repo/pull/3\",\"title\":\"Draft Fix\"}");
        var created = server.client().createMergeRequest(
            new CreateMergeRequest("fix/1", "main", "Fix").draft(true));
        assertEquals(3, created.iid());
        assertTrue(server.lastBody().contains("\"head\":\"owner:fix/1\""));
        assertTrue(server.lastBody().contains("\"draft\":true"));
        assertTrue(server.lastBody().contains("\"base\":\"main\""));
    }
}

@Test
public void assignsReviewerAfterCreate() throws Exception { ... }

@Test
public void mapsExistingPullRequestToConflict() throws Exception {
    server.respond(PULLS, 422, "{\"errors\":[{\"message\":\"A pull request already exists for owner:fix/1.\"}]}");
    ...
    assertEquals(HostingException.Kind.CONFLICT, e.kind());
}
```

Также: 422 «No commits between» → `BAD_REQUEST`; ошибка `requested_reviewers`
(422/403) не отменяет создание — метод возвращает созданный PR.

- [ ] **Step 2: Прогнать — ожидать падение**

Run: `./.tools/bin/mvn.cmd -B verify -Dtest=GitHubClientTest`
Expected: FAIL.

- [ ] **Step 3: Реализовать**

`POST /api/v3/repos/{o}/{r}/pulls` с телом
`{title, head: owner:branch, base, body, draft}` (поле `body` — только при
непустом описании). После 2xx — если `request.reviewer() != null`,
`POST .../pulls/{number}/requested_reviewers` с
`{"reviewers":["<login>"]}`; ошибку этого запроса проглотить.
Разбор 422: тело содержит `already exists` → `CONFLICT`, иначе `BAD_REQUEST`;
в диалоге сообщение «PR уже создан» / «Ветка не отправлена».

- [ ] **Step 4: Прогнать тесты**

Run: `./.tools/bin/mvn.cmd -B verify -Dtest=GitHubClientTest`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add dev.edt.gitflow.core dev.edt.gitflow.core.tests
git commit -m "feat: создание pull request из панели"
```

---
### Task 7: Общие настройки хостинга

**Files:**
- Rename: `dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/handlers/GitLabSettings.java` →
  `HostingSettings.java`
- Rename: `GitLabSsl.java` → `HostingSsl.java`,
  `GitLabLog.java` → `HostingLog.java`
- Modify: `GitLabSettingsDialog.java` (ссылки), `MergeRequestPane.java`,
  `GitFlowView.java`, `ReviewerCache.java`
- Rename test: `GitLabSettingsTest.java` → `HostingSettingsTest.java`

**Interfaces:**
- `HostingSettings`:
  `load()`, `forTesting(String baseUrl, String serverHost, HostingProvider provider)`,
  `baseUrl()`, `serverHost()`, `certMode()`, `certFile()`, `matches(host)`,
  `provider(host)`, `token(host)`, `saveToken(host, token)`,
  `save(baseUrl, serverHost, provider, certMode, certFile)`,
  `branchPrefixes()`, `saveBranchPrefixes(String)`.
- Хранение: узел `hosting` (`baseUrl.<host>`, `provider.<host>`, `certMode`,
  `certFile`, `branchPrefixes`), токен `hosting.token.<host>`.
  При отсутствии новых ключей читать `gitlab` и `gitlab.token.<host>`.
- `ReviewerCache`: узел `hosting`, при отсутствии — старый `gitlab`.

- [ ] **Step 1: Написать тесты**

```java
@Test
public void matchesOnlyConfiguredHost() {
    var settings = HostingSettings.forTesting("https://akeso.dept07", "akeso.dept07",
        HostingProvider.GITLAB);
    assertTrue(settings.matches("akeso.dept07"));
    assertFalse(settings.matches("github.com"));
}

@Test
public void exposesProvider() {
    var settings = HostingSettings.forTesting("https://github.com", "github.com",
        HostingProvider.GITHUB);
    assertEquals(HostingProvider.GITHUB, settings.provider("github.com"));
}
```

- [ ] **Step 2: Прогнать — ожидать падение компиляции**

Run: `./.tools/bin/mvn.cmd -B verify -Dtest=HostingSettingsTest`
Expected: ошибка компиляции.

- [ ] **Step 3: Реализовать и обновить ссылки**

Переименовать классы; в `MergeRequestPane` и `GitLabSettingsDialog` заменить
имена (логику не менять — она переедет в Task 9 и Task 10). В `GitFlowView`
обновить импорт и вызов. `HostingSsl.reset()` и кеш `HostingSettings` — как
раньше.

- [ ] **Step 4: Прогнать тесты**

Run: `./.tools/bin/mvn.cmd -B verify -Dtest='HostingSettingsTest,UiClassLoadingTest'`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add dev.edt.gitflow.ui dev.edt.gitflow.core.tests
git commit -m "refactor: общие настройки хостинга по хосту"
```

---
### Task 8: Текст строки для обоих провайдеров

**Files:**
- Modify: `dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/views/MergeRequestText.java`
- Modify: `dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/handlers/messages*.properties`
- Test: `MergeRequestTextTest.java`

**Interfaces:**
- `describe(MergeRequestStatus, Function<String,String>, String shortName)`,
  `describeLink(..., String shortName)`, `createLink(Function, String shortName)`.
- Новые ключи: `mergeRequestClosed=Закрыт`, `mergeRequestApproved=Одобрено %s`.

- [ ] **Step 1: Написать тесты**

```java
@Test
public void usesProviderShortName() {
    assertEquals("PR-7 · Черновик", MergeRequestText.describe(
        status("opened", true, 0, 0, null, false, true), MergeRequestTextTest::message, "PR"));
}

@Test
public void showsClosedState() {
    assertEquals("PR-7 · Закрыт", MergeRequestText.describe(
        status("closed", false, 0, 0, null, false, true), MergeRequestTextTest::message, "PR"));
}

@Test
public void showsApprovedWithoutRequiredRule() {
    var noRule = new MergeRequestStatus(7, "Fix", "opened", false, "url", 1, 0,
        null, false, true, 0, null, null, null, false);
    assertEquals("PR-7 · Одобрено 1", MergeRequestText.describe(
        noRule, MergeRequestTextTest::message, "PR"));
}
```

- [ ] **Step 2: Прогнать — ожидать падение**

Run: `./.tools/bin/mvn.cmd -B verify -Dtest=MergeRequestTextTest`
Expected: FAIL.

- [ ] **Step 3: Реализовать**

Номер — `shortName + "-" + iid`. Закрытый (`closed`) — часть `Закрыт`.
Одобрения: при `approvalsRequiredKnown() && required > 0` — «Одобрено N из M»,
иначе при `approved > 0` — «Одобрено N». Ключи добавить в оба файла.
В `MergeRequestPane` обновить вызовы `describeLink`/`createLink`, передавая
`"MR"`: параметр провайдера появится в Task 9, до тех пор панель должна
компилироваться.

- [ ] **Step 4: Прогнать тесты**

Run: `./.tools/bin/mvn.cmd -B verify -Dtest=MergeRequestTextTest`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add dev.edt.gitflow.ui dev.edt.gitflow.core.tests
git commit -m "feat: строка запроса для MR и PR"
```

---
### Task 9: Выбор провайдера в панели

**Files:**
- Create: `dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/handlers/HostingConnection.java`
- Modify: `MergeRequestPane.java`

**Interfaces:**
- `HostingConnection.create(Repository repository)` → `MergeRequestProvider` или
  `null`, если remote неизвестен, хост не совпал с настройками или нет токена.
  Использует `HostingSettings.load()`, `GitLabRemote`/`GitHubRemote`,
  `HostingSsl.context(settings)`, таймаут 10 с.
- `HostingConnection.probe(String serverUrl, HostingProvider provider,
  String projectPath, String token)` → `MergeRequestProvider` для проверки
  подключения из формы настроек (без репозитория).

- [ ] **Step 1: Проверка «чужой хост»**

`HostingConnection` создает клиента, только если
`settings.matches(remote.host())` и токен для этого хоста непуст. Тест — в
`HostingSettingsTest` (`matchesOnlyConfiguredHost`), плюс ручная проверка:
в рабочей области есть репозиторий GitHub, а токен настроен только для GitLab —
строка PR не появляется, запрос не уходит.

- [ ] **Step 2: Перевести панель на фабрику**

`MergeRequestPane.refresh` получает провайдера через
`HostingConnection.create(repository)`; удалить прямые импорты `GitLabClient`,
`GitLabRemote`, `GitLabSettings`, `GitLabSsl`. Фильтр префиксов и проверку
интеграционной ветки оставить; ветку по умолчанию взять из
`client.defaultBranch()` и скрывать строку на ней (плюс `master`/`main`/`develop`).
Текст строки и ссылку создания вызывать с `client.requestShortName()`.

- [ ] **Step 3: Собрать и проверить**

Run: `./.tools/bin/mvn.cmd -B verify -DskipTests`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Ручная проверка в EDT**

Для ветки `fix/...` в репозитории GitHub с настроенным токеном: строка
появляется, показывает состояние, клик открывает PR. Для репозитория без
настроенного хоста строки нет.

- [ ] **Step 5: Коммит**

```bash
git add dev.edt.gitflow.ui
git commit -m "feat: панель создает клиента по хосту репозитория"
```

---
### Task 10: Форма настроек хостинга

**Files:**
- Rename/Modify: `GitLabSettingsDialog.java` → `HostingSettingsDialog.java`
- Modify: `GitFlowView.java`, `messages*.properties`

**Interfaces:**
- `HostingSettingsDialog.open(Shell, Repository)`; форма показывает адрес из
  remote, выбор типа сервера (GitLab/GitHub), токен, сертификат, префиксы веток,
  проверку подключения. Сохраняет `HostingSettings.save(...)` и
  `saveToken(host, token)`.

- [ ] **Step 1: Расширить форму**

Добавить `Combo` типа сервера; при открытии выбрать
`HostingSettings.provider(host)` или `HostingProvider.detect(host)`.
Подсказка токена зависит от провайдера: GitLab — scope `api`; GitHub —
`Pull requests: read and write`, `Contents: read`, `Metadata: read` (для
проверок — `Checks: read`, для правила одобрений — `Administration: read`).
Ссылка выпуска токена — на страницу провайдера.

- [ ] **Step 2: Обобщить проверку подключения**

Проверка создает клиента через `HostingConnection.probe(serverUrl, provider,
projectPath, token)`: `currentUser()`, затем `canCreateRequests()` (справочно) и
`projectMembers()` (если 403 — подсказка о правах). Сообщения — новые ключи
`hostingConnectedAs`, `hostingTokenRejected`, `hostingNoWrite`,
`hostingCertificateRejected`, `hostingUnreachable`, `hostingMembersHidden`.

- [ ] **Step 3: Собрать и проверить**

Run: `./.tools/bin/mvn.cmd -B verify -DskipTests`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Ручная проверка**

В EDT: шестеренка → «Хостинг…», для репозитория GitHub проверить подключение,
увидеть логин; для GitLab — прежнее поведение.

- [ ] **Step 5: Коммит**

```bash
git add dev.edt.gitflow.ui
git commit -m "feat: общая форма настроек GitLab и GitHub"
```

---
### Task 11: Диалог создания для GitHub

**Files:**
- Modify: `dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/views/MergeRequestCreateDialog.java`
- Modify: `MergeRequestPane.java`, `messages*.properties`

**Interfaces:**
- Диалог получает `MergeRequestProvider` и использует
  `supportsDeleteSourceBranch()`, `requestShortName()`, `requestFullName()`.

- [ ] **Step 1: Настроить поля**

Для GitHub скрыть «Удалить ветку после слияния»; проверяющий необязателен:
список из `projectMembers()` (логины), при пустом списке — поле ввода логина.
Для GitLab поведение прежнее (проверяющий обязателен, удаление ветки есть).
Заголовок окна и кнопки — из `requestFullName()`/`requestShortName()`.

- [ ] **Step 2: Собрать и проверить**

Run: `./.tools/bin/mvn.cmd -B verify -DskipTests`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Ручная проверка**

В EDT на тестовой ветке GitHub: «Создать PR», черновик, проверяющий; PR
создается, строка обновляется. Ошибка «ветка не отправлена» — на неотправленной
ветке.

- [ ] **Step 4: Коммит**

```bash
git add dev.edt.gitflow.ui
git commit -m "feat: создание PR из панели"
```

---
### Task 12: Документация и полная проверка

**Files:**
- Modify: `README.md`, `CHANGELOG.md`, `docs/git-clients-overview.md`

- [ ] **Step 1: Обновить README**

Добавить в «Возможности» абзац о карточке и создании PR GitHub; в «Первый
коммит» или отдельном разделе — настройку «Хостинг…»: адрес, тип сервера,
токен (права), сертификаты. Указать, что поддерживаются github.com и GitHub
Enterprise.

- [ ] **Step 2: Обновить CHANGELOG**

Добавить пункты к текущей записи 0.18.31: карточка PR, создание PR, общие
настройки хостинга, ограничения (без обсуждений и слияния).

- [ ] **Step 3: Обновить обзор клиентов**

В `docs/git-clients-overview.md` заменить «оформлена отдельной задачей» на
ссылку на реализацию (спеку и README).

- [ ] **Step 4: Полная проверка**

Run: `./.tools/bin/mvn.cmd -B verify`
Expected: `BUILD SUCCESS`, все тесты PASS.
Run: `./.tools/bin/mvn.cmd -B verify -DskipTests` и проверить ZIP
`dev.edt.gitflow.repository/target/GitFlow-*.zip` через `unzip -t`.
Expected: архив целый.

- [ ] **Step 5: Коммит**

```bash
git add README.md CHANGELOG.md docs
git commit -m "docs: GitHub pull request и общие настройки хостинга"
```
