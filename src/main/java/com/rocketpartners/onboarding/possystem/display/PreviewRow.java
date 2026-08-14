package com.rocketpartners.onboarding.possystem.display;

/**
 * Marker for display-only basket rows the domain never sees — a promotion's free units
 * ({@link FreeLineItem}) or a per-item discount preview ({@link DiscountLineItem}). Both subclass
 * {@link com.rocketpartners.onboarding.commons.model.LineItem} so the {@code JList} model can hold
 * them, but {@code CustomerView} keys every "this row is inert" decision (item count, density,
 * flash, selection) off this interface rather than each concrete type — so a new kind of preview
 * row is inert by construction, no extra {@code instanceof} to remember.
 */
interface PreviewRow {
}
