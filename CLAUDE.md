# CLAUDE.md

## What this is

Rocket Partners POS Onboarding Project — a mock Point-of-Sale system.

**One project.** One repo, one `build.gradle`, one `src/main/java`, one `src/test/java`. No Gradle subprojects, no per-module dependency blocks, no `settings.gradle` include list. Separation between areas of the system is by **package**, not by build module.

The work is divided into three phases, but the phases are a **task-sequencing device for the developer** — a way to build one system a piece at a time — not a structural division. Don't turn them into modules, source sets, or artifacts.

- **Phase 1** — the POS desktop client (Java Swing). Live.
- **Phase 2** — a virtual journal server that receives and prints the POS's transaction logs over a socket. Live.
- **Phase 3** — a discount engine REST API, containerized and deployed to AWS. Not yet implemented (see the Phase 3 section below).

**`possystem` is worked on in all three phases.** Phase 2 added journal sending to it; Phase 3 will add the discount-engine call. Phase 1 code is not frozen — extend it. But Phase 1 behavior and tests must stay green while you do.

This is a **learning project**, not production. When there's a tradeoff between a clever solution and one that clearly demonstrates the pattern being taught (event-driven design in Phase 1, sockets in Phase 2, REST and containers in Phase 3), pick the clear one.

## Repo layout

```
build.gradle              # the only build file
settings.gradle           # just rootProject.name
src/main/java/com/rocketpartners/onboarding/
    commons/{model,dto,utils}
    possystem/{component,event,display,repository,service,tools,constant}
    posvirtualjournal/
    posdiscountengine/{component,controller,entity,repository,service}   # empty package-info.java only
src/main/resources/
    pricebook.tsv         # sole resource today
src/test/java/...         # mirrors the main package tree
docs/
    Phase 1/{architecture,user-flow,event-flow,domain-model}.md
    swing-notes.md        # Swing footguns
    known-issues.md       # live bugs and dispositions
logs/                     # runtime output of FileJournal (git-ignored)
```

Everything sits under `com.rocketpartners.onboarding`. There is no `Dockerfile`, no `discounts.csv`, no `application*.properties` in the repo today — the Phase 3 build has not started.

### Package discipline

With one project there's no build-enforced dependency direction, so it's on you to maintain:

- **`commons` depends on nothing else here.** Models, DTOs, utilities only. If something in `commons` imports from `possystem`, that's a bug.
- **`posvirtualjournal` and `posdiscountengine` never import from `possystem`.** They're servers; the POS calls them, not the reverse.
- **`possystem` may use `commons`, and talks to the other two only over the wire** — socket and (planned) HTTP — never by direct method call. Importing `posdiscountengine.service.DiscountService` into the POS would make the whole Phase 3 exercise meaningless.

An import that crosses these lines is the main thing to watch for in review.

`docs/` is not decoration — the brief calls for data-flow and user-flow diagrams *before* code. If you change transaction flow, event routing, or the (future) discount request/response contract, update the matching diagram in the same change. The diagrams live in `docs/Phase 1/`, not here.

## Domain glossary

Use these terms exactly in class, method, and variable names. No synonyms — not `CartRow` for a line item, not `Sale` for a transaction.

| Term | Meaning |
| --- | --- |
| **Line Item** | One product on a transaction: description, quantity, unit price, extended total. |
| **Transaction** | A whole sale: line items, totals, tender, discounts, taxes. |
| **Void** | Cancel a whole transaction (void basket) or a single line (void line). |
| **Receipt** | Proof-of-purchase output: items, prices, discounts, taxes, total paid. |
| **UPC** | Barcode identifying a product; the pricebook lookup key. |
| **Tender Type** | Payment method — cash, debit/credit, etc. |
| **Discount** | Price reduction: percent off, fixed amount off, or promo (e.g. BOGO). |
| **Pricebook** | UPC → product/price store the POS looks items up in. |
| **POS Terminal** | The simulated hardware the client stands in for. |

## The assembled system

Three intended processes at runtime, one codebase. The POS is the only initiator; the other processes know nothing about each other or the POS. See [docs/Phase 1/architecture.md](docs/Phase%201/architecture.md) for the full diagram — do not duplicate it here.

**Both network hops are optional at runtime.** The POS starts, rings up sales, and completes transactions with either or both peers down: journal writes are fire-and-forget, discount lookups (once implemented) time out and apply no discount. Hard requirement, not a nicety. `JournalCrossPhaseTest` pins the "Phase 1 stays green with the journal unreachable" invariant.

## Phase 1 — the POS client (`possystem`)

### Architecture (the actual lesson)

Phase 1's core lesson is event-driven separation of concerns. Swing views are dumb — render only, forward user actions. All business logic lives in `*ViewController` classes and services. `PosComponent` is the main driver, holding transaction state (via `TransactionService`) and the pricebook. Everything else talks to it through a typed `PosEvent` dispatched via `IPosEventDispatcher`/`IPosEventListener`; a class may implement both. If you're about to put logic inside a `*View` class, stop — it belongs in the controller or a service instead.

Keep Swing components lightweight. A new cross-component interaction means a new `PosEvent` type, not a direct reference between components.

### Required behavior — all live

Quick Add tiles, Void Line, Void Basket, Total, Pay Cash (two-step: mode choice → entry), Pay Debit, Pay Credit, Change Quantity, and scanner-driven barcode capture against the pricebook. Change Quantity is fully built and tested (`ChangeQuantityView` / `ChangeQuantityViewController`, `TransactionService.updateLineItemQuantity`, quantity-≥1 invariant, `QUANTITY_CHANGED` journalling) — do not treat it as optional.

**The Total invariant.** Once **Total** is pressed the basket is finalized: `TransactionState` becomes `TOTALED` and further mutation is rejected by `Transaction` itself. Add-item, void-line, and change-quantity all throw. The only actions legal in `TOTALED` are the tenders and Void Basket. Enforced on the aggregate — disabling buttons is a nicety, not the guarantee.

Every action is journalled. Adding a user action means adding its journal entry — do that by naming a new `PosEventType` and letting `JournalListener` pick it up.

**Pricebook.** H2 in file mode, opened via `H2ItemRepository.open(dbDir, dbName, "/pricebook.tsv")`. The DB lives at `<--db-dir>/<--db-name>.mv.db` (default `data/pricebook.mv.db`, git-ignored). On first run the `ITEMS` table is empty and gets seeded from the classpath TSV; on later runs the seed is skipped and lookups come from the DB — edits to `ITEMS` survive restarts. `InMemoryItemRepository` is kept for tests and other in-process fixtures; both impls share the `PricebookTsv` parser. Everything downstream (`TransactionService`, `PosComponent`, controllers) depends only on the `ItemRepository` interface — do not import a concrete impl into service or display code.

**H2 is single-writer.** One JDBC connection is opened for the process lifetime and closed on window close. A second POS against the same DB file fails loudly — do not "fix" that by switching to `AUTO_SERVER=TRUE` without an actual multi-process requirement.

### Invariants worth naming out loud

- **Quantity ≥ 1** on any non-voided `LineItem`. `updateLineItemQuantity(li, 0)` routes through `voidLine` — one implementation for both paths.
- **Max quantity** = `TransactionService.DEFAULT_MAX_LINE_QUANTITY = 999`. Overflow is an `ABOVE_MAX_QUANTITY` error event.
- **Void Line is the only path to removing a line.** A zero quantity is a void, not a delete.
- **Unchanged quantity is a no-op — double-gated on purpose.** Both `ChangeQuantityViewController` and `TransactionService.updateLineItemQuantity` short-circuit when `newQuantity == currentQuantity`. Two guards mean neither depends on the other. Do not "deduplicate" this.
- **Money.** All computation in `BigDecimal`. Intermediate totals are unrounded; `Transaction.grandTotal()` is the sole rounding site (scale 2, HALF_UP). Never introduce a second rounding call.
- **Next Dollar ceils the amount due, not the tender.** `PayWithCashViewController.nextDollar(grandTotal)` computes `ceil(grandTotal())` and drives the two-step cash flow with that value as the settled `amountDue`; confirmation persists it via the three-argument `TransactionService.tenderCash(cashReceived, amountDue)`. `changeDue()` computes against `amountDue()` — not `grandTotal()`. On a $7.30 basket, Next Dollar makes amount due $8.00: a customer handing $8.00 gets no change; a customer handing $10.00 gets exactly $2.00 rather than $2.70. The cashier never counts coins. Rewriting `changeDue()` to use `grandTotal()` destroys the feature — `ReceiptFormatter` prints a dedicated `Amount Due (Next Dollar)` line as corroboration. See [docs/Phase 1/domain-model.md](docs/Phase%201/domain-model.md).
- **`voidBasket()`** is legal in `IN_PROGRESS` and `TOTALED`. The `priorState` field on `BASKET_VOIDED` distinguishes the two — post-Total voids are the operationally interesting case.

### Barcode capture

`ScannerViewController` installs an application-wide `KeyEventDispatcher` on the `KeyboardFocusManager`. Every KEY_TYPED event goes through `BarcodeInputBuffer` before it reaches any focused component. The dispatcher **only consumes** the terminator that closes a fast-enough burst — every other keystroke passes through so text fields keep working. `BarcodeInputBuffer` defaults: `burstGapMs = 50`, `staleTimeoutMs = 200`, `NO_PREFIX`, terminators `\n` and `\t`. Prefix stripping is per-burst on the first char only.

**`Barcodes.isValidUpc(raw)` accepts any non-empty string of digits.** No length constraint — the bundled `pricebook.tsv` carries UPCs of assorted lengths, so UPC-A (12) and EAN-13 (13) checks would reject valid entries. Do not add a length check without swapping the pricebook.

Two capture paths land in the same place. Scanner bursts complete via the dispatcher and dispatch `ITEM_SCANNED`. Manual entry into the scan field dispatches `SCAN_SUBMIT_PRESSED`, which the controller validates and forwards as `ITEM_SCANNED` on success.

### Journalling

`Journals` is a composite `Journal` that fans one record out to three concrete journals:

- **`LocalJournal`** — pipe-delimited to stdout.
- **`FileJournal`** — one JSON object per line, appended to `<log-dir>/journal-YYYY-MM-DD.jsonl`, rolled at UTC midnight. Read it live with the `tailJournalLog` Gradle task (`com.rocketpartners.onboarding.possystem.tools.TailJournal`).
- **`RemoteJournal`** — the Phase 2 socket hop.

**Delivery contract.** `Journal.journal(...)` must not block or throw. Enqueue via non-blocking `offer(...)`. `RemoteJournal` runs a dedicated `remote-journal-sender` daemon thread reading the queue and shipping records; every other implementation flushes on the caller's thread (`FileJournal` does this deliberately — small writes, no network — see its class Javadoc and `docs/known-issues.md`).

Connection state (`CONNECTED` / `DISCONNECTED`) is exposed via `RemoteJournal.setConnectionListener`; `CustomerView` renders a header pill from it, marshalling onto the EDT itself. Anti-regression: `CustomerViewJournalStatusTest`.

### PosTheme and PosDialog — the design system

- **`PosTheme` is the sole source of colour and typography tokens.** Every colour and font in the app reads through it (`INK`, `SURFACE`, `RULE`, `MUTED`, `GO`, `STOP`, and the type roles `EYEBROW`, `BODY`, `ROW`, `BUTTON`, `HEADLINE`). If you find yourself typing `new Color(0x…)` outside `PosTheme`, stop.
- **`PosDialog` is the shell for every modal.** Dark `INK` header, `SURFACE` body, footer with **secondary on the left, primary on the right, always**. ESC = cancel, Enter = primary (except `VoidBasketConfirmView` — see below). The native window title is empty on purpose so the label only appears in the dark strip.

### Copy conventions

- **Title Case** for every button label and dialog title. Enforced by `ButtonLabelTitleCaseTest`.
- **Eyebrow labels** ("SCAN", "QUICK ADD", "AMOUNT DUE", "OPEN", "AWAITING PAYMENT", "LOCKED") are uppercase via `PosTheme.eyebrow()`'s font and tracking, *not* by capitalising the string. Set the label text sentence-case in the source; the theme paints it uppercase.
- **Void basket dialog is the one deliberate exception to Title Case.** `VoidBasketConfirmView` and its `VoidBasketConfirmViewTest#everyVisibleString_isSentenceCase` are on a sentence-case island today; the drift is scheduled to be fixed together with two other sites — see `docs/known-issues.md` for the group.
- **`VoidBasketConfirmView` also inverts the keyboard default** on purpose: initial focus, the root pane's default button, and ESC all point at *Keep basket*, not the danger-styled *Void basket*. A stray Enter or scanner terminator must never void a basket. This inversion must survive the eventual casing fix.

### The event vocabulary and error codes

`PosEventType` carries every event that crosses `PosComponent`. The `ERROR` event's `code` property is the routing key for cashier messaging in `ErrorPopupViewController`:

| Code | Cashier-facing message today | Where dispatched |
| --- | --- | --- |
| `UPC_NOT_FOUND` | Item not found: `{upc}` | `TransactionService.addItemByUpc` |
| `INVALID_BARCODE` | Not a valid barcode: `{raw}` | `ScannerViewController` (non-digit or empty burst) |
| `SCAN_LOCKED` | Scanning is locked — press Total to tender. | `ScannerViewController` on scan attempts in TOTALED |
| `INVALID_CASH_AMOUNT` | Invalid cash amount. Enter a valid, non-negative number. | `PayWithCashViewController` |
| `UNDERPAYMENT` | Cash received is less than the amount due. | `PayWithCashViewController` |
| `TOTALED_INVARIANT` | That action isn't allowed right now. | `TransactionService` on state-machine violations |
| `NO_TRANSACTION` | That action isn't allowed right now. | `TransactionService` when nothing is open |
| `INVALID_ARGUMENT` | Invalid input: `{message}` | `TransactionService` on argument validation |
| `TRANSACTION_ALREADY_OPEN` | *(falls through to "An unexpected error occurred.")* | `TransactionService.startTransaction` |
| `ABOVE_MAX_QUANTITY` | *(falls through to generic)* | `TransactionService.updateLineItemQuantity` |
| `ILLEGAL_STATE` | *(falls through to generic)* | `TransactionService.voidBasket` |

The three "falls through to generic" rows are cashier-copy gaps tracked in `docs/known-issues.md`, not intentional. Do not add a switch case for one and skip the other two.

### Two-step cash flow

`TENDER_CASH_PRESSED` opens `CashModeChoiceView`, which dispatches either `CASH_EXACT_PRESSED` (pre-fills the entry dialog with grand total) or `CASH_NEXT_DOLLAR_PRESSED` (pre-fills with the ceiled amount). Both open `PayWithCashView`, which dispatches `CASH_CONFIRM_PRESSED` with the raw entered string. `PayWithCashViewController` validates upstream — Next Dollar and Exact converge on `TransactionService.tenderCash(cash, amountDue)` with `amountDue` set only when Next Dollar was used.

### Card approval

`PayWithCardViewController` schedules its simulated approval via `javax.swing.Timer` (default 800 ms), so the delay runs off the EDT — a `Thread.sleep` on the EDT would freeze the whole UI mid-"approval". Tests inject a synchronous `ApprovalScheduler`.

### Item.displayName

`Item` carries a fourth field, `displayName`, and `getDisplayLabel()` falls back to `description` when it is null or blank. Both `QuickAddTile` and `BasketCellRenderer` render via `getDisplayLabel()`. Today's `pricebook.tsv` carries three columns, so the field is always null and the fallback always runs — but the code is ready for the fourth column whenever it's added. Keep the constructor and the getter; do not delete as "unused."

### Swing traps

One-line symptoms — the recognisable thing that happens to you. Full explanations in [docs/swing-notes.md](docs/swing-notes.md).

- **Cashier types `0`, clicks Confirm, and nothing happens.** `JFormattedTextField` reverts invalid input on focus loss.
- **Two children in a `BoxLayout` are visually skewed.** Mixed `alignmentX` between siblings.
- **A label grows to fill the whole column in a `BoxLayout`.** No `setMaximumSize` on the child.
- **Two square tiles in a `GridLayout` come out rectangular.** `GridLayout` ignores per-child max size.
- **Custom-painted button appears clickable but doesn't fire.** Nested child components inside a custom-painted `JButton` swallow the mouse.
- **Enter in a dialog fires a different button than Space does.** Enter goes to the root pane's *default* button; Space goes to the focused one.
- **Basket list stutters under scrolling.** Allocations inside `ListCellRenderer.getListCellRendererComponent`.
- **UI freezes for a beat when a background thread updates state.** Touching Swing off the EDT — marshal via `SwingUtilities.invokeLater`.

## Phase 2 — the virtual journal (`posvirtualjournal`)

A server that receives log lines from the POS over `java.net.Socket` and prints them. Two halves: the server (its own entry point, `Driver`) and the client-side integration inside `possystem` (`RemoteJournal`, plus `JournalListener` feeding it).

Socket error handling is the graded part. The POS must not hang, crash, or lose a transaction because the journal is down, slow, or drops mid-transaction. Journal sending is best-effort, runs off the Swing EDT on a dedicated `remote-journal-sender` daemon thread, and never blocks checkout.

## Phase 3 — the discount engine (`posdiscountengine`) — **not yet implemented**

The package tree exists (`component/`, `controller/`, `entity/`, `repository/`, `service/`), but every file under it is an empty `package-info.java`. No `Application` class, no `Dockerfile`, no `discounts.csv`, no `application.properties`. Nothing crosses HTTP today; the POS ships and paints as if the discount engine returned an empty discount list.

**Target shape** (paraphrased from the onboarding brief — full detail in `docs/POS Onboarding Project - Phase 3 In-Depth.docx`):

- Spring Boot REST API on `:8080`, taking a `TransactionDto` and returning a list of discounts to apply. Rules live in the database (Spring Data JPA + H2) rather than being hard-coded — adding a rule is data, not code.
- `@SpringBootApplication` class placed under `posdiscountengine` so component scan does not reach the Swing packages. Do not move it up to `com.rocketpartners.onboarding`.
- Container: `Dockerfile` at the repo root, built on `eclipse-temurin:17-jdk-jammy`, exposing `8080`. Since it is one project, `bootJar` produces one fat jar containing every set of code; the image is fatter than a stripped-down engine build would be, and that is accepted for onboarding.
- Deployment: ECR → ECS behind an Application Load Balancer.

**Broken until Phase 3 lands.** `build.gradle` points `bootJar` and `bootRun` at `com.rocketpartners.onboarding.posdiscountengine.Application`, which does not exist. `./gradlew build` still passes (nothing references the class); `./gradlew bootRun` fails at task execution with `ClassNotFoundException`. Tracked in `docs/known-issues.md`. Do not "fix" this by pointing the tasks at the Swing `Application` — the fix is to write the discount engine's `Application`.

## Stack

Java 17, Gradle (Groovy DSL), one dependency block for everything: Swing + FlatLaf + JCommander (POS), H2 in file mode for the POS pricebook (JDBC — no Spring on the POS side), Spring Boot 3.3.x + Spring Data JPA + H2 (discount engine, when it lands), Apache HttpClient5 and Jackson (POS → engine, when it lands), Lombok. Tests: JUnit 5, Mockito, Awaitility for socket/async code.

## Build & run

Verified — these commands were run before this section was written.

```bash
./gradlew build              # works — compiles and runs the full test suite
./gradlew runPos             # works — Swing POS client
./gradlew runJournal         # works — virtual journal server on :12345
./gradlew tailJournalLog     # works — tails logs/journal-YYYY-MM-DD.jsonl
./gradlew bootRun            # FAILS at runtime — see Phase 3 section above
```

The four `JavaExec`/Spring tasks are distinguished by main class — that's how one project yields multiple entry points. Don't add subprojects to solve this.

Run `./gradlew build` before considering any change finished.

**POS CLI flags** (JCommander; pass via `--args="…"` on `runPos`):

| Flag | Default | Meaning |
| --- | --- | --- |
| `--debug` | `false` | Verbose event tracing to stderr; also arms the F12 demo-scan hotkey. |
| `--app-mode` | `NORMAL` | Reserved (`TRAINING` unused today). |
| `--store-name` | `Rocket Store` | Label on the window and receipts. |
| `--lane-number` | `1` | Terminal/lane number. |
| `--journal-host` | `localhost` | Journal socket hostname. |
| `--journal-port` | `12345` | Journal socket port. |
| `--discount-engine-url` | `http://localhost:8080` | Reserved for Phase 3. |
| `--scan-burst-gap-ms` | `50` | Max inter-character gap inside a scanner burst. |
| `--log-dir` | `logs` | Directory the file journal writes into. |
| `--db-dir` | `data` | Directory holding the H2 pricebook DB file. |
| `--db-name` | `pricebook` | Base name of the H2 DB (no extension). |
| `--help` / `-h` | — | Print usage and exit. |

**Journal CLI flags** (`runJournal`): `--port` (default `12345`) and `--help`. That's the whole surface.

**Cross-phase regression rule.** Work on a later phase must not break an earlier one. If a Phase 3 change turns a Phase 1 test red, the change is wrong until proven otherwise — don't edit the test to match the new behavior without flagging it explicitly. `JournalCrossPhaseTest` is the pinned example: Phase 1 stays green with the journal unreachable.

To exercise the whole system, run `runPos` and `runJournal` in separate terminals and ring up a real sale. The POS takes CLI args for journal host/port and (future) discount-engine base URL, so pointing it at a deployed engine will be an argument change, not a code change.

## Working on this repo

Do all work on a feature branch off `main`, never directly on `main`. **Do not run `git commit` or `git push`** — leave committing to the user; your job ends at working, tested code and a clean `git diff`/`git status` for them to review.

## Reference implementations

Prior junior-dev versions, useful for comparison — not for copying wholesale, and not authoritative over this repo's conventions:

- https://github.com/JohnLavender474/RocketPartners-PosOnboardingProject-DesktopClient
- https://github.com/wesHawkeyeMaszk/PoS
