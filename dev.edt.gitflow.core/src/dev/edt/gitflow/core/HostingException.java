package dev.edt.gitflow.core;

/**
 * Ошибка обращения к хостингу (GitLab, GitHub): тип определяет, что показать
 * пользователю.
 */
public final class HostingException extends Exception
{
    public enum Kind
    {
        UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, BAD_REQUEST, NETWORK, TLS, SERVER, RATE_LIMIT
    }

    private final Kind kind;

    public HostingException(Kind kind, String message, Throwable cause)
    {
        super(message, cause);
        this.kind = kind;
    }

    public HostingException(Kind kind, String message)
    {
        this(kind, message, null);
    }

    public Kind kind()
    {
        return kind;
    }
}
