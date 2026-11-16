package dev.edt.gitflow.ui.views;

import java.util.Optional;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Link;

import dev.edt.gitflow.core.GitLabRemote;
import dev.edt.gitflow.core.HostingException;
import dev.edt.gitflow.core.HostingProvider;
import dev.edt.gitflow.core.MergeRequestProvider;
import dev.edt.gitflow.core.MergeRequestStatus;
import dev.edt.gitflow.ui.handlers.HostingConnection;
import dev.edt.gitflow.ui.handlers.HostingSettings;
import dev.edt.gitflow.ui.handlers.HostingSettingsDialog;
import dev.edt.gitflow.ui.handlers.Messages;

/**
 * Строка состояния merge request текущей ветки. Одна ссылка: номер открывает
 * merge request в браузере, «Создать» открывает диалог создания.
 * <p>
 * Вся подготовка (настройки, хранилище сертификатов, запрос) выполняется в
 * фоновой задаче: чтение хранилища сертификатов Windows заметно замедляет
 * переключение веток, если делать это в потоке интерфейса.
 */
final class MergeRequestPane
{
    private final Composite composite;
    private final Link link;
    private boolean refreshing;
    private Repository repository;
    private MergeRequestProvider client;
    private String branch;
    private String defaultTargetBranch;
    private String webUrl;
    private String lastKey = ""; //$NON-NLS-1$
    private String lastSuccessText;
    private String lastSuccessUrl;

    MergeRequestPane(Composite parent)
    {
        composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        composite.setLayout(layout);
        composite.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        link = new Link(composite, SWT.NONE);
        link.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        link.addListener(SWT.Selection, event ->
        {
            if ("settings".equals(event.text)) //$NON-NLS-1$
                HostingSettingsDialog.open(composite.getShell(), repository);
            else if ("create".equals(event.text)) //$NON-NLS-1$
                openCreateDialog();
            else if (webUrl != null)
                Program.launch(webUrl);
        });
        composite.setVisible(false);
        excludeRow(true);
    }

    /** Обновляет строку, только если сменился репозиторий или ветка. */
    void refreshIfChanged(Repository repository)
    {
        String key = key(repository);
        if (key.equals(lastKey))
            return;
        lastKey = key;
        // Другая ветка или репозиторий: прежняя успешная строка к новой ветке
        // не относится, в том числе как «последнее известное состояние».
        lastSuccessText = null;
        lastSuccessUrl = null;
        refresh(repository);
    }

    void refresh(Repository repository)
    {
        if (refreshing)
            return;
        if (repository == null)
        {
            hide();
            return;
        }
        refreshing = true;
        this.repository = repository;
        final Repository currentRepository = repository;
        final Display display = link.getDisplay();
        final String previousText = lastSuccessText;
        final String previousUrl = lastSuccessUrl;
        Job job = new Job(String.format(Messages.get("mergeRequestRefreshTitle"), //$NON-NLS-1$
            requestShortName(repository)))
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                String text;
                String url = null;
                boolean success = false;
                MergeRequestProvider created = null;
                String sourceBranch = null;
                String defaultTarget = null;
                try
                {
                    HostingSettings settings = HostingSettings.load();
                    sourceBranch = branchName(currentRepository);
                    if (sourceBranch == null || isIntegrationBranch(sourceBranch)
                        || !matchesPrefix(sourceBranch, settings))
                        return hideInBackground(display);
                    created = HostingConnection.create(currentRepository);
                    if (created == null)
                        return hideInBackground(display);
                    defaultTarget = defaultBranch(created);
                    if (sourceBranch.equals(defaultTarget))
                        return hideInBackground(display);
                    String shortName = created.requestShortName();
                    try
                    {
                        Optional<MergeRequestStatus> status = created.statusForBranch(sourceBranch);
                        if (status.isPresent())
                        {
                            url = status.get().webUrl();
                            text = MergeRequestText.describeLink(status.get(), Messages::get,
                                shortName);
                        }
                        else
                            text = MergeRequestText.createLink(Messages::get, shortName);
                    }
                    catch (HostingException e)
                    {
                        // 404 проекта или запроса — это отсутствие запроса, а не сбой
                        // связи: строка показывает ссылку создания.
                        if (e.kind() != HostingException.Kind.NOT_FOUND)
                            throw e;
                        text = MergeRequestText.createLink(Messages::get, shortName);
                    }
                    success = true;
                }
                catch (HostingException e)
                {
                    text = MergeRequestErrorText.describe(e, created, previousText, Messages::get);
                    // При потере связи показываем последний успешный адрес: клик
                    // по номеру остается осмысленным.
                    if (e.kind() == HostingException.Kind.NETWORK)
                        url = previousUrl;
                }
                catch (Exception e)
                {
                    text = String.format(Messages.get("mergeRequestUnavailable"), //$NON-NLS-1$
                        created == null ? "MR" : created.requestShortName()); //$NON-NLS-1$
                }
                final String result = text;
                final String address = url;
                final boolean loaded = success;
                final MergeRequestProvider readyClient = created;
                final String readyBranch = sourceBranch;
                final String readyTarget = defaultTarget;
                display.asyncExec(() ->
                {
                    refreshing = false;
                    if (composite.isDisposed())
                        return;
                    if (loaded)
                    {
                        lastSuccessText = result;
                        lastSuccessUrl = address;
                    }
                    webUrl = address;
                    client = readyClient;
                    branch = readyBranch;
                    defaultTargetBranch = readyTarget;
                    link.setText(result);
                    composite.setVisible(true);
                    excludeRow(false);
                    composite.getParent().layout();
                });
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.schedule();
    }

    /**
     * Сокращение запроса для заголовка задачи. Хост и тип хостинга известны по
     * remote и настройкам без сетевого запроса.
     */
    private static String requestShortName(Repository repository)
    {
        try
        {
            String url = repository.getConfig().getString("remote", "origin", "url"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            String host = GitLabRemote.parse(url).host();
            return HostingSettings.load().provider(host) == HostingProvider.GITHUB
                ? "PR" : "MR"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            return "MR"; //$NON-NLS-1$
        }
    }

    private void openCreateDialog()
    {
        if (repository == null || client == null || branch == null)
            return;
        MergeRequestStatus created = MergeRequestCreateDialog.open(composite.getShell(), client,
            branch, lastCommitSubject(repository),
            targetBranches(repository, defaultTargetBranch));
        if (created != null)
            refresh(repository);
    }

    /** Скрывает строку и завершает фоновую задачу. */
    private IStatus hideInBackground(Display display)
    {
        display.asyncExec(() ->
        {
            refreshing = false;
            hide();
        });
        return Status.OK_STATUS;
    }

    void hide()
    {
        if (composite.isDisposed())
            return;
        composite.setVisible(false);
        excludeRow(true);
        composite.getParent().layout();
    }

    private void excludeRow(boolean excluded)
    {
        if (composite.getLayoutData() instanceof GridData data)
            data.exclude = excluded;
    }

    private static String key(Repository repository)
    {
        if (repository == null)
            return ""; //$NON-NLS-1$
        try
        {
            return repository.getDirectory() + "|" + repository.getBranch(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    private static String lastCommitSubject(Repository repository)
    {
        try (RevWalk walk = new RevWalk(repository))
        {
            var head = repository.resolve("HEAD"); //$NON-NLS-1$
            if (head == null)
                return ""; //$NON-NLS-1$
            RevCommit commit = walk.parseCommit(head);
            String message = commit.getFullMessage();
            int lineEnd = message.indexOf('\n');
            return lineEnd < 0 ? message.trim() : message.substring(0, lineEnd).trim();
        }
        catch (Exception e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    /** Ветка по умолчанию у провайдера; {@code null}, если запрос не удался. */
    private static String defaultBranch(MergeRequestProvider client)
    {
        try
        {
            String branch = client.defaultBranch();
            return branch == null || branch.isBlank() ? null : branch;
        }
        catch (HostingException | RuntimeException e)
        {
            // Без ответа провайдера строка строится по локальным веткам.
            return null;
        }
    }

    private static String branchName(Repository repository)
    {
        try
        {
            String full = repository.getFullBranch();
            if (full == null || !full.startsWith(Constants.R_HEADS))
                return null;
            return Repository.shortenRefName(full);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** Ветки, для которых предлагается merge request. */
    private static boolean matchesPrefix(String branch, HostingSettings settings)
    {
        for (String prefix : settings.branchPrefixes())
        {
            String clean = prefix.trim();
            if (!clean.isEmpty() && branch.startsWith(clean))
                return true;
        }
        return false;
    }

    /**
     * Целевые ветки: ветка по умолчанию у провайдера, затем все ветки origin без
     * служебного HEAD. Интеграционные ветки не исключаются: скрытие строки на
     * текущей ветке — отдельное правило и на список целей не влияет.
     */
    private static java.util.List<String> targetBranches(Repository repository,
        String defaultBranch)
    {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        // Без ответа провайдера целью остается прежняя ветка master.
        result.add(defaultBranch == null || defaultBranch.isBlank() ? "master" : defaultBranch); //$NON-NLS-1$
        try
        {
            String prefix = "refs/remotes/origin/"; //$NON-NLS-1$
            for (var ref : repository.getRefDatabase().getRefsByPrefix(prefix))
            {
                String name = ref.getName().substring(prefix.length());
                if (!"HEAD".equals(name)) //$NON-NLS-1$
                    result.add(name);
            }
        }
        catch (Exception e)
        {
            // Без удаленных веток останется только ветка по умолчанию.
        }
        return new java.util.ArrayList<>(result);
    }

    private static boolean isIntegrationBranch(String branch)
    {
        return "master".equals(branch) || "main".equals(branch) || "develop".equals(branch); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
