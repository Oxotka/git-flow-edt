package dev.edt.gitflow.ui.views;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.fieldassist.ComboContentAdapter;
import org.eclipse.jface.fieldassist.ContentProposal;
import org.eclipse.jface.fieldassist.ContentProposalAdapter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import dev.edt.gitflow.core.CreateMergeRequest;
import dev.edt.gitflow.core.HostingException;
import dev.edt.gitflow.core.MergeRequestProvider;
import dev.edt.gitflow.core.MergeRequestStatus;
import dev.edt.gitflow.core.ProjectMember;
import dev.edt.gitflow.ui.handlers.Messages;
import dev.edt.gitflow.ui.handlers.ReviewerCache;

/**
 * Диалог создания запроса на слияние для текущей ветки: merge request в GitLab
 * или pull request в GitHub. Заголовок заполнен первой строкой последнего
 * коммита, целевая ветка — ветка по умолчанию хостинга.
 */
final class MergeRequestCreateDialog extends Dialog
{
    private final MergeRequestProvider client;
    private final String sourceBranch;
    private final String suggestedTitle;
    private final List<String> targets;
    private Text titleField;
    private Text descriptionField;
    private Combo targetField;
    private Button draftButton;
    private Button removeBranchButton;
    private Label resultLabel;
    private Label reviewerLabel;
    private Combo reviewerCombo;
    private final List<ProjectMember> reviewerOptions = new ArrayList<>();
    private MergeRequestStatus created;
    private boolean creating;

    MergeRequestCreateDialog(Shell shell, MergeRequestProvider client, String sourceBranch,
        String suggestedTitle, List<String> targets)
    {
        super(shell);
        this.client = client;
        this.sourceBranch = sourceBranch;
        this.suggestedTitle = suggestedTitle;
        this.targets = targets;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected void configureShell(Shell newShell)
    {
        super.configureShell(newShell);
        newShell.setText(dialogTitle());
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
        targetField = new Combo(grid, SWT.DROP_DOWN);
        targetField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        targetField.setItems(targets.toArray(new String[0]));
        targetField.setText(targets.isEmpty() ? "master" : targets.get(0)); //$NON-NLS-1$

        new Label(grid, SWT.NONE).setText(Messages.get("mergeRequestDescription")); //$NON-NLS-1$
        descriptionField = new Text(grid, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData descriptionData = new GridData(SWT.FILL, SWT.FILL, true, true);
        descriptionData.heightHint = 90;
        descriptionField.setLayoutData(descriptionData);

        reviewerLabel = new Label(grid, SWT.NONE);
        reviewerLabel.setText(Messages.get(client.requiresReviewer()
            ? "mergeRequestReviewer" : "mergeRequestReviewerOptional")); //$NON-NLS-1$ //$NON-NLS-2$
        reviewerCombo = new Combo(grid, SWT.DROP_DOWN);
        reviewerCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        // Подсказки выводятся отдельно: выбор в нативном списке Combo тоже
        // вызывает Modify, поэтому менять его элементы из Modify нельзя.
        ContentProposalAdapter proposals = new ContentProposalAdapter(reviewerCombo,
            new ComboContentAdapter(), (text, position) -> reviewerOptions.stream()
                .map(ProjectMember::reviewerLabel)
                .filter(name -> !text.isBlank() && name.toLowerCase(java.util.Locale.ROOT)
                    .contains(text.toLowerCase(java.util.Locale.ROOT)))
                .map(ContentProposal::new).toArray(ContentProposal[]::new), null, null);
        proposals.setProposalAcceptanceStyle(ContentProposalAdapter.PROPOSAL_REPLACE);
        reviewerLabel.setVisible(false);
        reviewerCombo.setVisible(false);
        loadReviewers();

        draftButton = new Button(grid, SWT.CHECK);
        draftButton.setText(Messages.get("mergeRequestDraft")); //$NON-NLS-1$
        draftButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        // GitHub не удаляет ветку по pull request: это настройка репозитория.
        if (client.supportsDeleteSourceBranch())
        {
            removeBranchButton = new Button(grid, SWT.CHECK);
            removeBranchButton.setText(Messages.get("mergeRequestRemoveBranch")); //$NON-NLS-1$
            removeBranchButton.setSelection(true);
            removeBranchButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        }

        resultLabel = new Label(grid, SWT.WRAP);
        resultLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        return area;
    }

    private void loadReviewers()
    {
        String projectPath = client.projectPath();
        for (ProjectMember member : ReviewerCache.recent(client.host(), projectPath))
            reviewerOptions.add(member);
        applyReviewerOptions();
        final var display = reviewerCombo.getDisplay();
        Job job = new Job(Messages.get("mergeRequestReviewerLoading")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                List<ProjectMember> loaded = null;
                try
                {
                    loaded = client.projectMembers();
                }
                catch (Exception e)
                {
                    // Без права Member: Read остаются только прошлые выборы.
                }
                final List<ProjectMember> members = loaded;
                if (members == null || members.isEmpty())
                    return Status.OK_STATUS;
                display.asyncExec(() ->
                {
                    if (reviewerCombo.isDisposed())
                        return;
                    for (ProjectMember member : members)
                    {
                        boolean known = reviewerOptions.stream()
                            .anyMatch(existing -> existing.id() == member.id());
                        if (!known)
                            reviewerOptions.add(member);
                    }
                    applyReviewerOptions();
                });
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.schedule();
    }

    private void applyReviewerOptions()
    {
        if (reviewerCombo.isDisposed())
            return;
        // У GitHub проверяющего можно ввести логином, даже если список
        // участников недоступен: поле остается на виду.
        boolean visible = !reviewerOptions.isEmpty() || !client.requiresReviewer();
        reviewerLabel.setVisible(visible);
        reviewerCombo.setVisible(visible);
        if (!visible)
            return;
        String previous = reviewerCombo.getText();
        reviewerCombo.setItems(reviewerOptions.stream().map(ProjectMember::reviewerLabel)
            .toArray(String[]::new));
        if (!previous.isEmpty())
            reviewerCombo.setText(previous);
        else if (client.requiresReviewer() && reviewerCombo.getItemCount() > 0)
            // GitLab без проверяющего не создает merge request, поэтому первая
            // подсказка выбирается сразу. В GitHub проверяющий необязателен:
            // подставлять первого участника без выбора пользователя нельзя.
            reviewerCombo.select(0);
        reviewerCombo.getParent().layout();
    }

    private ProjectMember selectedReviewer()
    {
        String typed = reviewerCombo.getText().trim();
        if (typed.isEmpty())
            return null;
        for (ProjectMember member : reviewerOptions)
        {
            if (typed.equalsIgnoreCase(member.reviewerLabel())
                || typed.equalsIgnoreCase(member.username()))
                return member;
        }
        if (client.requiresReviewer())
            return null;
        // GitHub принимает логин: список collaborators может быть пустым или
        // недоступным, а назначение проверяющего выполняет клиент.
        return new ProjectMember(0, null, typed);
    }

    MergeRequestStatus created()
    {
        return created;
    }

    @Override
    protected void okPressed()
    {
        if (creating)
            return;
        String title = titleField.getText().trim();
        if (title.isEmpty())
        {
            resultLabel.setText(Messages.get("mergeRequestTitleRequired")); //$NON-NLS-1$
            return;
        }
        String target = targetField.getText().trim();
        if (target.isEmpty() || target.equals(sourceBranch))
        {
            resultLabel.setText(Messages.get(target.isEmpty()
                ? "mergeRequestTargetRequired" : "mergeRequestTargetIsSource")); //$NON-NLS-1$ //$NON-NLS-2$
            resultLabel.getParent().layout();
            targetField.setFocus();
            return;
        }
        if (client.requiresReviewer() && !reviewerOptions.isEmpty()
            && !reviewerCombo.getText().isBlank() && selectedReviewer() == null)
        {
            resultLabel.setText(Messages.get("mergeRequestReviewerRequired")); //$NON-NLS-1$
            return;
        }
        CreateMergeRequest request = new CreateMergeRequest(sourceBranch, target, title)
                .description(descriptionField.getText())
                .draft(draftButton.getSelection())
                .removeSourceBranch(removeBranchButton != null
                    && removeBranchButton.getSelection());
        ProjectMember reviewer = selectedReviewer();
        if (reviewer != null)
            request.reviewer(reviewer);
        creating = true;
        getButton(IDialogConstants.OK_ID).setEnabled(false);
        resultLabel.setText(String.format(Messages.get("mergeRequestCreating"), //$NON-NLS-1$
            client.requestFullName()));
        final var display = resultLabel.getDisplay();
        Job job = new Job(dialogTitle())
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                MergeRequestProvider.CreationResult result = null;
                String error = null;
                try
                {
                    result = client.createWithReviewerResult(request);
                }
                catch (HostingException e)
                {
                    error = switch (e.kind())
                    {
                        case BAD_REQUEST -> Messages.get("mergeRequestBranchNotPushed"); //$NON-NLS-1$
                        case CONFLICT -> String.format(Messages.get("mergeRequestAlreadyExists"), //$NON-NLS-1$
                            client.requestShortName());
                        case UNAUTHORIZED -> Messages.get("gitLabTokenRejected"); //$NON-NLS-1$
                        case FORBIDDEN -> String.format(
                            Messages.get(client.writePermissionMessageKey()),
                            client.requestShortName());
                        default -> String.format(Messages.get("mergeRequestUnavailable"), //$NON-NLS-1$
                            client.requestShortName());
                    };
                }
                catch (Exception e)
                {
                    error = String.format(Messages.get("mergeRequestUnavailable"), //$NON-NLS-1$
                        client.requestShortName());
                }
                final MergeRequestProvider.CreationResult value = result;
                final String message = error;
                display.asyncExec(() ->
                {
                    if (getShell() == null || getShell().isDisposed())
                        return;
                    if (message != null)
                    {
                        creating = false;
                        getButton(IDialogConstants.OK_ID).setEnabled(true);
                        resultLabel.setText(message);
                        return;
                    }
                    created = value.status();
                    // Логин, введенный вручную, не несет id участника: в кеш
                    // попадают только выбранные из списка участники проекта.
                    if (!value.reviewerAssignmentFailed() && reviewer != null && reviewer.id() > 0)
                        ReviewerCache.remember(client.host(), client.projectPath(), reviewer);
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
        // Enter в полях принадлежит самому полю, у диалога нет кнопки по умолчанию.
        createButton(parent, IDialogConstants.OK_ID, String.format(
            Messages.get("mergeRequestCreate"), client.requestShortName()), false); //$NON-NLS-1$
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    /** Заголовок окна: «Новый merge request» или «Новый pull request». */
    private String dialogTitle()
    {
        return String.format(Messages.get("mergeRequestCreateTitle"), //$NON-NLS-1$
            client.requestFullName());
    }

    static MergeRequestStatus open(Shell shell, MergeRequestProvider client, String sourceBranch,
        String suggestedTitle, List<String> targets)
    {
        MergeRequestCreateDialog dialog = new MergeRequestCreateDialog(shell, client, sourceBranch,
            suggestedTitle, targets);
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
