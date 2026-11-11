package dev.edt.gitflow.ui.handlers;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import dev.edt.gitflow.core.ProjectMember;

/**
 * Последние выбранные проверяющие по проекту. Команды повторяются, поэтому
 * прошлые выборы показываются первыми и фильтруются быстрее, чем полный список
 * участников.
 */
public final class ReviewerCache
{
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$
    private static final String NODE = "gitlab"; //$NON-NLS-1$
    private static final String PREFIX = "reviewers."; //$NON-NLS-1$
    private static final int LIMIT = 8;

    private ReviewerCache()
    {
    }

    public static List<ProjectMember> recent(String projectPath)
    {
        List<ProjectMember> result = new ArrayList<>();
        if (projectPath == null || projectPath.isBlank())
            return result;
        String stored = node().get(PREFIX + projectPath, ""); //$NON-NLS-1$
        for (String entry : stored.split(";")) //$NON-NLS-1$
        {
            String[] parts = entry.split("\\|"); //$NON-NLS-1$
            if (parts.length < 2)
                continue;
            try
            {
                result.add(new ProjectMember(Integer.parseInt(parts[0]), parts[1],
                    parts.length > 2 ? parts[2] : parts[1]));
            }
            catch (NumberFormatException e)
            {
                // Испорченная запись кеша не должна мешать созданию merge request.
            }
        }
        return result;
    }

    public static void remember(String projectPath, ProjectMember member)
    {
        if (projectPath == null || projectPath.isBlank() || member == null)
            return;
        List<ProjectMember> all = recent(projectPath);
        all.removeIf(existing -> existing.id() == member.id());
        all.add(0, member);
        while (all.size() > LIMIT)
            all.remove(all.size() - 1);
        StringBuilder stored = new StringBuilder();
        for (ProjectMember item : all)
        {
            if (stored.length() > 0)
                stored.append(';');
            stored.append(item.id()).append('|').append(item.displayName()).append('|')
                .append(item.username());
        }
        Preferences node = node();
        node.put(PREFIX + projectPath, stored.toString());
        try
        {
            node.flush();
        }
        catch (BackingStoreException e)
        {
            // Кеш проверяющих не критичен: при ошибке просто не сохранится.
        }
    }

    private static Preferences node()
    {
        return InstanceScope.INSTANCE.getNode(PLUGIN_ID).node(NODE);
    }
}
