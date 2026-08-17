# rocketpartners-pos

Onboarding project for Rocket Partners: a Java 17 point-of-sale system with a Swing
desktop client, a socket-based journal server, and a Spring Boot discount engine, all
in one Gradle project. See `CLAUDE.md` for the full project brief and `docs/Phase 1/`
for architecture, event flow, and domain model.

Three processes, one codebase — the POS is the only initiator:

| Phase | Process | Package | Entry point |
| --- | --- | --- | --- |
| 1 | Swing POS desktop client | `possystem` | `./gradlew runPos` |
| 2 | Virtual journal socket server (`:12345`) | `posvirtualjournal` | `./gradlew runJournal` |
| 3 | Discount engine REST API (`:8080`) — containerized, deployed to AWS ECS behind an ALB | `posdiscountengine` | `./gradlew bootRun` |

The POS talks to the journal over a socket and to the discount engine over HTTP; both
hops are optional at runtime, so the POS rings up sales even with either peer down.

For every run/test/deploy command in one place — including `curl` and Postman cases
against both `localhost` and the deployed engine — see
[`docs/run-and-test-commands.md`](docs/run-and-test-commands.md).

## Build

```bash
./gradlew build
```

## Run

```bash
./gradlew runPos       # Swing POS client
./gradlew runJournal   # virtual journal server (Phase 2)
./gradlew bootRun      # discount engine REST API (Phase 3)
```

## Running with Docker

The discount engine (Phase 3) ships as a container. The image is built from the fat `bootJar`,
so **build the jar first** — the `Dockerfile` copies it from `build/libs`, it does not run Gradle:

```bash
./gradlew bootJar
docker build --platform linux/amd64 -t pos-discount-engine .
docker run -d -p 8080:8080 pos-discount-engine
```

Verify it's up and calculating discounts (the second call rings up 3× a buy-2-get-1 item, so the
engine returns one unit free):

```bash
curl -i localhost:8080/health

curl -X POST localhost:8080/discounts/calculate \
  -H "Content-Type: application/json" \
  -d '{
        "transactionId": "T-1",
        "lineItems": [
          {"upc": "070847811169", "description": "Monster Energy", "quantity": 3, "unitPrice": 2.50}
        ],
        "subtotal": 7.50,
        "appliedEligibilityCodes": []
      }'
```

Or run it with Compose (same image, one command — still build the jar first):

```bash
./gradlew bootJar
docker compose up --build
```

### Why `--platform linux/amd64`

On Apple silicon, Docker defaults to building an `arm64` image. That image builds and runs fine
locally, then fails on an x86 (Fargate) task with `exec format error` — and the mismatch is silent
until the task won't start. Pinning `--platform linux/amd64` (also set in `compose.yaml`) builds the
x86 image the deploy target expects. The alternative is equally valid: build a native `arm64` image
and set the ECS task definition's runtime platform to `ARM64`. Either works; just don't mix them.

### Why the image is larger than a lean engine build

This is a single Gradle project, so `bootJar` produces one fat jar containing **all** the code —
the Swing POS client and the journal server as well as the discount engine. The container therefore
carries code it never runs. That's accepted for this onboarding project: the simpler single-artifact
build is worth more here than a stripped-down, engine-only image. The container starts the API and
not a GUI because `bootJar`'s main class is pinned to the discount engine's `Application`
(see `build.gradle`); nothing in the image attempts to open a window.

## Deploy to AWS

The discount engine deploys to ECS Fargate behind an Application Load Balancer, driven
by the idempotent `deploy-ecs.sh` (ECR → ECS + ALB). After building and pushing the
image:

```bash
./gradlew clean bootJar
docker buildx build --platform linux/amd64 \
  -t <account>.dkr.ecr.us-east-1.amazonaws.com/pos-discount-engine:latest --load .
aws ecr get-login-password --region us-east-1 \
  | docker login --username AWS --password-stdin <account>.dkr.ecr.us-east-1.amazonaws.com
docker push <account>.dkr.ecr.us-east-1.amazonaws.com/pos-discount-engine:latest

./deploy-ecs.sh deploy      # create everything, wait for a healthy target, print the endpoint
./deploy-ecs.sh status      # diagnostics: target health, ECS events, recent logs
./deploy-ecs.sh redeploy    # after pushing a new :latest image
./deploy-ecs.sh teardown    # delete the service, ALB, target group, cluster
```

Then point the POS at the deployed engine:

```bash
./gradlew runPos --args="--discount-engine-url http://<alb-dns-name>"
```

The current ALB DNS name lives in `deploy-state.env` (`ALB_DNS`). Full command reference,
including the discount-engine REST API and Postman setup, is in
[`docs/run-and-test-commands.md`](docs/run-and-test-commands.md).

## POS CLI arguments

Pass CLI args via Gradle's `--args="..."`:

```bash
./gradlew runPos --args="--store-name 'Downtown' --lane-number 3 --debug"
./gradlew runPos --args="--help"
```

| Flag | Default | Description |
| --- | --- | --- |
| `--debug` | `false` | Enable verbose event tracing to stderr. |
| `--app-mode` | `NORMAL` | Application mode (`NORMAL`; `TRAINING` reserved for later). |
| `--store-name` | `Rocket Store` | Store label shown on the window and receipts. |
| `--lane-number` | `1` | Terminal / lane number for this POS. |
| `--journal-host` | `localhost` | Virtual journal hostname (Phase 2). |
| `--journal-port` | `12345` | Virtual journal TCP port (Phase 2). |
| `--discount-engine-url` | `http://localhost:8080` | Discount engine base URL (Phase 3). Point at a deployed ALB to use the AWS engine; an unreachable engine degrades to "no discount," never blocking a sale. |
| `--scan-burst-gap-ms` | `50` | Max inter-character gap (ms) inside a scanner burst; input arriving beyond this gap is treated as human typing. |
| `--log-dir` | `logs` | Directory to write on-disk JSONL journal files into. Each run appends to `journal-YYYY-MM-DD.jsonl`. |
| `--help` / `-h` | — | Print usage and exit. |

The virtual journal server (`runJournal`) accepts only `--port` (default `12345`) and `--help`.

## Documentation

| Doc | What it covers |
| --- | --- |
| [`CLAUDE.md`](CLAUDE.md) | Full project brief, conventions, invariants, and package discipline. |
| [`docs/run-and-test-commands.md`](docs/run-and-test-commands.md) | Every run/build/deploy command, the discount-engine REST API, and `curl`/Postman test cases (local + AWS). |
| [`docs/architecture-summary.md`](docs/architecture-summary.md) | How `possystem` is organized by package and how data flows. |
| [`docs/Phase 1/architecture.md`](docs/Phase%201/architecture.md) | The three-process runtime, package boundaries, and build → entry-point diagrams. |
| [`docs/Phase 1/event-flow.md`](docs/Phase%201/event-flow.md) | Every `PosEvent`, end to end. |
| [`docs/Phase 1/domain-model.md`](docs/Phase%201/domain-model.md) | The domain nouns and their invariants. |
| [`docs/Phase 1/user-flow.md`](docs/Phase%201/user-flow.md) | The cashier's path through a sale. |
| [`docs/swing-notes.md`](docs/swing-notes.md) | Swing footguns and dispositions. |
| [`docs/known-issues.md`](docs/known-issues.md) | Live bugs, dead code, and convention drift teed up for follow-up. |
