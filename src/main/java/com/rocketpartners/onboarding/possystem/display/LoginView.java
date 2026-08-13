package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.possystem.component.Journal;
import com.rocketpartners.onboarding.possystem.component.JournalRecord;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.net.URL;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The sign-in screen shown before the POS boots. A standalone {@link JFrame} at the same fixed
 * 1512×982 register surface as {@link CustomerView} — disposed on a successful login, at which
 * point {@link com.rocketpartners.onboarding.possystem.Application} constructs and starts the POS.
 *
 * <p><strong>It runs before the event bus exists.</strong> There is no {@code PosComponent} and no
 * {@link com.rocketpartners.onboarding.possystem.event.PosEvent} here — login is a pre-flight step,
 * not part of the transaction lifecycle. It talks to two collaborators only: a {@link Journal}
 * (to record the attempt) and an {@code onLoginSuccess} callback carrying the operator id into the
 * session. Keep it that way; wiring this into the bus would blur the ordering the whole startup
 * sequence depends on.</p>
 *
 * <p><strong>This is not authentication.</strong> The credential check is
 * {@link DemoCredentials#matches(String, char[])} — a hardcoded pair, no user store, no hashing,
 * no roles. Both fields are pre-filled so a presenter can click straight through, and a MUTED
 * "Demo build" marker sits on screen so nobody watching mistakes it for the real thing. See
 * {@link DemoCredentials} for what a real deployment must replace.</p>
 *
 * <p><strong>Layout.</strong> The window splits evenly: the product vector on the left, centred and
 * scaled to fit within a slim margin (aspect preserved, never distorted) on a {@link PosTheme#GO}
 * green panel — a missing image leaves a plain green panel rather than crashing — and the sign-in
 * form centred on a {@link PosTheme#SURFACE} background on the right.</p>
 *
 * <p><strong>Both fields are numeric</strong>, which shapes the rest: a digit-only
 * {@link DocumentFilter} with a length cap on each (same shape as the quantity field's), an
 * always-visible {@link OnScreenKeypad} with no decimal key that follows focus between the two
 * fields, and a keypad {@code →} key that <em>advances focus</em> — from Operator ID it moves to
 * PIN, from PIN it moves to the Login button. It never submits: the Login button is the single
 * submit path, so there is no "did the arrow or the button do it" ambiguity, and the small grey
 * keypad key never gets mistaken for the wide green submit button. The PIN never unmasks (a real
 * lane is public) and there is no sign-up link (accounts come from head office).</p>
 *
 * <p><strong>Logout / shift-change are out of scope.</strong> They would hook in as a control on
 * the running POS (e.g. a header action on {@link CustomerView}) that, on activation, disposes the
 * POS window and re-shows a fresh {@code LoginView} — the inverse of the success path in
 * {@code Application} — so the operator id carried into the session is replaced rather than
 * mutated.</p>
 */
public class LoginView extends JFrame {

    /** Fixed register-display size, matched to {@link CustomerView} so the two screens read as one product. */
    private static final int WINDOW_WIDTH = 1512;
    private static final int WINDOW_HEIGHT = 982;

    /** Width of the centred sign-in form. */
    private static final int FORM_WIDTH = 380;

    /** Max characters either field accepts. Demo ids/PINs are 4–6 digits; the cap keeps input tidy
     *  and, as on the quantity field, keeps a keyed run inside a sane length. */
    private static final int MAX_LENGTH = 6;

    /** Login button height — the brief's ≥52 px touch target, a hair taller than the standard primary. */
    private static final int LOGIN_BUTTON_HEIGHT = 52;

    /** Classpath location of the left-hand product vector. */
    static final String VECTOR_RESOURCE = "/login-vector.png";

    /**
     * The single message shown for every failed attempt — wrong id, wrong PIN, or empty field. One
     * message, never "unknown id" vs "wrong PIN": that is both the correct security posture and
     * simpler. Title Case, matching the app-wide label convention.
     */
    static final String INCORRECT_MESSAGE = "Incorrect Operator ID Or PIN";

    /** Placeholder that keeps the message row's slot occupied so the form never changes height. */
    private static final String MESSAGE_PLACEHOLDER = " ";

    private final transient Journal journal;
    private final String storeName;
    private final int laneNumber;
    private final transient Consumer<String> onLoginSuccess;

    private final JTextField operatorField = new JTextField();
    private final JPasswordField pinField = new JPasswordField();
    private final JLabel messageLabel = new JLabel(MESSAGE_PLACEHOLDER);
    private final PosButton loginButton = PosButtons.primary("Login");
    private OnScreenKeypad keypad;
    private VectorPanel vectorPanel;
    private JPanel form;

    /**
     * @param journal        sink for the login-attempt record; must not be {@code null}
     * @param storeName      store label attached to the journal record; must not be {@code null}
     * @param laneNumber     lane number attached to the journal record
     * @param onLoginSuccess invoked with the operator id after a successful login (the window is
     *                       already disposed by then); must not be {@code null}
     */
    public LoginView(Journal journal, String storeName, int laneNumber,
                     Consumer<String> onLoginSuccess) {
        super("Rocket POS — Sign In");
        if (journal == null) throw new IllegalArgumentException("journal must not be null");
        if (storeName == null) throw new IllegalArgumentException("storeName must not be null");
        if (onLoginSuccess == null) throw new IllegalArgumentException("onLoginSuccess must not be null");
        this.journal = journal;
        this.storeName = storeName;
        this.laneNumber = laneNumber;
        this.onLoginSuccess = onLoginSuccess;

        // Fixed, non-resizable, centred — same surface contract as CustomerView. DISPOSE (not EXIT)
        // by default keeps the class test-friendly; Application flips it to EXIT_ON_CLOSE so closing
        // the sign-in window ends the process.
        setPreferredSize(new Dimension(WINDOW_WIDTH, WINDOW_HEIGHT));
        setSize(new Dimension(WINDOW_WIDTH, WINDOW_HEIGHT));
        setResizable(false);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new GridLayout(1, 2, 0, 0));
        root.add(buildVectorHalf());
        root.add(buildFormHalf());
        setContentPane(root);

        wireBehaviour();
        prefillDemoCredentials();
        setLocationRelativeTo(null);
    }

    // ---- Layout ------------------------------------------------------------

    private JPanel buildVectorHalf() {
        vectorPanel = new VectorPanel(loadVector());
        return vectorPanel;
    }

    private JPanel buildFormHalf() {
        JPanel half = new JPanel(new GridBagLayout());
        half.setBackground(PosTheme.SURFACE);
        // GridBagLayout with a single child and default constraints centres it at its preferred
        // size — the form block sits in the middle of the right half without stretching.
        half.add(buildForm());
        return half;
    }

    private JPanel buildForm() {
        form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JLabel heading = new JLabel("Login");
        heading.setFont(PosTheme.base(Font.BOLD, PosTheme.DISPLAY));
        heading.setForeground(PosTheme.INK);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(heading);
        form.add(Box.createVerticalStrut(6));

        JLabel subtitle = new JLabel("Sign in to start your shift.");
        subtitle.setFont(PosTheme.base(Font.PLAIN, PosTheme.BODY));
        subtitle.setForeground(PosTheme.MUTED);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(subtitle);
        form.add(Box.createVerticalStrut(24));

        form.add(fieldGroup("Operator ID", operatorField, "Enter your operator id"));
        form.add(Box.createVerticalStrut(16));
        form.add(fieldGroup("PIN", pinField, "Enter your PIN"));
        form.add(Box.createVerticalStrut(10));

        // Reserved message row — always present, only its text changes, so the form's height is
        // identical whether or not an error is showing and nothing shifts under the cashier.
        messageLabel.setFont(PosTheme.base(Font.PLAIN, PosTheme.BODY));
        messageLabel.setForeground(PosTheme.STOP);
        messageLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        messageLabel.setMaximumSize(new Dimension(FORM_WIDTH, messageLabel.getPreferredSize().height));
        form.add(messageLabel);
        form.add(Box.createVerticalStrut(14));

        // One keypad, no decimal key, always visible. It follows focus between the two fields
        // (see the focus listeners in wireBehaviour) and its → key advances focus to the next field.
        keypad = new OnScreenKeypad(operatorField, false, this::onKeypadNext);
        keypad.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(keypad);
        form.add(Box.createVerticalStrut(18));

        loginButton.setTouchMinHeight(LOGIN_BUTTON_HEIGHT);
        loginButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        loginButton.setMaximumSize(new Dimension(FORM_WIDTH, LOGIN_BUTTON_HEIGHT + PosButton.SHADOW_INSET));
        form.add(loginButton);
        form.add(Box.createVerticalStrut(16));

        JLabel demoMarker = new JLabel("Demo build — not real authentication");
        demoMarker.setFont(PosTheme.base(Font.PLAIN, PosTheme.EYEBROW));
        demoMarker.setForeground(PosTheme.MUTED);
        demoMarker.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(demoMarker);

        return form;
    }

    /** An eyebrow label above a numeric field, sized to the form width. */
    private JPanel fieldGroup(String label, JTextField field, String placeholder) {
        JPanel group = new JPanel();
        group.setOpaque(false);
        group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
        group.setAlignmentX(Component.LEFT_ALIGNMENT);
        group.setMaximumSize(new Dimension(FORM_WIDTH, 84));

        JLabel eyebrow = new JLabel(label);
        eyebrow.setFont(PosTheme.eyebrow());
        eyebrow.setForeground(PosTheme.MUTED);
        eyebrow.setAlignmentX(Component.LEFT_ALIGNMENT);
        group.add(eyebrow);
        group.add(Box.createVerticalStrut(6));

        field.setFont(PosTheme.base(Font.BOLD, PosTheme.HEADLINE));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PosTheme.RULE, 1),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        field.setPreferredSize(new Dimension(FORM_WIDTH, 52));
        field.setMaximumSize(new Dimension(FORM_WIDTH, 52));
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Placeholder via FlatLaf's client property — NOT a focus listener, which would put the
        // placeholder text into the document and break empty-field validation.
        field.putClientProperty("JTextField.placeholderText", placeholder);
        installDigitFilter(field);
        group.add(field);
        return group;
    }

    // ---- Behaviour ---------------------------------------------------------

    private void wireBehaviour() {
        loginButton.addActionListener(e -> attemptLogin());

        // Physical Enter (development happens on a laptop) advances focus, it does not submit:
        // Operator ID -> PIN, PIN -> the Login button. Submitting is exclusively the Login button.
        // JPasswordField extends JTextField, so both fire an ActionEvent on Enter.
        operatorField.addActionListener(e -> advanceToPin());
        pinField.addActionListener(e -> focusLoginButton());

        // The one keypad follows focus: whichever field the cashier is in becomes its target.
        operatorField.addFocusListener(retargetKeypadTo(operatorField));
        pinField.addFocusListener(retargetKeypadTo(pinField));

        // Any input clears a showing message, so a correction wipes the error as the cashier types.
        DocumentListener clearOnEdit = new ClearMessageOnEdit();
        operatorField.getDocument().addDocumentListener(clearOnEdit);
        pinField.getDocument().addDocumentListener(clearOnEdit);
    }

    private FocusAdapter retargetKeypadTo(JTextComponent field) {
        return new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                keypad.setTarget(field);
            }
        };
    }

    private void onKeypadNext() {
        // The → key advances focus only — never submits. From Operator ID it moves to PIN; from PIN
        // it moves to the Login button (the sole submit path). Routed on the keypad's current target.
        if (keypad.getTarget() == pinField) {
            focusLoginButton();
        } else {
            advanceToPin();
        }
    }

    private void advanceToPin() {
        keypad.setTarget(pinField);
        pinField.requestFocusInWindow();
    }

    private void focusLoginButton() {
        // Move focus to the Login button rather than submitting — the cashier confirms with one more
        // tap/press. Keeps submission on a single, deliberate control.
        loginButton.requestFocusInWindow();
    }

    private void prefillDemoCredentials() {
        operatorField.setText(DemoCredentials.OPERATOR_ID);
        pinField.setText(DemoCredentials.PIN);
        clearMessage();
    }

    private void attemptLogin() {
        String operatorId = operatorField.getText();
        operatorId = operatorId == null ? "" : operatorId.trim();
        char[] pin = pinField.getPassword();
        try {
            if (operatorId.isEmpty() || pin.length == 0 || !DemoCredentials.matches(operatorId, pin)) {
                // Every miss — wrong credentials or empty field — is one inline message, never a
                // modal, and never says which half was wrong.
                showMessage();
                journalFailure(operatorId);
                return;
            }
            journalSuccess(operatorId);
            String settled = operatorId;
            dispose();
            onLoginSuccess.accept(settled);
        } finally {
            // Scrub the PIN copy — it was never journalled and shouldn't linger in the array.
            Arrays.fill(pin, '\0');
        }
    }

    private void showMessage() {
        messageLabel.setText(INCORRECT_MESSAGE);
    }

    private void clearMessage() {
        messageLabel.setText(MESSAGE_PLACEHOLDER);
    }

    // ---- Journalling -------------------------------------------------------
    // A successful login records the operator id and timestamp; a failed attempt records the
    // attempted id. The PIN is NEVER journalled, correct or otherwise. Store name and lane come
    // from the CLI args so a shrink review can attribute the lane to an operator.

    private void journalSuccess(String operatorId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("operator", operatorId);
        journal.journal(record("LOGIN_SUCCEEDED", fields));
    }

    private void journalFailure(String attemptedOperatorId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("attemptedOperator", attemptedOperatorId);
        journal.journal(record("LOGIN_FAILED", fields));
    }

    private JournalRecord record(String event, Map<String, Object> fields) {
        // No transaction exists yet, so the txn id is the "-" placeholder.
        return new JournalRecord(Instant.now(), storeName, laneNumber, "-", event, fields);
    }

    // ---- Image loading -----------------------------------------------------

    /** Loads the product vector from the classpath, or {@code null} if it isn't packaged. */
    static Image loadVector() {
        return loadVector(VECTOR_RESOURCE);
    }

    /**
     * Loads an image resource, returning {@code null} — never throwing — when it is missing or
     * unreadable. A packaging mistake must not make the application unlaunchable.
     */
    static Image loadVector(String resource) {
        try {
            URL url = LoginView.class.getResource(resource);
            if (url == null) return null;
            return ImageIO.read(url);
        } catch (Exception e) {
            System.err.println("[login] could not load " + resource + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Test hooks --------------------------------------------------------

    JTextField getOperatorFieldForTest() { return operatorField; }
    JPasswordField getPinFieldForTest() { return pinField; }
    JLabel getMessageLabelForTest() { return messageLabel; }
    PosButton getLoginButtonForTest() { return loginButton; }
    OnScreenKeypad getKeypadForTest() { return keypad; }
    VectorPanel getVectorPanelForTest() { return vectorPanel; }
    JPanel getFormForTest() { return form; }
    void showMessageForTest() { showMessage(); }

    // ---- Vector panel ------------------------------------------------------

    /**
     * The left half: a {@link PosTheme#GO} green panel that paints one image centred and scaled
     * proportionally to <em>fit</em> within a slim margin — aspect ratio preserved, never distorted,
     * green showing in the letterbox gutter around it. A {@code null} image (the resource failed to
     * load) leaves the panel plain green rather than throwing.
     */
    static final class VectorPanel extends JPanel {

        /** Slim gutter kept around the image on every side. Small so the vector reads large. */
        private static final int MARGIN = 40;

        private final transient Image image;

        VectorPanel(Image image) {
            this.image = image;
            setBackground(PosTheme.GO);
        }

        boolean hasImageForTest() {
            return image != null;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g); // fills the green background (and the letterbox gutter)
            if (image == null) return;
            int iw = image.getWidth(null);
            int ih = image.getHeight(null);
            if (iw <= 0 || ih <= 0) return;
            int availW = getWidth() - MARGIN * 2;
            int availH = getHeight() - MARGIN * 2;
            if (availW <= 0 || availH <= 0) return;

            // Fit (contain): single uniform scale (never distort) sized to the SMALLER ratio so the
            // whole image is visible within the margin; the looser axis keeps green gutter.
            double scale = Math.min((double) availW / iw, (double) availH / ih);
            int w = (int) Math.round(iw * scale);
            int h = (int) Math.round(ih * scale);
            int x = (getWidth() - w) / 2;
            int y = (getHeight() - h) / 2;

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                g2.drawImage(image, x, y, w, h, null);
            } finally {
                g2.dispose();
            }
        }
    }

    // ---- Digit-only filter -------------------------------------------------

    private static void installDigitFilter(JTextField field) {
        if (field.getDocument() instanceof AbstractDocument doc) {
            doc.setDocumentFilter(new DigitFilter(MAX_LENGTH));
        }
    }

    /**
     * Accepts digits only, capped at {@code maxLength} characters — same shape as the quantity
     * field's filter. Rejects, silently and wholesale, any keystroke or paste that would introduce
     * a non-digit or exceed the cap. Plain {@link JTextField} routes both typing and paste through
     * the document, so this one filter governs both paths.
     */
    private static final class DigitFilter extends DocumentFilter {
        private final int maxLength;

        DigitFilter(int maxLength) {
            this.maxLength = maxLength;
        }

        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                throws BadLocationException {
            String current = fb.getDocument().getText(0, fb.getDocument().getLength());
            String candidate = current.substring(0, offset) + string + current.substring(offset);
            if (isValid(candidate)) super.insertString(fb, offset, string, attr);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException {
            String current = fb.getDocument().getText(0, fb.getDocument().getLength());
            String candidate = current.substring(0, offset) + text + current.substring(offset + length);
            if (isValid(candidate)) super.replace(fb, offset, length, text, attrs);
        }

        private boolean isValid(String s) {
            if (s.length() > maxLength) return false;
            for (int i = 0; i < s.length(); i++) {
                if (!Character.isDigit(s.charAt(i))) return false;
            }
            return true;
        }
    }

    // ---- Message-clearing document listener --------------------------------

    private final class ClearMessageOnEdit implements DocumentListener {
        @Override public void insertUpdate(DocumentEvent e) { clearMessage(); }
        @Override public void removeUpdate(DocumentEvent e) { clearMessage(); }
        @Override public void changedUpdate(DocumentEvent e) { clearMessage(); }
    }
}
