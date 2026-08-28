package com.rocketpartners.onboarding.possystem.display;

import java.awt.Color;
import java.awt.Font;

/**
 * Named factories for the four button variants used across the POS.
 *
 * <ul>
 *   <li>{@link #primary(String)} — the pay-forward action on a surface (Total, Confirm,
 *       Dismiss). Solid {@link PosTheme#GO} fill, white text, {@link PosTheme#BUTTON} type,
 *       {@link PosTheme#BUTTON_HEIGHT_PRIMARY} px touch target.</li>
 *   <li>{@link #secondary(String)} — a supporting action (Cancel, Change qty). Warm-grey fill,
 *       {@link PosTheme#TEXT_PRIMARY} text, {@link PosTheme#BODY} type,
 *       {@link PosTheme#BUTTON_HEIGHT_SECONDARY} px touch target.</li>
 *   <li>{@link #danger(String)} — a destructive action (Void basket, Void line). Tinted fill,
 *       {@link PosTheme#STOP} text, {@link PosTheme#BUTTON_HEIGHT_SECONDARY} px touch target.</li>
 *   <li>{@link #tender(String)} — the tender-column trio. Solid ink fill so it reads as
 *       hardware, not a body-copy button. {@link PosTheme#BUTTON_HEIGHT_PRIMARY} px touch target.</li>
 * </ul>
 *
 * <p>Touch minimums are enforced by {@link PosButton#getPreferredSize()}, so a caller cannot
 * accidentally shrink a live button below the fingertip target by setting a smaller preferred
 * size — the minimum is a floor, not a starting hint.</p>
 */
final class PosButtons {

    private PosButtons() {}

    static PosButton primary(String text) {
        PosButton b = new PosButton(text, PosTheme.GO, Color.WHITE,
                PosTheme.base(Font.BOLD, PosTheme.BUTTON));
        b.setTouchMinHeight(PosTheme.BUTTON_HEIGHT_PRIMARY);
        return b;
    }

    static PosButton secondary(String text) {
        PosButton b = new PosButton(text, PosTheme.BUTTON_SECONDARY_FILL, PosTheme.TEXT_PRIMARY,
                PosTheme.base(Font.PLAIN, PosTheme.BODY));
        b.setTouchMinHeight(PosTheme.BUTTON_HEIGHT_SECONDARY);
        return b;
    }

    static PosButton danger(String text) {
        PosButton b = new PosButton(text, PosTheme.BUTTON_DANGER_FILL, PosTheme.STOP,
                PosTheme.base(Font.PLAIN, PosTheme.BODY));
        b.setTouchMinHeight(PosTheme.BUTTON_HEIGHT_SECONDARY);
        return b;
    }

    /**
     * A "tinted-green secondary": pale {@link PosTheme#GO} background with saturated GO text,
     * matched typographically to {@link #primary(String)} so it can pair with a primary at
     * equal weight in a dialog footer. Used where the cancel action is affirmative-neutral
     * rather than destructive — the same tinting move as {@link #danger(String)} but in the
     * pay-forward hue.
     */
    static PosButton secondaryGreen(String text) {
        PosButton b = new PosButton(text, PosTheme.BUTTON_GO_TINT_FILL, PosTheme.GO,
                PosTheme.base(Font.BOLD, PosTheme.BUTTON));
        b.setTouchMinHeight(PosTheme.BUTTON_HEIGHT_PRIMARY);
        return b;
    }

    /**
     * A tender button in the given fill. Every tender carries its own colour — cash green, debit
     * blue, credit indigo — so a cashier can distinguish them by pattern rather than reading. The
     * three now sit side by side in one row (≈125px each), so the label type is {@link
     * PosTheme#BUTTON} rather than {@link PosTheme#AMOUNT}: at this width the larger size clipped
     * "Pay Credit" to an ellipsis, and colour is already doing the identifying work.
     */
    static PosButton tender(String text, Color fill) {
        PosButton b = new PosButton(text, fill, Color.WHITE,
                PosTheme.base(Font.BOLD, PosTheme.BUTTON));
        b.setTouchMinHeight(PosTheme.BUTTON_HEIGHT_PRIMARY);
        return b;
    }
}
