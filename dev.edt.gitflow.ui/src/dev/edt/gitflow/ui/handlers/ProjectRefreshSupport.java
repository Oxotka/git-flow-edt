package dev.edt.gitflow.ui.handlers;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jgit.lib.Repository;

final class ProjectRefreshSupport
{
    /** Выше этого числа каталогов дешевле обойти проект целиком. */
    private static final int MAX_FOLDERS = 4096;

    private ProjectRefreshSupport()
    {
    }

    static void afterCheckout(Repository repository, List<String> changedPaths)
    {
        if (repository.getWorkTree() == null)
            return;
        List<String> paths = changedPaths == null ? List.of() : changedPaths;
        Path workTree = repository.getWorkTree().toPath();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (!project.isOpen() || project.getLocation() == null
                || !project.getLocation().toFile().toPath().startsWith(workTree))
                continue;
            List<String> relative = relativePaths(project, workTree, paths);
            Job refresh = new Job("Обновление проекта после переключения ветки") //$NON-NLS-1$
            {
                @Override
                protected org.eclipse.core.runtime.IStatus run(IProgressMonitor monitor)
                {
                    try
                    {
                        refresh(project, relative, monitor);
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

    private static void refresh(IProject project, List<String> paths, IProgressMonitor monitor)
        throws CoreException
    {
        Set<String> folders = new LinkedHashSet<>();
        for (String path : paths)
            folders.add(existingParent(project, path));
        if (folders.isEmpty() || folders.size() > MAX_FOLDERS)
        {
            project.refreshLocal(IProject.DEPTH_INFINITE, monitor);
            return;
        }
        monitor.beginTask("Обновление проекта", folders.size()); //$NON-NLS-1$
        try
        {
            for (String folder : folders)
            {
                IResource resource = folder.isEmpty() ? project : project.findMember(folder);
                if (resource != null
                    && (resource.getType() == IResource.FOLDER || resource.getType() == IResource.PROJECT))
                    resource.refreshLocal(IResource.DEPTH_ONE, null);
                monitor.worked(1);
            }
        }
        finally
        {
            monitor.done();
        }
    }

    /**
     * Ближайший существующий каталог проекта для измененного пути. Если файл удален
     * вместе с каталогом, обновляется первый существующий родитель.
     */
    private static String existingParent(IProject project, String path)
    {
        String parent = parentOf(path);
        while (!parent.isEmpty() && project.findMember(parent) == null)
            parent = parentOf(parent);
        return parent;
    }

    private static String parentOf(String path)
    {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash); //$NON-NLS-1$
    }

    private static List<String> relativePaths(IProject project, Path workTree, List<String> paths)
    {
        List<String> result = new ArrayList<>();
        Path projectPath = project.getLocation().toFile().toPath();
        Path prefix;
        try
        {
            prefix = workTree.relativize(projectPath);
        }
        catch (IllegalArgumentException e)
        {
            return result;
        }
        String base = prefix.toString().replace(File.separatorChar, '/');
        for (String path : paths)
        {
            if (base.isEmpty())
                result.add(path);
            else if (path.startsWith(base + '/'))
                result.add(path.substring(base.length() + 1));
        }
        return result;
    }
}
