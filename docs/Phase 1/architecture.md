# Architecture

One repo, one `../../build.gradle`, one source tree — three runnable entry points that talk to each other at runtime. Separation is by **package**, not by build module.

## Runtime — three processes, two network hops

```mermaid
flowchart LR
    subgraph POSHost["POS host (cashier's machine)"]
        POS["possystem.Application<br/>Swing client<br/>(only initiator)"]
    end

    subgraph JournalHost["Journal host"]
        JOURNAL["posvirtualjournal.Driver<br/>socket server :12345"]
    end

    subgraph CloudHost["AWS (ECS behind ALB)"]
        ENGINE["posdiscountengine.Application<br/>Spring Boot REST :8080"]
    end

    POS -- "TCP socket :12345<br/>UTF-8 newline-delimited<br/>fire-and-forget, one-way<br/>no ACK, off Swing EDT" --> JOURNAL
    POS -- "HTTP POST /discounts/calculate<br/>request/response, times out<br/>on Total press" --> ENGINE

    classDef initiator stroke-width:2px,stroke:#2b6cb0
    class POS initiator
```

**Direction of dependency.** The POS is the only process that initiates anything. The journal server and the discount engine know nothing about each other and nothing about the POS.

**Both hops are optional at runtime.** Journal sends fail silently. Discount calls time out and the sale completes with no discount. This is a hard requirement, not a nicety — the POS must ring up sales with either or both peers down.

**Journal hop specifics (Phase 2).** The POS → journal socket is:
- **One-way and unacknowledged.** The server never writes back; the client never expects to hear anything. Delivery is best-effort. No ACK protocol.
- **UTF-8 newline-delimited.** Pipe-delimited fields; entries are capped at 4096 characters and truncated with a marker. Descriptions with embedded newlines are sanitized at the client so one entry stays one line.
- **Off the Swing EDT.** All work happens on a single daemon `remote-journal-sender` thread. Journal calls from the EDT do a non-blocking `offer()` into a bounded queue and return immediately.
- **Reconnecting under back-pressure.** Connect timeout 2s. On any write failure the sender backs off (`1s → 2s → 4s → 8s → 16s → 30s` cap) rather than reconnecting per entry. Dropped-while-full entries are counted, and one `JOURNAL_DROPPED n=…` line is emitted on recovery so the gap is visible.
- **Unconditional local copy.** Every entry is also written to a `LocalJournal` (stdout by default), so there is always a record even when the remote journal has never been reachable.

## Ring-up, end to end

```mermaid
sequenceDiagram
    autonumber
    participant POS as possystem<br/>(Swing)
    participant J as posvirtualjournal<br/>(socket :12345)
    participant E as posdiscountengine<br/>(HTTP :8080)

    Note over POS: scan / Quick Add
    POS-->>J: log "line item added" (async, fire-and-forget)
    Note over POS: (many scans...)
    Note over POS: cashier presses Total
    POS->>E: POST /discounts/calculate<br/>{TransactionDTO}
    alt engine reachable
        E-->>POS: 200 OK {discounts[]}
    else timeout or 5xx
        E--xPOS: (no response in budget)
        Note over POS: apply no discount,<br/>continue the sale
    end
    POS-->>J: log "totaled"
    Note over POS: cashier tenders + receipt prints
    POS-->>J: log "tendered"
    POS-->>J: log "receipt printed"
```

Only pricing crosses HTTP. Every user action produces a journal line; journal sends are fire-and-forget and never block checkout.

## Package boundaries (single project)

```mermaid
flowchart TB
    subgraph Repo["com.rocketpartners.onboarding"]
        subgraph COMMONS["commons"]
            CM["model"]
            CD["dto"]
            CU["utils"]
        end

        subgraph POSSYS["possystem (Phase 1 → grows in 2 &amp; 3)"]
            PCMP["component<br/>(PosComponent)"]
            PEV["event<br/>(PosEvent, dispatcher, listener)"]
            PDIS["display<br/>(Swing views + controllers)"]
            PREPO["repository<br/>(Pricebook)"]
            PSVC["service<br/>(TransactionService,<br/>JournalClient,<br/>DiscountEngineClient)"]
            PCON["constant"]
        end

        subgraph VJ["posvirtualjournal (Phase 2)"]
            VJALL["Driver + socket handler"]
        end

        subgraph DE["posdiscountengine (Phase 3)"]
            DECMP["component"]
            DECTL["controller"]
            DEENT["entity"]
            DEREPO["repository"]
            DESVC["service"]
        end
    end

    POSSYS -- OK --> COMMONS
    VJ -- OK --> COMMONS
    DE -- OK --> COMMONS

    POSSYS -. socket .-> VJ
    POSSYS -. HTTP .-> DE

    COMMONS -.->|MUST NOT| POSSYS
    VJ -.->|MUST NOT| POSSYS
    DE -.->|MUST NOT| POSSYS
    POSSYS -.->|MUST NOT<br/>import types| DE

    classDef forbidden stroke:#c53030,stroke-dasharray: 4 3,color:#c53030
    linkStyle 5,6,7,8 stroke:#c53030,stroke-dasharray: 4 3
```

**The rules that matter in review** (nothing in the build enforces these — an import that crosses a red line is the main thing to look for):

- `commons` depends on nothing else here. Models, DTOs, utilities only.
- `posvirtualjournal` and `posdiscountengine` never import from `possystem`. They are servers; the POS calls them.
- `possystem` uses `commons`, and talks to the other two **only over the wire**. Importing `posdiscountengine.service.DiscountService` into the POS defeats the whole Phase 3 exercise; the POS communicates with a DTO over HTTP.

## Build → three entry points

```mermaid
flowchart LR
    SRC[/"src/main/java/com/rocketpartners/onboarding/**"/] --> BUILD["./gradlew build"]
    BUILD --> JAR["build/libs/*.jar<br/>(bootJar main = discount engine)"]

    BUILD --> TPOS["gradle task<br/>runPos → JavaExec<br/>main = possystem.Application"]
    BUILD --> TJRN["gradle task<br/>runJournal → JavaExec<br/>main = posvirtualjournal.Driver"]
    BUILD --> TBR["gradle task<br/>bootRun → Spring Boot<br/>main = posdiscountengine.Application"]

    JAR --> DOCKER["docker build<br/>--platform linux/amd64<br/>-t pos-discount-engine ."]
    DOCKER --> ECR["ECR"] --> ECS["ECS + ALB<br/>stable HTTP endpoint"]
    ECS -. HTTP .-> TPOS
```

Three run tasks distinguished by main class — that's how one project yields three entry points. `bootJar` produces a single fat jar with all three sets of code; its main class points at the discount engine so the container starts the API rather than trying to open a Swing window. `--platform linux/amd64` is required when building on Apple silicon for AWS.

## Cross-references

- Cashier's path through a sale: [user-flow.md](user-flow.md)
- One scan's journey through the event system: [event-flow.md](event-flow.md)
- The nouns (`Item`, `LineItem`, `Transaction`, `Discount`): [domain-model.md](domain-model.md)
