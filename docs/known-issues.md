# Known Issues

Bugs, dead code, and convention drift teed up for follow-up branches.

Format: **file:line — one-line description. severity. disposition. suggested branch.**

Severity is the impact if the code path fires, not how easy it is to reach. Several items below are unreachable today; that is captured under disposition.

## Live bugs

### `FileJournal.java` — synchronous disk write + flush on the caller's thread
`journal(...)` opens/rolls the daily file, writes one line, then `flush()`es — all on the caller's thread, which is the Swing EDT. This violates the `Journal` contract ("must not block the caller ... must be safe to call from the Swing event dispatch thread"). In practice each write is well under a millisecond and the class Javadoc explicitly accepts this tradeoff.

- **Severity:** low today.
- **Disposition:** accepted; documented. If the write volume or size ever grows (screenshots-in-journal, base64 blobs), migrate to the same queue+sender shape `RemoteJournal` uses.
- **Suggested branch:** `perf/file-journal-async-sender` (only if the accepting rationale changes).

## Contract shortcuts around the `Journal` interface

The `Journal` contract says implementations "must not throw". The composite wrapper doesn't take that at face value, and neither does `close()`. This is defence-in-depth around the Swing EDT — but the code says one thing and reads another.

### `Journals.java:34` — `journal(...)` swallows `RuntimeException`
Every delegate's `journal(record)` is called inside `try { ... } catch (RuntimeException e) { println }`. If the contract holds, this catch is unreachable. Either drop it and let a violation surface loudly, or update the contract to say "throwing is a bug the composite tolerates for you."

### `Journals.java:46` — `close()` swallows delegate exceptions
Same shape: `try { j.close(); } catch (RuntimeException ignored) {}`. The comment attributes this to "shutdown may have already torn things down", but the effect is that a real close-time bug in `RemoteJournal` or `FileJournal` never reaches stderr.

### `RemoteJournal.java:209` — `close()` uses `offer()` for the poison pill
`queue.offer(POISON)` in `close()`. When the queue is full the pill drops silently, and the shutdown path falls back to `sender.interrupt()` plus a 2-second `join`. Under back-pressure this means close takes 2s and prints no diagnostic. Prefer `put()` here (or drain-then-offer) — close is not on the hot path.

- **Severity for all three:** low (defensive; hides latent bugs, does not cause them).
- **Disposition:** bundle into a single review pass on the journal contract. Either strengthen the callers or weaken the contract, but stop having both.
- **Suggested branch:** `refactor/journal-contract-audit`.

## Suggested order for follow-up branches

Rough priority — highest impact and lowest coordination cost first. Nothing here is urgent; the app runs green.

1. `refactor/journal-contract-audit` — needs a design decision, not a mechanical fix.

## Resolved

Fixed when Phase 3 landed (verified with `./gradlew build`):

- **`bootJar` / `bootRun` main class now exists.** `com.rocketpartners.onboarding.posdiscountengine.Application` is a real `@SpringBootApplication`; `./gradlew bootRun` starts the discount engine on `:8080` and `./gradlew bootJar` produces the one fat jar the `Dockerfile` ships. The old `ClassNotFoundException` at task execution is gone.

Fixed on branch `fix/known-issues-md` (verified with `./gradlew build`):

- **`Transaction` 3-arg cash tender now guards `amountDue >= grandTotal()`.** The overload throws `IllegalArgumentException` when a non-null `amountDue` is below the grand total; `TransactionService.tenderCash` already translates that into an `INVALID_ARGUMENT` error event. Closes the "underpayment framed as change" gap.
- **`LineItemCellRenderer.java` deleted.** Confirmed zero references under `src/main` and `src/test`; `CustomerView` uses `BasketCellRenderer` throughout.
- **`ScannerViewController` now suspends capture while a modal is open.** A `suspendCapture()` method exists and the modal-open arm (`TENDER_*_PRESSED`, `CHANGE_QTY_PRESSED`, `VOID_BASKET_PRESSED`, `TRANSACTION_COMPLETED`) calls it; the receipt path resumes on `RECEIPT_DISMISSED` via `resumeReady()`. The three tests that pinned the old always-running behaviour were flipped, plus a new test covering suspend-on-`TRANSACTION_COMPLETED` / resume-on-`RECEIPT_DISMISSED`.
- **`PosEventType` ERROR Javadoc lists the full 12-code vocabulary**, grouped by dispatch site.
- **`ErrorPopupViewController` gives cashier copy for all three previously-generic codes:** `TRANSACTION_ALREADY_OPEN` → "Finish the current transaction first.", `ABOVE_MAX_QUANTITY` → "Quantity is limited to {max}." (the event now carries a `max` property), `ILLEGAL_STATE` → shares `TOTALED_INVARIANT`'s "That action isn't allowed right now."
- **Title Case convention applied to `VoidBasketConfirmView`** (title "Void Basket?", buttons "Void Basket" / "Keep Basket"); `ButtonLabelTitleCaseTest` now covers that dialog and its old sentence-case exclusion is gone; `VoidBasketConfirmViewTest` asserts the new convention. The deliberate inverted keyboard default (Keep Basket) is preserved. `ChangeQuantityView`'s stale "sentence case throughout" Javadoc claim was corrected (its buttons/title were already Title Case). `CashModeChoiceView` was already Title Case.
