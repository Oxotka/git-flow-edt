package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jgit.lib.Repository;

final class ProjectRefreshSupport
{
    private ProjectRefreshSupport()
    {
    }

    static void afterCheckout(Repository repository)
    {
        if (repository.getWorkTree() == null)
            return;
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (!project.isOpen() || project.getLocation() == null
                || !project.getLocation().toFile().toPath().startsWith(repository.getWorkTree().toPath()))
                continue;
            Job refresh = new Job("Обновление проекта после переключения ветки") //$NON-NLS-1$
            {
                @Override
                protected org.eclipse.core.runtime.IStatus run(IProgressMonitor monitor)
                {
                    try
                    {
                        project.refreshLocal(IProject.DEPTH_INFINITE, monitor);
                        return Status.OK_STATUS;
                    }
                    catch (org.eclipse.core.runtime.CoreException e)
                    {
                        return new Status(org.eclipse.core.runtime.IStatus.ERROR, "dev.edt.gitflow.ui", //$NON-NLS-1$
                            e.getMessage(), e);
                    }
                }
            };
            refresh.setRule(project);
            refresh.schedule();
        }
    }
}
