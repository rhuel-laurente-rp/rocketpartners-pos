# Architecture

One repo, one `../../build.gradle`, one source tree — three intended runnable entry points. Only two are live today (`runPos`, `runJournal`); the discount engine is a scaffolded package with no `Application` class yet. Separation is by **package**, not by build module.

## Runtime — three processes, two network hops (one implemented)

```mermaid
flowchart LR
    subgraph POSHost["POS host (cashier's machine)"]
        POS["possystem.Application<br/>Swing client<br/>(only initiator)"]
    end

    subgraph JournalHost["Journal host"]
        JOURNAL["posvirtualjournal.Driver<br/>socket server :12345"]
    end

    subgraph CloudHost["AWS (target — not yet implemented)"]
        ENGINE["posdiscountengine.Application<br/>Spring Boot REST :8080"]
    end

    POS -- "TCP socket :12345<br/>UTF-8 newline-delimited<br/>fire-and-forget, one-way<br/>no ACK, off Swing EDT" --> JOURNAL
    POS -. "planned: HTTP POST /discounts/calculate<br/>on Total press" .-> ENGINE

    classDef initiator stroke-width:2px,stroke:#2b6cb0
    class POS initiator
    classDef planned stroke-dasharray: 4 3,color:#6E7379
    class ENGINE planned
```

**Direction of dependency.** The POS is the only process that initiates anything. The journal server and the (future) discount engine know nothing about each other and nothing about the POS.

**Both hops are optional at runtime — a hard requirement.** Journal writes are best-effort; discount calls (once implemented) time out and complete the sale with no discount. The POS must ring up sales with either or both peers down. `JournalCrossPhaseTest` is the anti-regression: it pins "Phase 1 stays green when the journal is unreachable."

**Journal fan-out.** The POS runs a composite `Journals(...)` wrapping three concrete `Journal` implementations, all three receiving every entry:

- **`LocalJournal`** — pipe-delimited to stdout; unconditional local record.
- **`FileJournal`** — one JSON object per line, appended to `<log-dir>/journal-YYYY-MM-DD.jsonl`, rolled at UTC midnight. `TailJournal` (and the `tailJournalLog` Gradle task) reads this file.
- **`RemoteJournal`** — ships to the socket server on a dedicated `remote-journal-sender` daemon thread.

**Journal hop specifics (Phase 2).**
- **One-way and unacknowledged.** The server never writes back.
- **UTF-8 newline-delimited.** Pipe-delimited fields; entries are capped at 4096 characters and truncated with `…TRUNCATED`. Embedded newlines are folded at the sender so one entry stays one line.
- **Off the Swing EDT.** Every `journal(record)` call does a non-blocking `queue.offer(...)` and returns; a single daemon sender thread reads the queue and writes the socket.
- **Reconnecting under back-pressure.** Connect timeout 2s. Backoff on failure: `1s → 2s → 4s → 8s → 16s → 30s` cap. The pending record is held across reconnects so ordering is preserved.
- **Full queue drops silently, then reports.** When the sender falls behind, `offer()` returns false and a counter increments; on recovery the sender emits one `JOURNAL_DROPPED n=…` record so the gap is visible.
- **Connection state.** Transitions (`JOURNAL_CONNECTED`, `JOURNAL_UNREACHABLE`, `JOURNAL_DISCONNECTED`) are recorded through `LocalJournal`, and observers (the header status pill) subscribe via `RemoteJournal.setConnectionListener`.

## Ring-up, end to end

```mermaid
sequenceDiagram
    autonumber
    participant POS as possystem<br/>(Swing)
    participant J as posvirtualjournal<br/>(socket :12345)
    participant E as posdiscountengine<br/>(HTTP :8080 — planned)

    Note over POS: scan / Quick Add
    POS-->>J: log "line item added"<br/>(offer, fan-out to 3 journals)
    Note over POS: (many scans...)
    Note over POS: cashier presses Total
    Note over POS,E: Discount call planned for Phase 3 —<br/>not implemented today; the sale<br/>completes with no discount applied.
    POS-->>J: log "totaled"
    Note over POS: cashier tenders + receipt prints
    POS-->>J: log "tendered"
    POS-->>J: log "receipt printed"
```

Only pricing crosses HTTP in the target architecture. Every user action produces a journal line; journal writes are fire-and-forget and never block checkout.

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
            PCMP["component<br/>(PosComponent,<br/>Journal impls, buffer)"]
            PEV["event<br/>(PosEvent, dispatcher, listener)"]
            PDIS["display<br/>(Swing views + controllers,<br/>PosTheme, PosDialog)"]
            PREPO["repository<br/>(H2ItemRepository — file mode;<br/>InMemoryItemRepository — tests)"]
            PSVC["service<br/>(TransactionService,<br/>TaxService,<br/>ReceiptFormatter)"]
            PTOOL["tools<br/>(TailJournal)"]
            PCON["constant"]
        end

        subgraph VJ["posvirtualjournal (Phase 2)"]
            VJALL["Driver + socket handler"]
        end

        subgraph DE["posdiscountengine (Phase 3 — scaffolded, empty)"]
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
    POSSYS -. HTTP (planned) .-> DE

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

## Component shape inside `possystem`

The POS keeps its socket and (future) HTTP integrations behind small in-repo classes rather than importing anything from the server packages:

- **`JournalListener`** — subscribes to every `PosEventType`, formats each event as a `JournalRecord`, and hands it to the composite `Journals(...)`. It is *the* journal integration point; there is no separate "JournalClient".
- **`Journals`** — a composite `Journal` wrapping `LocalJournal`, `FileJournal`, and `RemoteJournal`. Fans one record out to all three.
- **`RemoteJournal`** — owns the socket, queue, sender thread, backoff, and connection listener.

There is no `DiscountEngineClient` in the tree today; when Phase 3 lands it will sit under `possystem.service` and consume `commons.dto` types over HTTP.

## Pricebook storage

The pricebook is held in H2 in file mode, not in memory. `H2ItemRepository.open(dbDir, dbName, "/pricebook.tsv")` opens (or creates) `<dbDir>/<dbName>.mv.db`, ensures the `ITEMS` table exists, and — only when the table is empty — seeds it from the classpath TSV. Later runs skip the seed and read straight from the DB, so an operator can edit `ITEMS` in H2 and those edits survive restarts. The bundled `pricebook.tsv` becomes a first-run bootstrap, not the runtime source of truth.

- **Where the file lives.** `--db-dir` (default `data`) and `--db-name` (default `pricebook`) on `runPos`. The `data/` directory is git-ignored.
- **One connection, single-writer.** H2 file DBs are single-writer; the POS keeps one JDBC connection open for the process lifetime, closed on window close via `H2ItemRepository.close()`. Trying to launch a second POS against the same DB file fails loudly rather than silently sharing state.
- **`InMemoryItemRepository` is still in the tree** for tests and anywhere else that wants a pricebook without touching disk. Both implementations parse via the shared `PricebookTsv` helper.
- **The interface is impl-agnostic.** `TransactionService`, `PosComponent`, and every controller depend only on `ItemRepository`, so swapping impls does not ripple.

## Build → runnable entry points

```mermaid
flowchart LR
    SRC[/"src/main/java/com/rocketpartners/onboarding/**"/] --> BUILD["./gradlew build"]
    BUILD --> TPOS["gradle task<br/>runPos → JavaExec<br/>main = possystem.Application<br/>(works)"]
    BUILD --> TJRN["gradle task<br/>runJournal → JavaExec<br/>main = posvirtualjournal.Driver<br/>(works)"]
    BUILD --> TTAIL["gradle task<br/>tailJournalLog → JavaExec<br/>main = possystem.tools.TailJournal<br/>(works)"]
    BUILD --> TBR["gradle task<br/>bootRun → Spring Boot<br/>main = posdiscountengine.Application<br/>(fails: class does not exist)"]

    BUILD -. planned .-> JAR["build/libs/*.jar<br/>(bootJar main = discount engine)"]
    JAR -. planned .-> DOCKER["docker build<br/>--platform linux/amd64<br/>-t pos-discount-engine ."]
    DOCKER -. planned .-> ECR["ECR"] -. planned .-> ECS["ECS + ALB<br/>stable HTTP endpoint"]

    classDef planned stroke-dasharray: 4 3,color:#6E7379
    class JAR,DOCKER,ECR,ECS planned
```

Three run tasks (`runPos`, `runJournal`, `tailJournalLog`) plus `bootRun` distinguished by main class — that is how one project yields multiple entry points. `bootJar` builds a single fat jar and is intended, in the target architecture, to point at the discount engine so the container starts the API rather than opening a GUI. Today its main class does not exist, so `bootRun` fails at runtime — see `docs/known-issues.md` for the disposition.

## Cross-references

- Cashier's path through a sale: [user-flow.md](user-flow.md)
- Every `PosEvent`, end to end: [event-flow.md](event-flow.md)
- The nouns (`Item`, `LineItem`, `Transaction`, `Discount`): [domain-model.md](domain-model.md)
- Live code-level bugs and dispositions: [../known-issues.md](../known-issues.md)
