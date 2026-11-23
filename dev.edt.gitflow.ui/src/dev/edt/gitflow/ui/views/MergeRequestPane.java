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
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

import dev.edt.gitflow.core.HostingException;
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
    private final MergeRequestRefreshSession session = new MergeRequestRefreshSession();
    private Repository repository;
    private MergeRequestProvider client;
    private String branch;
    private String defaultTargetBranch;
    private String webUrl;
    private String lastSuccessText;
    private String lastSuccessUrl;
    private boolean creationAvailable;

    MergeRequestPane(Composite parent)
    {
        composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        composite.setLayout(layout);
        composite.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        link = new Link(composite, SWT.WRAP);
        link.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        link.addListener(SWT.Selection, event ->
        {
            if ("settings".equals(event.text)) //$NON-NLS-1$
            {
                if (HostingSettingsDialog.open(composite.getShell(), repository))
                    refresh(repository);
            }
            else if ("create".equals(event.text)) //$NON-NLS-1$
                openCreateDialog(repository);
            else if (webUrl != null)
                Program.launch(webUrl);
        });
        composite.setVisible(false);
        excludeRow(true);
    }

    /** Обновляет строку, только если сменился репозиторий или ветка. */
    void refreshIfChanged(Repository repository)
    {
        if (!session.matches(key(repository)))
            refresh(repository);
    }

    void refresh(Repository repository)
    {
        if (repository == null)
        {
            hide();
            return;
        }
        // Ручное обновление тоже может прийти уже для другого контекста.
        if (session.select(key(repository)))
        {
            lastSuccessText = null;
            lastSuccessUrl = null;
            setHidden();
        }
        this.repository = repository;
        final long request = session.begin();
        final Repository currentRepository = repository;
        final Display display = link.getDisplay();
        final String refreshKey = key(repository);
        final String previousText = lastSuccessText;
        final String previousUrl = lastSuccessUrl;
        final boolean previouslyCreatable = creationAvailable;
        Job job = new Job(Messages.get("mergeRequestRefreshJob")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                String text;
                String url = null;
                boolean keepCached = false;
                boolean success = false;
                boolean allowCreation = false;
                MergeRequestProvider created = null;
                String sourceBranch = null;
                String defaultTarget = null;
                try
                {
                    HostingSettings settings = HostingSettings.load();
                    sourceBranch = branchName(currentRepository);
                    if (sourceBranch == null || !settings.matchesBranch(sourceBranch))
                        return hideInBackground(display, request);
                    created = HostingConnection.create(currentRepository);
                    if (created == null)
                        return hideInBackground(display, request);
                    defaultTarget = defaultBranch(created);
                    String shortName = created.requestShortName();
                    Optional<MergeRequestStatus> status = created.statusForBranch(sourceBranch);
                    if (status.isPresent())
                    {
                        allowCreation = status.get().closedWithoutMerge();
                        url = status.get().webUrl();
                        text = MergeRequestText.describeLink(status.get(), Messages::get,
                            shortName);
                    }
                    else
                    {
                        text = MergeRequestText.createLink(Messages::get, shortName);
                        allowCreation = true;
                    }
                    success = true;
                }
                catch (HostingException e)
                {
                    text = MergeRequestErrorText.describe(e, created, previousText, Messages::get);
                    // При потере связи показываем последний успешный адрес: клик
                    // по номеру остается осмысленным.
                    if (e.kind() == HostingException.Kind.NETWORK)
                    {
                        keepCached = true;
                        url = previousUrl;
                        allowCreation = previousText != null && previouslyCreatable;
                    }
                }
                catch (Exception e)
                {
                    text = String.format(Messages.get("mergeRequestUnavailable"), //$NON-NLS-1$
                        created == null ? "MR" : created.requestShortName()); //$NON-NLS-1$
                }
                final String result = text;
                final String address = url;
                final boolean loaded = success;
                final boolean preserveCache = keepCached;
                final MergeRequestProvider readyClient = created;
                final String readyBranch = sourceBranch;
                final String readyTarget = defaultTarget;
                final boolean readyToCreate = allowCreation;
                display.asyncExec(() ->
                {
                    if (composite.isDisposed() || !session.isCurrent(request)
                        || !refreshKey.equals(key(currentRepository)))
                        return;
                    if (!preserveCache)
                    {
                        lastSuccessText = loaded ? result : null;
                        lastSuccessUrl = loaded ? address : null;
                    }
                    webUrl = address;
                    client = readyClient;
                    branch = readyBranch;
                    defaultTargetBranch = readyTarget;
                    creationAvailable = readyToCreate;
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

    void addCreateAction(Menu menu, Repository selected, boolean enabled)
    {
        if (!canCreate(selected))
            return;
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(String.format(Messages.get("mergeRequestCreate"), client.requestShortName()) + "…"); //$NON-NLS-1$ //$NON-NLS-2$
        item.setEnabled(enabled);
        item.addListener(SWT.Selection, event -> openCreateDialog(selected));
    }

    private boolean canCreate(Repository selected)
    {
        return creationAvailable && client != null && branch != null && repository != null
            && selected != null && repository.getDirectory().equals(selected.getDirectory())
            && branch.equals(branchName(selected));
    }

    private void openCreateDialog(Repository selected)
    {
        if (!canCreate(selected))
            return;
        MergeRequestStatus created = MergeRequestCreateDialog.open(composite.getShell(), client,
            branch, lastCommitSubject(selected),
            targetBranches(selected, defaultTargetBranch));
        if (created != null)
        {
            refresh(selected);
            String url = created.webUrl();
            if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) //$NON-NLS-1$ //$NON-NLS-2$
                Program.launch(url);
        }
    }

    /** Скрывает строку и завершает фоновую задачу. */
    private IStatus hideInBackground(Display display, long request)
    {
        display.asyncExec(() ->
        {
            if (session.isCurrent(request))
                setHidden();
        });
        return Status.OK_STATUS;
    }

    void hide()
    {
        session.clear();
        repository = null;
        lastSuccessText = null;
        lastSuccessUrl = null;
        setHidden();
    }

    private void setHidden()
    {
        client = null;
        creationAvailable = false;
        branch = null;
        webUrl = null;
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

    /**
     * Целевые ветки: ветка по умолчанию у провайдера, затем все ветки origin без
     * служебного HEAD.
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
}
