package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;

import java.time.Duration;

import org.junit.Test;

import dev.edt.gitflow.core.GitHubClient;
import dev.edt.gitflow.core.GitHubRemote;
import dev.edt.gitflow.core.GitLabClient;
import dev.edt.gitflow.core.GitLabRemote;
import dev.edt.gitflow.core.HostingException;
import dev.edt.gitflow.core.MergeRequestProvider;
import dev.edt.gitflow.ui.views.MergeRequestErrorText;

public class MergeRequestErrorTextTest
{
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private static String message(String key)
    {
        return switch (key)
        {
            case "gitLabTokenRejected" -> "Токен не принят"; //$NON-NLS-1$
            case "gitLabTokenReadOnly" -> "Токен без права записи: нужен scope api"; //$NON-NLS-1$
            case "gitHubCreateForbidden" -> "Нет права на создание %s"; //$NON-NLS-1$
            case "mergeRequestRateLimit" -> "Лимит запросов исчерпан"; //$NON-NLS-1$
            case "mergeRequestCertificateRejected" -> "Сертификат сервера не доверен"; //$NON-NLS-1$
            case "gitLabUnreachable" -> "Сервер не отвечает"; //$NON-NLS-1$
            case "mergeRequestNoConnection" -> "нет связи"; //$NON-NLS-1$
            case "mergeRequestServerError" -> "Сервер ответил ошибкой"; //$NON-NLS-1$
            case "mergeRequestConfigureHosting" -> "Настроить хостинг…"; //$NON-NLS-1$
            case "mergeRequestUnavailable" -> "Статус %s недоступен"; //$NON-NLS-1$
            default -> key;
        };
    }

    private static MergeRequestProvider github()
    {
        return new GitHubClient(GitHubRemote.parse("https://github.com/owner/repo.git"), //$NON-NLS-1$
            "token", null, TIMEOUT); //$NON-NLS-1$
    }

    private static MergeRequestProvider gitlab()
    {
        return new GitLabClient(GitLabRemote.parse(
            "https://gitlab.example.com/group/project.git"), "token", null, TIMEOUT); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String describe(HostingException.Kind kind, MergeRequestProvider client,
        String lastSuccess)
    {
        return MergeRequestErrorText.describe(new HostingException(kind, "fail"), client, //$NON-NLS-1$
            lastSuccess, MergeRequestErrorTextTest::message);
    }

    @Test
    public void explainsAuthorizationAndPermission()
    {
        assertEquals("Токен не принят · <a href=\"settings\">Настроить хостинг…</a>", //$NON-NLS-1$
            describe(HostingException.Kind.UNAUTHORIZED, github(), null));
        assertEquals("Нет права на создание PR", //$NON-NLS-1$
            describe(HostingException.Kind.FORBIDDEN, github(), null));
        assertEquals("Токен без права записи: нужен scope api", //$NON-NLS-1$
            describe(HostingException.Kind.FORBIDDEN, gitlab(), null));
    }

    @Test
    public void explainsLimitCertificateAndServer()
    {
        assertEquals("Лимит запросов исчерпан", //$NON-NLS-1$
            describe(HostingException.Kind.RATE_LIMIT, github(), null));
        assertEquals("Сертификат сервера не доверен", //$NON-NLS-1$
            describe(HostingException.Kind.TLS, github(), null));
        assertEquals("Сервер ответил ошибкой", //$NON-NLS-1$
            describe(HostingException.Kind.SERVER, github(), null));
    }

    @Test
    public void keepsLastRowWhenConnectionLost()
    {
        assertEquals("PR-3 · Черновик · нет связи", //$NON-NLS-1$
            describe(HostingException.Kind.NETWORK, github(), "PR-3 · Черновик")); //$NON-NLS-1$
        assertEquals("Сервер не отвечает", //$NON-NLS-1$
            describe(HostingException.Kind.NETWORK, github(), null));
    }

    @Test
    public void otherKindsUseProviderShortName()
    {
        assertEquals("Статус PR недоступен", //$NON-NLS-1$
            describe(HostingException.Kind.BAD_REQUEST, github(), null));
        assertEquals("Статус MR недоступен", //$NON-NLS-1$
            describe(HostingException.Kind.BAD_REQUEST, gitlab(), null));
    }
}
