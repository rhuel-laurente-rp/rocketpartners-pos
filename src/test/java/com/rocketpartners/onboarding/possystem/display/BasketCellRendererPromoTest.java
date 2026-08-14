package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.commons.model.LineItem;
import org.junit.jupiter.api.Test;

import javax.swing.JList;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inert, indented free-item row. Runs without a display: the renderer is a component that only
 * needs to be constructed and asked for its rendered text — no frame is shown.
 */
class BasketCellRendererPromoTest {

    private static final Item WIDGET = new Item("UPC-W", "Widget", new BigDecimal("10.00"));

    private final BasketCellRenderer renderer = new BasketCellRenderer();
    private final JList<LineItem> list = new JList<>();

    private void render(LineItem li) {
        renderer.getListCellRendererComponent(list, li, 0, false, false);
    }

    @Test
    void freeRow_rendersIndentedInPromoColour_withFreeCountAndNegativeTotal() {
        FreeLineItem free = new FreeLineItem(WIDGET, 1, new BigDecimal("10.00"));
        render(free);
        String desc = renderer.getDescriptionTextForTest();
        assertThat(desc).contains("1 free");
        assertThat(desc.toUpperCase()).contains("#9D2EA8"); // PROMO violet, not GO green
        assertThat(desc).contains("&#8627;");               // the ↳ indent glyph
        assertThat(renderer.getExtendedTextForTest()).isEqualTo("-$10.00");
    }

    @Test
    void freeRow_evenWhenListIndexWouldSelect_paintsSurfaceBackground() {
        // Free rows must never look highlighted. Rendered "selected", the background stays SURFACE.
        FreeLineItem free = new FreeLineItem(WIDGET, 2, new BigDecimal("20.00"));
        renderer.getListCellRendererComponent(list, free, 0, true, false);
        assertThat(renderer.getBackground()).isEqualTo(PosTheme.SURFACE);
    }

    @Test
    void normalLine_staysPlainText_withPositiveTotal() {
        LineItem li = new LineItem(WIDGET, 2);
        render(li);
        assertThat(renderer.getDescriptionTextForTest()).isEqualTo("Widget");
        assertThat(renderer.getExtendedTextForTest()).isEqualTo("$20.00");
    }

    @Test
    void discountRow_rendersIndentedInItsTypeColour_withNegativeTotal() {
        DiscountLineItem d = new DiscountLineItem(WIDGET,
                com.rocketpartners.onboarding.commons.model.DiscountType.PERCENT_OFF,
                "25% Off Widget", new BigDecimal("2.50"));
        render(d);
        String desc = renderer.getDescriptionTextForTest();
        assertThat(desc).contains("25% Off Widget");
        assertThat(desc.toUpperCase()).contains("#1C7ED6"); // PROMO_PERCENT azure, not the PROMO violet
        assertThat(desc).contains("&#8627;");                // the ↳ indent glyph
        assertThat(renderer.getExtendedTextForTest()).isEqualTo("-$2.50");
    }

    @Test
    void discountRow_isNeverHighlighted_evenWhenSelected() {
        DiscountLineItem d = new DiscountLineItem(WIDGET,
                com.rocketpartners.onboarding.commons.model.DiscountType.FIXED_AMOUNT_OFF,
                "$1.00 Off Widget", new BigDecimal("1.00"));
        renderer.getListCellRendererComponent(list, d, 0, true, false);
        assertThat(renderer.getBackground()).isEqualTo(PosTheme.SURFACE);
    }
}
