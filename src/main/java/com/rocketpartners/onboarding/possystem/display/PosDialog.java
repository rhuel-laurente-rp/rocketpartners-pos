package com.rocketpartners.onboarding.possystem.display;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared shell for every modal dialog in the POS. Guarantees uniform chrome so a cashier
 * doesn't feel like they've walked into a different application every time a dialog pops up.
 *
 * <p><strong>Structure.</strong></p>
 * <ul>
 *   <li>A dark {@link PosTheme#INK} header strip with the dialog title in white at
 *       {@link PosTheme#BUTTON} weight, echoing the main window's header.</li>
 *   <li>A {@link PosTheme#SURFACE} body area with 20 px padding — subclasses set body content
 *       through {@link #setBody(JComponent)}.</li>
 *   <li>A footer separated by a {@link PosTheme#RULE} hairline: secondary/cancel actions on
 *       the left, the primary action on the right. Primary is fixed at 48 px tall so tapping
 *       it doesn't require aim.</li>
 * </ul>
 *
 * <p><strong>Keyboard.</strong> ESC triggers the cancel action; Enter triggers the primary
 * action. On open focus lands on {@link #initialFocus} (set via
 * {@link #setInitialFocus(Component)}) or on the primary button when there is no obvious
 * input.</p>
 *
 * <p><strong>Scanner suspension.</strong> {@link ScannerViewController} listens for the same
 * events that open and close these dialogs and pauses its
 * {@link java.awt.KeyEventDispatcher} accordingly, so barcode keystrokes cannot leak in.
 * Subclasses don't need to plumb that themselves.</p>
 */
class PosDialog extends JDialog {

    /** Height of the primary footer button, per the design brief. */
    protected static final int PRIMARY_HEIGHT = 48;

    /** Content root; body slot is at BorderLayout.CENTER. */
    private final JPanel content = new JPanel(new BorderLayout());

    /** The body slot subclasses populate via {@link #setBody(JComponent)}. */
    private final JPanel bodySlot = new JPanel(new BorderLayout());

    /** Left-side actions in the footer (secondary buttons, laid out left-to-right). */
    private final JPanel footerLeft = new JPanel();

    /** Right-side action in the footer (primary button). */
    private final JPanel footerRight = new JPanel(new BorderLayout());

    private final JLabel titleLabel = new JLabel();

    /** Set on dialog open. May be {@code null} if no explicit primary was configured. */
    private PosButton primaryButton;

    /** Runnable invoked by ESC. Defaults to {@link #closeDialog()}. */
    private Runnable cancelAction = this::closeDialog;

    /** Component that should receive focus when the dialog opens. */
    private Component initialFocus;

    /** Guard so subclass listeners can react to the "just opened" moment exactly once. */
    private boolean firstOpen = true;

    /**
     * @param owner the parent frame, may be {@code null}
     * @param title the dialog title
     */
    protected PosDialog(JFrame owner, String title) {
        super(owner, title, true);
        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        setResizable(false);

        content.setBackground(PosTheme.SURFACE);
        content.add(buildHeader(title), BorderLayout.NORTH);

        bodySlot.setOpaque(false);
        bodySlot.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        content.add(bodySlot, BorderLayout.CENTER);

        content.add(buildFooter(), BorderLayout.SOUTH);
        getContentPane().add(content);

        wireKeyBindings();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowActivated(WindowEvent e) {
                if (firstOpen) {
                    firstOpen = false;
                    Component c = resolveInitialFocus();
                    if (c != null) c.requestFocusInWindow();
                }
            }
        });
    }

    // ---- Configuration API ------------------------------------------------

    /** Replaces the body content. Called by subclasses during construction. */
    protected void setBody(JComponent body) {
        if (body == null) throw new IllegalArgumentException("body must not be null");
        bodySlot.removeAll();
        bodySlot.add(body, BorderLayout.CENTER);
        bodySlot.revalidate();
        bodySlot.repaint();
    }

    /**
     * Sets the primary action button (the affirmative "commit" button on the right of the
     * footer). Enter triggers it. Only one primary is allowed — a second call replaces the
     * previous button.
     */
    protected void setPrimary(PosButton button) {
        if (button == null) throw new IllegalArgumentException("primary must not be null");
        this.primaryButton = button;
        button.setPreferredSize(new Dimension(button.getPreferredSize().width, PRIMARY_HEIGHT));
        footerRight.removeAll();
        footerRight.add(button, BorderLayout.EAST);
        footerRight.revalidate();
        footerRight.repaint();
    }

    /** @return the primary button, or {@code null} if none has been configured */
    protected PosButton getPrimary() {
        return primaryButton;
    }

    /** Adds a secondary action to the left of the footer. */
    protected void addSecondary(PosButton button) {
        if (button == null) throw new IllegalArgumentException("secondary must not be null");
        footerLeft.add(button);
        footerLeft.revalidate();
        footerLeft.repaint();
    }

    /**
     * Sets the ESC-key action. Defaults to {@link #closeDialog()} — override this when a
     * "Cancel" secondary should also fire dispatcher-visible events.
     */
    protected void setCancelAction(Runnable r) {
        this.cancelAction = r == null ? this::closeDialog : r;
    }

    /** Sets the component that receives focus on open. */
    protected void setInitialFocus(Component c) {
        this.initialFocus = c;
    }

    /** Updates the dialog title (also updates the header strip label). */
    public void setDialogTitle(String title) {
        setTitle(title);
        titleLabel.setText(title);
    }

    // ---- Lifecycle --------------------------------------------------------

    /** Opens the dialog. Blocks until it is closed (modal). */
    public void openDialog() {
        if (!isVisible()) {
            firstOpen = true;
            pack();
            setMinimumSize(getSize());
            setLocationRelativeTo(getOwner());
        }
        setVisible(true);
    }

    /** Hides the dialog. */
    public void closeDialog() {
        setVisible(false);
    }

    // ---- Internals --------------------------------------------------------

    private JPanel buildHeader(String title) {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(PosTheme.INK);
        header.setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 20));

        titleLabel.setText(title);
        titleLabel.setFont(PosTheme.base(Font.BOLD, PosTheme.BUTTON));
        titleLabel.setForeground(Color.WHITE);
        header.add(titleLabel, BorderLayout.WEST);
        return header;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(PosTheme.SURFACE);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, PosTheme.RULE),
                BorderFactory.createEmptyBorder(14, 20, 16, 20)));

        footerLeft.setOpaque(false);
        footerLeft.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        footer.add(footerLeft, BorderLayout.WEST);

        footerRight.setOpaque(false);
        // Push primary to the right by wrapping in a right-aligned FlowLayout container.
        JPanel rightWrap = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        rightWrap.setOpaque(false);
        rightWrap.add(footerRight);
        footer.add(rightWrap, BorderLayout.EAST);

        // Middle spacer so the two sides don't collapse against each other on narrow content.
        footer.add(Box.createHorizontalStrut(60), BorderLayout.CENTER);
        return footer;
    }

    private void wireKeyBindings() {
        JComponent root = (JComponent) getContentPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "cancel");
        root.getActionMap().put("cancel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (cancelAction != null) cancelAction.run();
            }
        });
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ENTER"), "primary");
        root.getActionMap().put("primary", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (primaryButton != null && primaryButton.isEnabled()) {
                    primaryButton.doClick();
                }
            }
        });
    }

    private Component resolveInitialFocus() {
        if (initialFocus != null && initialFocus.isShowing()) return initialFocus;
        return primaryButton;
    }

    /**
     * @return a list of every secondary button attached to the footer, in insertion order.
     *         Useful for tests.
     */
    protected List<PosButton> getSecondaries() {
        List<PosButton> out = new ArrayList<>();
        for (Component c : footerLeft.getComponents()) {
            if (c instanceof PosButton pb) out.add(pb);
        }
        return out;
    }
}
