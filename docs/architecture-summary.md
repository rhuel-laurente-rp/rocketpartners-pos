# Architecture Summary

A walkthrough of how the POS is organized by package, what each folder is for, and how data moves between them. Aimed at somebody new to the repo who wants a mental model before diving in.

For the diagrammed, phase-specific view (mermaid diagrams, the three-process runtime, the pricebook storage story), see [Phase 1/architecture.md](Phase%201/architecture.md). This document zooms in on the internal folder architecture of `possystem` and the shared `commons` package.

## The big picture

One project, one source tree. The POS is built as an **event-driven Swing client** with five clearly-separated layers under `com.rocketpartners.onboarding.possystem`:

```
commons/         shared, framework-free data types
possystem/
    component/   the driver (PosComponent), event bus wiring, journaling
    event/       the vocabulary — PosEvent, PosEventType, dispatcher/listener/manager interfaces
    display/     Swing views + view controllers (dumb views, smart controllers)
    repository/  pricebook storage — H2 (production) and in-memory (tests)
    service/     business logic — TransactionService, TaxService, ReceiptFormatter
    constant/    reserved for shared constants
    tools/       standalone utilities (e.g. TailJournal)
posvirtualjournal/  Phase 2 socket server (separate process)
posdiscountengine/  Phase 3 Spring Boot REST server (live; containerized, deployed to AWS ECS)
```

The two rules that make this work:

1. **A view does not know about a service.** Views paint and forward user actions. Services mutate state and dispatch events. Controllers glue the two together.
2. **Cross-component interaction is always a `PosEvent`, never a direct method call.** If class A needs to signal class B, A dispatches an event; B listens for it.

## `commons/` — the shared vocabulary

`commons` depends on nothing else in this repo. It is the shared alphabet everything else speaks.

### `commons/model/` — the domain

Framework-free Java objects for the nouns in the business:

- **`Item`** — a pricebook record (UPC, description, unit price, optional display name). Immutable.
- **`LineItem`** — one product on a transaction (item, quantity, voided flag). Extended total is computed, never stored.
- **`Transaction`** — the aggregate root. Owns line items, discounts, tender, and the `TransactionState` state machine. Money math (`subtotal()`, `discountTotal()`, `taxTotal()`, `grandTotal()`) lives here. **`grandTotal()` is the sole rounding site** — HALF_UP, scale 2.
- **`TransactionState`** — `IN_PROGRESS → TOTALED → PAID`, with `VOIDED` as a side-exit from either non-terminal state.
- **`TenderType`** — `CASH`, `DEBIT`, `CREDIT`.
- **`Discount`** / **`DiscountType`** — the shape of a discount the engine returns.

These types enforce their own invariants. `Transaction.addLineItem(...)` throws if the state is not `IN_PROGRESS`; disabling a Swing button is a nicety, the aggregate is the guarantee.

### `commons/dto/` — the wire contract

Data-transfer objects for the **discount engine call** (Phase 3, live):

- **`TransactionDto`** — the wire form of a transaction, sent to `POST /discounts/calculate`. Also carries `appliedEligibilityCodes` — the cashier-selected eligibility codes.
- **`LineItemDto`** — flattened line item; the item's UPC / description / unit price are inlined so the engine doesn't need to know the domain object graph.
- **`DiscountResponseDto`** — the engine's reply: the `Discount` values to apply, in application order, plus their `discountTotal`.

**Why DTOs are separate from the model.** The engine reads a narrow subset of what a `Transaction` holds — lifecycle state, tender type, cash tendered, and previously-applied discounts are deliberately omitted from the wire form. Keeping a separate DTO means the wire contract can evolve independently of the aggregate. Adding a field to `Transaction` doesn't force a bump of the API version, and vice versa.

The mapping between `Transaction` and `TransactionDto` is a POS-side concern — it lives in `CloudApiComponent` (the POS's HTTP client to the engine), not in `commons` itself.

### `commons/utils/`

Reserved. Currently only a `package-info.java` — small helpers land here as they emerge.

## `event/` — the nervous system

This is what lets the layers stay decoupled. Everything hangs off three tiny interfaces:

- **`IPosEventDispatcher`** — a class that *emits* events (`dispatchPosEvent(event)`).
- **`IPosEventListener`** — a class that *reacts* to events (`onPosEvent(event)` plus a `getListeningEventTypes()` filter).
- **`IPosEventManager`** — a class that *holds a registry* of listeners (`register` / `unregister`).

A class may implement any combination. `PosComponent` implements all three — it holds the master listener registry, it broadcasts events, and it listens for `ERROR` events itself.

The event payload is:

- **`PosEvent`** — an immutable typed event with an unmodifiable property bag (`{String → Object}`). Typed accessors (`getProperty(key, Class)`) do the cast. Events carry payloads like `{lineItem, newQuantity}` or `{code, message, upc}` — never live references that would let a listener mutate hidden state.
- **`PosEventType`** — the enum of every event kind in the system: `QUICK_ADD_PRESSED`, `ITEM_SCANNED`, `LINE_VOIDED`, `TRANSACTION_TOTALED`, `CASH_TENDERED`, `ERROR`, etc. Two families: **input events** (a user did something) and **notification events** (transaction state changed).

Adding a new user action = naming a new `PosEventType`, dispatching it from a view, listening for it in a controller. That's the entire extension point.

## `component/` — the driver and journaling

This package holds the pieces that stitch the system together at runtime.

### `PosComponent` — the main driver

- **Owns transaction state** via a `TransactionService`.
- **Owns the pricebook** (an `ItemRepository`).
- **Is the event bus**: listeners register with it (`IPosEventManager`), and events flow through its `dispatchPosEvent(...)` (`IPosEventDispatcher`).
- **Manages `IController` lifecycle**: `addController(...)` / `start()` / `shutdown()` fan out `onStart` / `onEnd` calls.
- **Deliberately does not import Swing** — it talks to the display layer purely through events.

Every cross-component interaction lands here. If a view wants to add an item, it dispatches an event, `PosComponent` routes it to a controller, the controller calls `TransactionService`, and the service dispatches follow-up events (`ITEM_ADDED`, `ERROR`, etc.) which flow back through `PosComponent` to the view's controller for re-render.

### `IController`

A tiny lifecycle interface — `onStart(PosComponent)` and `onEnd()`. Everything in `display/*ViewController` implements this so `PosComponent` can register / unregister them uniformly. `JournalListener` is also a controller.

### Journaling — `Journal`, `Journals`, `LocalJournal`, `FileJournal`, `RemoteJournal`, `JournalListener`, `JournalRecord`

Every user action produces a journal line. The wiring is:

- **`JournalListener`** — subscribes to *every* `PosEventType`, translates each event into a `JournalRecord`, and hands it to a `Journal`.
- **`Journals`** — a composite `Journal` that fans one record out to three concrete journals.
- **`LocalJournal`** — pipe-delimited to stdout.
- **`FileJournal`** — JSON lines appended to `<log-dir>/journal-YYYY-MM-DD.jsonl`, rolled at UTC midnight.
- **`RemoteJournal`** — ships to the Phase 2 socket server on a dedicated `remote-journal-sender` daemon thread. **Never blocks the Swing EDT.** Non-blocking `offer(...)` onto a bounded queue; a background sender drains it.

`JournalRecord` is the immutable shape of one journal entry.

### Barcode capture — `BarcodeInputBuffer`, `Barcodes`

`BarcodeInputBuffer` accumulates keystrokes into bursts and emits a completed UPC when a terminator (`\n` or `\t`) arrives inside the burst window. `Barcodes.isValidUpc(raw)` validates. The buffer is fed by `ScannerViewController`, which installs a KeyEventDispatcher on the KeyboardFocusManager — every keystroke passes through it before landing on a focused component.

## `display/` — Swing views and their controllers

The rule is strict: **views render, controllers decide.** For every screen there is a matched pair.

### Views

Each `*View` (e.g. `CustomerView`, `PayWithCashView`, `ChangeQuantityView`, `ReceiptView`, `ScannerView`, `ManualBarcodeEntryView`) is a `JPanel` / `JDialog` that:

- Renders state.
- Exposes hooks (buttons, list selection, key bindings) that dispatch a `PosEvent` when the user acts.
- Holds a reference to an `IPosEventDispatcher` (typically the `PosComponent`), never a service.

Business logic does *not* live in a view. If you catch yourself typing `transactionService.` inside a `*View`, stop.

**`QuickAddPanel`** is the extracted Quick Add grid (tiles + paging pills + a search field), pulled out of `CustomerView`. **`ManualBarcodeEntryView`** is a touch-keypad fallback for hand-keying a UPC when a barcode won't scan.

**On-screen input widgets — `OnScreenKeyboard`, `OnScreenKeypad`, `OnScreenKeys`.** Touch keyboards for a keyboard-less terminal: a QWERTY panel for the Quick Add search field and a numeric keypad for the money / quantity / manual-entry dialogs, over a shared document-mutation helper. They are dumb by construction — non-focusable keys that type by mutating the target field's `Document` (never a `PosEvent`, never a synthesised `KeyEvent`), which keeps taps invisible to the scanner's `KeyEventDispatcher` and preserves the field's own `DocumentFilter` and `PERSIST` validation. See [swing-notes.md](swing-notes.md).

### View controllers

Each `*ViewController` (e.g. `CustomerViewController`, `PayWithCashViewController`) is an `IController + IPosEventListener` that:

- Subscribes to the input events its view produces (`QUICK_ADD_PRESSED`, `CASH_CONFIRM_PRESSED`, ...) and to relevant notification events (`QUANTITY_CHANGED`, `LINE_VOIDED`, ...).
- On an input event: validates, calls `TransactionService`, and lets the resulting notification events flow back through `PosComponent`.
- On a notification event: reads the current transaction and asks the view to re-render.
- Swallows service exceptions — the service has already dispatched an `ERROR` event, and re-throwing would kill the Swing event loop.

### Design system — `PosTheme`, `PosDialog`, `PosButton(s)`, cell renderers

- **`PosTheme`** — the sole source of colour and typography tokens (`INK`, `SURFACE`, `RULE`, `MUTED`, `GO`, `STOP`, and font roles `EYEBROW`, `BODY`, `ROW`, `BUTTON`, `HEADLINE`). No colour literals belong outside this class.
- **`PosDialog`** — the modal shell used by every dialog in the app. Dark `INK` header, `SURFACE` body, footer with secondary-on-left, primary-on-right.
- **`PosButton(s)`** — button primitives that read from `PosTheme`.
- **`BasketCellRenderer`, `LineItemCellRenderer`** — how basket rows paint themselves inside a `JList`. Renderers stay allocation-free per call.

## `repository/` — pricebook storage

Behind an interface, so services and controllers never see the concrete impl.

- **`ItemRepository`** — the interface: `Optional<Item> findByUpc(String)`, `List<Item> getAll()` (backs the Quick Add grid), `int size()`.
- **`h2/H2ItemRepository`** — production. File-mode H2, one JDBC connection held for the process lifetime. On first run the `ITEMS` table is seeded from the classpath `pricebook.tsv`; on later runs the seed is skipped and lookups come straight from the DB. Edits to `ITEMS` survive restarts.
- **`inmemory/InMemoryItemRepository`** — the same interface, backed by a `Map<String, Item>`. Used by tests and any in-process fixture that shouldn't touch disk.
- **`PricebookTsv`** — a shared parser both implementations reuse.
- **`UpcResolver`** — an ordered normalization ladder (exact → strip leading zeros → drop UPC-A check digit) that resolves a scanned code against the pricebook without rewriting it, since the scanner always emits 12 digits but the pricebook keys items on codes of assorted lengths. Used by `TransactionService.addItemByUpcDetailed`. When *every* rung misses **and** the raw input is 12 digits with an invalid UPC-A check digit, the service treats it as a likely scanner misread and dispatches `ERROR{code=UPC_MISREAD}` (prompt to rescan) rather than `UPC_NOT_FOUND` (unlisted item).

Everything downstream (`TransactionService`, `PosComponent`, controllers) depends only on `ItemRepository`. Swapping impls doesn't ripple.

## `service/` — business logic

Where the rules of a sale live.

- **`TransactionService`** — the stateful facade over one in-flight `Transaction`. Everything a controller wants to do to the current sale routes through here: `startTransaction`, `addItemByUpc`, `voidLine`, `updateLineItemQuantity`, `voidBasket`, `total`, `tenderCash`, `tenderCard`. On any invariant violation or lookup miss, it **dispatches an `ERROR` event via the event bus before rethrowing** — the UI is not the only listener, so callers who don't render an error still get it recorded.
- **`TaxService`** — supplies the flat tax rate. A rate source, not a compute layer; the actual tax formula lives on `Transaction`.
- **`ReceiptFormatter`** — pure function from a paid `Transaction` to receipt text.

Services depend on `commons` and `repository`, and on `event` (only to dispatch outbound events). They **never** import from `display/`.

## `constant/` and `tools/`

- **`constant/`** — reserved for shared constants. Package-info-only today.
- **`tools/`** — standalone utilities with their own `main`. `TailJournal` tails the FileJournal output (wrapped as the `tailJournalLog` Gradle task).

## How data flows — one end-to-end example

Scan a UPC through to a printed receipt:

```
1.  ScannerViewController receives KEY_TYPED events
    → BarcodeInputBuffer accumulates a burst
    → burst ends → controller dispatches ITEM_SCANNED{upc}

2.  CustomerViewController listens for ITEM_SCANNED
    → calls TransactionService.addItemByUpc(upc, 1)

3.  TransactionService
    → itemRepository.findByUpc(upc)      // repository layer
    → currentTransaction.addLineItem(item, 1)   // domain layer enforces state machine
    → (implicit) event chain continues via subsequent notification events

4.  Notification events (ITEM_ADDED, and errors when applicable) flow
    through PosComponent to every registered listener:
    → CustomerViewController re-renders the basket
    → JournalListener writes a JournalRecord
    → Journals fans it out to LocalJournal, FileJournal, RemoteJournal

5.  On error (unknown UPC), TransactionService dispatches
    ERROR{code=UPC_NOT_FOUND, upc, message} before rethrowing.
    → ErrorPopupViewController listens for ERROR, shows the popup
    → JournalListener also records the error line

6.  Cashier presses Total → TOTAL_PRESSED → TransactionService.total()
    → Transaction moves to TOTALED (basket frozen)
    → TRANSACTION_TOTALED event → CustomerView repaints "AWAITING PAYMENT"

7.  Cashier presses Pay Cash → TENDER_CASH_PRESSED
    → CashModeChoiceView opens (Exact / Next Dollar)
    → CASH_EXACT_PRESSED or CASH_NEXT_DOLLAR_PRESSED
    → PayWithCashView opens pre-filled
    → CASH_CONFIRM_PRESSED{cashReceived}
    → PayWithCashViewController validates → TransactionService.tenderCash(cash, amountDue)
    → Transaction moves to PAID
    → CASH_TENDERED + TRANSACTION_COMPLETED events

8.  ReceiptViewController listens for TRANSACTION_COMPLETED
    → calls TransactionService.generateReceipt(tx, storeName, laneNumber, cashier)
      (cashier = the operator id from the login screen, carried on PosComponent;
       the receipt header prints it as a "Cashier:" line)
    → ReceiptView opens showing the formatted text
      (the transaction id on the header is a plain sequential integer, not a UUID)

9.  Cashier dismisses receipt → RECEIPT_DISMISS_PRESSED → RECEIPT_DISMISSED
    → CustomerViewController opens a fresh transaction — ready for the next customer
```

Each numbered step crosses exactly one layer boundary, and every boundary crossing is either an event or a well-defined service call. No shortcuts.

## Package boundaries — the rules to hold in your head

Nothing in the build enforces these; they are enforced in review.

- **`commons` depends on nothing else here.** Never `commons → possystem`.
- **`posvirtualjournal` and `posdiscountengine` never import from `possystem`.** They are servers; the POS calls them, not the reverse.
- **`possystem` talks to the other two only over the wire** — socket for the journal, HTTP (via `CloudApiComponent`) for the discount engine. Importing `posdiscountengine.service.*` into the POS defeats the whole Phase 3 exercise.
- **Inside `possystem`:** `display/` may reach into `service/` and `component/` only via events and controllers; services never import `display/`; nothing outside `repository/*` should reference a concrete `ItemRepository` implementation.

## Cross-references

- Runtime processes, the three-process picture, and pricebook storage: [Phase 1/architecture.md](Phase%201/architecture.md)
- The cashier's path through a sale: [Phase 1/user-flow.md](Phase%201/user-flow.md)
- Every `PosEvent`, end to end: [Phase 1/event-flow.md](Phase%201/event-flow.md)
- The nouns and their invariants: [Phase 1/domain-model.md](Phase%201/domain-model.md)
- Swing traps and dispositions: [swing-notes.md](swing-notes.md), [known-issues.md](known-issues.md)
