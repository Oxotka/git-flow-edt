package dev.edt.gitflow.core;

/**
 * Участник проекта GitLab: нужен для выбора проверяющего.
 */
public final class ProjectMember
{
    private final int id;
    private final String name;
    private final String username;

    public ProjectMember(int id, String name, String username)
    {
        this.id = id;
        this.name = name;
        this.username = username;
    }

    public int id()
    {
        return id;
    }

    public String name()
    {
        return name;
    }

    public String username()
    {
        return username;
    }

    /** Подпись для списка: имя, а если его нет — логин. */
    public String displayName()
    {
        return name == null || name.isBlank() ? username : name;
    }

    @Override
    public String toString()
    {
        return displayName();
    }
}
