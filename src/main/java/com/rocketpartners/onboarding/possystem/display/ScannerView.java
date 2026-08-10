package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * The scan bar mounted at the top of the Basket column.
 *
 * <p>Layout, left to right: a {@code SCAN} eyebrow, a monospaced editable field for manual UPC
 * entry, a Scan button that submits the field, and a right-aligned status hint. Sits inside the
 * basket card so the scan and the row it produces are vertically adjacent.</p>
 *
 * <p><strong>Three modes,</strong> switched by the controller:</p>
 * <ul>
 *   <li><em>Idle.</em> Field editable with a RULE border (GO on focus), Scan button enabled iff
 *       the field is non-empty, status hint blank. A permanent "Ready to scan" is furniture — the
 *       cashier stops seeing it — so idle carries no message.</li>
 *   <li><em>Locked.</em> Set on TOTALED. Field greyed and non-editable, Scan button disabled and
 *       flat, status hint reads {@link #STATUS_LOCKED} in {@link PosTheme#LIVE} amber. A cashier
 *       scanning into a dead field with no explanation concludes the hardware failed and starts
 *       unplugging things, so the lock must be conspicuous.</li>
 *   <li><em>Error.</em> A recoverable scan rejection (unknown UPC, non-digit input, likely
 *       misread). Field border turns {@link PosTheme#STOP}, the status hint shows the specific
 *       message in STOP, and the field's contents are selected so the next scan or keystroke
 *       overwrites them wholesale. Any subsequent input clears the error — no tap-to-dismiss, no
 *       modal to close. Kept inline because scan failures are frequent and instantly recoverable,
 *       and a modal costs a dismissal tap with a queue waiting.</li>
 * </ul>
 *
 * <p><strong>Success feedback</strong> is a brief {@link PosTheme#GO} border pulse on the field,
 * timed to the basket flash so a scan and the row it produces read as one event. A success toast
 * on every scan would be noise a hundred times an hour, so nothing more.</p>
 *
 * <p><strong>Dumb view.</strong> No {@code TransactionService} reference, no decisions about what
 * an error means. The controller drives every state transition via
 * {@link #setLocked(boolean)}, {@link #setInlineError(String)}, {@link #clearInlineError()}, and
 * {@link #pulseGo()}.</p>
 */
public class ScannerView extends JPanel {

    /**
     * The locked message shown in {@link PosTheme#LIVE} amber when the transaction is TOTALED.
     * Also dispatched from the KeyEventDispatcher when a scan is attempted while locked.
     */
    public static final String STATUS_LOCKED = "Locked — Complete Payment";

    private static final String PLACEHOLDER = "Scan or type a UPC and press Enter";

    /** Touch-target minimum for the field. Matches {@link PosTheme#BUTTON_HEIGHT_SECONDARY}. */
    private static final int FIELD_MIN_HEIGHT = 44;

    /**
     * How long the accepted-scan pulse lingers before the field border returns to whatever the
     * current mode requires. Matched to {@code CustomerView#FLASH_MS} on purpose so the pulse
     * and the basket-row flash read as one event.
     */
    static final int PULSE_MS = 400;

    private final IPosEventDispatcher dispatcher;

    private final JTextField scanField = new JTextField();
    private final PosButton scanButton = PosButtons.secondary("Scan");
    private final JLabel statusHint = new JLabel(" ");
    private final JLabel eyebrow = new JLabel("SCAN");

    private final Border idleBorder;
    private final Border focusBorder;
    private final Border errorBorder;
    private final Border lockedBorder;
    private final Border pulseBorder;

    private boolean locked;
    private boolean errorShown;

    /** Guards the placeholder swap so the DocumentListener doesn't treat it as user input. */
    private boolean placeholderMutation;

    private Timer pulseTimer;

    /**
     * @param dispatcher target for view-input events; must not be {@code null}
     */
    public ScannerView(IPosEventDispatcher dispatcher) {
        super(new BorderLayout(12, 0));
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        this.dispatcher = dispatcher;
        setOpaque(false);

        eyebrow.setFont(PosTheme.eyebrow());
        eyebrow.setForeground(PosTheme.MUTED);
        eyebrow.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));

        idleBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PosTheme.RULE, 1),
                BorderFactory.createEmptyBorder(9, 12, 9, 12));
        focusBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PosTheme.GO, 2),
                BorderFactory.createEmptyBorder(8, 11, 8, 11));
        errorBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PosTheme.STOP, 2),
                BorderFactory.createEmptyBorder(8, 11, 8, 11));
        lockedBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PosTheme.RULE, 1),
                BorderFactory.createEmptyBorder(9, 12, 9, 12));
        pulseBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PosTheme.GO, 3),
                BorderFactory.createEmptyBorder(7, 10, 7, 10));

        scanField.setName("scanField");
        // Monospaced so digits sit in a fixed-width face — far easier to compare against a
        // printed label than the proportional UI font.
        scanField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        scanField.setPreferredSize(new Dimension(260, FIELD_MIN_HEIGHT));
        scanField.setBorder(idleBorder);
        installPlaceholder(scanField, PLACEHOLDER);
        scanField.addActionListener(e -> submitCurrentField());
        scanField.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                if (!locked && !errorShown && pulseTimer == null) {
                    scanField.setBorder(focusBorder);
                }
            }
            @Override public void focusLost(FocusEvent e) {
                if (!locked && !errorShown && pulseTimer == null) {
                    scanField.setBorder(idleBorder);
                }
            }
        });
        scanField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onFieldChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { onFieldChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { onFieldChanged(); }
        });

        scanButton.setName("scanButton");
        scanButton.setEnabled(false);
        scanButton.addActionListener(e -> submitCurrentField());

        statusHint.setFont(PosTheme.eyebrow());
        statusHint.setForeground(PosTheme.MUTED);
        statusHint.setHorizontalAlignment(SwingConstants.RIGHT);
        // Reserve a stable slice of width so the button doesn't jitter when the hint text length
        // changes between blank, "Locked — Complete Payment", and error copy.
        statusHint.setPreferredSize(new Dimension(220, FIELD_MIN_HEIGHT));

        JPanel rightSide = new JPanel();
        rightSide.setOpaque(false);
        rightSide.setLayout(new BoxLayout(rightSide, BoxLayout.X_AXIS));
        rightSide.add(scanButton);
        rightSide.add(Box.createHorizontalStrut(12));
        rightSide.add(statusHint);

        add(eyebrow, BorderLayout.WEST);
        add(scanField, BorderLayout.CENTER);
        add(rightSide, BorderLayout.EAST);
    }

    // ---- Public API called by ScannerViewController -----------------------

    public JTextField getScanField() {
        return scanField;
    }

    public String getScanText() {
        String text = scanField.getText();
        return isPlaceholderShowing(scanField) || text == null ? "" : text;
    }

    public void setScanText(String text) {
        clearPlaceholderState(scanField);
        scanField.setText(text == null ? "" : text);
    }

    public void clearScanField() {
        clearPlaceholderState(scanField);
        scanField.setText("");
        // Only paint the placeholder back if the field isn't currently focused. After an
        // accepted or rejected scan the controller re-focuses the field via
        // requestScanFieldFocus() — a re-focus on an already-focused component does NOT fire
        // focusGained, so an unconditional showPlaceholderIfEmpty here would leave the
        // placeholder text sitting inside the field. The next character the cashier typed would
        // then concatenate onto the placeholder string ("Scan or type a UPC and press Enter1")
        // and the Scan button would stay disabled because PLACEHOLDER_ACTIVE reads as empty.
        if (!scanField.hasFocus()) {
            showPlaceholderIfEmpty(scanField);
        }
        refreshScanButton();
    }

    public void requestScanFieldFocus() {
        scanField.requestFocusInWindow();
    }

    /**
     * Enter or leave the locked mode.
     *
     * <p>Locked mode disables editing, disables the Scan button, and shows
     * {@link #STATUS_LOCKED} in {@link PosTheme#LIVE} amber. Clears any inline error first so the
     * two modes can't co-exist — the lock supersedes.</p>
     *
     * <p>Idempotent — calling with the current value is a cheap no-op path.</p>
     */
    public void setLocked(boolean locked) {
        if (this.locked == locked) {
            // Idempotent, but still refresh the hint on repeat locks so a re-attempted scan
            // has something re-painting to acknowledge it.
            if (locked) applyLockedHint();
            return;
        }
        this.locked = locked;
        this.errorShown = false;
        scanField.setEditable(!locked);
        scanField.setBackground(locked ? PosTheme.DISABLED_BG : PosTheme.SURFACE);
        if (locked) {
            applyLockedHint();
            scanField.setBorder(lockedBorder);
            if (!isPlaceholderShowing(scanField)) {
                scanField.setForeground(PosTheme.DISABLED_FG);
            }
        } else {
            statusHint.setText(" ");
            statusHint.setForeground(PosTheme.MUTED);
            scanField.setBorder(scanField.hasFocus() ? focusBorder : idleBorder);
            if (!isPlaceholderShowing(scanField)) {
                scanField.setForeground(PosTheme.INK);
            }
        }
        refreshScanButton();
    }

    /**
     * Shows an inline scan error. Border turns {@link PosTheme#STOP}, the status hint shows the
     * message in STOP, and the field's contents are selected so the next scan or keystroke
     * overwrites them wholesale. No dismissal — the next input clears it.
     *
     * <p>Ignored when {@link #setLocked(boolean) locked} — the lock is more important than a
     * transient scan error, and letting an error paint over the locked hint would send the
     * cashier a mixed message.</p>
     */
    public void setInlineError(String message) {
        if (locked) return;
        errorShown = true;
        scanField.setBorder(errorBorder);
        statusHint.setText(message == null ? " " : message);
        statusHint.setForeground(PosTheme.STOP);
        if (!isPlaceholderShowing(scanField)) {
            scanField.selectAll();
        }
        // Make sure the Scan button reflects the current field content — an error paint doesn't
        // change the text, but the caller's flow (submit → error → set-inline) can be reached
        // from a state where the button's enabled bit is stale.
        refreshScanButton();
    }

    /**
     * Clears any inline error. No-op if none is showing or if the view is locked. Called by the
     * controller on the next keystroke or manual submit.
     */
    public void clearInlineError() {
        if (!errorShown || locked) return;
        errorShown = false;
        statusHint.setText(" ");
        statusHint.setForeground(PosTheme.MUTED);
        scanField.setBorder(scanField.hasFocus() ? focusBorder : idleBorder);
    }

    /**
     * Briefly emphasises the field border in {@link PosTheme#GO} to confirm an accepted scan.
     * Timed to the basket flash so the two read as one event.
     */
    public void pulseGo() {
        if (locked) return;
        if (pulseTimer != null) pulseTimer.stop();
        scanField.setBorder(pulseBorder);
        pulseTimer = new Timer(PULSE_MS, e -> {
            pulseTimer.stop();
            pulseTimer = null;
            // Restore whatever border the current mode requires — the pulse must not override
            // an error state that arrived while it was in flight.
            if (locked) scanField.setBorder(lockedBorder);
            else if (errorShown) scanField.setBorder(errorBorder);
            else scanField.setBorder(scanField.hasFocus() ? focusBorder : idleBorder);
        });
        pulseTimer.setRepeats(false);
        pulseTimer.start();
    }

    // ---- Test hooks --------------------------------------------------------

    /** For tests: whether the view is currently locked. */
    boolean isLockedForTest() { return locked; }

    /** For tests: whether an inline error is currently showing. */
    boolean isErrorShownForTest() { return errorShown; }

    /** For tests: the current status-hint text (empty string counts as "blank"). */
    String getStatusHintTextForTest() {
        String t = statusHint.getText();
        return t == null ? "" : t.trim();
    }

    /** For tests: the current status-hint foreground colour. */
    java.awt.Color getStatusHintColorForTest() { return statusHint.getForeground(); }

    /** For tests: the Scan button, so callers can assert enabled state and click it. */
    PosButton getScanButtonForTest() { return scanButton; }

    /** For tests: the field's current border. Useful for asserting error/lock/pulse. */
    Border getFieldBorderForTest() { return scanField.getBorder(); }

    Border idleBorderForTest() { return idleBorder; }
    Border focusBorderForTest() { return focusBorder; }
    Border errorBorderForTest() { return errorBorder; }
    Border lockedBorderForTest() { return lockedBorder; }
    Border pulseBorderForTest() { return pulseBorder; }

    // ---- Internals --------------------------------------------------------

    private void submitCurrentField() {
        Map<String, Object> props = new HashMap<>();
        props.put("raw", getScanText());
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.SCAN_SUBMIT_PRESSED, props));
    }

    private void applyLockedHint() {
        statusHint.setText(STATUS_LOCKED);
        statusHint.setForeground(PosTheme.LIVE);
    }

    private void onFieldChanged() {
        // Placeholder swaps mutate the document; they must not be treated as user typing.
        if (placeholderMutation) return;
        refreshScanButton();
    }

    private void refreshScanButton() {
        boolean nonEmpty = !getScanText().isEmpty();
        scanButton.setEnabled(!locked && nonEmpty);
    }

    // ---- Placeholder plumbing ---------------------------------------------
    // Swing has no native placeholder for JTextField; wire it via focus listeners plus a
    // client property so getScanText() can distinguish "empty" from "user typed the placeholder
    // string literally".

    private static final String PLACEHOLDER_ACTIVE = "scanner.placeholder.active";

    private void installPlaceholder(JTextField field, String text) {
        placeholderMutation = true;
        try {
            field.putClientProperty(PLACEHOLDER_ACTIVE, Boolean.TRUE);
            field.setText(text);
            field.setForeground(PosTheme.MUTED);
        } finally {
            placeholderMutation = false;
        }
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                if (Boolean.TRUE.equals(field.getClientProperty(PLACEHOLDER_ACTIVE))) {
                    placeholderMutation = true;
                    try {
                        field.setText("");
                        field.setForeground(locked ? PosTheme.DISABLED_FG : PosTheme.INK);
                        field.putClientProperty(PLACEHOLDER_ACTIVE, Boolean.FALSE);
                    } finally {
                        placeholderMutation = false;
                    }
                }
            }
            @Override public void focusLost(FocusEvent e) {
                showPlaceholderIfEmpty(field);
            }
        });
    }

    private static boolean isPlaceholderShowing(JTextComponent field) {
        return Boolean.TRUE.equals(field.getClientProperty(PLACEHOLDER_ACTIVE));
    }

    private void clearPlaceholderState(JTextComponent field) {
        placeholderMutation = true;
        try {
            if (isPlaceholderShowing(field)) {
                field.setText("");
            }
            field.setForeground(locked ? PosTheme.DISABLED_FG : PosTheme.INK);
            field.putClientProperty(PLACEHOLDER_ACTIVE, Boolean.FALSE);
        } finally {
            placeholderMutation = false;
        }
    }

    private void showPlaceholderIfEmpty(JTextComponent field) {
        // Invariant: placeholder is visible only when the field is empty AND unfocused. Painting
        // the placeholder into a focused field lets the next keystroke land after it — the buffer
        // still captures the burst correctly via the KeyEventDispatcher, but the JTextField's
        // document ends up with placeholder-plus-user-text.
        if (field.hasFocus()) return;
        String txt = field.getText();
        if (txt == null || txt.isEmpty()) {
            placeholderMutation = true;
            try {
                field.putClientProperty(PLACEHOLDER_ACTIVE, Boolean.TRUE);
                field.setText(PLACEHOLDER);
                field.setForeground(PosTheme.MUTED);
            } finally {
                placeholderMutation = false;
            }
            refreshScanButton();
        }
    }
}
