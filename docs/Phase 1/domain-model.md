# Domain Model

The nouns of the POS. Names here are the vocabulary from CLAUDE.md — use them exactly in code (`LineItem`, not `CartRow`; `Transaction`, not `Sale`).

## Class diagram

```mermaid
classDiagram
    class Item {
        +String upc
        +String description
        +BigDecimal unitPrice
        +boolean taxable
    }

    class LineItem {
        +Item item
        +int quantity
        +BigDecimal extendedTotal()
        +boolean voided
    }

    class Transaction {
        +String transactionId
        +List~LineItem~ lineItems
        +List~Discount~ discounts
        +TransactionState state
        +TenderType tenderType
        +BigDecimal cashTendered
        +BigDecimal subtotal()
        +BigDecimal discountTotal()
        +BigDecimal taxTotal()
        +BigDecimal grandTotal()
        +BigDecimal changeDue()
        +addLineItem(Item, int)
        +voidLine(LineItem)
        +voidBasket()
        +total()
        +tender(TenderType, BigDecimal)
    }

    class TransactionState {
        <<enumeration>>
        IDLE
        IN_PROGRESS
        FINALIZED
        TENDERED
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
    [*] --> IDLE
    IDLE --> IN_PROGRESS: addLineItem
    IN_PROGRESS --> IN_PROGRESS: addLineItem / voidLine
    IN_PROGRESS --> IDLE: voidBasket
    IN_PROGRESS --> FINALIZED: total()
    FINALIZED --> TENDERED: tender(...)
    TENDERED --> [*]
```

`addLineItem`, `voidLine`, `voidBasket` are illegal in `FINALIZED` and `TENDERED`. `tender` is illegal in `IDLE` and `IN_PROGRESS`. The check lives on `Transaction` (or `TransactionService`), never solely on the button.

## Notes on the shape

- **`Item` vs `LineItem`.** `Item` is the product record from the Pricebook — immutable, one per UPC. `LineItem` is that product's appearance on a specific Transaction with a quantity; two scans of the same UPC produce either two `LineItem`s or one `LineItem` with `quantity = 2` (implementation choice — pick one and be consistent).
- **`Discount` is a value on a Transaction, not a rule.** The rule that produced it (BOGO, "10% off produce", etc.) lives in the discount engine's database (Phase 3). What the POS holds is the *result* of applying a rule: an amount and a description for the Receipt.
- **Money is `BigDecimal`.** Never `double`. Rounding happens once, at `grandTotal()`.
- **`Void`** is captured two ways: `voidBasket()` transitions the whole Transaction back to `IDLE` (or discards it and starts a new one); `voidLine` marks a `LineItem` as `voided` (soft-delete keeps the audit trail for the journal) or removes it outright.
- **Receipt** is not modeled as a class here — it's a render of a `TENDERED` `Transaction`. If a `Receipt` class emerges later, it's a projection, not a new source of truth.

## Where these live

All four nouns are shared vocabulary: they belong in `commons.model` (or their DTO counterparts in `commons.dto` when they cross the wire to the discount engine). `commons` depends on nothing else in the repo — a `Transaction` cannot import from `possystem` or `posdiscountengine`.
