# CLAUDE.md

## What this is

Rocket Partners POS Onboarding Project — a mock Point-of-Sale system.

**One project.** One repo, one `build.gradle`, one `src/main/java`, one `src/test/java`. No Gradle subprojects, no per-module dependency blocks, no `settings.gradle` include list. Separation between areas of the system is by **package**, not by build module.

The work is divided into three phases, but the phases are a **task-sequencing device for the developer** — a way to build one system a piece at a time — not a structural division. Don't turn them into modules, source sets, or artifacts.

- **Phase 1**: the POS desktop client (Java Swing).
- **Phase 2**: a virtual journal server that receives and prints the POS's transaction logs over a socket.
- **Phase 3**: a discount engine REST API, containerized and deployed to AWS.

The finished product is a single codebase producing three runnable entry points that talk to each other, plus shared code they all use.

**`possystem` is worked on in all three phases.** Phase 2 adds journal sending to it; Phase 3 adds the discount-engine call. Phase 1 code is not frozen — extend it. But Phase 1 behavior and tests must stay green while you do.

This is a **learning project**, not production. When there's a tradeoff between a clever solution and one that clearly demonstrates the pattern being taught (event-driven design in Phase 1, sockets in Phase 2, REST and containers in Phase 3), pick the clear one.

## Repo layout

```
build.gradle              # the only build file
settings.gradle           # just rootProject.name
Dockerfile                # for the discount engine jar
src/main/java/com/rocketpartners/onboarding/
    commons/{model,dto,utils}
    possystem/{component,event,display,repository,service,constant}
    posvirtualjournal/
    posdiscountengine/{component,controller,entity,repository,service}
src/main/resources/       # pricebook.tsv, discounts.csv, application.properties
src/test/java/...         # mirrors the main package tree
docs/                     # diagrams, ERDs, mockups, onboarding briefs
```

Everything sits under `com.rocketpartners.onboarding`.

### Package discipline

With one project there's no build-enforced dependency direction, so it's on you to maintain:

- **`commons` depends on nothing else here.** Models, DTOs, utilities only. If something in `commons` imports from `possystem`, that's a bug.
- **`posvirtualjournal` and `posdiscountengine` never import from `possystem`.** They're servers; the POS calls them, not the reverse.
- **`possystem` may use `commons`, and talks to the other two only over the wire** — socket and HTTP — never by direct method call. Importing `posdiscountengine.service.DiscountService` into the POS would make the whole Phase 3 exercise meaningless.

An import that crosses these lines is the main thing to watch for in review.

`docs/` is not decoration — the brief calls for data-flow and user-flow diagrams *before* code. If you change transaction flow, event routing, or the discount request/response contract, update the matching diagram in the same change.

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

Three entry points, three processes at runtime, one codebase:

```
  posvirtualjournal.Driver        posdiscountengine.Application
      (socket, :12345)                    (HTTP, :8080)
              ▲                                 ▲
              │ log lines                       │ POST /discounts/calculate
              │ (fire-and-forget)               │ (request/response, times out)
              │                                 │
              └────  possystem.Application  ────┘
                        (Swing client)
```

The POS is the only process that initiates anything. The other two are servers and know nothing about each other or about the POS.

**Both network hops are optional at runtime.** The POS starts, rings up sales, and completes transactions with either or both peers down: journal writes are fire-and-forget, discount lookups time out and apply no discount. Hard requirement, not a nicety.

One sale, end to end: barcode or quick-add → `PosEvent` → `TransactionService` looks up the pricebook and adds a `LineItem` → journal line sent async → Total → discount engine called with the transaction DTO → discounts and tax applied → tender → receipt → journal line sent. Every step is journaled; only pricing crosses HTTP.

## Phase 1 — the POS client (`possystem`)

Starts here and keeps growing through Phases 2 and 3 — this is where it begins, not its final shape.

### Architecture (the actual lesson)

Phase 1's core lesson is event-driven separation of concerns. Swing views are dumb — render only, forward user actions. All business logic lives in `*ViewController` classes and services. `PosComponent` is the main driver, holding transaction state and the pricebook. Everything else talks to it through a typed `PosEvent` dispatched via `IPosEventDispatcher`/`IPosEventListener`; a class may implement both. If you're about to put logic inside a `*View` class, stop — it belongs in the controller or a service instead.

Keep Swing components lightweight. A new cross-component interaction means a new `PosEvent` type, not a direct reference between components.

### Required behavior

The GUI must support: Quick Add buttons, Void Line, Void Basket, Total, Pay Cash, Pay Next Dollar, Pay Debit/Credit, and barcode-scanner input against the pricebook. Change Quantity is optional but nice to have.

**Invariant:** once **Total** is pressed the basket is finalized and accepts no further input — no adds, no voids, no quantity edits. Only tender actions are legal from that state. Enforce this in `PosComponent`/the service layer, not by disabling buttons alone.

Every action the POS takes is logged to the virtual journal (Phase 2). Adding a user action means adding its journal entry.

Pricebook persistence via a file-backed H2 database is optional; an in-memory repository behind the same interface is fine.

## Phase 2 — the virtual journal (`posvirtualjournal`)

A server that receives log lines from the POS over `java.net.Socket` and prints them. Two halves: the server (its own entry point, `Driver`) and the client code inside `possystem` that sends the logs.

Socket error handling is the graded part. The POS must not hang, crash, or lose a transaction because the journal is down, slow, or drops mid-transaction. Journal sending is best-effort, runs off the Swing event dispatch thread, and never blocks checkout.

## Phase 3 — the discount engine (`posdiscountengine`)

Spring Boot REST API taking a transaction and returning discounts to apply. Rules live in the database (Spring Data JPA + H2) rather than hard-coded — adding a rule should be data, not a code change.

Because Spring Boot component-scans downward from the `@SpringBootApplication` class's package, keeping the app class in `posdiscountengine` means it will never try to scan the Swing code. Don't move it up to `com.rocketpartners.onboarding`.

### Container

The `Dockerfile` sits at the repo root, built on `eclipse-temurin:17-jdk-jammy`, working dir `/app`, jar copied from `build/libs/`, exposing **8080**.

```bash
./gradlew bootJar
docker build --platform linux/amd64 -t pos-discount-engine .
docker run -d -p 8080:8080 pos-discount-engine
```

Keep `--platform linux/amd64` when building on Apple silicon for AWS — an arm64 image runs fine locally and then fails to start on an x86 task.

Since it's one project, `bootJar` produces a single fat jar containing the Swing and journal code too. That's accepted here: the image is fatter than it needs to be, and for an onboarding project the simpler build is worth more than a lean image. Just make sure `bootJar`'s main class points at the discount engine, so the container starts the API rather than trying to open a GUI.

### Deployment

Image → **ECR**, run on **ECS**, fronted by an **Application Load Balancer** giving the POS a stable HTTP endpoint. The POS must degrade gracefully when the engine is unreachable: complete the sale with no discount rather than blocking it.

## Stack

Java 17, Gradle (Groovy DSL), one dependency block for everything: Swing + FlatLaf + JCommander (POS), Spring Boot 3.3.x + Spring Data JPA + H2 (discount engine), Apache HttpClient5 and Jackson (POS → engine), Lombok. Tests: JUnit 5, Mockito, Awaitility for socket/async code.

## Build & run

```bash
./gradlew build              # compiles everything, runs all tests via `check`
./gradlew runPos             # Swing POS client
./gradlew runJournal         # virtual journal server
./gradlew bootRun            # discount engine API
```

The three run tasks are `JavaExec` tasks (plus `bootRun`) distinguished by main class — that's how one project yields three entry points. Don't add subprojects to solve this.

Run `./gradlew build` before considering any change finished.

**Cross-phase regression rule:** work on a later phase must not break an earlier one. If a Phase 3 change turns a Phase 1 test red, the change is wrong until proven otherwise — don't edit the test to match the new behavior without flagging it explicitly.

To exercise the whole system, run all three tasks in separate terminals and ring up a real sale. The POS takes CLI args for journal host/port and discount-engine base URL, so pointing it at a deployed engine is an argument change, not a code change.

## Working on this repo

Do all work on a feature branch off `main`, never directly on `main`. **Do not run `git commit` or `git push`** — leave committing to the user; your job ends at working, tested code and a clean `git diff`/`git status` for them to review.

## Reference implementations

Prior junior-dev versions, useful for comparison — not for copying wholesale, and not authoritative over this repo's conventions:

- https://github.com/JohnLavender474/RocketPartners-PosOnboardingProject-DesktopClient
- https://github.com/wesHawkeyeMaszk/PoS
