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
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;

import dev.edt.gitflow.core.GitLabClient;
import dev.edt.gitflow.core.GitLabException;
import dev.edt.gitflow.core.GitLabRemote;
import dev.edt.gitflow.core.MergeRequestStatus;
import dev.edt.gitflow.ui.handlers.GitLabSettings;
import dev.edt.gitflow.ui.handlers.GitLabSsl;
import dev.edt.gitflow.ui.handlers.Messages;

/**
 * Строка состояния merge request текущей ветки. Показывается только когда
 * настроен токен для сервера репозитория и ветка уже есть на сервере.
 */
final class MergeRequestPane
{
    private final Composite composite;
    private final Label label;
    private final Button createButton;
    private boolean refreshing;
    private Repository repository;
    private GitLabClient client;
    private String branch;

    MergeRequestPane(Composite parent)
    {
        composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        composite.setLayout(layout);
        composite.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        label = new Label(composite, SWT.NONE);
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        createButton = new Button(composite, SWT.PUSH);
        createButton.setText(Messages.get("mergeRequestCreate")); //$NON-NLS-1$
        createButton.setVisible(false);
        createButton.addListener(SWT.Selection, event -> openCreateDialog());
        composite.setVisible(false);
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
        String remote = repository.getConfig().getString("remote", "origin", "url"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        GitLabRemote gitLab = remote == null ? null : parse(remote);
        GitLabSettings settings = GitLabSettings.load();
        String token = gitLab == null ? "" : settings.token(gitLab.host()); //$NON-NLS-1$
        String branch = branchName(repository);
        if (gitLab == null || !settings.matches(gitLab.host()) || token.isBlank()
            || branch == null || isIntegrationBranch(branch))
        {
            hide();
            return;
        }
        refreshing = true;
        final Display display = label.getDisplay();
        final String sourceBranch = branch;
        final GitLabClient client;
        try
        {
            client = new GitLabClient(gitLab, token, GitLabSsl.context(settings), Duration.ofSeconds(10));
        }
        catch (Exception e)
        {
            refreshing = false;
            hide();
            return;
        }
        Job job = new Job(Messages.get("mergeRequestRefreshTitle")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                String text;
                boolean present = false;
                try
                {
                    Optional<MergeRequestStatus> status = client.statusForBranch(sourceBranch);
                    present = status.isPresent();
                    text = present ? MergeRequestText.describe(status.get(), Messages::get)
                        : Messages.get("mergeRequestNotCreated"); //$NON-NLS-1$
                }
                catch (GitLabException e)
                {
                    text = Messages.get("mergeRequestUnavailable"); //$NON-NLS-1$
                }
                catch (Exception e)
                {
                    text = Messages.get("mergeRequestUnavailable"); //$NON-NLS-1$
                }
                final String result = text;
                final boolean exists = present;
                display.asyncExec(() ->
                {
                    refreshing = false;
                    if (composite.isDisposed())
                        return;
                    boolean connectionLost = Messages.get("mergeRequestUnavailable").equals(result); //$NON-NLS-1$
                    composite.setVisible(!connectionLost);
                    label.setText(result);
                    createButton.setVisible(!exists && !connectionLost);
                    createButton.getParent().layout();
                });
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.schedule();
        this.repository = repository;
        this.client = client;
        this.branch = sourceBranch;
    }

    private void openCreateDialog()
    {
        if (repository == null || client == null || branch == null)
            return;
        MergeRequestStatus created = MergeRequestCreateDialog.open(composite.getShell(), client,
            branch, lastCommitSubject(repository));
        if (created != null)
            refresh(repository);
    }

    void hide()
    {
        if (composite.isDisposed())
            return;
        composite.setVisible(false);
        composite.getParent().layout();
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

    private static boolean isIntegrationBranch(String branch)
    {
        return "master".equals(branch) || "main".equals(branch) || "develop".equals(branch); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
