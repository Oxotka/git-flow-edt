package dev.edt.gitflow.ui.handlers;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.osgi.framework.Bundle;
import org.osgi.util.tracker.ServiceTracker;

import dev.edt.gitflow.core.BranchOperations;

/** Public EDT snapshot API, discovered at runtime for installations without snapshot integration. */
final class EdtCheckoutSupport
{
    private static final String BUNDLE = "com.e1c.g5.v8.dt.snapshot.integration.git"; //$NON-NLS-1$
    private static final String SERVICE = "com.e1c.g5.v8.dt.snapshot.integration.git.IGitIntegrationManager"; //$NON-NLS-1$

    private EdtCheckoutSupport()
    {
    }

    static void checkout(Repository repository, String target, boolean create, String startPoint,
        IProgressMonitor monitor) throws GitAPIException, IOException
    {
        Bundle bundle = Platform.getBundle(BUNDLE);
        if (bundle == null)
        {
            BranchOperations.checkoutDirect(repository, target, create, startPoint, monitor);
            return;
        }
        try
        {
            bundle.start(Bundle.START_TRANSIENT);
            Class<?> api = bundle.loadClass(SERVICE);
            ServiceTracker<Object, Object> tracker = new ServiceTracker<>(bundle.getBundleContext(), SERVICE, null);
            try
            {
                tracker.open();
                Object manager = tracker.getService();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (manager == null && System.nanoTime() < deadline && !monitor.isCanceled())
                    manager = tracker.waitForService(100);
                if (monitor.isCanceled())
                    throw new IOException(Messages.get("checkoutCancelledBeforeSwitch")); //$NON-NLS-1$
                if (manager == null)
                    throw new IOException(Messages.get("checkoutIntegrationUnavailable")); //$NON-NLS-1$
                checkoutWithManager(manager, api, repository, target, create, startPoint, monitor);
            }
            finally
            {
                tracker.close();
            }
        }
        catch (GitAPIException | IOException e)
        {
            throw e;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException(Messages.get("checkoutCancelledBeforeSwitch"), e); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            throw new IOException(Messages.get("checkoutIntegrationFailed"), e); //$NON-NLS-1$
        }
    }

    static void checkoutWithManager(Object manager, Class<?> api, Repository repository, String target,
        boolean create, String startPoint, IProgressMonitor monitor) throws Exception
    {
        Git git = Git.wrap(repository);
        Ref created = null;
        if (create)
        {
            var command = git.branchCreate().setName(target);
            if (startPoint != null)
                command.setStartPoint(startPoint).setUpstreamMode(SetupUpstreamMode.TRACK);
            created = command.call();
        }
        try
        {
            Ref ref = repository.findRef(target);
            perform(manager, api, repository, ref, monitor,
                () -> BranchOperations.checkoutDirect(repository, target, false, null, monitor));
        }
        catch (Exception failure)
        {
            if (created != null)
            {
                try
                {
                    Ref current = repository.exactRef(created.getName());
                    if (!created.getName().equals(repository.getFullBranch()) && current != null
                        && created.getObjectId().equals(current.getObjectId()))
                        git.branchDelete().setBranchNames(created.getName()).setForce(true).call();
                }
                catch (Exception cleanupFailure)
                {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    @FunctionalInterface
    interface Action
    {
        void run() throws GitAPIException, IOException;
    }

    static void perform(Object manager, Class<?> api, Repository repository, Ref target,
        IProgressMonitor monitor, Action action) throws Exception
    {
        Object handle = invoke(api.getMethod("beginManagedCheckout", Repository.class, Ref.class, //$NON-NLS-1$
            IProgressMonitor.class), manager, repository, target, monitor);
        Exception failure = null;
        try
        {
            if (monitor.isCanceled())
                throw new IOException(Messages.get("checkoutCancelledBeforeSwitch")); //$NON-NLS-1$
            action.run();
        }
        catch (Exception e)
        {
            failure = e;
            throw e;
        }
        finally
        {
            if (handle != null)
            {
                try
                {
                    invoke(api.getMethod("endManagedCheckout", Object.class), manager, handle); //$NON-NLS-1$
                }
                catch (Exception e)
                {
                    if (failure == null)
                        throw e;
                    failure.addSuppressed(e);
                }
            }
        }
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object... arguments) throws Exception
    {
        try
        {
            return method.invoke(target, arguments);
        }
        catch (InvocationTargetException e)
        {
            if (e.getCause() instanceof Exception cause)
                throw cause;
            throw e;
        }
    }
}
