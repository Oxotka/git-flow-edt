package dev.edt.gitflow.ui.handlers;

import java.time.Duration;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import dev.edt.gitflow.core.GitLabClient;
import dev.edt.gitflow.core.GitLabException;
import dev.edt.gitflow.core.GitLabRemote;

/**
 * Форма подключения к GitLab. Адрес сервера подставляется из репозитория, но
 * остается редактируемым. Токен — поле со ссылкой на страницу его выпуска,
 * сертификат — одна ссылка, проверка подключения справа под полями.
 */
public final class GitLabSettingsDialog extends Dialog
{
    private final Repository repository;
    private GitLabSettings settings;
    private Text serverField;
    private Text tokenField;
    private Link certificateLink;
    private Link tokenLink;
    private Label resultLabel;

    private GitLabSettingsDialog(Shell shell, Repository repository)
    {
        super(shell);
        this.repository = repository;
        this.settings = GitLabSettings.load();
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    public static void open(Shell shell, Repository repository)
    {
        new GitLabSettingsDialog(shell, repository).open();
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
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 0;
        grid.setLayout(layout);

        new Label(grid, SWT.NONE).setText(Messages.get("gitLabAddress")); //$NON-NLS-1$
        serverField = new Text(grid, SWT.BORDER);
        serverField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        String knownServer = repositoryServer();
        serverField.setText(knownServer == null ? settings.baseUrl() : knownServer);

        new Label(grid, SWT.NONE).setText(Messages.get("gitLabToken")); //$NON-NLS-1$
        tokenField = new Text(grid, SWT.BORDER | SWT.PASSWORD);
        tokenField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        String existing = settings.token(host());
        if (!existing.isEmpty())
        {
            // Токен уже сохранен: поле заполнено маскировкой, значение не раскрываем.
            tokenField.setText(existing.repeat(1).replaceAll(".", "•")); //$NON-NLS-1$ //$NON-NLS-2$
            tokenField.setData("saved", Boolean.TRUE); //$NON-NLS-1$
        }
        tokenField.addListener(SWT.FocusIn, event ->
        {
            if (Boolean.TRUE.equals(tokenField.getData("saved"))) //$NON-NLS-1$
            {
                tokenField.setData("saved", null); //$NON-NLS-1$
                tokenField.setText(""); //$NON-NLS-1$
            }
        });

        tokenLink = new Link(grid, SWT.NONE);
        tokenLink.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false, 2, 1));
        tokenLink.setText(link(Messages.get("gitLabTokenWhere"))); //$NON-NLS-1$
        tokenLink.addListener(SWT.Selection, event -> openTokenPage());

        certificateLink = new Link(grid, SWT.NONE);
        certificateLink.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        certificateLink.addListener(SWT.Selection, event -> showCertificateMenu());
        updateCertificateLink();
        if ("win32".equals(SWT.getPlatform())) //$NON-NLS-1$
        {
            // На Windows доверия к системному хранилищу достаточно.
            certificateLink.setVisible(false);
            ((GridData) certificateLink.getLayoutData()).exclude = true;
        }

        resultLabel = new Label(grid, SWT.WRAP);
        resultLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Button check = new Button(grid, SWT.PUSH);
        check.setText(Messages.get("gitLabCheck")); //$NON-NLS-1$
        check.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false, 2, 1));
        check.addListener(SWT.Selection, event -> checkConnection());
        return area;
    }

    private static String link(String text)
    {
        return "<a>" + text + "</a>"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String repositoryServer()
    {
        GitLabRemote remote = repositoryRemote();
        return remote == null ? null : serverRoot(remote);
    }

    private GitLabRemote repositoryRemote()
    {
        if (repository == null)
            return null;
        String url = repository.getConfig().getString("remote", "origin", "url"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (url == null || url.isBlank())
            return null;
        try
        {
            return GitLabRemote.parse(url);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private GitLabRemote serverRemote()
    {
        try
        {
            return GitLabRemote.forServer(serverField.getText());
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private String host()
    {
        GitLabRemote remote = serverRemote();
        return remote == null ? "" : remote.host(); //$NON-NLS-1$
    }

    private void openTokenPage()
    {
        GitLabRemote remote = serverRemote();
        if (remote != null)
            Program.launch(serverRoot(remote) + "/-/user_settings/personal_access_tokens"); //$NON-NLS-1$
    }

    /** Страница токенов живет в корне GitLab, а не под путем проекта. */
    private static String serverRoot(GitLabRemote remote)
    {
        java.net.URI uri = java.net.URI.create(remote.webBase());
        return uri.getScheme() + "://" + uri.getRawAuthority(); //$NON-NLS-1$
    }

    private void updateCertificateLink()
    {
        if (certificateLink == null || certificateLink.isDisposed())
            return;
        boolean file = settings.certMode() == GitLabSettings.CertMode.FILE
            && settings.certFile() != null && !settings.certFile().isBlank();
        String text = file
            ? String.format(Messages.get("gitLabCertificateFileLink"), //$NON-NLS-1$
                java.nio.file.Path.of(settings.certFile()).getFileName().toString())
            : Messages.get("gitLabCertificateOsLink"); //$NON-NLS-1$
        certificateLink.setText(link(text));
        certificateLink.getParent().layout();
    }

    private void showCertificateMenu()
    {
        Menu menu = new Menu(certificateLink);
        MenuItem system = new MenuItem(menu, SWT.RADIO);
        system.setText(Messages.get("gitLabCertificateWindows")); //$NON-NLS-1$
        system.setSelection(settings.certMode() != GitLabSettings.CertMode.FILE);
        system.addListener(SWT.Selection, event -> useSystemCertificates());
        MenuItem file = new MenuItem(menu, SWT.RADIO);
        file.setText(Messages.get("gitLabCertificateChoose")); //$NON-NLS-1$
        file.setSelection(settings.certMode() == GitLabSettings.CertMode.FILE);
        file.addListener(SWT.Selection, event -> chooseCertificateFile());
        menu.setLocation(certificateLink.toDisplay(0, certificateLink.getSize().y));
        menu.setVisible(true);
    }

    private void useSystemCertificates()
    {
        settings = GitLabSettings.forTesting(serverField.getText(), host(),
            GitLabSettings.CertMode.WINDOWS, ""); //$NON-NLS-1$
        updateCertificateLink();
    }

    private void chooseCertificateFile()
    {
        FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
        dialog.setFilterExtensions(new String[] {"*.pem;*.crt;*.cer", "*.*"}); //$NON-NLS-1$ //$NON-NLS-2$
        String selected = dialog.open();
        if (selected == null)
            return;
        settings = GitLabSettings.forTesting(serverField.getText(), host(),
            GitLabSettings.CertMode.FILE, selected);
        updateCertificateLink();
    }

    /** Токен из поля, а если оно показывает сохраненный — из настроек. */
    private String effectiveToken()
    {
        if (Boolean.TRUE.equals(tokenField.getData("saved"))) //$NON-NLS-1$
            return settings.token(host());
        return tokenField.getText().trim();
    }

    private void checkConnection()
    {
        GitLabRemote remote = serverRemote();
        if (remote == null)
        {
            resultLabel.setText(Messages.get("gitLabServerUnknown")); //$NON-NLS-1$
            return;
        }
        GitLabSettings candidate = settings;
        // Значения полей читаем на потоке UI: из фоновой задачи обращаться к
        // элементам SWT нельзя.
        final String token = effectiveToken();
        String currentBranch;
        try
        {
            currentBranch = repository == null ? null : repository.getBranch();
        }
        catch (java.io.IOException e)
        {
            currentBranch = null;
        }
        final String branch = currentBranch;
        resultLabel.setText(Messages.get("gitLabChecking")); //$NON-NLS-1$
        final var display = resultLabel.getDisplay();
        Job job = new Job(Messages.get("gitLabCheck")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                String text;
                try
                {
                    GitLabRemote projectRemote = repositoryRemote();
                    GitLabLog.write("check start: server=" + remote.webBase() //$NON-NLS-1$
                        + " project=" + (projectRemote == null ? "нет" : projectRemote.projectPath()) //$NON-NLS-1$ //$NON-NLS-2$
                        + " host=" + remote.host() //$NON-NLS-1$
                        + " tokenLength=" + token.length() //$NON-NLS-1$
                        + " certMode=" + candidate.certMode()); //$NON-NLS-1$
                    GitLabClient client = new GitLabClient(
                        projectRemote == null ? remote : projectRemote, token,
                        GitLabSsl.context(candidate), Duration.ofSeconds(10));
                    // Проверяем именно то, ради чего нужен токен: доступ к merge
                    // requests проекта. Право User: Read для этого не требуется.
                    if (projectRemote != null && branch != null)
                    {
                        client.statusForBranch(branch);
                        StringBuilder status = new StringBuilder(
                            Messages.get("gitLabConnectedMergeRequests")); //$NON-NLS-1$
                        try
                        {
                            client.projectMembers();
                            status.append(". ").append(Messages.get("gitLabMembersAvailable")); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                        catch (GitLabException e)
                        {
                            if (e.kind() == GitLabException.Kind.FORBIDDEN)
                                status.append(". ").append(Messages.get("gitLabHintMemberRead")); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                        text = status.toString();
                    }
                    else
                    {
                        text = Messages.get("gitLabConnectedLimited"); //$NON-NLS-1$
                    }
                }
                catch (GitLabException e)
                {
                    GitLabLog.write("gitlab error: " + e.kind(), e); //$NON-NLS-1$
                    text = textFor(e);
                }
                catch (Exception e)
                {
                    GitLabLog.write("unexpected error", e); //$NON-NLS-1$
                    text = Messages.get("gitLabUnreachable") + " [" //$NON-NLS-1$ //$NON-NLS-2$
                        + e.getClass().getSimpleName() + ": " + e.getMessage() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                final String message = text;
                display.asyncExec(() ->
                {
                    if (resultLabel.isDisposed())
                        return;
                    resultLabel.setText(message);
                    MessageDialog.openInformation(getShell(), Messages.get("gitLabCheck"), message); //$NON-NLS-1$
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
            case NOT_FOUND -> Messages.get("gitLabNotFound"); //$NON-NLS-1$
            case TLS -> Messages.get("gitLabCertificateRejected"); //$NON-NLS-1$
            case SERVER -> Messages.get("gitLabServerError"); //$NON-NLS-1$
            default -> Messages.get("gitLabUnreachable") //$NON-NLS-1$
                + (error.getMessage() == null ? "" : " [" + error.getMessage() + "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        };
    }

    @Override
    protected void okPressed()
    {
        GitLabRemote remote = serverRemote();
        if (remote == null)
        {
            resultLabel.setText(Messages.get("gitLabServerUnknown")); //$NON-NLS-1$
            return;
        }
        settings.save(remote.webBase(), remote.host(), settings.certMode(), settings.certFile());
        String token = effectiveToken();
        if (!token.isEmpty())
        {
            try
            {
                settings.saveToken(remote.host(), token);
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
