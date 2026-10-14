package dev.edt.gitflow.ui.views;

import java.util.ArrayDeque;
import java.util.Deque;

public final class MessageUndoHistory
{
    private static final int MAX_HISTORY = 100;
    public record State(String text, int selectionStart, int selectionEnd)
    {
    }

    private final Deque<State> undo = new ArrayDeque<>();
    private final Deque<State> redo = new ArrayDeque<>();

    public void record(State before)
    {
        undo.push(before);
        if (undo.size() > MAX_HISTORY)
            undo.removeLast();
        redo.clear();
    }

    public State undo(State current)
    {
        if (undo.isEmpty())
            return null;
        redo.push(current);
        return undo.pop();
    }

    public State redo(State current)
    {
        if (redo.isEmpty())
            return null;
        undo.push(current);
        return redo.pop();
    }

    public void clear()
    {
        undo.clear();
        redo.clear();
    }

    public boolean canUndo()
    {
        return !undo.isEmpty();
    }

    public boolean canRedo()
    {
        return !redo.isEmpty();
    }
}
