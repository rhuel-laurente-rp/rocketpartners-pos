package com.rocketpartners.onboarding.possystem.display;

import java.awt.Color;
import java.awt.Font;

/**
 * Named factories for the four button variants used across the POS.
 *
 * <ul>
 *   <li>{@link #primary(String)} — the pay-forward action on a surface (Total, Confirm,
 *       Dismiss). Solid {@link PosTheme#GO} fill, white text, {@link PosTheme#BUTTON} type.</li>
 *   <li>{@link #secondary(String)} — a supporting action (Cancel, Change qty). Warm-grey fill,
 *       {@link PosTheme#INK} text, {@link PosTheme#BODY} type.</li>
 *   <li>{@link #danger(String)} — a destructive action (Void basket, Void line). Tinted fill,
 *       {@link PosTheme#STOP} text.</li>
 *   <li>{@link #tender(String)} — the tender-column trio. Solid ink fill so it reads as
 *       hardware, not a body-copy button.</li>
 * </ul>
 */
final class PosButtons {

    private PosButtons() {}

    static PosButton primary(String text) {
        return new PosButton(text, PosTheme.GO, Color.WHITE, PosTheme.base(Font.BOLD, PosTheme.BUTTON));
    }

    static PosButton secondary(String text) {
        return new PosButton(text, new Color(0xF2, 0xF1, 0xED), PosTheme.INK,
                PosTheme.base(Font.PLAIN, PosTheme.BODY));
    }

    static PosButton danger(String text) {
        return new PosButton(text, new Color(0xFD, 0xF1, 0xEF), PosTheme.STOP,
                PosTheme.base(Font.PLAIN, PosTheme.BODY));
    }

    static PosButton tender(String text) {
        return new PosButton(text, PosTheme.INK, Color.WHITE,
                PosTheme.base(Font.BOLD, PosTheme.BUTTON - 1f));
    }
}
