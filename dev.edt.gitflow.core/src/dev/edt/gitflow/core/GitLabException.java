package dev.edt.gitflow.core;

/**
 * Ошибка обращения к GitLab: тип определяет, что показать пользователю.
 */
public final class GitLabException extends Exception
{
    public enum Kind
    {
        UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, BAD_REQUEST, NETWORK, TLS, SERVER
    }

    private final Kind kind;

    public GitLabException(Kind kind, String message, Throwable cause)
    {
        super(message, cause);
        this.kind = kind;
    }

    public GitLabException(Kind kind, String message)
    {
        this(kind, message, null);
    }

    public Kind kind()
    {
        return kind;
    }
}
