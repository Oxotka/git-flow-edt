package dev.edt.gitflow.ui.views;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.ProgressMonitorWrapper;

public final class OperationProgressMonitor extends ProgressMonitorWrapper
{
    private final Consumer<String> progress;
    private final BiConsumer<String, String> task;
    private String currentPhase;
    private int totalWork;
    private int worked;
    private int lastPercent = -1;

    public OperationProgressMonitor(IProgressMonitor monitor, String initialPhase,
        Consumer<String> progress, BiConsumer<String, String> task)
    {
        super(monitor);
        currentPhase = initialPhase;
        this.progress = progress;
        this.task = task;
    }

    @Override
    public void beginTask(String name, int totalWork)
    {
        this.totalWork = totalWork;
        worked = 0;
        lastPercent = -1;
        super.beginTask(name, totalWork);
        if (name != null && !name.isBlank())
            currentPhase = name;
        progress.accept(currentPhase);
    }

    @Override
    public void subTask(String name)
    {
        super.subTask(name);
        if (name != null && !name.isBlank())
            currentPhase = name;
        task.accept(name, currentPhase);
    }

    @Override
    public void worked(int work)
    {
        super.worked(work);
        worked += work;
        if (totalWork > 0)
        {
            int percent = Math.min(100, worked * 100 / totalWork);
            if (percent != lastPercent)
            {
                lastPercent = percent;
                progress.accept(currentPhase + " (" + percent + "%)"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

}
