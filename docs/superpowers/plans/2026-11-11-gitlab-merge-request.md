# Карточка merge request текущей ветки — план реализации

> **Для агентов:** обязательный под-навык: `superpowers:subagent-driven-development` (рекомендуется) или `superpowers:executing-plans`. Шаги отмечены чекбоксами (`- [ ]`).

**Цель:** показать в панели Git Flow merge request текущей ветки GitLab и создать его оттуда же, не уходя в браузер.

**Архитектура:** клиент GitLab REST API живет в `dev.edt.gitflow.core` без SWT и проверяется на фейковом HTTP-сервере. UI-бандл добавляет строку в панель, форму настроек и диалог создания; все сетевые вызовы идут в фоновых `Job`, результат отдается в UI через `Display.asyncExec`.

**Технологии:** Java 17, JGit 6.8, SWT/JFace, Eclipse Jobs, `java.net.http`, Gson 2.10.1 из target-платформы, Equinox secure storage.

**Спецификация:** [docs/superpowers/specs/2026-11-11-gitlab-merge-request-design.md](../specs/2026-11-11-gitlab-merge-request-design.md)

## Глобальные ограничения

- Core не зависит от SWT и Eclipse UI: тесты идут обычным JUnit под Tycho.
- Токен отправляется только на хост из настроек. Чужой хост — нет запроса и нет строки MR.
- Токен не хранится открытым текстом нигде; только `SecurePreferencesFactory`.
- Галочки «не проверять сертификат» не существует.
- Новые UI-строки добавляются и в русские, и в английские ресурсы.
- Ошибки не всплывают диалогами: текст уходит в `GitFlowView.publish`.
- Версии бандлов и feature не меняются до релиза; коммиты по-русски, без `Co-Authored-By`.
- Машинные пути (`releng/edt-2026-1.target`) не коммитятся.

## Фокус проверки

Пять случаев, которые спецификация подразумевает, но ни один тест не покрывает по умолчанию:

1. **Remote указывает на чужой хост** (`github.com`, `helius`) — токен не должен утечь, строки MR быть не должно.
2. **Сервер вернул 401 или 403** — панель обязана сказать, что именно не так, и не падать.
3. **Пайплайна нет вовсе** (нет CI на проекте) — статус сборки просто не показывается.
4. **Ветка уже имеет MR, но он слит** — показывается `Слит`, а не «MR не создан».
5. **Ответ сервера без `detailed_merge_status`, `draft`, `approvals`** (старая версия или младшая редакция) — строка строится по резервным полям.

Для каждого пункта тест добавлен в задачу, владеющую кодом.

---

### Задача 1: Зеленый прогон тестов на Windows

**Файлы:**
- Создать: `dev.edt.gitflow.core.tests/src/dev/edt/gitflow/core/tests/TestRepository.java`
- Изменить: тесты, создающие репозитории через `Git.init()`: `BranchOperationsTest`, `StashOperationsTest`, `WorkingChangesTest`, `WorkingChangesBulkTest`, `CommitOperationsTest`, `PullOperationsTest`, `RecentHistoryTest`, `RepositoryOverviewTest`
- Изменить: `ChangePathFormatterTest.java`

**Интерфейсы:**
- Производит: `TestRepository.init(Path work)`, возвращающий `Git` с выключенным `core.autocrlf`.

- [ ] **Шаг 1: Воспроизвести падение**

Run: `.tools/bin/mvn.cmd -B verify -Dtest=BranchOperationsTest`
Ожидается: FAIL, в ожидании `dirty`, в факте `dirty` плюс перевод строки.

- [ ] **Шаг 2: Написать `TestRepository` и перевести на него тесты**

```java
static Git init(Path work) throws Exception
{
    Git git = Git.init().setDirectory(work.toFile()).call();
    git.getRepository().getConfig().setBoolean("core", null, "autocrlf", false);
    git.getRepository().getConfig().setString("core", null, "eol", "lf");
    git.getRepository().getConfig().save();
    return git;
}
```

Заменить `Git.init().setDirectory(work.toFile()).call()` на `TestRepository.init(work)` во всех перечисленных тестах.

- [ ] **Шаг 3: Прогнать тесты репозиториев**

Run: `.tools/bin/mvn.cmd -B verify -Dtest='BranchOperationsTest,StashOperationsTest,WorkingChangesTest,CommitOperationsTest,PullOperationsTest'`
Ожидается: PASS.

- [ ] **Шаг 4: Закрепить локаль в тесте форматирования**

В `ChangePathFormatterTest`:

```java
private Locale previous;

@Before
public void pinEnglish()
{
    previous = Locale.getDefault();
    Locale.setDefault(Locale.ENGLISH);
}

@After
public void restoreLocale()
{
    Locale.setDefault(previous);
}
```

Причина: при `ru_RU` по умолчанию `ResourceBundle` для `Locale.ENGLISH` падает в русский бандл.

- [ ] **Шаг 5: Прогнать форматирование**

Run: `.tools/bin/mvn.cmd -B verify -Dtest=ChangePathFormatterTest`
Ожидается: PASS.

- [ ] **Шаг 6: Проверить массовый тест**

Run: `.tools/bin/mvn.cmd -B verify -Dtest=WorkingChangesBulkTest`
Ожидается: PASS. Если снова `AccessDenied` в `%TEMP%`, добавить в `dev.edt.gitflow.core.tests/pom.xml` в настройку `tycho-surefire-plugin` `argLine` со `-Djava.io.tmpdir=${project.build.directory}/tmp` и повторить.

- [ ] **Шаг 7: Полный прогон**

Run: `.tools/bin/mvn.cmd -B verify`
Ожидается: BUILD SUCCESS, 0 падений.

- [ ] **Шаг 8: Коммит**

```bash
git add dev.edt.gitflow.core.tests
git commit -m "test: прогнать тесты на Windows без зависимости от autocrlf и локали"
```

---

### Задача 2: Разбор адреса GitLab из remote

**Файлы:**
- Создать: `dev.edt.gitflow.core/src/dev/edt/gitflow/core/GitLabRemote.java`
- Изменить: `dev.edt.gitflow.core/src/dev/edt/gitflow/core/GitLinks.java` (сделать базовый разбор общим)
- Тест: `dev.edt.gitflow.core.tests/src/dev/edt/gitflow/core/tests/GitLabRemoteTest.java`

**Интерфейсы:**
- Производит: `GitLabRemote.parse(String remoteUrl)`; методы `host()`, `webBase()`, `apiBase()`, `projectPath()`; `IllegalArgumentException` на неподдерживаемый адрес.

- [ ] **Шаг 1: Написать падающий тест**

```java
@Test
public void parsesHttpsRemote()
{
    var remote = GitLabRemote.parse("https://akeso.dept07/BP/npo.git");
    assertEquals("akeso.dept07", remote.host());
    assertEquals("BP/npo", remote.projectPath());
    assertEquals("https://akeso.dept07/api/v4", remote.apiBase());
    assertEquals("https://akeso.dept07/BP/npo", remote.webBase());
}

@Test
public void parsesSshRemoteWithPort()
{
    var remote = GitLabRemote.parse("ssh://git@git.example.com:2222/group/sub/project.git");
    assertEquals("git.example.com", remote.host());
    assertEquals("group/sub/project", remote.projectPath());
    assertEquals("https://git.example.com:2222/api/v4", remote.apiBase());
}

@Test
public void keepsInstancePathPrefix()
{
    var remote = GitLabRemote.parse("git@host:group/project.git");
    assertEquals("group/project", remote.projectPath());
}

@Test(expected = IllegalArgumentException.class)
public void rejectsRemoteWithoutHost()
{
    GitLabRemote.parse("C:/repos/local");
}
```

- [ ] **Шаг 2: Прогнать тест**

Run: `.tools/bin/mvn.cmd -B verify -Dtest=GitLabRemoteTest`
Ожидается: FAIL, класса нет.

- [ ] **Шаг 3: Реализовать `GitLabRemote`**

Публичный финальный класс, неизменяемые поля, `parse` использует разбор из `GitLinks` (вынести `baseUrl` в `GitLinks.webBase(String)` с видимостью пакета и вызвать оттуда). Префикс экземпляра сохраняется как часть `webBase`: адрес вида `https://host/gitlab/group/project.git` дает `webBase` `https://host/gitlab` плюс проект определяется как `group/project`.

- [ ] **Шаг 4: Прогнать тест**

Run: `.tools/bin/mvn.cmd -B verify -Dtest='GitLabRemoteTest,GitLinksTest'`
Ожидается: PASS.

- [ ] **Шаг 5: Коммит**

```bash
git add dev.edt.gitflow.core/src/dev/edt/gitflow/core/GitLabRemote.java dev.edt.gitflow.core/src/dev/edt/gitflow/core/GitLinks.java dev.edt.gitflow.core.tests/src/dev/edt/gitflow/core/tests/GitLabRemoteTest.java
git commit -m "feat: разбор адреса GitLab из remote"
```

---

### Задача 3: Клиент GitLab, ошибки и проверка токена

**Файлы:**
- Создать: `dev.edt.gitflow.core/src/dev/edt/gitflow/core/GitLabException.java`
- Создать: `dev.edt.gitflow.core/src/dev/edt/gitflow/core/GitLabClient.java`
- Изменить: `dev.edt.gitflow.core/META-INF/MANIFEST.MF` (Import-Package `com.google.gson`, optional)
- Тест: `dev.edt.gitflow.core.tests/src/dev/edt/gitflow/core/tests/GitLabClientTest.java`

**Интерфейсы:**
- Потребляет: `GitLabRemote` из задачи 2.
- Производит:
  - `GitLabClient(GitLabRemote remote, String token, SSLContext ssl, Duration timeout)`
  - `String currentUser() throws GitLabException`
  - `List<String> tokenScopes() throws GitLabException`
  - `GitLabException.Kind`: `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, `BAD_REQUEST`, `NETWORK`, `TLS`, `SERVER`

- [ ] **Шаг 1: Написать падающий тест с фейковым сервером**

```java
@Test
public void readsCurrentUser() throws Exception
{
    try (FakeGitLab server = new FakeGitLab())
    {
        server.respond("/api/v4/user", 200, "{\"name\":\"Иван\",\"username\":\"ivan\"}");
        GitLabClient client = server.client();
        assertEquals("Иван", client.currentUser());
    }
}

@Test
public void mapsUnauthorized() throws Exception
{
    try (FakeGitLab server = new FakeGitLab())
    {
        server.respond("/api/v4/user", 401, "{\"message\":\"401 Unauthorized\"}");
        try
        {
            server.client().currentUser();
            fail("ожидалось исключение");
        }
        catch (GitLabException e)
        {
            assertEquals(GitLabException.Kind.UNAUTHORIZED, e.kind());
        }
    }
}
```

`FakeGitLab` — вспомогательный класс в тестовом бандле на `com.sun.net.httpserver.HttpServer`, порт `0`, хранит запросы в списке для проверки заголовков и тела.

- [ ] **Шаг 2: Прогнать тест**

Run: `.tools/bin/mvn.cmd -B verify -Dtest=GitLabClientTest`
Ожидается: FAIL, класса нет.

- [ ] **Шаг 3: Реализовать `GitLabException` и `GitLabClient`**

`GitLabClient` держит `HttpClient` с переданным `SSLContext`, заголовок `PRIVATE-TOKEN`, таймаут из параметра. Ответы 2xx разбираются Gson, остальные маппятся по коду: 400 → `BAD_REQUEST`, 401 → `UNAUTHORIZED`, 403 → `FORBIDDEN`, 404 → `NOT_FOUND`, 409 → `CONFLICT`, 5xx → `SERVER`, `IOException` → `NETWORK`, `SSLException` → `TLS`.

- [ ] **Шаг 4: Прогнать тест**

Run: `.tools/bin/mvn.cmd -B verify -Dtest=GitLabClientTest`
Ожидается: PASS.

- [ ] **Шаг 5: Коммит**

```bash
git add dev.edt.gitflow.core/src/dev/edt/gitflow/core/GitLabClient.java dev.edt.gitflow.core/src/dev/edt/gitflow/core/GitLabException.java dev.edt.gitflow.core/META-INF/MANIFEST.MF dev.edt.gitflow.core.tests/src/dev/edt/gitflow/core/tests/GitLabClientTest.java
git commit -m "feat: клиент GitLab API с разбором ошибок"
```

---

### Задача 4: Состояние MR по ветке

**Файлы:**
- Создать: `dev.edt.gitflow.core/src/dev/edt/gitflow/core/MergeRequestStatus.java`
- Создать: `dev.edt.gitflow.core/src/dev/edt/gitflow/core/GitLabProject.java`
- Изменить: `dev.edt.gitflow.core/src/dev/edt/gitflow/core/GitLabClient.java`
- Тест: `GitLabClientTest.java`

**Интерфейсы:**
- Производит:
  - `GitLabProject defaultBranch()` и `boolean removeSourceBranchAfterMerge()`
  - `Optional<MergeRequestStatus> statusForBranch(String branch) throws GitLabException`
  - `MergeRequestStatus`: `iid()`, `title()`, `state()`, `draft()`, `webUrl()`, `approvedCount()`, `approvalsRequired()`, `pipelineStatus()` (nullable `String`), `hasConflicts()`, `discussionsResolved()`, `notesCount()`, `mergeStatus()` (nullable `String`)

- [ ] **Шаг 1: Написать падающий тест**

```java
@Test
public void buildsStatusFromMergeRequestAndApprovals() throws Exception
{
    try (FakeGitLab server = new FakeGitLab())
    {
        server.respond("/api/v4/projects/BP%2Fnpo/merge_requests?source_branch=fix%2F1", 200,
            "[{\"iid\":7,\"state\":\"opened\",\"title\":\"Fix\",\"draft\":false,"
                + "\"web_url\":\"https://akeso.dept07/BP/npo/-/merge_requests/7\","
                + "\"has_conflicts\":false,\"blocking_discussions_resolved\":true,"
                + "\"user_notes_count\":3,\"detailed_merge_status\":\"mergeable\","
                + "\"head_pipeline\":{\"status\":\"success\"}}]");
        server.respond("/api/v4/projects/BP%2Fnpo/merge_requests/7/approvals", 200,
            "{\"approved_by\":[{\"user\":{\"id\":1}}],\"approvals_required\":2}");

        MergeRequestStatus status = server.client().statusForBranch("fix/1").orElseThrow();
        assertEquals(7, status.iid());
        assertEquals(1, status.approvedCount());
        assertEquals(2, status.approvalsRequired());
        assertEquals("success", status.pipelineStatus());
    }
}

@Test
public void fallsBackWhenDetailedStatusAndApprovalsMissing() throws Exception
{
    try (FakeGitLab server = new FakeGitLab())
    {
        server.respond("/api/v4/projects/BP%2Fnpo/merge_requests?source_branch=fix%2F1", 200,
            "[{\"iid\":9,\"state\":\"opened\",\"title\":\"WIP: Fix\","
                + "\"merge_status\":\"cannot_be_merged\",\"has_conflicts\":true}]");
        server.respond("/api/v4/projects/BP%2Fnpo/merge_requests/9/approvals", 403, "{}");

        MergeRequestStatus status = server.client().statusForBranch("fix/1").orElseThrow();
        assertEquals("cannot_be_merged", status.mergeStatus());
        assertTrue(status.draft());
        assertEquals(0, status.approvedCount());
        assertEquals(0, status.approvalsRequired());
    }
}

@Test
public void hidesPipelineWhenItIsAbsent() throws Exception
{
    try (FakeGitLab server = new FakeGitLab())
    {
        server.respond("/api/v4/projects/BP%2Fnpo/merge_requests?source_branch=fix%2F1", 200,
            "[{\"iid\":10,\"state\":\"opened\",\"title\":\"Fix\"}]");
        server.respond("/api/v4/projects/BP%2Fnpo/merge_requests/10/approvals", 200,
            "{\"approved_by\":[],\"approvals_required\":0}");

        assertNull(server.client().statusForBranch("fix/1").orElseThrow().pipelineStatus());
    }
}

- [ ] **Шаг 2: Прогнать тест**

Run: `.tools/bin/mvn.cmd -B verify -Dtest=GitLabClientTest`
Ожидается: FAIL, метода нет.

- [ ] **Шаг 3: Реализовать чтение статуса**

`statusForBranch` делает запрос списка по `source_branch` с `state=all`, выбирает первый открытый MR, иначе первый по порядку; затем запрашивает `/approvals` и собирает `MergeRequestStatus`. Ошибка `FORBIDDEN` или `NOT_FOUND` на апрувах не прерывает работу: счетчик остается нулевым. Отсутствующий `head_pipeline` дает `null`.

- [ ] **Шаг 4: Прогнать тест**

Run: `.tools/bin/mvn.cmd -B verify -Dtest=GitLabClientTest`
Ожидается: PASS.

- [ ] **Шаг 5: Коммит**

```bash
git add dev.edt.gitflow.core/src/dev/edt/gitflow/core/MergeRequestStatus.java dev.edt.gitflow.core/src/dev/edt/gitflow/core/GitLabProject.java dev.edt.gitflow.core/src/dev/edt/gitflow/core/GitLabClient.java dev.edt.gitflow.core.tests/src/dev/edt/gitflow/core/tests/GitLabClientTest.java
git commit -m "feat: состояние merge request по ветке"
```

---

### Задача 5: Создание merge request

**Файлы:**
- Создать: `dev.edt.gitflow.core/src/dev/edt/gitflow/core/CreateMergeRequest.java`
- Изменить: `GitLabClient.java`
- Тест: `GitLabClientTest.java`

**Интерфейсы:**
- Производит: `MergeRequestStatus createMergeRequest(CreateMergeRequest request)`; `CreateMergeRequest(String sourceBranch, String targetBranch, String title)` плюс цепочные `description(String)`, `draft(boolean)`, `removeSourceBranch(boolean)`.

- [ ] **Шаг 1: Написать падающий тест**

```java
@Test
public void createsDraftMergeRequest() throws Exception
{
    try (FakeGitLab server = new FakeGitLab())
    {
        server.respondToPost("/api/v4/projects/BP%2Fnpo/merge_requests", 201,
            "{\"iid\":8,\"state\":\"opened\",\"title\":\"Draft: Fix\",\"draft\":true,"
                + "\"web_url\":\"https://akeso.dept07/BP/npo/-/merge_requests/8\"}");
        var request = new CreateMergeRequest("fix/1", "master", "Fix")
            .draft(true).removeSourceBranch(true);
        MergeRequestStatus created = server.client().createMergeRequest(request);
        assertEquals(8, created.iid());
        assertTrue(created.draft());
        String body = server.lastBody();
        assertTrue(body.contains("\"source_branch\":\"fix/1\""));
        assertTrue(body.contains("\"target_branch\":\"master\""));
        assertTrue(body.contains("\"remove_source_branch\":true"));
        assertTrue(body.contains("\"title\":\"Draft: Fix\""));
    }
}
```

- [ ] **Шаг 2: Прогнать тест**

Run: `.tools/bin/mvn.cmd -B verify -Dtest=GitLabClientTest`
Ожидается: FAIL.

- [ ] **Шаг 3: Реализовать создание**

Черновик передается префиксом `Draft: ` в заголовке (совместимо со старыми версиями). Тело — JSON через Gson. Ответ разбирается в `MergeRequestStatus` без запроса апрувов.

- [ ] **Шаг 4: Прогнать тест**

Run: `.tools/bin/mvn.cmd -B verify -Dtest=GitLabClientTest`
Ожидается: PASS.

- [ ] **Шаг 5: Коммит**

```bash
git add dev.edt.gitflow.core/src/dev/edt/gitflow/core/CreateMergeRequest.java dev.edt.gitflow.core/src/dev/edt/gitflow/core/GitLabClient.java dev.edt.gitflow.core.tests/src/dev/edt/gitflow/core/tests/GitLabClientTest.java
git commit -m "feat: создание merge request"
```

---

### Задача 6: Настройки подключения и хранилище токена

**Файлы:**
- Создать: `dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/handlers/GitLabSettings.java`
- Создать: `dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/handlers/GitLabSsl.java`
- Изменить: `dev.edt.gitflow.ui/META-INF/MANIFEST.MF` (`org.eclipse.equinox.security`)
- Тест: `dev.edt.gitflow.core.tests/src/dev/edt/gitflow/core/tests/GitLabSettingsTest.java`

**Интерфейсы:**
- Производит:
  - `GitLabSettings.load()`, `save(String baseUrl, String serverHost, CertMode mode, String certFile)`
  - `String baseUrl()`, `String serverHost()`, `CertMode certMode()` (`WINDOWS`, `FILE`), `String certFile()`
  - `String token(String host)`, `void saveToken(String host, String token)`
  - `boolean matches(String remoteHost)` — сравнение хоста без учета регистра и порта
  - `GitLabSsl.context(GitLabSettings settings)` → `SSLContext`

- [ ] **Шаг 1: Написать падающий тест на сопоставление хоста**

```java
@Test
public void matchesOnlyTheConfiguredHost()
{
    var settings = GitLabSettings.forTesting("https://akeso.dept07", "akeso.dept07");
    assertTrue(settings.matches("akeso.dept07"));
    assertTrue(settings.matches("AKESO.DEPT07"));
    assertFalse(settings.matches("github.com"));
    assertFalse(settings.matches("helius"));
}
```

`forTesting` — статический фабричный метод без обращения к Eclipse preferences.

- [ ] **Шаг 2: Прогнать тест**

Run: `.tools/bin/mvn.cmd -B verify -Dtest=GitLabSettingsTest`
Ожидается: FAIL.

- [ ] **Шаг 3: Реализовать `GitLabSettings`**

`load()` читает узел `dev.edt.gitflow.ui/gitlab` (`baseUrl`, `serverHost`, `certMode`, `certFile`), токен — через `SecurePreferencesFactory.getDefault().node("dev.edt.gitflow.ui")`, ключ `gitlab.token.<host>`. Если шифрованное хранилище недоступно, `saveToken` бросает `IllegalStateException` с понятным текстом.

- [ ] **Шаг 4: Реализовать `GitLabSsl`**

`WINDOWS`: `KeyStore.getInstance("Windows-ROOT", "SunMSCAPI")`. `FILE`: `CertificateFactory` по файлу, `KeyStore` в памяти, `TrustManagerFactory` с `PKIX`. Не `Windows` и не файл — стандартный контекст.

- [ ] **Шаг 5: Прогнать тест и собрать UI**

Run: `.tools/bin/mvn.cmd -B verify -Dtest=GitLabSettingsTest`
Ожидается: PASS.

- [ ] **Шаг 6: Коммит**

```bash
git add dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/handlers/GitLabSettings.java dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/handlers/GitLabSsl.java dev.edt.gitflow.ui/META-INF/MANIFEST.MF dev.edt.gitflow.core.tests/src/dev/edt/gitflow/core/tests/GitLabSettingsTest.java
git commit -m "feat: настройки подключения к GitLab и доверие сертификатам"
```

---

### Задача 7: Форма настроек

**Файлы:**
- Создать: `dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/handlers/GitLabSettingsDialog.java`
- Изменить: `dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/handlers/BranchPicker.java:172-196` (пункт меню)
- Изменить: `dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/handlers/messages.properties` и `messages_ru.properties`
- Тест: `GitLabSettingsDialogTest` не пишется; проверка ручная

**Интерфейсы:**
- Потребляет: `GitLabSettings`, `GitLabSsl`, `GitLabClient`.
- Производит: `GitLabSettingsDialog.open(Shell shell)`.

- [ ] **Шаг 1: Добавить пункт меню**

В `BranchPicker`, рядом с пунктом иерархии веток: `MenuItem gitLabItem = new MenuItem(settingsMenu, SWT.PUSH); gitLabItem.setText(Messages.get("gitLabSettings"));` со слушателем, вызывающим `GitLabSettingsDialog.open(panel.getShell())`.

- [ ] **Шаг 2: Реализовать диалог**

Поля из спецификации: адрес, токен (скрытый ввод), режим сертификата с выбором файла, кнопка «Проверить подключение», «Сохранить», «Отмена». Кнопка проверки выполняет `currentUser()` и `tokenScopes()` в `Job`, результат пишет в метку диалога. Пустой scope `api` дает текст «Токен без права записи: нужен scope api».

- [ ] **Шаг 3: Добавить тексты**

Ключи в оба файла, например `gitLabSettings=GitLab…`, `gitLabAddress`, `gitLabToken`, `gitLabCertificateWindows`, `gitLabCertificateFile`, `gitLabCheck`, `gitLabConnectedAs`, `gitLabTokenRejected`, `gitLabTokenReadOnly`, `gitLabCertificateRejected`, `gitLabUnreachable`.

- [ ] **Шаг 4: Проверить вручную**

Собрать ZIP, установить в EDT, открыть шестеренку → **GitLab…**, ввести адрес и токен, нажать «Проверить подключение».
Ожидается: «Подключено как <имя>». Без токена — «Токен не принят». С испорченным адресом — «Сервер не отвечает».

- [ ] **Шаг 5: Коммит**

```bash
git add dev.edt.gitflow.ui
git commit -m "feat: форма настроек подключения к GitLab"
```

---

### Задача 8: Строка MR в панели

**Файлы:**
- Создать: `dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/views/MergeRequestText.java` (без SWT, чтобы тестировать)
- Создать: `dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/views/MergeRequestPane.java`
- Создать: `dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/views/MergeRequestRefreshJob.java`
- Изменить: `GitFlowView.java` (`createPartControl` — вставить панель после `header`; `updateRepository()` — передавать репозиторий и ветку; `runAdaptiveSync()` — после синхронизации запускать обновление MR)
- Тест: `dev.edt.gitflow.core.tests/src/dev/edt/gitflow/core/tests/MergeRequestTextTest.java`

**Интерфейсы:**
- Потребляет: `MergeRequestStatus`, `GitLabSettings`, `GitLabClient`.
- Производит: `MergeRequestText.describe(MergeRequestStatus status, Function<String,String> messages)` → `String`.

- [ ] **Шаг 1: Написать падающий тест форматирования**

```java
@Test
public void describesApprovalsAndPipeline()
{
    var status = new MergeRequestStatus(7, "Fix", "opened", false, "url", 1, 2,
        "running", false, true, 0, "checking");
    assertEquals("!7 · Одобрено 1 из 2 · Сборка идет", MergeRequestText.describe(status, key -> key));
}

@Test
public void describesMergedAndDraft()
{
    // state merged -> "!7 · Слит"; draft true -> "!7 · Черновик"
}

@Test
public void omitsMissingPipelineAndApprovals()
{
    // pipelineStatus == null, approvalsRequired == 0 -> "!7"
}
```

- [ ] **Шаг 2: Прогнать тест**

Run: `.tools/bin/mvn.cmd -B verify -Dtest=MergeRequestTextTest`
Ожидается: FAIL.

- [ ] **Шаг 3: Реализовать форматирование и панель**

`MergeRequestText` собирает части в фиксированном порядке: номер, черновик, список, слит, апрувы, сборка, конфликты, обсуждения; пустые части пропускаются. `MergeRequestPane` — `Composite` с меткой и кнопкой «Создать», скрывается при `setRepository(null)`.

- [ ] **Шаг 4: Встроить в панель и обновление**

`MergeRequestRefreshJob` создает `GitLabClient` из настроек, проверяет `settings.matches(remote.host())`, вызывает `statusForBranch`; результат передается в `MergeRequestPane` через `Display.asyncExec`. Повторный запуск при работающем job игнорируется. Ветка без remote-ссылки, detached HEAD и ветка по умолчанию скрывают панель.

- [ ] **Шаг 5: Прогнать тесты**

Run: `.tools/bin/mvn.cmd -B verify -Dtest='MergeRequestTextTest,UiClassLoadingTest'`
Ожидается: PASS.

- [ ] **Шаг 6: Ручная проверка**

В EDT с настроенным токеном открыть ветку `fix/...`, нажать кнопку синхронизации.
Ожидается: строка с номером MR и статусами; при отсутствии MR — `MR не создан` и кнопка `Создать`.

- [ ] **Шаг 7: Коммит**

```bash
git add dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/views dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/views/GitFlowView.java dev.edt.gitflow.core.tests/src/dev/edt/gitflow/core/tests/MergeRequestTextTest.java
git commit -m "feat: строка merge request текущей ветки в панели"
```

---

### Задача 9: Диалог создания MR и документация

**Файлы:**
- Создать: `dev.edt.gitflow.ui/src/dev/edt/gitflow/ui/views/MergeRequestCreateDialog.java`
- Изменить: `MergeRequestPane.java` (кнопка «Создать»)
- Изменить: `README.md`, `CHANGELOG.md`
- Изменить: `docs/git-clients-overview.md` (ссылка на реализованную задачу)

**Интерфейсы:**
- Потребляет: `GitLabClient.createMergeRequest`, `GitLabProject`.
- Производит: `MergeRequestCreateDialog.open(Shell shell, String sourceBranch, GitLabProject project, GitLabClient client)` → `MergeRequestStatus` или `null`.

- [ ] **Шаг 1: Реализовать диалог**

Поля: заголовок (по умолчанию первая строка сообщения последнего коммита), описание, цель (`project.defaultBranch()`), галочки «Черновик» и «Удалить ветку после слияния» (по умолчанию из проекта). Кнопка «Создать» выполняет запрос в `Job`, при 400 показывает `Ветка не отправлена на сервер`, при 409 — `MR уже создан` и обновляет строку.

- [ ] **Шаг 2: Проверить вручную создание**

В EDT: ветка `fix/...`, отправлена, MR нет, нажать `Создать`, заполнить, создать.
Ожидается: строка показывает `!N`, в GitLab MR существует, цель — `master`.

- [ ] **Шаг 3: Обновить документацию**

В `README.md` — абзац о возможностях и о настройке (шестеренка → GitLab…, токен со scope `api`, сертификаты Windows по умолчанию). В `CHANGELOG.md` — запись новой версии. В `docs/git-clients-overview.md` — заменить «оформлена отдельной задачей» на ссылку на реализацию.

- [ ] **Шаг 4: Полный прогон и сборка**

Run: `.tools/bin/mvn.cmd -B verify`
Ожидается: BUILD SUCCESS.

Run: `.tools/bin/mvn.cmd -B verify -DskipTests` и проверить ZIP в `dev.edt.gitflow.repository/target/` через `unzip -t`.
Ожидается: архив целый.

- [ ] **Шаг 5: Коммит**

```bash
git add dev.edt.gitflow.ui README.md CHANGELOG.md docs/git-clients-overview.md
git commit -m "feat: создание merge request из панели"
```
