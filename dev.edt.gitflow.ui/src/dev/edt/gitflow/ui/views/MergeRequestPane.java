package dev.edt.gitflow.ui.views;

import java.time.Duration;
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

import dev.edt.gitflow.core.GitLabClient;
import dev.edt.gitflow.core.GitLabRemote;
import dev.edt.gitflow.core.HostingException;
import dev.edt.gitflow.core.MergeRequestProvider;
import dev.edt.gitflow.core.MergeRequestStatus;
import dev.edt.gitflow.ui.handlers.GitLabSettings;
import dev.edt.gitflow.ui.handlers.GitLabSsl;
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
    private String webUrl;
    private String lastKey = ""; //$NON-NLS-1$

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
            if ("create".equals(event.text)) //$NON-NLS-1$
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
        Job job = new Job(Messages.get("mergeRequestRefreshTitle")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                String text;
                String url = null;
                boolean present = false;
                MergeRequestProvider created = null;
                String sourceBranch = null;
                try
                {
                    String remote = currentRepository.getConfig()
                        .getString("remote", "origin", "url"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    GitLabRemote gitLab = remote == null ? null : parse(remote);
                    GitLabSettings settings = GitLabSettings.load();
                    String token = gitLab == null ? "" : settings.token(gitLab.host()); //$NON-NLS-1$
                    sourceBranch = branchName(currentRepository);
                    if (gitLab == null || !settings.matches(gitLab.host()) || token.isBlank()
                        || sourceBranch == null || isIntegrationBranch(sourceBranch)
                        || !matchesPrefix(sourceBranch, settings))
                    {
                        display.asyncExec(() ->
                        {
                            refreshing = false;
                            hide();
                        });
                        return Status.OK_STATUS;
                    }
                    created = new GitLabClient(gitLab, token, GitLabSsl.context(settings),
                        Duration.ofSeconds(10));
                    Optional<MergeRequestStatus> status = created.statusForBranch(sourceBranch);
                    if (status.isPresent())
                    {
                        present = true;
                        url = status.get().webUrl();
                        text = MergeRequestText.describeLink(status.get(), Messages::get);
                    }
                    else
                        text = MergeRequestText.createLink(Messages::get);
                }
                catch (HostingException e)
                {
                    text = Messages.get("mergeRequestUnavailable") + " [" + e.kind() + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                catch (Exception e)
                {
                    text = Messages.get("mergeRequestUnavailable") + " [" //$NON-NLS-1$ //$NON-NLS-2$
                        + e.getClass().getSimpleName() + "]"; //$NON-NLS-1$
                }
                final String result = text;
                final String address = url;
                final boolean exists = present;
                final MergeRequestProvider readyClient = created;
                final String readyBranch = sourceBranch;
                display.asyncExec(() ->
                {
                    refreshing = false;
                    if (composite.isDisposed())
                        return;
                    webUrl = address;
                    client = readyClient;
                    branch = readyBranch;
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

    private void openCreateDialog()
    {
        if (repository == null || client == null || branch == null)
            return;
        MergeRequestStatus created = MergeRequestCreateDialog.open(composite.getShell(), client,
            branch, lastCommitSubject(repository), targetBranches(repository));
        if (created != null)
            refresh(repository);
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

    private static GitLabRemote parse(String remote)
    {
        try
        {
            return GitLabRemote.parse(remote);
        }
        catch (IllegalArgumentException e)
        {
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
    private static boolean matchesPrefix(String branch, GitLabSettings settings)
    {
        for (String prefix : settings.branchPrefixes())
        {
            String clean = prefix.trim();
            if (!clean.isEmpty() && branch.startsWith(clean))
                return true;
        }
        return false;
    }

    /** Целевые ветки: master и ветки origin, без служебного HEAD. */
    private static java.util.List<String> targetBranches(Repository repository)
    {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        result.add("master"); //$NON-NLS-1$
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
            // Без удаленных веток останется только master.
        }
        return new java.util.ArrayList<>(result);
    }

    private static boolean isIntegrationBranch(String branch)
    {
        return "master".equals(branch) || "main".equals(branch) || "develop".equals(branch); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
