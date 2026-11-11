package dev.edt.gitflow.ui.handlers;

import java.time.Duration;
import java.util.List;

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
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import dev.edt.gitflow.core.GitLabClient;
import dev.edt.gitflow.core.GitLabException;
import dev.edt.gitflow.core.GitLabRemote;

/**
 * Форма подключения к GitLab. Открывается из шестеренки панели. Кнопка проверки
 * отвечает словами, что именно настроено не так.
 */
public final class GitLabSettingsDialog extends Dialog
{
    private final GitLabSettings settings;
    private Text baseUrlField;
    private Text tokenField;
    private Button windowsCertificate;
    private Button fileCertificate;
    private Text certificateFileField;
    private Button browseButton;
    private Label resultLabel;

    private GitLabSettingsDialog(Shell shell, GitLabSettings settings)
    {
        super(shell);
        this.settings = settings;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    public static void open(Shell shell)
    {
        GitLabSettingsDialog dialog = new GitLabSettingsDialog(shell, GitLabSettings.load());
        dialog.open();
    }

    @Override
    protected void configureShell(Shell newShell)
    {
        super.configureShell(newShell);
        newShell.setText(Messages.get("gitLabSettings")); //$NON-NLS-1$
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite grid = new Composite(area, SWT.NONE);
        grid.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout layout = new GridLayout(3, false);
        layout.marginWidth = 0;
        grid.setLayout(layout);

        new Label(grid, SWT.NONE).setText(Messages.get("gitLabAddress")); //$NON-NLS-1$
        baseUrlField = new Text(grid, SWT.BORDER);
        baseUrlField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        baseUrlField.setText(settings.baseUrl());

        new Label(grid, SWT.NONE).setText(Messages.get("gitLabToken")); //$NON-NLS-1$
        tokenField = new Text(grid, SWT.BORDER | SWT.PASSWORD);
        tokenField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        tokenField.setToolTipText(Messages.get("gitLabTokenHint")); //$NON-NLS-1$

        new Label(grid, SWT.NONE).setText(Messages.get("gitLabCertificate")); //$NON-NLS-1$
        Composite certificateModes = new Composite(grid, SWT.NONE);
        certificateModes.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        certificateModes.setLayout(new GridLayout(1, false));
        windowsCertificate = new Button(certificateModes, SWT.RADIO);
        windowsCertificate.setText(Messages.get("gitLabCertificateWindows")); //$NON-NLS-1$
        fileCertificate = new Button(certificateModes, SWT.RADIO);
        fileCertificate.setText(Messages.get("gitLabCertificateFile")); //$NON-NLS-1$
        windowsCertificate.setSelection(settings.certMode() != GitLabSettings.CertMode.FILE);
        fileCertificate.setSelection(settings.certMode() == GitLabSettings.CertMode.FILE);

        new Label(grid, SWT.NONE);
        certificateFileField = new Text(grid, SWT.BORDER);
        certificateFileField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        certificateFileField.setText(settings.certFile() == null ? "" : settings.certFile()); //$NON-NLS-1$
        browseButton = new Button(grid, SWT.PUSH);
        browseButton.setText(Messages.get("gitLabBrowse")); //$NON-NLS-1$
        browseButton.addListener(SWT.Selection, event -> chooseCertificateFile());
        fileCertificate.addListener(SWT.Selection, event -> updateCertificateFields());
        windowsCertificate.addListener(SWT.Selection, event -> updateCertificateFields());
        updateCertificateFields();

        resultLabel = new Label(grid, SWT.WRAP);
        resultLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        Button check = new Button(area, SWT.PUSH);
        check.setText(Messages.get("gitLabCheck")); //$NON-NLS-1$
        check.addListener(SWT.Selection, event -> checkConnection());
        return area;
    }

    private void chooseCertificateFile()
    {
        FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
        dialog.setFilterExtensions(new String[] {"*.pem;*.crt;*.cer", "*.*"}); //$NON-NLS-1$ //$NON-NLS-2$
        String selected = dialog.open();
        if (selected != null)
            certificateFileField.setText(selected);
    }

    private void updateCertificateFields()
    {
        boolean file = fileCertificate.getSelection();
        certificateFileField.setEnabled(file);
        browseButton.setEnabled(file);
    }

    private void checkConnection()
    {
        GitLabSettings candidate = candidate();
        resultLabel.setText(Messages.get("gitLabChecking")); //$NON-NLS-1$
        Job job = new Job(Messages.get("gitLabCheck")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                String text;
                try
                {
                    GitLabClient client = client(candidate, tokenField.getText());
                    String name = client.currentUser();
                    List<String> scopes = client.tokenScopes();
                    boolean canWrite = scopes.isEmpty() || scopes.contains("api"); //$NON-NLS-1$
                    text = canWrite ? Messages.get("gitLabConnectedAs") + " " + name //$NON-NLS-1$
                        : Messages.get("gitLabTokenReadOnly"); //$NON-NLS-1$
                }
                catch (GitLabException e)
                {
                    text = textFor(e);
                }
                catch (Exception e)
                {
                    text = Messages.get("gitLabUnreachable"); //$NON-NLS-1$
                }
                final String message = text;
                resultLabel.getDisplay().asyncExec(() ->
                {
                    if (!resultLabel.isDisposed())
                        resultLabel.setText(message);
                });
                return Status.OK_STATUS;
            }
        };
        job.schedule();
    }

    private static String textFor(GitLabException error)
    {
        return switch (error.kind())
        {
            case UNAUTHORIZED -> Messages.get("gitLabTokenRejected"); //$NON-NLS-1$
            case FORBIDDEN -> Messages.get("gitLabTokenReadOnly"); //$NON-NLS-1$
            case TLS -> Messages.get("gitLabCertificateRejected"); //$NON-NLS-1$
            default -> Messages.get("gitLabUnreachable"); //$NON-NLS-1$
        };
    }

    private static GitLabClient client(GitLabSettings candidate, String token) throws Exception
    {
        return new GitLabClient(GitLabRemote.forServer(candidate.baseUrl()), token,
            GitLabSsl.context(candidate), Duration.ofSeconds(10));
    }

    private GitLabSettings candidate()
    {
        return GitLabSettings.forTesting(baseUrlField.getText().trim(),
            host(baseUrlField.getText()),
            fileCertificate.getSelection() ? GitLabSettings.CertMode.FILE : GitLabSettings.CertMode.WINDOWS,
            certificateFileField.getText().trim());
    }

    private static String host(String serverUrl)
    {
        try
        {
            return GitLabRemote.forServer(serverUrl).host();
        }
        catch (IllegalArgumentException e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    @Override
    protected void okPressed()
    {
        String serverUrl = baseUrlField.getText().trim();
        String host = host(serverUrl);
        if (host.isEmpty())
        {
            resultLabel.setText(Messages.get("gitLabUnreachable")); //$NON-NLS-1$
            return;
        }
        settings.save(serverUrl, host,
            fileCertificate.getSelection() ? GitLabSettings.CertMode.FILE : GitLabSettings.CertMode.WINDOWS,
            certificateFileField.getText().trim());
        String token = tokenField.getText().trim();
        if (!token.isEmpty())
        {
            try
            {
                settings.saveToken(host, token);
            }
            catch (IllegalStateException e)
            {
                resultLabel.setText(e.getMessage());
                return;
            }
        }
        super.okPressed();
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, Messages.get("gitLabSave"), true); //$NON-NLS-1$
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }
}
