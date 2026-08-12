package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.event.IPosEventDispatcher;
import com.rocketpartners.onboarding.possystem.event.PosEvent;
import com.rocketpartners.onboarding.possystem.event.PosEventType;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.text.JTextComponent;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * The scan bar mounted at the top of the Basket column.
 *
 * <p>Layout, two stacked full-width rows: a monospaced editable field for barcode entry on top,
 * and a reserved status/message row beneath it. The field is the only element that grows — it
 * claims the full bar width (GridBagLayout, {@code weightx = 1}) so a twelve-digit UPC has room.
 * Sits inside the basket card so the scan and the row it produces are vertically adjacent.</p>
 *
 * <p><strong>Enter is the only submit trigger.</strong> There is no Scan button: the scanner's
 * terminator is Enter, so hardware reads and manual entry take the same path and there is no
 * second route to keep in sync. Enter on an empty field is a no-op — no event, no journal entry —
 * because cashiers press Enter reflexively. The placeholder carries the affordance.</p>
 *
 * <p><strong>Three modes,</strong> switched by the controller:</p>
 * <ul>
 *   <li><em>Idle.</em> Field editable with a RULE border (GO on focus), message row blank. A
 *       permanent "Ready to scan" is furniture — the cashier stops seeing it — so idle carries no
 *       message.</li>
 *   <li><em>Locked.</em> Set on TOTALED. Field greyed and non-editable, message reads
 *       {@link #STATUS_LOCKED} in {@link PosTheme#LIVE} amber. A cashier scanning into a dead field
 *       with no explanation concludes the hardware failed and starts unplugging things, so the lock
 *       must be conspicuous.</li>
 *   <li><em>Error.</em> A recoverable scan rejection (unknown UPC, non-digit input, likely
 *       misread). Field border turns {@link PosTheme#STOP}, the message shows the specific text in
 *       STOP, and the field's contents are selected so the next scan or keystroke overwrites them
 *       wholesale. Any subsequent input clears the error — no tap-to-dismiss, no modal to close.
 *       Kept inline because scan failures are frequent and instantly recoverable, and a modal costs
 *       a dismissal tap with a queue waiting.</li>
 * </ul>
 *
 * <p><strong>The message row is reserved, not conditional.</strong> It keeps its slot whether or
 * not there is a message, so the bar's height never changes and the basket beneath it never
 * shifts. Message copy is {@link PosTheme#BODY} Title Case — plain reading text, not a tracked
 * eyebrow label — coloured {@link PosTheme#STOP} for errors and {@link PosTheme#LIVE} for the lock.</p>
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

    static final String PLACEHOLDER = "Scan or type a barcode, then Enter";

    /** Touch-target minimum for the field. Matches {@link PosTheme#BUTTON_HEIGHT_SECONDARY}. */
    private static final int FIELD_MIN_HEIGHT = 44;

    /** Reserved height of the message row beneath the field. Fixed so the bar's overall height is
     *  identical whether or not a message is showing — the basket beneath never shifts. */
    private static final int MESSAGE_ROW_HEIGHT = 22;

    /** Vertical gap between the field and the reserved message row. */
    private static final int MESSAGE_GAP = 6;

    /**
     * How long the accepted-scan pulse lingers before the field border returns to whatever the
     * current mode requires. Matched to {@code CustomerView#FLASH_MS} on purpose so the pulse
     * and the basket-row flash read as one event.
     */
    static final int PULSE_MS = 400;

    private final IPosEventDispatcher dispatcher;

    private final JTextField scanField = new JTextField();
    private final JLabel statusHint = new JLabel(" ");

    private final Border idleBorder;
    private final Border focusBorder;
    private final Border errorBorder;
    private final Border lockedBorder;
    private final Border pulseBorder;

    private boolean locked;
    private boolean errorShown;

    private Timer pulseTimer;

    /**
     * @param dispatcher target for view-input events; must not be {@code null}
     */
    public ScannerView(IPosEventDispatcher dispatcher) {
        super(new GridBagLayout());
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher must not be null");
        this.dispatcher = dispatcher;
        setOpaque(false);

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
        // Only the height matters: GridBagLayout stretches the field to the full bar width, so a
        // preferred width would just be ignored. The height is the 44px touch-target minimum.
        scanField.setPreferredSize(new Dimension(0, FIELD_MIN_HEIGHT));
        scanField.setMinimumSize(new Dimension(0, FIELD_MIN_HEIGHT));
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

        // BODY, Title Case, no tracking — plain reading text carrying information (often a
        // twelve-digit number), not a decorative eyebrow. Left-aligned on its own full-width row.
        statusHint.setFont(PosTheme.base(Font.PLAIN, PosTheme.BODY));
        statusHint.setForeground(PosTheme.MUTED);
        statusHint.setHorizontalAlignment(SwingConstants.LEFT);
        statusHint.setPreferredSize(new Dimension(0, MESSAGE_ROW_HEIGHT));
        statusHint.setMinimumSize(new Dimension(0, MESSAGE_ROW_HEIGHT));

        // Two stacked rows. The field claims all surplus width (weightx = 1, horizontal fill); the
        // message row sits directly beneath it, also full width, at its reserved fixed height.
        GridBagConstraints fieldC = new GridBagConstraints();
        fieldC.gridx = 0;
        fieldC.gridy = 0;
        fieldC.weightx = 1.0;
        fieldC.fill = GridBagConstraints.HORIZONTAL;
        add(scanField, fieldC);

        GridBagConstraints msgC = new GridBagConstraints();
        msgC.gridx = 0;
        msgC.gridy = 1;
        msgC.weightx = 1.0;
        msgC.fill = GridBagConstraints.HORIZONTAL;
        msgC.insets = new Insets(MESSAGE_GAP, 2, 0, 2);
        add(statusHint, msgC);
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
        // then concatenate onto the placeholder string and the submit would misread it.
        if (!scanField.hasFocus()) {
            showPlaceholderIfEmpty(scanField);
        }
    }

    public void requestScanFieldFocus() {
        scanField.requestFocusInWindow();
    }

    /**
     * Enter or leave the locked mode.
     *
     * <p>Locked mode disables editing and shows {@link #STATUS_LOCKED} in {@link PosTheme#LIVE}
     * amber. Clears any inline error first so the two modes can't co-exist — the lock supersedes.</p>
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

    /** For tests: the field's current border. Useful for asserting error/lock/pulse. */
    Border getFieldBorderForTest() { return scanField.getBorder(); }

    Border idleBorderForTest() { return idleBorder; }
    Border focusBorderForTest() { return focusBorder; }
    Border errorBorderForTest() { return errorBorder; }
    Border lockedBorderForTest() { return lockedBorder; }
    Border pulseBorderForTest() { return pulseBorder; }

    // ---- Internals --------------------------------------------------------

    private void submitCurrentField() {
        // Enter on an empty field is a no-op: no event dispatched, so nothing is journalled and no
        // error is painted. Cashiers press Enter reflexively; a blank submit must do nothing. A
        // hardware scan never reaches here empty — the buffer only emits on a non-empty burst.
        String raw = getScanText();
        if (raw.isEmpty()) return;
        Map<String, Object> props = new HashMap<>();
        props.put("raw", raw);
        dispatcher.dispatchPosEvent(new PosEvent(PosEventType.SCAN_SUBMIT_PRESSED, props));
    }

    private void applyLockedHint() {
        statusHint.setText(STATUS_LOCKED);
        statusHint.setForeground(PosTheme.LIVE);
    }

    // ---- Placeholder plumbing ---------------------------------------------
    // Swing has no native placeholder for JTextField; wire it via focus listeners plus a
    // client property so getScanText() can distinguish "empty" from "user typed the placeholder
    // string literally".

    private static final String PLACEHOLDER_ACTIVE = "scanner.placeholder.active";

    private void installPlaceholder(JTextField field, String text) {
        field.putClientProperty(PLACEHOLDER_ACTIVE, Boolean.TRUE);
        field.setText(text);
        field.setForeground(PosTheme.MUTED);
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                if (Boolean.TRUE.equals(field.getClientProperty(PLACEHOLDER_ACTIVE))) {
                    field.setText("");
                    field.setForeground(locked ? PosTheme.DISABLED_FG : PosTheme.INK);
                    field.putClientProperty(PLACEHOLDER_ACTIVE, Boolean.FALSE);
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
        if (isPlaceholderShowing(field)) {
            field.setText("");
        }
        field.setForeground(locked ? PosTheme.DISABLED_FG : PosTheme.INK);
        field.putClientProperty(PLACEHOLDER_ACTIVE, Boolean.FALSE);
    }

    private void showPlaceholderIfEmpty(JTextComponent field) {
        // Invariant: placeholder is visible only when the field is empty AND unfocused. Painting
        // the placeholder into a focused field lets the next keystroke land after it — the buffer
        // still captures the burst correctly via the KeyEventDispatcher, but the JTextField's
        // document ends up with placeholder-plus-user-text.
        if (field.hasFocus()) return;
        String txt = field.getText();
        if (txt == null || txt.isEmpty()) {
            field.putClientProperty(PLACEHOLDER_ACTIVE, Boolean.TRUE);
            field.setText(PLACEHOLDER);
            field.setForeground(PosTheme.MUTED);
        }
    }
}
