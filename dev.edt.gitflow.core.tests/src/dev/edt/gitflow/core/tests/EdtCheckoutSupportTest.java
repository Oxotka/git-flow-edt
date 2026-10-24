package dev.edt.gitflow.core.tests;

import static org.junit.Assert.*;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.junit.Test;

public class EdtCheckoutSupportTest
{
    public static class Manager
    {
        final List<String> events = new ArrayList<>();
        Object handle = new Object();
        IOException beginFailure;
        IOException endFailure;
        boolean requireExistingRef;
        public Object beginManagedCheckout(Repository repo, Ref ref, IProgressMonitor monitor) throws IOException
        {
            events.add("begin");
            if (requireExistingRef)
            {
                assertNotNull(ref);
                assertEquals(ref.getObjectId(), repo.exactRef(ref.getName()).getObjectId());
            }
            if (beginFailure != null) throw beginFailure;
            return handle;
        }
        public void endManagedCheckout(Object value) throws IOException
        {
            assertSame(handle, value);
            events.add("end");
            if (endFailure != null) throw endFailure;
        }
    }

    static void perform(Manager manager, IOException checkoutFailure, NullProgressMonitor monitor) throws Throwable
    {
        var bundle = org.eclipse.core.runtime.Platform.getBundle("dev.edt.gitflow.ui");
        Class<?> type = bundle.loadClass("dev.edt.gitflow.ui.handlers.EdtCheckoutSupport");
        Class<?> action = bundle.loadClass("dev.edt.gitflow.ui.handlers.EdtCheckoutSupport$Action");
        var method = type.getDeclaredMethod("perform", Object.class, Class.class, Repository.class, Ref.class,
            IProgressMonitor.class, action);
        method.setAccessible(true);
        Object callback = Proxy.newProxyInstance(action.getClassLoader(), new Class<?>[]{action}, (p, m, a) ->
        {
            manager.events.add("checkout");
            if (checkoutFailure != null) throw checkoutFailure;
            return null;
        });
        try { method.invoke(null, manager, Manager.class, null, null, monitor, callback); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    private static void checkout(Manager manager, Repository repository, String target, String start) throws Throwable
    {
        Class<?> type = org.eclipse.core.runtime.Platform.getBundle("dev.edt.gitflow.ui")
            .loadClass("dev.edt.gitflow.ui.handlers.EdtCheckoutSupport");
        var method = type.getDeclaredMethod("checkoutWithManager", Object.class, Class.class, Repository.class,
            String.class, boolean.class, String.class, IProgressMonitor.class);
        method.setAccessible(true);
        try { method.invoke(null, manager, Manager.class, repository, target, true, start,
            new NullProgressMonitor()); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    @Test public void newBranchExistsBeforeNativePreparationAndFailureCleansItUp() throws Throwable
    {
        var work = java.nio.file.Files.createTempDirectory("gitflow-native-new-");
        try (var git = org.eclipse.jgit.api.Git.init().setDirectory(work.toFile()).call())
        {
            git.commit().setAllowEmpty(true).setMessage("base").setAuthor("Test", "test@example.com").call();
            String original = git.getRepository().getBranch();
            var manager = new Manager(); manager.requireExistingRef = true;
            manager.beginFailure = new IOException("preparation failed");
            try { checkout(manager, git.getRepository(), "new-branch", null); fail(); }
            catch (IOException expected) { assertSame(manager.beginFailure, expected); }
            assertNull(git.getRepository().exactRef("refs/heads/new-branch"));
            assertEquals(original, git.getRepository().getBranch());
            manager.beginFailure = null;
            checkout(manager, git.getRepository(), "new-branch", null);
            assertEquals("new-branch", git.getRepository().getBranch());
        }
    }

    @Test public void newTrackingBranchKeepsUpstream() throws Throwable
    {
        var work = java.nio.file.Files.createTempDirectory("gitflow-native-tracking-");
        try (var git = org.eclipse.jgit.api.Git.init().setDirectory(work.toFile()).call())
        {
            var commit = git.commit().setAllowEmpty(true).setMessage("base")
                .setAuthor("Test", "test@example.com").call();
            var repository = git.getRepository();
            var config = repository.getConfig();
            config.setString("remote", "origin", "url", work.toString());
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            config.save();
            var update = repository.updateRef("refs/remotes/origin/topic");
            update.setNewObjectId(commit); update.update();
            var manager = new Manager(); manager.requireExistingRef = true;
            checkout(manager, repository, "topic", "refs/remotes/origin/topic");
            assertEquals("topic", repository.getBranch());
            assertEquals("origin", config.getString("branch", "topic", "remote"));
            assertEquals("refs/heads/topic", config.getString("branch", "topic", "merge"));
        }
    }

    @Test public void nativeEndAlwaysRunsAfterCheckout() throws Throwable
    {
        var m = new Manager(); perform(m, null, new NullProgressMonitor());
        assertEquals(List.of("begin", "checkout", "end"), m.events);
        m = new Manager(); var failure = new IOException("checkout failed");
        try { perform(m, failure, new NullProgressMonitor()); fail(); }
        catch (IOException e) { assertSame(failure, e); }
        assertEquals(List.of("begin", "checkout", "end"), m.events);
    }
    @Test public void failureInBeginDoesNotRunCheckout() throws Throwable
    {
        var m = new Manager(); m.beginFailure = new IOException("begin failed");
        try { perform(m, null, new NullProgressMonitor()); fail(); }
        catch (IOException e) { assertSame(m.beginFailure, e); }
        assertEquals(List.of("begin"), m.events);
    }
    @Test public void nullHandleDoesNotCallEnd() throws Throwable
    {
        var m = new Manager(); m.handle = null; perform(m, null, new NullProgressMonitor());
        assertEquals(List.of("begin", "checkout"), m.events);
    }
    @Test public void endFailureDoesNotReplaceCheckoutFailure() throws Throwable
    {
        var m = new Manager(); m.endFailure = new IOException("end failed");
        var failure = new IOException("checkout failed");
        try { perform(m, failure, new NullProgressMonitor()); fail(); }
        catch (IOException e) { assertSame(failure, e); assertSame(m.endFailure, e.getSuppressed()[0]); }
    }
    @Test public void canceledPreparationStillEndsHandleWithoutSwitching() throws Throwable
    {
        var m = new Manager(); var monitor = new NullProgressMonitor(); monitor.setCanceled(true);
        try { perform(m, null, monitor); fail(); }
        catch (IOException expected) { }
        assertEquals(List.of("begin", "end"), m.events);
    }
}
