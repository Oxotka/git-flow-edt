package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import dev.edt.gitflow.ui.views.MessageUndoHistory;

public class MessageUndoHistoryTest
{
    @Test
    public void undoAndRedoRestoreTextAndSelection()
    {
        MessageUndoHistory history = new MessageUndoHistory();
        MessageUndoHistory.State before = new MessageUndoHistory.State("first", 2, 2); //$NON-NLS-1$
        MessageUndoHistory.State after = new MessageUndoHistory.State("first!", 6, 6); //$NON-NLS-1$

        history.record(before);

        assertEquals(before, history.undo(after));
        assertEquals(after, history.redo(before));
    }

    @Test
    public void aNewEditClearsRedoHistory()
    {
        MessageUndoHistory history = new MessageUndoHistory();
        MessageUndoHistory.State first = new MessageUndoHistory.State("first", 5, 5); //$NON-NLS-1$
        MessageUndoHistory.State second = new MessageUndoHistory.State("first!", 6, 6); //$NON-NLS-1$

        history.record(first);
        history.undo(second);
        history.record(new MessageUndoHistory.State("new", 3, 3)); //$NON-NLS-1$

        assertNull(history.redo(first));
    }
}
