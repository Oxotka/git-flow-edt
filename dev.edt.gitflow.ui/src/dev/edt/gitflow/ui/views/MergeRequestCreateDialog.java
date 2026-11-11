package dev.edt.gitflow.ui.views;

import java.time.Duration;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import dev.edt.gitflow.core.CreateMergeRequest;
import dev.edt.gitflow.core.GitLabClient;
import dev.edt.gitflow.core.GitLabException;
import dev.edt.gitflow.core.MergeRequestStatus;
import dev.edt.gitflow.ui.handlers.Messages;

/**
 * Диалог создания merge request для текущей ветки. Заголовок заполнен первой
 * строкой последнего коммита, целевая ветка — master.
 */
final class MergeRequestCreateDialog extends Dialog
{
    private final GitLabClient client;
    private final String sourceBranch;
    private final String suggestedTitle;
    private Text titleField;
    private Text descriptionField;
    private Text targetField;
    private Button draftButton;
    private Button removeBranchButton;
    private Label resultLabel;
    private MergeRequestStatus created;

    MergeRequestCreateDialog(Shell shell, GitLabClient client, String sourceBranch,
        String suggestedTitle)
    {
        super(shell);
        this.client = client;
        this.sourceBranch = sourceBranch;
        this.suggestedTitle = suggestedTitle;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected void configureShell(Shell newShell)
    {
        super.configureShell(newShell);
        newShell.setText(Messages.get("mergeRequestCreateTitle")); //$NON-NLS-1$
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite grid = new Composite(area, SWT.NONE);
        grid.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        grid.setLayout(new GridLayout(2, false));

        new Label(grid, SWT.NONE).setText(Messages.get("mergeRequestTitle")); //$NON-NLS-1$
        titleField = new Text(grid, SWT.BORDER);
        titleField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        titleField.setText(suggestedTitle);

        new Label(grid, SWT.NONE).setText(Messages.get("mergeRequestTarget")); //$NON-NLS-1$
        targetField = new Text(grid, SWT.BORDER);
        targetField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        targetField.setText("master"); //$NON-NLS-1$

        new Label(grid, SWT.NONE).setText(Messages.get("mergeRequestDescription")); //$NON-NLS-1$
        descriptionField = new Text(grid, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData descriptionData = new GridData(SWT.FILL, SWT.FILL, true, true);
        descriptionData.heightHint = 90;
        descriptionField.setLayoutData(descriptionData);

        draftButton = new Button(grid, SWT.CHECK);
        draftButton.setText(Messages.get("mergeRequestDraft")); //$NON-NLS-1$
        draftButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        removeBranchButton = new Button(grid, SWT.CHECK);
        removeBranchButton.setText(Messages.get("mergeRequestRemoveBranch")); //$NON-NLS-1$
        removeBranchButton.setSelection(true);
        removeBranchButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        resultLabel = new Label(grid, SWT.WRAP);
        resultLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        return area;
    }

    MergeRequestStatus created()
    {
        return created;
    }

    @Override
    protected void okPressed()
    {
        String title = titleField.getText().trim();
        if (title.isEmpty())
        {
            resultLabel.setText(Messages.get("mergeRequestTitleRequired")); //$NON-NLS-1$
            return;
        }
        CreateMergeRequest request = new CreateMergeRequest(sourceBranch,
            targetField.getText().trim(), title)
                .description(descriptionField.getText())
                .draft(draftButton.getSelection())
                .removeSourceBranch(removeBranchButton.getSelection());
        resultLabel.setText(Messages.get("mergeRequestCreating")); //$NON-NLS-1$
        final var display = resultLabel.getDisplay();
        Job job = new Job(Messages.get("mergeRequestCreateTitle")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                MergeRequestStatus result = null;
                String error = null;
                try
                {
                    result = client.createMergeRequest(request);
                }
                catch (GitLabException e)
                {
                    error = switch (e.kind())
                    {
                        case BAD_REQUEST -> Messages.get("mergeRequestBranchNotPushed"); //$NON-NLS-1$
                        case CONFLICT -> Messages.get("mergeRequestAlreadyExists"); //$NON-NLS-1$
                        case UNAUTHORIZED -> Messages.get("gitLabTokenRejected"); //$NON-NLS-1$
                        case FORBIDDEN -> Messages.get("gitLabTokenReadOnly"); //$NON-NLS-1$
                        default -> Messages.get("mergeRequestUnavailable"); //$NON-NLS-1$
                    };
                }
                catch (Exception e)
                {
                    error = Messages.get("mergeRequestUnavailable"); //$NON-NLS-1$
                }
                final MergeRequestStatus value = result;
                final String message = error;
                display.asyncExec(() ->
                {
                    if (getShell() == null || getShell().isDisposed())
                        return;
                    if (message != null)
                    {
                        resultLabel.setText(message);
                        return;
                    }
                    created = value;
                    okPressedDone();
                });
                return Status.OK_STATUS;
            }
        };
        job.schedule();
    }

    private void okPressedDone()
    {
        setReturnCode(OK);
        close();
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, Messages.get("mergeRequestCreate"), true); //$NON-NLS-1$
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    static MergeRequestStatus open(Shell shell, GitLabClient client, String sourceBranch,
        String suggestedTitle)
    {
        MergeRequestCreateDialog dialog = new MergeRequestCreateDialog(shell, client, sourceBranch,
            suggestedTitle);
        return dialog.open() == OK ? dialog.created() : null;
    }

    /**
     * Короткая пауза перед повторным обновлением строки: GitLab успевает
     * зарегистрировать новый merge request.
     */
    static Duration refreshDelay()
    {
        return Duration.ofMillis(500);
    }
}
