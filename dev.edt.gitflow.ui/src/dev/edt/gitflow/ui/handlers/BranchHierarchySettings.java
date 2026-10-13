package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.IStateListener;
import org.eclipse.core.commands.State;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

public final class BranchHierarchySettings
{
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$
    private static final String COMMAND_ID = "org.eclipse.egit.ui.RepositoriesToggleBranchHierarchy"; //$NON-NLS-1$
    private static final String STATE_ID = "org.eclipse.ui.commands.toggleState"; //$NON-NLS-1$
    private static final String PREFERENCE = "branchHierarchy"; //$NON-NLS-1$
    private static boolean initialized;

    private BranchHierarchySettings()
    {
    }

    public static void initialize()
    {
        if (initialized)
            return;
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        Command command = commands.getCommand(COMMAND_ID);
        State state = command.getState(STATE_ID);
        if (state == null)
        {
            logWarning("В EGit не найдено состояние иерархии веток.", null); //$NON-NLS-1$
            return;
        }
        Preferences preferences = InstanceScope.INSTANCE.getNode(PLUGIN_ID).node("branchPicker"); //$NON-NLS-1$
        state.addListener(new IStateListener()
        {
            @Override
            public void handleStateChange(State changedState, Object oldValue)
            {
                preferences.putBoolean(PREFERENCE, Boolean.TRUE.equals(changedState.getValue()));
                try
                {
                    preferences.flush();
                }
                catch (BackingStoreException e)
                {
                    logWarning("Не удалось сохранить настройку иерархии веток.", e); //$NON-NLS-1$
                }
            }
        });
        state.setValue(preferences.getBoolean(PREFERENCE, true));
        initialized = true;
    }

    public static State state()
    {
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        return commands.getCommand(COMMAND_ID).getState(STATE_ID);
    }

    public static boolean isEnabled()
    {
        State state = state();
        return state != null && Boolean.TRUE.equals(state.getValue());
    }

    private static void logWarning(String message, Exception error)
    {
        Platform.getLog(Platform.getBundle(PLUGIN_ID)).log(new Status(IStatus.WARNING,
            PLUGIN_ID, message, error));
    }
}
