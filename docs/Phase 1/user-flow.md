# User Flow — Cashier's Path Through a Sale

The cashier drives one Transaction from empty basket to Receipt. Pressing **Total** is the hinge: before Total the basket is mutable; after Total it is finalized and only tender actions are legal. `PosComponent` / `TransactionService` enforce this — disabling buttons in the UI is a nicety, not the guarantee.

## States and legal actions

```mermaid
stateDiagram-v2
    [*] --> Idle

    Idle --> InProgress: scan UPC / Quick Add
    InProgress --> InProgress: scan UPC / Quick Add (add Line Item)
    InProgress --> InProgress: Void Line
    InProgress --> InProgress: Change Quantity (optional)
    InProgress --> Idle: Void Basket
    InProgress --> Finalized: Total

    Finalized --> Tendered: Pay Cash
    Finalized --> Tendered: Pay Next Dollar
    Finalized --> Tendered: Pay Debit/Credit

    Tendered --> [*]: print Receipt

    note right of InProgress
        Basket is mutable.
        Legal: add Line Item,
        Void Line, Void Basket,
        Change Quantity, Total.
        Illegal: any tender.
    end note

    note right of Finalized
        Basket is frozen.
        Legal: Pay Cash,
        Pay Next Dollar,
        Pay Debit/Credit.
        Illegal: add Line Item,
        Void Line, Void Basket,
        Change Quantity.
    end note
```

## Happy-path walkthrough

```mermaid
flowchart TD
    Start([Cashier begins Transaction]) --> Scan{scan UPC or Quick Add?}
    Scan -->|UPC read| Lookup[Pricebook lookup by UPC]
    Scan -->|Quick Add button| Lookup
    Lookup --> Add[add Line Item to Transaction]
    Add --> More{more items?}
    More -->|yes| Scan
    More -->|Void Line| VL[remove one Line Item]
    VL --> More
    More -->|Void Basket| VB[discard Transaction] --> Start
    More -->|Total| Total[finalize Transaction<br/>discounts + tax applied]
    Total --> Tender{choose Tender Type}
    Tender -->|Pay Cash| Cash[enter cash tendered]
    Tender -->|Pay Next Dollar| Next[cash = ceil of total]
    Tender -->|Pay Debit/Credit| Card[card payment]
    Cash --> Receipt[print Receipt]
    Next --> Receipt
    Card --> Receipt
    Receipt --> End([Transaction complete])
```

## The invariant, restated

Before **Total**: any of `scan UPC`, `Quick Add`, `Void Line`, `Void Basket`, `Change Quantity` is legal; no tender is legal.

After **Total**: any of `Pay Cash`, `Pay Next Dollar`, `Pay Debit/Credit` is legal; no basket mutation is legal.

Every action in either state produces a journal entry (see [event-flow.md](event-flow.md) and Phase 2).
