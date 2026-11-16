package dev.edt.gitflow.ui.handlers;

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
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import dev.edt.gitflow.core.GitHubRemote;
import dev.edt.gitflow.core.GitLabRemote;
import dev.edt.gitflow.core.HostingException;
import dev.edt.gitflow.core.HostingProvider;
import dev.edt.gitflow.core.MergeRequestProvider;

/**
 * Форма подключения к хостингу: GitLab или GitHub. Адрес сервера
 * подставляется из репозитория, но остается редактируемым. Тип сервера
 * выбирается явно: для GitHub Enterprise с доменом без слова github это
 * единственный способ указать хостинг. Токен — поле со ссылкой на страницу его
 * выпуска, сертификат — одна ссылка, проверка подключения справа под полями.
 */
public final class HostingSettingsDialog extends Dialog
{
    private final Repository repository;
    private HostingSettings settings;
    private Text serverField;
    private Combo providerCombo;
    private Text tokenField;
    private Link certificateLink;
    private Link tokenLink;
    private Label tokenHint;
    private Label prefixesLabel;
    private Text prefixesField;
    private Label resultLabel;

    private HostingSettingsDialog(Shell shell, Repository repository)
    {
        super(shell);
        this.repository = repository;
        this.settings = HostingSettings.load();
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    public static void open(Shell shell, Repository repository)
    {
        new HostingSettingsDialog(shell, repository).open();
    }

    @Override
    protected void configureShell(Shell newShell)
    {
        super.configureShell(newShell);
        newShell.setText(title());
    }

    /**
     * Заголовок формы: у известного хоста виден тип хостинга, иначе нейтральный
     * «Хостинг…». Тип меняется вместе с выбором в форме.
     */
    private String title()
    {
        if (knownHost() == null)
        {
            // Репозиторий не выбран или remote не разобран: тип сервера еще не
            // известен, заголовок нейтральный.
            return Messages.get("hostingSettings"); //$NON-NLS-1$
        }
        return Messages.get(currentProvider() == HostingProvider.GITHUB
            ? "githubSettings" : "gitLabSettings"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Провайдер из формы, а до создания списка — значение по умолчанию. */
    private HostingProvider currentProvider()
    {
        if (providerCombo == null || providerCombo.isDisposed())
            return initialProvider();
        return selectedProvider();
    }

    private void updateTitle()
    {
        if (getShell() != null && !getShell().isDisposed())
            getShell().setText(title());
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

        new Label(grid, SWT.NONE).setText(Messages.get("hostingProvider")); //$NON-NLS-1$
        providerCombo = new Combo(grid, SWT.READ_ONLY);
        providerCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        providerCombo.setItems(Messages.get("hostingProviderGitLab"), //$NON-NLS-1$
            Messages.get("hostingProviderGitHub")); //$NON-NLS-1$
        providerCombo.select(initialProvider() == HostingProvider.GITHUB ? 1 : 0);
        providerCombo.addListener(SWT.Selection, event ->
        {
            updateTitle();
            updateTokenLinkText();
            updateTokenHint();
            updatePrefixesLabel();
        });

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
        tokenLink.addListener(SWT.Selection, event -> openTokenPage());
        updateTokenLinkText();

        tokenHint = new Label(grid, SWT.WRAP);
        tokenHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        updateTokenHint();

        prefixesLabel = new Label(grid, SWT.NONE);
        prefixesField = new Text(grid, SWT.BORDER);
        prefixesField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        prefixesField.setText(String.join(", ", settings.branchPrefixes())); //$NON-NLS-1$
        updatePrefixesLabel();

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

    /**
     * Тип хостинга по умолчанию: сохраненный для хоста, затем распознанный по
     * хосту, иначе GitLab.
     */
    private HostingProvider initialProvider()
    {
        String host = knownHost();
        if (host == null)
            return HostingProvider.GITLAB;
        HostingProvider provider = settings.provider(host);
        if (provider == null)
            provider = HostingProvider.detect(host);
        return provider == null ? HostingProvider.GITLAB : provider;
    }

    private HostingProvider selectedProvider()
    {
        return providerCombo.getSelectionIndex() == 1 ? HostingProvider.GITHUB
            : HostingProvider.GITLAB;
    }

    private String repositoryServer()
    {
        GitLabRemote remote = repositoryRemote();
        return remote == null ? null : serverRoot(remote);
    }

    private String repositoryHost()
    {
        GitLabRemote remote = repositoryRemote();
        return remote == null ? null : remote.host();
    }

    /**
     * Хост, для которого открыта форма: из репозитория, а если он не выбран —
     * сохраненный в настройках. По нему выбираются заголовок и тип хостинга.
     */
    private String knownHost()
    {
        String host = repositoryHost();
        if (host != null)
            return host;
        String saved = settings.serverHost();
        return saved == null || saved.isBlank() ? null : saved;
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

    /**
     * Хост введенного адреса. Для сохранения и проверки разбор идет по
     * выбранному провайдеру: так ошибка в адресе видна сразу.
     */
    private static String serverHost(String serverUrl, HostingProvider provider)
    {
        String url = serverUrl == null ? "" : serverUrl.trim(); //$NON-NLS-1$
        if (url.isEmpty())
            return null;
        try
        {
            return provider == HostingProvider.GITHUB ? GitHubRemote.forServer(url).host()
                : GitLabRemote.forServer(url).host();
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    /** Адрес сервера без завершающих слэшей: так он и сохраняется. */
    private static String cleanServerUrl(String serverUrl)
    {
        String base = serverUrl == null ? "" : serverUrl.trim(); //$NON-NLS-1$
        while (base.endsWith("/")) //$NON-NLS-1$
            base = base.substring(0, base.length() - 1);
        return base;
    }

    /**
     * Путь проекта для проверки: из remote репозитория, если проверяется тот же
     * сервер. Для измененного адреса проверяется корень сервера.
     */
    private String projectPath(String serverHost)
    {
        GitLabRemote remote = repositoryRemote();
        if (remote == null || !remote.host().equalsIgnoreCase(serverHost))
            return ""; //$NON-NLS-1$
        return remote.projectPath();
    }

    private void updateTokenLinkText()
    {
        if (tokenLink == null || tokenLink.isDisposed())
            return;
        tokenLink.setText(link(Messages.get("gitLabTokenWhere"))); //$NON-NLS-1$
    }

    private void updateTokenHint()
    {
        if (tokenHint == null || tokenHint.isDisposed())
            return;
        tokenHint.setText(Messages.get(selectedProvider() == HostingProvider.GITHUB
            ? "githubTokenHint" : "gitLabTokenHint")); //$NON-NLS-1$ //$NON-NLS-2$
        tokenHint.getParent().layout();
    }

    private void updatePrefixesLabel()
    {
        if (prefixesLabel == null || prefixesLabel.isDisposed())
            return;
        boolean github = selectedProvider() == HostingProvider.GITHUB;
        prefixesLabel.setText(Messages.get(github
            ? "hostingBranchPrefixes" : "gitLabBranchPrefixes")); //$NON-NLS-1$ //$NON-NLS-2$
        prefixesField.setToolTipText(Messages.get(github
            ? "hostingBranchPrefixesHint" : "gitLabBranchPrefixesHint")); //$NON-NLS-1$ //$NON-NLS-2$
        prefixesLabel.getParent().layout();
    }

    private void openTokenPage()
    {
        String root = serverRoot();
        if (root == null)
            return;
        if (selectedProvider() == HostingProvider.GITHUB)
            Program.launch(root + "/settings/tokens"); //$NON-NLS-1$
        else
            Program.launch(root + "/-/user_settings/personal_access_tokens"); //$NON-NLS-1$
    }

    /** Корень сервера: страница токенов живет в корне, а не под путем проекта. */
    private String serverRoot()
    {
        String url = serverField.getText().trim();
        if (url.isEmpty())
            return null;
        try
        {
            java.net.URI uri = java.net.URI.create(url);
            if (uri.getScheme() == null || uri.getRawAuthority() == null)
                return null;
            return uri.getScheme() + "://" + uri.getRawAuthority(); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
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
        boolean file = settings.certMode() == HostingSettings.CertMode.FILE
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
        system.setSelection(settings.certMode() != HostingSettings.CertMode.FILE);
        system.addListener(SWT.Selection, event -> useSystemCertificates());
        MenuItem file = new MenuItem(menu, SWT.RADIO);
        file.setText(Messages.get("gitLabCertificateChoose")); //$NON-NLS-1$
        file.setSelection(settings.certMode() == HostingSettings.CertMode.FILE);
        file.addListener(SWT.Selection, event -> chooseCertificateFile());
        menu.setLocation(certificateLink.toDisplay(0, certificateLink.getSize().y));
        menu.setVisible(true);
    }

    private void useSystemCertificates()
    {
        settings = HostingSettings.forTesting(serverField.getText(), host(),
            HostingSettings.CertMode.WINDOWS, ""); //$NON-NLS-1$
        updateCertificateLink();
    }

    private void chooseCertificateFile()
    {
        FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
        dialog.setFilterExtensions(new String[] {"*.pem;*.crt;*.cer", "*.*"}); //$NON-NLS-1$ //$NON-NLS-2$
        String selected = dialog.open();
        if (selected == null)
            return;
        settings = HostingSettings.forTesting(serverField.getText(), host(),
            HostingSettings.CertMode.FILE, selected);
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
        HostingProvider provider = selectedProvider();
        String serverUrl = cleanServerUrl(serverField.getText());
        String host = serverHost(serverUrl, provider);
        if (host == null)
        {
            resultLabel.setText(Messages.get("hostingBadAddress")); //$NON-NLS-1$
            return;
        }
        final String projectPath = projectPath(host);
        // Значения полей читаем на потоке UI: из фоновой задачи обращаться к
        // элементам SWT нельзя.
        final String token = effectiveToken();
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
                    HostingLog.write("check start: server=" + serverUrl //$NON-NLS-1$
                        + " provider=" + provider //$NON-NLS-1$
                        + " project=" + (projectPath.isEmpty() ? "нет" : projectPath) //$NON-NLS-1$ //$NON-NLS-2$
                        + " host=" + host //$NON-NLS-1$
                        + " tokenLength=" + token.length() //$NON-NLS-1$
                        + " certMode=" + settings.certMode()); //$NON-NLS-1$
                    MergeRequestProvider client = HostingConnection.probe(serverUrl, provider,
                        projectPath, token);
                    text = checkText(client, provider);
                }
                catch (IllegalArgumentException e)
                {
                    HostingLog.write("check: bad server address", e); //$NON-NLS-1$
                    text = Messages.get("hostingBadAddress"); //$NON-NLS-1$
                }
                catch (HostingException e)
                {
                    HostingLog.write("hosting error: " + e.kind(), e); //$NON-NLS-1$
                    text = textFor(e, provider);
                }
                catch (Exception e)
                {
                    HostingLog.write("unexpected error", e); //$NON-NLS-1$
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

    /**
     * Итог проверки: текущий пользователь, справочно право на создание запроса
     * и доступ к участникам. Недоступные участники проверку не отменяют.
     */
    private static String checkText(MergeRequestProvider client, HostingProvider provider)
        throws HostingException
    {
        StringBuilder text = new StringBuilder(Messages.get("gitLabConnectedAs")) //$NON-NLS-1$
            .append(' ').append(client.currentUser());
        boolean canCreate;
        try
        {
            canCreate = client.canCreateRequests();
        }
        catch (HostingException e)
        {
            // Право на создание запроса — справочный признак: без него проверка
            // подключения все равно полезна.
            HostingLog.write("check: create permission " + e.kind(), e); //$NON-NLS-1$
            canCreate = true;
        }
        if (!canCreate)
            text.append(". ").append(Messages.get(provider == HostingProvider.GITHUB //$NON-NLS-1$
                ? "githubNoWrite" : "gitLabTokenReadOnly")); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            client.projectMembers();
            text.append(". ").append(Messages.get("gitLabMembersAvailable")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (HostingException e)
        {
            if (e.kind() == HostingException.Kind.FORBIDDEN)
                text.append(". ").append(Messages.get(provider == HostingProvider.GITHUB //$NON-NLS-1$
                    ? "githubMembersHidden" : "gitLabHintMemberRead")); //$NON-NLS-1$ //$NON-NLS-2$
            else
                HostingLog.write("check: project members " + e.kind(), e); //$NON-NLS-1$
        }
        return text.toString();
    }

    private static String textFor(HostingException error, HostingProvider provider)
    {
        boolean github = provider == HostingProvider.GITHUB;
        return switch (error.kind())
        {
            case UNAUTHORIZED -> Messages.get("gitLabTokenRejected"); //$NON-NLS-1$
            case FORBIDDEN -> Messages.get(github ? "githubNoWrite" : "gitLabTokenReadOnly"); //$NON-NLS-1$ //$NON-NLS-2$
            case NOT_FOUND -> Messages.get(github ? "githubNotFound" : "gitLabNotFound"); //$NON-NLS-1$ //$NON-NLS-2$
            case TLS -> Messages.get("gitLabCertificateRejected"); //$NON-NLS-1$
            case SERVER -> Messages.get(github ? "githubServerError" : "gitLabServerError"); //$NON-NLS-1$ //$NON-NLS-2$
            case RATE_LIMIT -> github ? Messages.get("githubRateLimit") : fallback(error); //$NON-NLS-1$
            default -> fallback(error);
        };
    }

    private static String fallback(HostingException error)
    {
        return Messages.get("gitLabUnreachable") //$NON-NLS-1$
            + (error.getMessage() == null ? "" : " [" + error.getMessage() + "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Override
    protected void okPressed()
    {
        HostingProvider provider = selectedProvider();
        String serverUrl = cleanServerUrl(serverField.getText());
        String host = serverHost(serverUrl, provider);
        if (host == null)
        {
            resultLabel.setText(Messages.get("hostingBadAddress")); //$NON-NLS-1$
            return;
        }
        settings.save(serverUrl, host, provider, settings.certMode(),
            settings.certFile());
        settings.saveBranchPrefixes(prefixesField.getText());
        String token = effectiveToken();
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
