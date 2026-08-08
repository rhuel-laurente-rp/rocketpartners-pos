# Swing Traps

The Swing footguns this codebase has already hit. Each entry leads with the **symptom** — that's the thing you recognise when it happens to you — then cause, fix, and where it bit us. When a code comment already explains it, that comment is the source of truth; this file points at it and paraphrases.

CLAUDE.md's "Swing traps" bullet points reference this file by name. If you add a new trap, add it here and add the one-line symptom to that section.

---

## The cashier types `0`, clicks Confirm, and nothing happens

**Cause.** `JFormattedTextField` defaults to `COMMIT_OR_REVERT`. On focus loss it asks its formatter to parse the current text, and on failure it *silently restores the last valid value*. Clicking a footer button moves focus off the field, so the invalid entry is reverted before any confirm handler runs. The handler then reads a perfectly-valid number, matches it against the current quantity, takes the no-op branch, and closes the dialog. The cashier's input never reached the code that was supposed to validate it.

**Fix.** `field.setFocusLostBehavior(JFormattedTextField.PERSIST)`. The field keeps the raw text on focus loss; the confirm handler reads what was actually typed and either validates or shows an inline error.

**Where it bit us.** `ChangeQuantityView.configureEditor()`. Full explanation in the class Javadoc — that is the load-bearing comment of the whole class.

---

## Two children in a `BoxLayout` are visually skewed even though they should line up

**Cause.** `BoxLayout` uses `Component#getAlignmentX()` (or `AlignmentY`) to lay siblings out. `JLabel` and `JPanel` default to different alignment values (0.0f vs 0.5f), so a body containing a mix of both drifts left or right unless every child sets the same alignment.

**Fix.** Set `alignmentX` (or `alignmentY`) explicitly on every child of a `BoxLayout` container, matching the container's axis. `Component.LEFT_ALIGNMENT` on everything is the safe default when the body reads top-down.

**Where it bit us.** `ChangeQuantityView.buildBody()` — every label, spinner, strut, and validation slot sets `setAlignmentX(Component.LEFT_ALIGNMENT)`. Same pattern in `VoidBasketConfirmView.buildBody()`.

---

## A label or button grows to fill the whole column in a `BoxLayout`

**Cause.** `BoxLayout` inflates each child to its *maximum size* along the layout axis, and most Swing components report `Short.MAX_VALUE` (effectively unlimited) as their maximum. A vertical stack with unconstrained children stretches every row to fill the available height, which looks like a bug on paper but is `BoxLayout` doing exactly what it was asked.

**Fix.** Set `setMaximumSize(new Dimension(width, height))` on every child that should keep its preferred height. `Integer.MAX_VALUE` is fine for the axis you *do* want to fill; use a concrete height for the axis you don't.

**Where it bit us.** `CustomerView.buildTape*` — the inline row, hairline separator, and total row each cap their height (`22`, `1`, `44`) so the tape column doesn't rearrange when the basket contents change. Grep for `setMaximumSize(new Dimension(Integer.MAX_VALUE, ...))`.

---

## Two square tiles in a `GridLayout` come out rectangular

**Cause.** `GridLayout` ignores each child's maximum size and stretches every cell to fill the row equally along both axes. Constraining the buttons themselves has no effect — `GridLayout` overrides both `maximumSize` and `preferredSize`.

**Fix.** Pin the layout container to a fixed `(width, cell-height)` and let `GridLayout` divide the width across the columns. Both cells get identical dimensions for free.

**Where it bit us.** `CashModeChoiceView.buildBody()` — `row.setPreferredSize / setMinimumSize / setMaximumSize` all set to `(BODY_MIN_WIDTH, MODE_TILE_SIDE)` so the two mode tiles measure exactly `MODE_TILE_SIDE` square. The class comment (near line 182) explains: "GridLayout gives the two tiles identical cells and ignores their maximum size, so squareness is enforced by constraining this row rather than the buttons."

---

## Custom-painted button appears clickable but doesn't fire

**Cause.** A `JButton` that overrides `paintComponent` and adds nested child components (e.g. an inner `JLabel` for a two-line description) can end up with the child intercepting mouse events. Swing dispatches mouse events to the topmost component under the cursor; a nested `JLabel` with default behaviour is opaque enough to receive them before the outer button.

**Fix.** Don't nest child components inside custom-painted buttons — paint the content directly on the button's `Graphics`. `QuickAddTile` in `CustomerView` paints its description and price via `Graphics#drawString` rather than adding child components; the whole face is the button's paint surface, so the mouse event lands on the button.

**Where it bit us.** `CustomerView.QuickAddTile` (line ~940). The comment on the class explains: "Drawn rather than composed from HTML so the price keeps its accent colour and the whole tile dims correctly."

---

## Enter in a dialog fires a different button than Space does

**Cause.** In Swing, a focused `JButton` responds to **Space** (the "activate the focused component" key), while Enter fires the root pane's *default button* — which is a separate concept from focus. Focusing a button with `requestFocusInWindow()` does not make it the default; Enter still goes to whichever button (if any) has been marked default. A dialog with no configured default swallows Enter.

**Fix.** For dialogs where the primary action is unambiguous, set it on both:

```java
setInitialFocus(primary);
getRootPane().setDefaultButton(primary);
```

For dialogs where the primary is the *destructive* action, invert the default (see `VoidBasketConfirmView`):

```java
setInitialFocus(keepButton);
getRootPane().setDefaultButton(keepButton);
```

**Where it bit us.** `CashModeChoiceView` (Enter did nothing before the default was set — see the class comment at line ~104: "Focusing a JButton alone is not enough to give Enter a target — a focused button responds to Space; Enter activates the root pane's *default* button."). `VoidBasketConfirmView` deliberately points the default at the safe action — a stray Enter or scanner terminator must never void a basket.

---

## Basket list stutters under heavy scrolling

**Cause.** Every `ListCellRenderer.getListCellRendererComponent(...)` call runs during paint, and Swing paints on demand. Allocating inside the render path — `new Color(...)`, `new Font(...)`, HTML tables — creates garbage on every hover, scroll, and repaint, which the GC then has to sweep during the render loop.

**Fix.** Hoist every colour, font, and derived value to a `static final` (or an instance field constructed once). The renderer's job is to configure itself for the given row, not to allocate. `BasketCellRenderer` follows this rule; `LineItemCellRenderer` does not, which is one of the reasons it's listed for deletion in `docs/known-issues.md`.

**Where it bit us.** Live rendering perf is fine today only because the basket is short. This is a trap waiting for the first large-basket demo.

---

## The UI freezes for a beat when a background thread updates state

**Cause.** Swing is single-threaded — every read and write to a Swing component must happen on the EDT, or you get race conditions that manifest as painting glitches, dropped clicks, or silent lockups. `SwingUtilities.isEventDispatchThread()` is how you check; `SwingUtilities.invokeLater(Runnable)` is how you marshal.

**Fix.** Any code path that can be called from a non-EDT thread wraps its Swing touches:

```java
if (SwingUtilities.isEventDispatchThread()) {
    doTheThing();
} else {
    SwingUtilities.invokeLater(this::doTheThing);
}
```

**Where it bit us.**

- `CustomerView.setJournalConnected(...)` is called from `RemoteJournal`'s sender thread when the socket transitions. It marshals through the check-and-`invokeLater` idiom above (lines ~510–516).
- `PayWithCardViewController` schedules its simulated card approval via `javax.swing.Timer`, which delivers the callback on the EDT — the alternative would be `Thread.sleep(800)` on the EDT itself, freezing the whole UI mid-"approval". The class Javadoc names this explicitly.
- `ErrorPopupViewController.onPosEvent(...)` calls through an injectable `EdtInvoker` (default: `SwingUtilities::invokeLater`) because Phase 2's journal client and Phase 3's discount-engine call will dispatch `ERROR` events from background threads.

The pattern is the same in all three places. Any new component that reacts to journal state, network callbacks, or `javax.swing.Timer` — or is likely to in a later phase — should use the same idiom.
