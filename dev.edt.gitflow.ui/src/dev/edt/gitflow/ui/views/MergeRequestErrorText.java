package dev.edt.gitflow.ui.views;

import java.util.function.Function;

import dev.edt.gitflow.core.HostingException;
import dev.edt.gitflow.core.MergeRequestProvider;

/**
 * Текст строки состояния merge request при ошибке хостинга. Без SWT:
 * проверяется обычным тестом, сообщения передаются функцией.
 */
public final class MergeRequestErrorText
{
    private MergeRequestErrorText()
    {
    }

    /**
     * Текст ошибки для строки. Провайдер нужен для названия запроса (MR или PR)
     * и ключа сообщения о нехватке прав; {@code null} допустим, пока клиент не
     * создан.
     */
    public static String describe(HostingException error, MergeRequestProvider client,
        String lastSuccess, Function<String, String> messages)
    {
        String shortName = client == null ? "MR" : client.requestShortName(); //$NON-NLS-1$
        return switch (error.kind())
        {
            case UNAUTHORIZED -> messages.apply("gitLabTokenRejected") + " · " //$NON-NLS-1$ //$NON-NLS-2$
                + "<a href=\"settings\">" + messages.apply("mergeRequestConfigureHosting") //$NON-NLS-1$ //$NON-NLS-2$
                + "</a>"; //$NON-NLS-1$
            case FORBIDDEN -> String.format(messages.apply(client == null
                ? "gitLabTokenReadOnly" : client.writePermissionMessageKey()), shortName); //$NON-NLS-1$
            case RATE_LIMIT -> messages.apply("mergeRequestRateLimit"); //$NON-NLS-1$
            case TLS -> messages.apply("mergeRequestCertificateRejected"); //$NON-NLS-1$
            case NETWORK -> lastSuccess == null || lastSuccess.isBlank()
                ? messages.apply("gitLabUnreachable") //$NON-NLS-1$
                : lastSuccess + " · " + messages.apply("mergeRequestNoConnection"); //$NON-NLS-1$ //$NON-NLS-2$
            case SERVER -> messages.apply("mergeRequestServerError"); //$NON-NLS-1$
            default -> String.format(messages.apply("mergeRequestUnavailable"), shortName); //$NON-NLS-1$
        };
    }
}
