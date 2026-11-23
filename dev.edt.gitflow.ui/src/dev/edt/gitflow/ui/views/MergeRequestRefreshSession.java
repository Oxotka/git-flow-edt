package dev.edt.gitflow.ui.views;

/** Контекст фонового обновления карточки. Используется только потоком UI. */
public final class MergeRequestRefreshSession
{
    private String key = ""; //$NON-NLS-1$
    private long revision;

    public boolean matches(String selectedKey)
    {
        return key.equals(selectedKey);
    }

    public boolean select(String selectedKey)
    {
        if (matches(selectedKey))
            return false;
        key = selectedKey;
        revision++;
        return true;
    }

    public long begin()
    {
        return ++revision;
    }

    public boolean isCurrent(long request)
    {
        return request == revision;
    }

    public void clear()
    {
        key = ""; //$NON-NLS-1$
        revision++;
    }
}
