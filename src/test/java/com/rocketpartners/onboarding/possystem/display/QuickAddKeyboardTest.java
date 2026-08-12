package com.rocketpartners.onboarding.possystem.display;

import com.rocketpartners.onboarding.commons.model.Item;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JTextField;
import java.awt.event.ActionEvent;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Show/hide behaviour of the on-screen QWERTY inside {@link QuickAddPanel}. Pure view state — no
 * JFrame — so these run headless. Focus is driven through the panel's focus-listener test hooks
 * rather than a real native focus transfer.
 */
class QuickAddKeyboardTest {

    private static final List<Item> ITEMS = List.of(
            new Item("111", "Cola", new BigDecimal("2.00")),
            new Item("222", "Water", new BigDecimal("1.00")),
            new Item("333", "Chips", new BigDecimal("1.50")));

    private static QuickAddPanel panel() {
        QuickAddPanel p = new QuickAddPanel(ITEMS, item -> { });
        // Force a deterministic non-zero tile capacity so the grid actually holds tappable tiles.
        p.setCapacityForTest(3, 6);
        return p;
    }

    @Test
    void keyboard_appearsOnSearchFieldFocus() {
        QuickAddPanel p = panel();
        assertThat(p.isKeyboardVisibleForTest()).isFalse();

        p.fireSearchFocusGainedForTest();

        assertThat(p.isKeyboardVisibleForTest()).isTrue();
    }

    @Test
    void keyboard_dismissesOnDoneKey() {
        QuickAddPanel p = panel();
        p.fireSearchFocusGainedForTest();

        p.getKeyboardForTest().getDoneKeyForTest().doClick();

        assertThat(p.isKeyboardVisibleForTest()).isFalse();
    }

    @Test
    void keyboard_reappearsOnTap_afterDoneWhileFieldStillHasFocus() {
        // Regression: Done (and ESC) dismiss the keyboard but leave focus in the field, so
        // focusGained won't fire again. A tap must bring the keyboard back.
        QuickAddPanel p = panel();
        p.fireSearchFocusGainedForTest();
        p.getKeyboardForTest().getDoneKeyForTest().doClick();
        assertThat(p.isKeyboardVisibleForTest()).isFalse();

        // Focus never left the field (no focusGained), only a tap.
        p.fireSearchTapForTest();

        assertThat(p.isKeyboardVisibleForTest())
                .as("tapping the already-focused field must re-show the keyboard").isTrue();
    }

    @Test
    void keyboard_dismissesOnEscape() {
        QuickAddPanel p = panel();
        p.fireSearchFocusGainedForTest();

        Action esc = p.getSearchFieldForTest().getActionMap().get("hideKeyboard");
        assertThat(esc).as("ESC action must be wired on the search field").isNotNull();
        esc.actionPerformed(new ActionEvent(p.getSearchFieldForTest(), ActionEvent.ACTION_PERFORMED, "esc"));

        assertThat(p.isKeyboardVisibleForTest()).isFalse();
    }

    @Test
    void keyboard_dismissesWhenATileIsTapped() {
        QuickAddPanel p = panel();
        p.fireSearchFocusGainedForTest();
        assertThat(p.getGridForTest().getComponentCount()).isGreaterThan(0);

        ((AbstractButton) p.getGridForTest().getComponent(0)).doClick();

        assertThat(p.isKeyboardVisibleForTest()).isFalse();
    }

    @Test
    void keyboard_dismissesWhenFocusMovesElsewhere() {
        QuickAddPanel p = panel();
        p.fireSearchFocusGainedForTest();

        p.fireSearchFocusLostForTest();

        assertThat(p.isKeyboardVisibleForTest()).isFalse();
    }

    @Test
    void keyboard_dismissesWhenTheSearchFieldIsCleared() {
        QuickAddPanel p = panel();
        p.fireSearchFocusGainedForTest();
        JTextField search = p.getSearchFieldForTest();

        search.setText("cola");
        assertThat(p.isKeyboardVisibleForTest()).as("still open while there is a query").isTrue();

        search.setText("");
        assertThat(p.isKeyboardVisibleForTest()).as("clearing the query dismisses it").isFalse();
    }

    @Test
    void typingOnTheKeyboard_filtersTheGrid_andLeavesTheFieldFocusable() {
        QuickAddPanel p = panel();
        p.fireSearchFocusGainedForTest();

        // The keyboard types into the real search field through its document.
        p.getKeyboardForTest().getKeyForTest("c").doClick();
        p.getKeyboardForTest().getKeyForTest("o").doClick();

        assertThat(p.getSearchFieldForTest().getText()).isEqualTo("co");
        // Cola and Chips both contain "c"; "co" narrows to Cola.
        assertThat(p.filteredSortedForTest()).extracting(Item::getDisplayLabel).containsExactly("Cola");
        // A physical keyboard must still work: the field itself stays focusable/editable.
        assertThat(p.getSearchFieldForTest().isFocusable()).isTrue();
        assertThat(p.getSearchFieldForTest().isEditable()).isTrue();
    }
}
