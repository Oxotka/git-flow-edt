package dev.edt.gitflow.ui.views;

import java.util.Optional;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
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
    private boolean refreshing;

    MergeRequestPane(Composite parent)
    {
        composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        composite.setLayout(layout);
        composite.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        label = new Label(composite, SWT.NONE);
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
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
        if (gitLab == null || !settings.matches(gitLab.host()))
        {
            hide();
            return;
        }
        String token = settings.token(gitLab.host());
        if (token == null || token.isBlank())
        {
            hide();
            return;
        }
        String branch = branchName(repository);
        if (branch == null || isIntegrationBranch(branch))
        {
            hide();
            return;
        }
        refreshing = true;
        final org.eclipse.swt.widgets.Display display = label.getDisplay();
        Job job = new Job(Messages.get("gitLabCheck")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                String text = null;
                boolean present = false;
                try
                {
                    GitLabClient client = new GitLabClient(gitLab, token,
                        GitLabSsl.context(settings), java.time.Duration.ofSeconds(10));
                    Optional<MergeRequestStatus> status = client.statusForBranch(branch);
                    present = status.isPresent();
                    text = present
                        ? MergeRequestText.describe(status.get(), Messages::get)
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
                final boolean shown = present
                    || Messages.get("mergeRequestNotCreated").equals(result); //$NON-NLS-1$
                display.asyncExec(() ->
                {
                    refreshing = false;
                    if (composite.isDisposed())
                        return;
                    composite.setVisible(shown);
                    label.setText(result == null ? "" : result); //$NON-NLS-1$
                    composite.getParent().layout();
                });
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.schedule();
    }

    void hide()
    {
        if (composite.isDisposed())
            return;
        composite.setVisible(false);
        composite.getParent().layout();
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
