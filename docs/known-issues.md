# Known Issues

Bugs, dead code, and convention drift teed up for follow-up branches. This file is documentation only — nothing here is being fixed on the branch that created it.

Format: **file:line — one-line description. severity. disposition. suggested branch.**

Severity is the impact if the code path fires, not how easy it is to reach. Several items below are unreachable today; that is captured under disposition.

## Live bugs

### `ScannerView.java:128` — debug `System.out.println` on every status-hint change
`setStatusHint(...)` prints its argument to stdout. Leftover from local debugging; the status hint fires on every scan, tender, void, dialog open, and receipt dismiss, so a normal session produces a stream of `Ready to scan` / `Locked …` lines on the terminal.

- **Severity:** low (noise only).
- **Disposition:** remove the `println`; no other change needed.
- **Suggested branch:** `chore/scanner-view-remove-println`.

### `Transaction.java:239` — `tender(...)` accepts `amountDue < grandTotal()` with no check
The three-argument tender overload writes whatever `amountDue` is passed in, no comparison against `grandTotal()`. If a caller ever passed a settled amount below the grand total, `changeDue()` would still compute positive change: an underpayment silently framed as change owed.

- **Severity:** medium — this contradicts the aggregate's own claim in its Javadoc that state-machine and money invariants live here rather than in callers.
- **Reachable today?** No. The three-argument overload has two callers: `payNextDollar()` which passes the ceiled figure, and `TransactionService.tenderCash(BigDecimal, BigDecimal)` — itself only called by `PayWithCashViewController`, which validates that cash received ≥ grand total upstream.
- **Disposition:** add the guard; do not fix on a doc branch. Reject with `IllegalArgumentException` (or an ERROR event) rather than trying to salvage the tender — a caller that reaches this branch is asking the wrong question.
- **Suggested branch:** `fix/transaction-guard-amount-due`.

### `ChangeQuantityView.java:283-284` — `body.add(validationMessage)` called twice
Two consecutive `body.add(validationMessage)` calls. Swing tolerates re-adding a component (it removes it from its previous slot on re-add), so the layout ends up with one instance of the label, but the intent was clearly a struct-and-label pair. The vertical strut is elsewhere in the method, so the visible layout is intact — this is a leftover, not a bug the cashier sees.

- **Severity:** low.
- **Disposition:** delete the duplicate line.
- **Suggested branch:** rolled into `chore/change-quantity-view-cleanup`.

### `LineItemCellRenderer.java` — dead code
Never referenced. `CustomerView` uses `BasketCellRenderer` throughout. The two renderers disagree on styling, void treatment (grey + strikethrough vs. the design-system rules `BasketCellRenderer` follows), and money rounding call site — keeping both is a trap.

- **Severity:** medium (drift risk, not a runtime bug).
- **Disposition:** delete the file. Confirm nothing under `src/test` references it before deleting.
- **Suggested branch:** `chore/delete-line-item-cell-renderer`.

### `build.gradle` — `bootJar` / `bootRun` main class doesn't exist
Both tasks point at `com.rocketpartners.onboarding.posdiscountengine.Application`, which is not on the classpath (only empty `package-info.java` files exist under that tree). `bootJar` currently succeeds because Spring Boot doesn't verify the main class at packaging time; `bootRun` fails at task execution:

```
Error: Could not find or load main class com.rocketpartners.onboarding.posdiscountengine.Application
```

- **Severity:** medium — the CLAUDE.md brief lists `./gradlew bootRun` as a supported command; anyone running it hits a hard failure.
- **Disposition:** flag on the Phase 3 kickoff branch. Options: (a) leave broken until Phase 3 lands and the class exists, (b) temporarily point both tasks at a placeholder `Main` class that logs "not yet implemented" and exits. Prefer (b) so `bootJar` produces something runnable and the "always green" invariant covers `bootRun` too.
- **Suggested branch:** `phase3/scaffold-discount-engine-application`.

### `FileJournal.java` — synchronous disk write + flush on the caller's thread
`journal(...)` opens/rolls the daily file, writes one line, then `flush()`es — all on the caller's thread, which is the Swing EDT. This violates the `Journal` contract ("must not block the caller ... must be safe to call from the Swing event dispatch thread"). In practice each write is well under a millisecond and the class Javadoc explicitly accepts this tradeoff.

- **Severity:** low today.
- **Disposition:** accepted; documented. If the write volume or size ever grows (screenshots-in-journal, base64 blobs), migrate to the same queue+sender shape `RemoteJournal` uses.
- **Suggested branch:** `perf/file-journal-async-sender` (only if the accepting rationale changes).

## Contract shortcuts around the `Journal` interface

The `Journal` contract says implementations "must not throw". The composite wrapper doesn't take that at face value, and neither does `close()`. This is defence-in-depth around the Swing EDT — but the code says one thing and reads another.

### `Journals.java:34` — `journal(...)` swallows `RuntimeException`
Every delegate's `journal(record)` is called inside `try { ... } catch (RuntimeException e) { println }`. If the contract holds, this catch is unreachable. Either drop it and let a violation surface loudly, or update the contract to say "throwing is a bug the composite tolerates for you."

### `Journals.java:47` — `close()` swallows delegate exceptions
Same shape: `try { j.close(); } catch (RuntimeException ignored) {}`. The comment attributes this to "shutdown may have already torn things down", but the effect is that a real close-time bug in `RemoteJournal` or `FileJournal` never reaches stderr.

### `RemoteJournal.java:209` — `close()` uses `offer()` for the poison pill
`queue.offer(POISON)` in `close()`. When the queue is full the pill drops silently, and the shutdown path falls back to `sender.interrupt()` plus a 2-second `join`. Under back-pressure this means close takes 2s and prints no diagnostic. Prefer `put()` here (or drain-then-offer) — close is not on the hot path.

- **Severity for all three:** low (defensive; hides latent bugs, does not cause them).
- **Disposition:** bundle into a single review pass on the journal contract. Either strengthen the callers or weaken the contract, but stop having both.
- **Suggested branch:** `refactor/journal-contract-audit`.

## Documentation and cashier-copy gaps

### `PosEventType` Javadoc — only 6 of 11 error codes documented
The `ERROR` event's Javadoc lists `UPC_NOT_FOUND`, `TOTALED_INVARIANT`, `INVALID_CASH_AMOUNT`, `UNDERPAYMENT`, `NO_TRANSACTION`, `INVALID_ARGUMENT`. Also dispatched at runtime (grep the source): `SCAN_LOCKED`, `INVALID_BARCODE`, `TRANSACTION_ALREADY_OPEN`, `ABOVE_MAX_QUANTITY`, `ILLEGAL_STATE`. New codes were added when the flows they cover were added; the umbrella Javadoc was not.

- **Severity:** low.
- **Disposition:** update the Javadoc to list the full vocabulary, cross-linked to the site that dispatches each. Do it when someone next touches `PosEventType` for another reason.
- **Suggested branch:** rolled into whichever event-adds-a-code branch lands next.

### `ErrorPopupViewController.cashierMessage` — three codes have no cashier-facing message
`TRANSACTION_ALREADY_OPEN`, `ABOVE_MAX_QUANTITY`, `ILLEGAL_STATE` all fall through the `switch` to the generic `"An unexpected error occurred."` string. Each of the three has a concrete failure the cashier can act on:

- `TRANSACTION_ALREADY_OPEN` — "Finish the current transaction first."
- `ABOVE_MAX_QUANTITY` — "Quantity is limited to {max}." (populated from `TransactionService.DEFAULT_MAX_LINE_QUANTITY = 999`.)
- `ILLEGAL_STATE` — a corollary of `TOTALED_INVARIANT`; probably wants the same "That action isn't allowed right now." message.

- **Severity:** low (cashiers do get a dialog; it's just uselessly generic).
- **Disposition:** add message rows in the switch when the copy is signed off.
- **Suggested branch:** `polish/error-dialog-cashier-copy`.

## Convention drift — one shared cause

An earlier design spec asserted sentence case for copy and put the primary button first in dialog footers. That spec has since been reversed: convention is Title Case for buttons and dialog titles (enforced by `ButtonLabelTitleCaseTest`), and `PosDialog`'s footer layout is secondary-left / primary-right. Three files were not updated:

- **`VoidBasketConfirmView`** — dialog title, description, and button labels are sentence case ("Void basket?", "Void basket", "Keep basket"). Pinned by `VoidBasketConfirmViewTest#everyVisibleString_isSentenceCase`, and `ButtonLabelTitleCaseTest` explicitly excludes this dialog. Both need updating together.
- **`ChangeQuantityView`** — footer wiring calls `setPrimary(confirmButton)` and `addSecondary(cancelButton)`, which under the current `PosDialog` builds the correct secondary-left / primary-right layout. Copy strings, however, remain sentence case in the body text and eyebrow label ("Enter a quantity between …", "Quantity" as the field eyebrow). Class Javadoc explicitly claims sentence case as intentional — that claim needs to go.
- **`CashModeChoiceView`** — the two mode-tile labels are Title Case (`Exact Amount`, `Next Dollar`) and already pass `ButtonLabelTitleCaseTest`. Verify on the branch that no future edit adds sentence-case tiles.

Whoever fixes one should fix all three, and update `ButtonLabelTitleCaseTest` to cover the void-basket vocabulary once the strings are corrected. Take the class-Javadoc claims with them.

**Separately, and deliberately kept:** `VoidBasketConfirmView` points initial focus, the root pane's default button, and ESC at the safe `Keep basket` button rather than the danger-styled primary. A stray Enter or scanner terminator must not void a basket. That inversion is intentional and must survive the casing fix — see class Javadoc.

- **Severity:** low (correctness is fine; consistency is not).
- **Disposition:** one branch that touches all three, plus the test that pins the sentence-case dialog.
- **Suggested branch:** `polish/apply-title-case-convention`.

## Suggested order for follow-up branches

Rough priority — highest impact and lowest coordination cost first. Nothing here is urgent; the app runs green.

1. `chore/scanner-view-remove-println` — one-line delete, no risk.
2. `chore/delete-line-item-cell-renderer` — remove the drift trap while it's small.
3. `chore/change-quantity-view-cleanup` — the double-add is trivial and lets the same PR touch other stale bits nearby.
4. `polish/apply-title-case-convention` — bundle the three casing sites and the test flip.
5. `polish/error-dialog-cashier-copy` — pair with the `PosEventType` Javadoc update.
6. `fix/transaction-guard-amount-due` — unreachable today, but a load-bearing aggregate contract shouldn't rely on that.
7. `refactor/journal-contract-audit` — needs a design decision, not a mechanical fix.
8. `phase3/scaffold-discount-engine-application` — unblocks `bootRun` and sets up the Phase 3 tree at the same time.
