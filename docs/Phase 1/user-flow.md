# User Flow — Cashier's Path Through a Sale

The cashier drives one Transaction from empty basket to Receipt. Pressing **Total** is the hinge: before Total the basket is mutable; after Total it is finalized and only tender actions plus Void Basket are legal. `Transaction` and `TransactionService` enforce this — disabling buttons in the UI is a nicety, not the guarantee.

The scanner has both a burst-detection path (a real scanner types keystrokes application-wide) and a manual submit path (typing digits into the scan field and hitting Enter). That distinction is below the level a cashier-facing flow diagram should carry — the states below say "scan UPC" and mean both. See [event-flow.md](event-flow.md) for the mechanics.

## States and legal actions

```mermaid
stateDiagram-v2
    [*] --> IN_PROGRESS

    IN_PROGRESS --> IN_PROGRESS: scan UPC / Quick Add (add Line Item)
    IN_PROGRESS --> IN_PROGRESS: Void Line
    IN_PROGRESS --> IN_PROGRESS: Change Quantity
    IN_PROGRESS --> IN_PROGRESS: Void Basket → Keep basket
    IN_PROGRESS --> VOIDED: Void Basket → Void basket
    IN_PROGRESS --> TOTALED: Total

    TOTALED --> TOTALED: applyDiscount
    TOTALED --> TOTALED: Void Basket → Keep basket
    TOTALED --> VOIDED: Void Basket → Void basket
    TOTALED --> PAID: Pay Cash (Exact or Next Dollar) / Pay Debit / Pay Credit

    PAID --> [*]: Receipt → Start Next Sale
    VOIDED --> [*]

    note right of IN_PROGRESS
        Basket is mutable.
        Legal: add Line Item,
        Void Line, Void Basket,
        Change Quantity, Total.
        Illegal: any tender.
    end note

    note right of TOTALED
        Basket is frozen.
        Legal: Pay Cash, Pay Debit,
        Pay Credit, Void Basket.
        Illegal: add Line Item,
        Void Line, Change Quantity.
    end note
```

## Happy-path walkthrough

```mermaid
flowchart TD
    Start([Cashier begins Transaction]) --> Scan{scan UPC or Quick Add?}
    Scan -->|UPC read| Lookup[Pricebook lookup by UPC]
    Scan -->|Quick Add tile| Lookup
    Lookup --> Add[add Line Item to Transaction]
    Add --> More{next action?}
    More -->|more items| Scan
    More -->|Void Line| VL[remove one Line Item] --> More
    More -->|Change Quantity| CQ[open modal spinner<br/>ChangeQuantityView] --> CQchoose{Confirm Change<br/>or Cancel?}
    CQchoose -->|Cancel / ESC| More
    CQchoose -->|Confirm Change| CQdone[LineItem quantity updated] --> More
    More -->|Void Basket| VBprompt[open confirm modal<br/>VoidBasketConfirmView]
    VBprompt -->|Keep basket / ESC| More
    VBprompt -->|Void basket| VB[discard Transaction] --> Start
    More -->|Total| Total[finalize Transaction<br/>discounts + tax applied]
    Total --> Tchoose{choose tender}
    Tchoose -->|Void Basket| VBprompt
    Tchoose -->|Pay Cash| CashMode[open CashModeChoiceView]
    Tchoose -->|Pay Debit / Pay Credit| Card[open PayWithCardView<br/>simulated approval]
    CashMode --> CashChoose{Exact Amount or<br/>Next Dollar?}
    CashChoose -->|Exact Amount| CashEnter[open PayWithCashView<br/>pre-filled with grand total]
    CashChoose -->|Next Dollar| CashEnter2[open PayWithCashView<br/>pre-filled with ceil grand total]
    CashChoose -->|Cancel| Tchoose
    CashEnter --> CashConfirm{Confirm Payment<br/>or Cancel?}
    CashEnter2 --> CashConfirm
    CashConfirm -->|Cancel| Tchoose
    CashConfirm -->|Confirm Payment| Receipt
    Card --> Receipt[open ReceiptView modal]
    Receipt --> RcpDismiss{Start Next Sale}
    RcpDismiss --> Start
```

## The invariant, restated

Before **Total**: any of `scan UPC`, `Quick Add`, `Void Line`, `Change Quantity`, `Void Basket` is legal; no tender is legal.

After **Total**: any of `Pay Cash`, `Pay Debit`, `Pay Credit`, `Void Basket` is legal; no basket mutation is legal.

Void Basket is legal in both states. Post-Total voids are the more interesting case operationally — captured on the `BASKET_VOIDED` event as `priorState`. A `VOID_BASKET_DECLINED` (Keep basket / ESC / window close) is journalled too, as the shrink-review signal.

Every action in either state produces a journal entry (see [event-flow.md](event-flow.md) and Phase 2).
