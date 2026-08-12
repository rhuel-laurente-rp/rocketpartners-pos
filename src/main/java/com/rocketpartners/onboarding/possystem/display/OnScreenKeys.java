package com.rocketpartners.onboarding.possystem.display;

import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;

/**
 * Shared document-mutation primitives for the on-screen input components
 * ({@link OnScreenKeypad}, {@link OnScreenKeyboard}).
 *
 * <p>Every operation goes through the target field's {@link Document}, never through synthesised
 * {@link java.awt.event.KeyEvent}s. That keeps on-screen typing invisible to the application-wide
 * {@link java.awt.KeyEventDispatcher} that captures scanner bursts, and — critically — routes
 * through whatever {@link javax.swing.text.DocumentFilter} the field already installed, so
 * digit-only, single-decimal-point, and length-cap rules apply to a tapped key exactly as they do
 * to a physical keystroke.</p>
 *
 * <p>Each operation also positions the caret explicitly rather than leaning on the field's own
 * caret tracking. The keys are non-focusable and mutate the document programmatically, so the
 * field's {@link javax.swing.text.Caret} can't be relied on to advance on its own — driving it
 * here keeps successive taps landing where the last one left off.</p>
 */
final class OnScreenKeys {

    private OnScreenKeys() {}

    /**
     * Inserts {@code text} at the caret, replacing any current selection — the same behaviour a
     * physical keystroke has. Routes through the document's {@code replace}, so the field's
     * document filter still gets to reject it; a rejected edit leaves the field untouched.
     */
    static void insert(JTextComponent field, String text) {
        if (!editable(field) || text == null || text.isEmpty()) return;
        Document doc = field.getDocument();
        int start = Math.min(field.getSelectionStart(), field.getSelectionEnd());
        int end = Math.max(field.getSelectionStart(), field.getSelectionEnd());
        int lengthBefore = doc.getLength();
        try {
            if (doc instanceof AbstractDocument ad) {
                // Single replace op → the filter sees it as one replace (matching real typing over
                // a selection), and either accepts the whole thing or rejects it wholesale.
                ad.replace(start, end - start, text, null);
            } else {
                if (end > start) doc.remove(start, end - start);
                doc.insertString(start, text, null);
            }
        } catch (BadLocationException ignored) {
            return;
        }
        // How many characters the filter actually accepted (0 if it rejected the edit).
        int accepted = doc.getLength() - (lengthBefore - (end - start));
        setCaret(field, start + Math.max(0, accepted));
    }

    /**
     * Deletes at the caret: the current selection if there is one, otherwise the single character
     * before the caret. Mirrors the Backspace key.
     */
    static void backspace(JTextComponent field) {
        if (!editable(field)) return;
        Document doc = field.getDocument();
        int start = Math.min(field.getSelectionStart(), field.getSelectionEnd());
        int end = Math.max(field.getSelectionStart(), field.getSelectionEnd());
        try {
            if (end > start) {
                doc.remove(start, end - start);
                setCaret(field, start);
            } else if (start > 0) {
                doc.remove(start - 1, 1);
                setCaret(field, start - 1);
            }
        } catch (BadLocationException ignored) {
            // Offsets are read from the field's own selection model, so they are always valid;
            // unreachable in practice.
        }
    }

    /** Empties the field. Mirrors the Clear key. */
    static void clear(JTextComponent field) {
        if (!editable(field)) return;
        Document doc = field.getDocument();
        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException ignored) {
            // 0..length is always a valid span; unreachable in practice.
        }
    }

    private static void setCaret(JTextComponent field, int position) {
        int clamped = Math.max(0, Math.min(position, field.getDocument().getLength()));
        field.setCaretPosition(clamped);
    }

    private static boolean editable(JTextComponent field) {
        return field != null && field.isEnabled() && field.isEditable();
    }
}
