# Domain Model

The nouns of the POS. Names here are the vocabulary from CLAUDE.md — use them exactly in code (`LineItem`, not `CartRow`; `Transaction`, not `Sale`).

## Class diagram

```mermaid
classDiagram
    class Item {
        +String upc
        +String description
        +String displayName
        +BigDecimal unitPrice
        +getDisplayLabel() String
    }

    class LineItem {
        +Item item
        +int quantity
        +boolean voided
        +extendedTotal() BigDecimal
    }

    class Transaction {
        +String transactionId
        +Instant createdAt
        +List~LineItem~ lineItems
        +List~Discount~ discounts
        +BigDecimal taxRate
        +TransactionState state
        +TenderType tenderType
        +BigDecimal cashTendered
        +BigDecimal amountDue
        +subtotal() BigDecimal
        +discountTotal() BigDecimal
        +taxTotal() BigDecimal
        +grandTotal() BigDecimal
        +amountDue() BigDecimal
        +changeDue() BigDecimal
        +addLineItem(Item, int)
        +voidLine(LineItem)
        +updateLineItemQuantity(LineItem, int)
        +voidBasket()
        +total()
        +applyDiscount(Discount)
        +tender(TenderType, BigDecimal)
        +tender(TenderType, BigDecimal, BigDecimal)
    }

    class TransactionState {
        <<enumeration>>
        IN_PROGRESS
        TOTALED
        PAID
        VOIDED
    }

    class TenderType {
        <<enumeration>>
        CASH
        DEBIT
        CREDIT
    }

    class Discount {
        +String discountId
        +String description
        +DiscountType type
        +BigDecimal amount
        +BigDecimal appliedAmount
    }

    class DiscountType {
        <<enumeration>>
        PERCENT_OFF
        FIXED_AMOUNT_OFF
        PROMO
    }

    Transaction "1" *-- "0..*" LineItem : contains
    Transaction "1" *-- "0..*" Discount : applied
    Transaction "1" --> "1" TransactionState : state
    Transaction "1" --> "0..1" TenderType : paid with
    LineItem "1" --> "1" Item : refers to
    Discount "1" --> "1" DiscountType : kind of
```

## State machine — `TransactionState`

The state field on `Transaction` mirrors the user flow in [user-flow.md](user-flow.md). It's the single source of truth for which operations are legal.

```mermaid
stateDiagram-v2
    [*] --> IN_PROGRESS
    IN_PROGRESS --> IN_PROGRESS: addLineItem / voidLine / updateLineItemQuantity
    IN_PROGRESS --> VOIDED: voidBasket
    IN_PROGRESS --> TOTALED: total()
    TOTALED --> TOTALED: applyDiscount
    TOTALED --> VOIDED: voidBasket
    TOTALED --> PAID: tender(...)
    PAID --> [*]
    VOIDED --> [*]
```

`addLineItem`, `voidLine`, `updateLineItemQuantity` are illegal in `TOTALED`, `PAID`, and `VOIDED`. `total()` is illegal outside `IN_PROGRESS`. `tender` / `applyDiscount` are legal only in `TOTALED`. `voidBasket` is legal in `IN_PROGRESS` and `TOTALED`. `PAID` and `VOIDED` are terminal. The check lives on `Transaction` (or `TransactionService`), never solely on the button.

## Money — one rounding site

- **`BigDecimal` everywhere.** Never `double`.
- **Intermediate totals are unrounded.** `subtotal()`, `discountTotal()`, `taxTotal()`, and `LineItem.extendedTotal()` return raw values so rounding errors don't compound.
- **`grandTotal()` is the sole rounding site.** Scale 2, `HALF_UP`.
- **Tax.** A flat `taxRate` field on `Transaction`, applied after totaling to the post-discount subtotal: `taxTotal = (subtotal − discountTotal) × taxRate`. Not a per-line concern; `Item` does not carry a taxable flag.

## Next Dollar — the change-simplification device

**Next Dollar ceils the amount due, not the tender.** `PayWithCashViewController.nextDollar(grandTotal)` computes `ceil(grandTotal())` and drives the two-step cash flow with that value as the settled `amountDue`; confirmation calls the three-argument tender overload via `TransactionService.tenderCash(cashReceived, amountDue)`. `changeDue()` computes `cashTendered − amountDue()` — measuring against the settled amount, not the raw grand total.

**Why.** To keep coins out of change. On a $7.30 basket:

- **Exact Amount tender:** the customer owes $7.30. Handing over $10.00 produces $2.70 change — a quarter, two dimes, and a nickel.
- **Next Dollar tender:** the amount due becomes $8.00. Handing over $8.00 produces $0.00 change; handing over $10.00 produces exactly $2.00 change — one banknote. The cashier never counts coins.

The customer gives up $0.70 in exchange for a workflow where the coin drawer never opens on this class of tender. That is the substance of the feature, not a rounding footnote.

**Corroboration.** `ReceiptFormatter` prints a dedicated `Amount Due (Next Dollar):` line when `amountDue()` differs from `grandTotal()`, so the audit trail records the mode that was used. The `Amount Due (Exact):` variant appears when they match. Both lines print only on PAID transactions.

**What would break the feature.** Computing change against `grandTotal()` instead of `amountDue()`. Someone reading `changeDue()` fresh will squint at `cashTendered − amountDue()` and want to "fix" it to `cashTendered − grandTotal()`. Do not. That change destroys the whole feature: $8.00 tendered against a $7.30 grand total would produce $0.70 change again, and the cashier is back to counting coins.

`Transaction.amountDue()` returns `grandTotal()` when the field is null, so the two-argument tender path is unaffected — this is a strict superset, not a swap.

## Notes on the shape

- **`Item` vs `LineItem`.** `Item` is the product record from the pricebook — one per UPC. `LineItem` is that product's appearance on a specific Transaction with a quantity; a second scan of the same UPC increments the existing line rather than appending a new one, per `Transaction.addLineItem` and `TransactionService.addItemByUpc`.
- **`Item.displayName`** is a fourth pricebook column (customer-friendly label) that falls back to `description` via `getDisplayLabel()`. Used by `QuickAddTile` and `BasketCellRenderer`. The bundled `pricebook.tsv` carries three columns today, so the fallback path is what actually runs — the code is ready for the fourth column whenever it's added.
- **`Discount` is a value on a Transaction, not a rule.** The rule that produced it (BOGO, "10% off produce", etc.) will live in the discount engine's database (Phase 3). What the POS holds is the *result* of applying a rule: an amount and a description for the Receipt.
- **`Void`** is captured two ways. `voidBasket()` transitions the whole Transaction to the terminal `VOIDED` state; `voidLine` marks a `LineItem` as `voided` (soft-delete keeps the audit trail for the journal). `updateLineItemQuantity(li, 0)` on `TransactionService` routes through `voidLine` — one implementation for both paths.
- **Quantity is always ≥ 1** on a non-voided `LineItem`. `updateLineItemQuantity` is bounded above by `TransactionService.getMaxLineQuantity()` (default `TransactionService.DEFAULT_MAX_LINE_QUANTITY = 999`, error code `ABOVE_MAX_QUANTITY` on overflow). Passing zero is not a quantity change — it is a void.
- **Receipt** is not modeled as a class. It is a render of a `PAID` `Transaction`, produced by `ReceiptFormatter`.

## Where these live

All four nouns are shared vocabulary: they belong in `commons.model` (or their DTO counterparts in `commons.dto` when they cross the wire to the discount engine). `commons` depends on nothing else in the repo — a `Transaction` cannot import from `possystem` or `posdiscountengine`.
