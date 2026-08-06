# rocketpartners-pos

Onboarding project for Rocket Partners: a Java 17 point-of-sale system with a Swing
desktop client, a socket-based journal server, and a Spring Boot discount engine, all
in one Gradle project. See `CLAUDE.md` for the full project brief and `docs/Phase 1/`
for architecture, event flow, and domain model.

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
| `--discount-engine-url` | `http://localhost:8080` | Discount engine base URL (Phase 3). |
| `--help` / `-h` | — | Print usage and exit. |
