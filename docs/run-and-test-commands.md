# Run & Test Commands

A working cheat-sheet for running the three POS processes locally, building and
deploying the discount engine to AWS, and exercising the discount-engine REST API
(both against `localhost` and the deployed ALB) with `curl` and Postman.

Every command below has a one-line description of what it does and notes on the
parameters that matter.

---

## 0. Quick reference

```
AWS endpoint : http://pos-alb-253412522.us-east-1.elb.amazonaws.com
Local endpoint : http://localhost:8080

Health check (AWS) : curl -i http://pos-alb-253412522.us-east-1.elb.amazonaws.com/health
Health check (local): curl -i http://localhost:8080/health

Point POS at AWS   : ./gradlew runPos --args="--discount-engine-url http://pos-alb-253412522.us-east-1.elb.amazonaws.com"
Point POS at local : ./gradlew runPos --args="--discount-engine-url http://localhost:8080"
```

> The ALB DNS name comes from `deploy-state.env` (`ALB_DNS`). If you tear down and
> re-create the load balancer, that name changes — re-read `deploy-state.env` and
> swap it everywhere below.

---

## 1. Local run commands (Gradle tasks)

One project, one `build.gradle`; the entry points are distinguished by main class.
Pass POS/journal CLI flags with `--args="…"`.

| Command | What it does |
| --- | --- |
| `./gradlew build` | Compiles everything and runs the full test suite. **Run before considering any change finished.** |
| `./gradlew runPos` | Launches the Swing POS desktop client. |
| `./gradlew runJournal` | Starts the virtual journal socket server on `:12345`. |
| `./gradlew tailJournalLog` | Tails `logs/journal-YYYY-MM-DD.jsonl` live. |
| `./gradlew bootRun` | Runs the Phase 3 discount engine (Spring Boot) on `:8080`. |
| `./gradlew bootJar` | Builds the one fat jar (`build/libs/*.jar`) the Docker image ships. |

### `./gradlew runPos` — key flags

Pass via `--args="--flag value …"`. Full list is in `CLAUDE.md`; the ones that matter
for discount-engine work:

| Flag | Default | Meaning |
| --- | --- | --- |
| `--discount-engine-url` | `http://localhost:8080` | Base URL the POS calls for discounts. Point at the ALB to use the deployed engine. |
| `--journal-host` | `localhost` | Journal socket hostname. |
| `--journal-port` | `12345` | Journal socket port. |
| `--store-name` | `Rocket Store` | Label on window/receipts. |
| `--lane-number` | `1` | Terminal/lane number. |
| `--debug` | `false` | Verbose event tracing to stderr; arms the F12 demo-scan hotkey. |
| `--db-dir` / `--db-name` | `data` / `pricebook` | H2 pricebook DB location. |

Examples:

```bash
# POS against the deployed engine
./gradlew runPos --args="--discount-engine-url http://pos-alb-253412522.us-east-1.elb.amazonaws.com"

# POS against a locally-running engine, with debug tracing
./gradlew runPos --args="--discount-engine-url http://localhost:8080 --debug"
```

### `./gradlew bootRun` — run the engine locally

```bash
./gradlew bootRun
```

Starts Spring Boot on `:8080` with an in-memory H2 database that `CsvDiscountsLoader`
re-seeds from `src/main/resources/discounts.csv` on **every** boot (disposable, fresh
each run). Config lives in `src/main/resources/application.properties`
(`server.port=8080`, `jdbc:h2:mem:discounts`).

To run the whole system end-to-end, use **three terminals**: `runJournal`, `bootRun`,
and `runPos` pointed at `http://localhost:8080`.

---

## 2. Build, containerize & deploy to AWS

The deploy is driven by `deploy-ecs.sh` (ECR → ECS Fargate behind an ALB). It's
idempotent — every step checks for an existing resource first.

### 2a. Build the jar and image

```bash
# 1. Build the fat jar the image copies in
./gradlew clean bootJar          # produces build/libs/*.jar

# 2. Build a linux/amd64 image (ARCH in deploy-ecs.sh is X86_64 — match it)
docker buildx build --platform linux/amd64 \
  -t 115207986421.dkr.ecr.us-east-1.amazonaws.com/pos-discount-engine:latest \
  --load .
```

`Dockerfile` (repo root): `eclipse-temurin:17-jdk-jammy`, copies `build/libs/*.jar`
to `/app/app.jar`, exposes `8080`, `java -jar`.

> **Architecture matters.** The ECS task definition pins `X86_64`. On an Apple-silicon
> Mac, `docker build` defaults to arm64 and the task will fail to start — always pass
> `--platform linux/amd64`. `deploy-ecs.sh` warns if it detects an arm64 image in ECR.

### 2b. Push to ECR

```bash
# Log Docker into ECR (token valid ~12h)
aws ecr get-login-password --region us-east-1 \
  | docker login --username AWS --password-stdin \
      115207986421.dkr.ecr.us-east-1.amazonaws.com

# Push :latest
docker push 115207986421.dkr.ecr.us-east-1.amazonaws.com/pos-discount-engine:latest
```

### 2c. Deploy / manage the ECS service

`./deploy-ecs.sh <command>` — commands:

| Command | What it does |
| --- | --- |
| `./deploy-ecs.sh deploy` | Create everything: VPC/subnet discovery, security groups, IAM exec role, log group, task def, cluster, target group, ALB, service — then waits for a healthy target and prints the endpoint. |
| `./deploy-ecs.sh status` | Diagnostics: service desired/running counts, target health, recent ECS events, last stop reason, and the last 10 min of app logs. |
| `./deploy-ecs.sh redeploy` | Force a new deployment after pushing a new `:latest` (the tag is unchanged, so ECS must be told). Waits for `services-stable`, then curls `/health`. |
| `./deploy-ecs.sh teardown` | Delete service, listeners, ALB, target group, cluster, log group, security groups (prompts first). Keeps the ECR repo. |

Typical redeploy loop after a code change:

```bash
./gradlew clean bootJar
docker buildx build --platform linux/amd64 -t <ECR_URI>:latest --load .
docker push <ECR_URI>:latest
./deploy-ecs.sh redeploy
```

Key fixed values (from `deploy-ecs.sh` / `deploy-state.env`):

```
ACCOUNT=115207986421   REGION=us-east-1   REPO=pos-discount-engine
CLUSTER=pos-cluster    SERVICE=pos-engine-svc   FAMILY=pos-discount-engine
CONTAINER=discount-engine   PORT=8080   HEALTH_PATH=/health
ALB_DNS=pos-alb-253412522.us-east-1.elb.amazonaws.com
```

---

## 3. Discount engine REST API reference

Base URL: `http://localhost:8080` locally, or the ALB DNS on AWS.

### Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Liveness for the ALB. `200 {"status":"UP"}`. |
| `GET` | `/discounts/rules?category=ELIGIBILITY\|PROMOTIONAL` | List active rules of a category, in application (priority) order. Read-only; no calculation. |
| `POST` | `/discounts/calculate` | Given a transaction, return the discounts to apply and their total. |

### Request body — `POST /discounts/calculate` (`TransactionDto`)

```json
{
  "transactionId": "txn-001",
  "createdAt": "2026-08-17T15:00:00Z",
  "lineItems": [
    { "upc": "070847811169", "description": "MONSTER ENERGY", "quantity": 3, "unitPrice": 3.29 }
  ],
  "subtotal": 9.87,
  "appliedEligibilityCodes": ["SENIOR_20"]
}
```

Field notes:
- `lineItems[].upc` — the **pricebook key**, not a raw barcode. Rule `targetValue`s
  must be expressed as pricebook keys too, or they never match.
- `subtotal` — informational; the engine recomputes the net from `unitPrice × quantity`.
- `appliedEligibilityCodes` — cashier-selected `ELIGIBILITY` codes only (e.g. `SENIOR_20`,
  `VETERAN_15`, `EMPLOYEE_5`). Promotional/BOGO rules are applied **automatically** when
  the basket qualifies — never list them here. Null/empty = no eligibility discounts.

### Response body (`DiscountResponseDto`)

```json
{
  "discounts": [
    { "discountId": "BOGO_MONSTER", "description": "Buy 2 Get 1 Monster",
      "type": "PROMO", "amount": 0, "appliedAmount": 3.29 },
    { "discountId": "SENIOR_20", "description": "Senior Disc 20%",
      "type": "PERCENT_OFF", "amount": 20, "appliedAmount": 1.32 }
  ],
  "discountTotal": 4.61
}
```

- Rows are in ascending-priority application order (promotions at priority 1 before
  eligibility at priority 2). Each `appliedAmount` is computed against the **running net**,
  not the original subtotal.
- `discountTotal` = sum of every `appliedAmount`, scaled to 2dp, never exceeds subtotal.

### Errors

- `400 {"error": "..."}` — validation failure: unknown/inactive eligibility code,
  conflicting codes in one exclusivity group, blank UPC, negative quantity/price, null
  price. Raised as `DiscountValidationException`, mapped by `DiscountEngineExceptionHandler`.
- `400` — malformed JSON (Spring's default message-conversion handling).

---

## 4. curl test cases

Set a base-URL variable once, then reuse. Swap it to switch local ↔ AWS.

```bash
# Pick ONE:
BASE=http://localhost:8080
BASE=http://pos-alb-253412522.us-east-1.elb.amazonaws.com
```

### 4.1 Health (both environments)

```bash
curl -i "$BASE/health"
# -> HTTP/1.1 200 ; body {"status":"UP"}
```

### 4.2 List eligibility rules

```bash
curl -s "$BASE/discounts/rules?category=ELIGIBILITY" | jq
# -> SENIOR_20, VETERAN_15, EMPLOYEE_5 (all share group CUSTOMER_ELIGIBILITY)
```

### 4.3 List promotional rules

```bash
curl -s "$BASE/discounts/rules?category=PROMOTIONAL" | jq
# -> the active BOGO / "buy N save" rules from discounts.csv
```

### 4.4 BOGO only — Monster "Buy 2 Get 1"

UPC `070847811169` @ `3.29`, qty 3 → one free unit → `3.29` off.

```bash
curl -s -X POST "$BASE/discounts/calculate" \
  -H 'Content-Type: application/json' \
  -d '{
    "transactionId": "txn-bogo",
    "lineItems": [
      { "upc": "070847811169", "description": "MONSTER ENERGY", "quantity": 3, "unitPrice": 3.29 }
    ],
    "subtotal": 9.87,
    "appliedEligibilityCodes": []
  }' | jq
# -> discounts: [BOGO_MONSTER appliedAmount 3.29], discountTotal 3.29
```

### 4.5 Stacking — BOGO + Senior 20% (the worked example from DiscountService)

Red Bull `611269818994` @ `3.79`, qty 7. BOGO (priority 1): `floor(7/3)=2` free →
`7.58` off, net `18.95`. Senior 20% (priority 2): 20% of `18.95` = `3.79`. Total `11.37`.

```bash
curl -s -X POST "$BASE/discounts/calculate" \
  -H 'Content-Type: application/json' \
  -d '{
    "transactionId": "txn-stack",
    "lineItems": [
      { "upc": "611269818994", "description": "RED BULL ENERGY DRINK", "quantity": 7, "unitPrice": 3.79 }
    ],
    "subtotal": 26.53,
    "appliedEligibilityCodes": ["SENIOR_20"]
  }' | jq
# -> discountTotal 11.37 (BOGO 7.58 then Senior 3.79 — order is load-bearing)
```

### 4.6 Fixed amount off a UPC — "$1.00 Off C4 Strawberry"

UPC `842595121759` @ `2.99`, rule `C4_STRW_100OFF` = $1.00 off once if present.

```bash
curl -s -X POST "$BASE/discounts/calculate" \
  -H 'Content-Type: application/json' \
  -d '{
    "transactionId": "txn-fixed",
    "lineItems": [
      { "upc": "842595121759", "description": "C4 STRAWBERRY 16Z", "quantity": 1, "unitPrice": 2.99 }
    ],
    "subtotal": 2.99,
    "appliedEligibilityCodes": []
  }' | jq
# -> C4_STRW_100OFF appliedAmount 1.00
```

### 4.7 Error — unknown eligibility code (expect 400)

```bash
curl -i -X POST "$BASE/discounts/calculate" \
  -H 'Content-Type: application/json' \
  -d '{ "lineItems": [], "subtotal": 0, "appliedEligibilityCodes": ["NOT_A_CODE"] }'
# -> HTTP/1.1 400 ; {"error":"unknown eligibility code: NOT_A_CODE"}
```

### 4.8 Error — conflicting exclusivity group (expect 400)

`SENIOR_20` and `VETERAN_15` both live in `CUSTOMER_ELIGIBILITY`, so at most one.

```bash
curl -i -X POST "$BASE/discounts/calculate" \
  -H 'Content-Type: application/json' \
  -d '{ "lineItems": [], "subtotal": 0, "appliedEligibilityCodes": ["SENIOR_20", "VETERAN_15"] }'
# -> HTTP/1.1 400 ; {"error":"conflicting eligibility codes in exclusivity group 'CUSTOMER_ELIGIBILITY': ..."}
```

### 4.9 Empty basket — no discounts

```bash
curl -s -X POST "$BASE/discounts/calculate" \
  -H 'Content-Type: application/json' \
  -d '{ "lineItems": [], "subtotal": 0, "appliedEligibilityCodes": [] }' | jq
# -> discounts: [], discountTotal 0
```

---

## 5. Postman setup

### 5.1 Environments

Create two environments so you can flip the target with the environment dropdown.

**Local**
| Variable | Value |
| --- | --- |
| `baseUrl` | `http://localhost:8080` |

**AWS**
| Variable | Value |
| --- | --- |
| `baseUrl` | `http://pos-alb-253412522.us-east-1.elb.amazonaws.com` |

Then every request URL uses `{{baseUrl}}` and switching environments retargets the
whole collection. (The ALB serves plain **HTTP on :80** — no TLS — so don't prefix
`https://`.)

### 5.2 Collection — "POS Discount Engine"

| # | Name | Method | URL | Body (raw JSON) |
| --- | --- | --- | --- | --- |
| 1 | Health | `GET` | `{{baseUrl}}/health` | — |
| 2 | List eligibility rules | `GET` | `{{baseUrl}}/discounts/rules?category=ELIGIBILITY` | — |
| 3 | List promotional rules | `GET` | `{{baseUrl}}/discounts/rules?category=PROMOTIONAL` | — |
| 4 | Calculate — BOGO only | `POST` | `{{baseUrl}}/discounts/calculate` | example 4.4 above |
| 5 | Calculate — BOGO + Senior | `POST` | `{{baseUrl}}/discounts/calculate` | example 4.5 above |
| 6 | Calculate — fixed off UPC | `POST` | `{{baseUrl}}/discounts/calculate` | example 4.6 above |
| 7 | Error — unknown code | `POST` | `{{baseUrl}}/discounts/calculate` | example 4.7 above |
| 8 | Error — exclusivity conflict | `POST` | `{{baseUrl}}/discounts/calculate` | example 4.8 above |

For every `POST`: **Body → raw → JSON**, and set header
`Content-Type: application/json` (Postman adds it when you pick JSON).

### 5.3 Handy test scripts (Postman "Tests" tab)

Health request:
```javascript
pm.test("200 OK", () => pm.response.to.have.status(200));
pm.test("status UP", () => pm.expect(pm.response.json().status).to.eql("UP"));
```

Calculate request (BOGO + Senior):
```javascript
pm.test("200 OK", () => pm.response.to.have.status(200));
pm.test("discountTotal is 11.37", () =>
  pm.expect(Number(pm.response.json().discountTotal)).to.eql(11.37));
```

Error request:
```javascript
pm.test("400 Bad Request", () => pm.response.to.have.status(400));
pm.test("has error message", () =>
  pm.expect(pm.response.json()).to.have.property("error"));
```

---

## 6. Troubleshooting quick hits

- **AWS `/health` hangs or 5xx** → `./deploy-ecs.sh status` for target health, ECS
  events, stop reason, and recent logs.
- **Task won't start / stops immediately** → almost always an arch mismatch. Rebuild
  with `--platform linux/amd64` and `./deploy-ecs.sh redeploy`.
- **Pushed a new image but nothing changed** → the tag is still `:latest`; ECS needs
  `./deploy-ecs.sh redeploy` to force a new deployment.
- **POS applies no discounts** → confirm `--discount-engine-url` points at a reachable
  engine. By design the POS treats an unreachable engine as "empty discount list" and
  keeps ringing sales, so a wrong URL fails silently.
- **`./gradlew bootRun` fails with `ClassNotFoundException`** on older checkouts — the
  Phase 3 `Application` didn't exist yet. On this branch it does; a plain `bootRun`
  should start the engine.
