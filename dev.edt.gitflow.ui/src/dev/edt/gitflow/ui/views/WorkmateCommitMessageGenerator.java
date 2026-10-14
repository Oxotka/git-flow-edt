package dev.edt.gitflow.ui.views;

import java.util.Map;
import java.util.function.BiConsumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jgit.lib.Repository;

import com.e1c.edt.ai.CancellationTokenSource;
import com.e1c.edt.ai.IConversationFacade;
import com.e1c.edt.ai.IProjectProvider;
import com.e1c.edt.ai.assistent.SendMessageResult;
import com.e1c.edt.ai.assistent.SendUserMessageRequest;
import com.e1c.edt.ai.assistent.model.SkillExecutionRequest;
import com.e1c.edt.ai.assistent.model.SkillExecutionResult;
import com.e1c.edt.ai.skills.ISkillExecutor;
import com.e1c.edt.ai.ui.BaseActivator;
import com.e1c.edt.ai.ui.CommitMessageSanitizer;
import com.e1c.edt.ai.ui.ConversationProgressReporter;
import com.e1c.edt.ai.ui.IDispatcher;
import com.e1c.edt.ai.ui.JobContext;
import com.e1c.edt.ai.ui.JobFutures;
import com.google.inject.Inject;

import dev.edt.gitflow.core.WorkingChanges;
import dev.edt.gitflow.ui.handlers.Messages;

/** Workmate bridge. Loaded reflectively so Git Flow still works without Workmate. */
public final class WorkmateCommitMessageGenerator
{
    private static final String SKILL_ID = "git-commit"; //$NON-NLS-1$

    @Inject
    private IDispatcher dispatcher;
    @Inject
    private IConversationFacade conversationFacade;
    @Inject
    private IProjectProvider projectProvider;
    @Inject
    private ISkillExecutor skillExecutor;

    public static boolean isAvailable()
    {
        return BaseActivator.getDefault() != null;
    }

    public static void generate(Repository repository, String userText, BiConsumer<String, String> callback)
    {
        WorkmateCommitMessageGenerator generator = new WorkmateCommitMessageGenerator();
        BaseActivator.injectMembers(generator);
        if (generator.dispatcher == null || generator.conversationFacade == null
            || generator.projectProvider == null || generator.skillExecutor == null)
            throw new IllegalStateException("Workmate services are unavailable"); //$NON-NLS-1$

        CancellationTokenSource cancellation = new CancellationTokenSource();
        String jobName = Messages.get("generateCommitMessageJob"); //$NON-NLS-1$
        Job job = generator.dispatcher.createJob(jobName, context ->
        {
            context.Monitor.beginTask(jobName, -1);
            try
            {
                generator.generate(context, cancellation, repository, userText, callback);
            }
            catch (RuntimeException e)
            {
                generator.dispatcher.dispatchAsync(() -> callback.accept(null, rootMessage(e)));
            }
        }, false, cancellation);
        job.setPriority(Job.SHORT);
        job.schedule();
    }

    private void generate(JobContext context, CancellationTokenSource cancellation,
        Repository repository, String userText, BiConsumer<String, String> callback)
    {
        String workingDirectory = repository.getWorkTree().getAbsolutePath();
        IProject project = projectProvider.getProject(workingDirectory).orElse(null);
        if (project == null)
        {
            dispatcher.dispatchAsync(() -> callback.accept(null, Messages.get("workmateProjectMissing"))); //$NON-NLS-1$
            return;
        }

        String diff;
        try
        {
            diff = WorkingChanges.diffForCommitMessage(repository);
        }
        catch (java.io.IOException | org.eclipse.jgit.api.errors.GitAPIException e)
        {
            dispatcher.dispatchAsync(() -> callback.accept(null, rootMessage(e)));
            return;
        }
        if (diff.isBlank())
        {
            dispatcher.dispatchAsync(() -> callback.accept(null, Messages.get("generateCommitMessageNoChanges"))); //$NON-NLS-1$
            return;
        }

        SkillExecutionRequest skillRequest = new SkillExecutionRequest(SKILL_ID, Map.of(
            "user_text", userTextWithDiff(userText, diff), //$NON-NLS-1$
            "working_directory", workingDirectory, //$NON-NLS-1$
            "max_commit_count", "5")); //$NON-NLS-1$
        ConversationProgressReporter progress = new ConversationProgressReporter(context.Monitor);
        var result = skillExecutor.executeAsync(skillRequest, cancellation)
            .thenCompose(skill -> sendMessage(project, skill, cancellation, progress));
        var completed = result.handle((message, error) ->
        {
            String text = error == null ? sanitize(message) : null;
            String failure = error == null ? null : rootMessage(error);
            dispatcher.dispatchAsync(() -> callback.accept(text, failure));
            return null;
        });
        JobFutures.await(context, completed, cancellation);
    }

    private static String userTextWithDiff(String userText, String diff)
    {
        StringBuilder request = new StringBuilder(
            "Use the supplied Git diff as the source for the commit message. Do not stage files or change the repository index."); //$NON-NLS-1$
        if (userText != null && !userText.isBlank())
            request.append("\n\nAdditional guidance from the user:\n").append(userText); //$NON-NLS-1$
        return request.append("\n\nGit diff:\n").append(diff).toString(); //$NON-NLS-1$
    }

    private java.util.concurrent.CompletionStage<SendMessageResult> sendMessage(IProject project,
        SkillExecutionResult skill, CancellationTokenSource cancellation,
        ConversationProgressReporter progress)
    {
        SendUserMessageRequest request = new SendUserMessageRequest(project, skill.getPrompt(), null,
            true, "edt", Boolean.TRUE, 200, skill.getAllowedTools().orElse(null), //$NON-NLS-1$
            skill.getCompletionPolicy().orElse(null));
        return conversationFacade.sendAsync(request, cancellation, progress);
    }

    private static String sanitize(SendMessageResult result)
    {
        if (result == null || result.getText() == null)
            return ""; //$NON-NLS-1$
        return CommitMessageSanitizer.sanitize(result.getText());
    }

    private static String rootMessage(Throwable error)
    {
        Throwable cause = error;
        while (cause.getCause() != null)
            cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
