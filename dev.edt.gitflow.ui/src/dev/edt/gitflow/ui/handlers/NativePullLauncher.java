package dev.edt.gitflow.ui.handlers;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.egit.ui.JobFamilies;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;

import dev.edt.gitflow.core.RepositorySupport;
import dev.edt.gitflow.ui.views.GitFlowView;

public final class NativePullLauncher
{
    private static final String COMMAND_ID = "org.eclipse.egit.ui.team.Pull"; //$NON-NLS-1$
    private NativePullLauncher()
    {
    }

    public static void start(Repository repository, String title)
    {
        IProject project = projectFor(repository);
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (project == null || window == null)
        {
            GitFlowView.publish(title + ": Не удалось найти проект EDT для запуска штатного получения и слияния."); //$NON-NLS-1$
            return;
        }

        IHandlerService handlers = window.getService(IHandlerService.class);
        ICommandService commands = window.getService(ICommandService.class);
        IEvaluationContext context = handlers.createContextSnapshot(false);
        context.addVariable(ISources.ACTIVE_MENU_SELECTION_NAME, new StructuredSelection(project));
        AtomicReference<Job> pullJob = new AtomicReference<>();
        AtomicBoolean pullJobSeen = new AtomicBoolean();
        IJobManager manager = Job.getJobManager();
        JobChangeAdapter listener = new JobChangeAdapter()
        {
            @Override
            public void scheduled(IJobChangeEvent event)
            {
                Job job = event.getJob();
                if (!job.belongsTo(JobFamilies.PULL))
                    return;
                pullJobSeen.set(true);
                if (operatesOn(job, repository) && pullJob.compareAndSet(null, job))
                {
                    GitFlowView.started(repository);
                    GitFlowView.useRepository(repository);
                    GitFlowView.publish(title + ": Запущено штатное получение и слияние EDT. После него проверьте исходящие коммиты."); //$NON-NLS-1$
                }
            }

            @Override
            public void done(IJobChangeEvent event)
            {
                if (event.getJob() != pullJob.get())
                    return;
                manager.removeJobChangeListener(this);
                GitFlowView.finished(repository);
                GitFlowView.publish(title + ": EDT завершила получение и слияние. Отправка не выполнялась."); //$NON-NLS-1$
            }
        };
        manager.addJobChangeListener(listener);
        try
        {
            handlers.executeCommandInContext(
                org.eclipse.core.commands.ParameterizedCommand.generateCommand(
                    commands.getCommand(COMMAND_ID), Map.of()), null, context);
            if (pullJob.get() == null)
            {
                manager.removeJobChangeListener(listener);
                String message = pullJobSeen.get()
                    ? ": Штатный Pull запущен, но не удалось связать его с репозиторием для обновления панели. Проверьте результат в EDT." //$NON-NLS-1$
                    : ": EDT не запустила штатное получение и слияние."; //$NON-NLS-1$
                GitFlowView.publish(title + message);
            }
        }
        catch (Exception e)
        {
            if (pullJob.get() == null)
                manager.removeJobChangeListener(listener);
            GitFlowView.publish(title + ": Не удалось запустить штатное получение и слияние EDT: " //$NON-NLS-1$
                + e.getMessage());
        }
    }

    private static boolean operatesOn(Job job, Repository repository)
    {
        try
        {
            Field outerField = findField(job.getClass(), "this$0"); //$NON-NLS-1$
            outerField.setAccessible(true);
            Object operation = outerField.get(job);
            Field repositoriesField = findField(operation.getClass(), "repositories"); //$NON-NLS-1$
            repositoriesField.setAccessible(true);
            Object repositories = repositoriesField.get(operation);
            if (repositories instanceof Repository[] values)
                for (Repository value : values)
                    if (repository.equals(value))
                        return true;
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            // EGit's Pull job is internal; if its structure changes, keep the native action available.
        }
        return false;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException
    {
        for (Class<?> current = type; current != null; current = current.getSuperclass())
            try
            {
                return current.getDeclaredField(name);
            }
            catch (NoSuchFieldException e)
            {
                // The field may be declared by a superclass.
            }
        throw new NoSuchFieldException(type.getName() + "." + name); //$NON-NLS-1$
    }

    private static IProject projectFor(Repository repository)
    {
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            if (project.isOpen() && repository.equals(RepositorySupport.resolveFor(project)))
                return project;
        return null;
    }
}
