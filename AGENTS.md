# AGENTS.md — Coding Agent Execution Guide

This file provides high-signal rules, invariants, and verification commands for coding agents (e.g., Hydrogen High, Opus 5.5 Medium, Codex, Claude Code, DeepSeek Harness) developing **Neko-Secretary**.

---

## 1. Environment & Toolchain

- **Workspace OS**: Windows (`c:\PrivateProjects\Neko-secretary`)
- **`packages/protocol` & `packages/core-hub`**:
  - Runtime: **Node.js v24.15+** (native TypeScript type stripping + built-in `node:sqlite` `DatabaseSync` with WAL + FTS5).
  - Package manager: **pnpm 11+** (`pnpm install` outside sandbox on Windows; `pnpm typecheck` / `pnpm test` inside sandbox).
  - Zero heavy backend frameworks or native C++ SQLite bindings (`better-sqlite3` is NOT used).
- **`packages/pc-worker`**:
  - Runtime: **Go 1.27+**, standard library preferred to keep single-binary deployment trivial.
- **`apps/android`**:
  - **Kotlin + Jetpack Compose + Coroutines + Room + Ktor Client + Live2D (`TextureView` + EGLThread)**.
  - Note: Local CLI machine currently does not have `java`/`gradle` on PATH; validate Android changes carefully by static consistency and build in Android Studio when JDK/SDK is attached.

---

## 2. Hard Architectural Invariants (DO NOT VIOLATE)

1. **Always-on Agent ≠ Always-on LLM**:
   - Background events, periodic water reminders, Wi-Fi stealth transitions, and duplicate checks MUST flow through `Rule Engine` (`ignore` / `fast_path`) with **0 LLM tokens**. Only invoke `ModelGateway` on `wake_agent` paths.
2. **Persona Layer Decoupled from SafetyGate**:
   - Switching between `life` (catgirl/maid) and `secretary` (commander) modes ONLY changes `PersonaRenderer` tone, Live2D costume/motion, and interruption thresholds. It MUST NEVER relax `SafetyGate` (`L0` / `L1` / `L2`) permissions.
3. **Zero APK Reverse Engineering**:
   - Strictly follow the P1–P5 hierarchy: Official API/MCP -> Android `ACTION_SEND` ShareSheet & Screenshot intake -> `NotificationListenerService` -> Public Android APIs (`AlarmManager`, `CalendarProvider`, `HealthConnect`, `UsageStatsManager`, `Geofencing`) -> Optional isolated `Accessibility`/`Shizuku` module (disabled by default).
4. **Prompt Injection Defense**:
   - Any untrusted external text (screenshot OCR, third-party app notifications, monitored web pages, emails) MUST be wrapped via `wrapUntrustedExternalData(source, content)` inside `<untrusted_external_data source="...">...</untrusted_external_data>` before LLM consumption.
5. **Private-Mesh First, Cloud-Ready Seam**:
   - Network transport must sit behind `TransportAdapter` (Tailscale direct WS by default, future Cloud WSS Relay pluggable).
6. **Live2D & OSS Licensing Purity**:
   - NEVER commit `libLive2DCubismCore.so`, `.jar`/`.aar`, or copyrighted Live2D model assets to Git. Keep them gitignored under `apps/android/live2d-core/` and `apps/android/app/src/main/assets/live2d/`.
7. **Node 24 Erasable TypeScript Syntax Only**:
   - `tsconfig.base.json` sets `"erasableSyntaxOnly": true` and `"allowImportingTsExtensions": true`.
   - **NEVER** use TypeScript `enum`, `namespace` with runtime code, or constructor parameter properties (`constructor(public x: string)`). Use `as const` objects and string literal union types, and always include `.ts` extensions in relative TS imports.
8. **Product Tone Guardrail (User Preference)**:
   - Keep deadline radar + pre-interview/pre-meeting quick briefs, and optional post-interview voice dump; **DO NOT** build unsolicited pop-quiz / "八股文" interrogation prompts.
   - TTS is **optional** and MUST auto-mute in `STEALTH_OFFICE` mode or when connected to work Wi-Fi without headphones.

---

## 3. Repository Map & Source of Truth

- Architecture blueprint: [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md)
- Wire & RPC contract: [`docs/PROTOCOL.md`](./docs/PROTOCOL.md)
- Coding & reliability standards: [`docs/ENGINEERING_SPEC.md`](./docs/ENGINEERING_SPEC.md)
- Milestone execution checklist: [`docs/ROADMAP.md`](./docs/ROADMAP.md)

---

## 4. Verification Commands

Run after any TypeScript or Core Hub change:
```bash
pnpm typecheck
pnpm test
```

Run after any Go worker change:
```bash
cd packages/pc-worker
go test ./...
go run ./cmd/neko-worker --dry-run
```

Quick runtime health check for Core Hub:
```bash
# Start core-hub and probe GET /health in one command (remember to terminate cleanly)
```

Before starting a new task from [`docs/ROADMAP.md`](./docs/ROADMAP.md), inspect the current file implementations first and update checklist items in `docs/ROADMAP.md` as milestones complete.

---

## 5. Model Selection Guide (`Hydrogen High` vs `Opus 5.5 Medium`)

To optimize token efficiency without compromising quality on hard subsystems, follow this split (also tagged per milestone in [`docs/ROADMAP.md`](./docs/ROADMAP.md)):

- **Default Workhorse — `Hydrogen High` (~75% of tasks)**:
  - Use for routine TypeScript/Go/Kotlin feature wiring, `ModelGateway` HTTP providers, Compose UI screens, `ShareIntakeHandler` multimodal parsing, notification filters, deadline/weekly-report plugins, SQLite queries, and standard unit tests.
- **Escalation Model — `Opus 5.5 Medium` (~25% of tasks)**:
  - Switch to `Opus 5.5 Medium` when implementing or debugging these 4 high-complexity areas:
    1. **Live2D OpenGL ES / EGL Transparent Overlay & Adaptive FPS** (`Week 1 Day 2–3`):
       - `apps/android/app/src/main/java/com/neko/secretary/live2d/Live2DTextureRenderer.kt`
       - `apps/android/app/src/main/java/com/neko/secretary/overlay/CompanionOverlayService.kt`
       - *Why*: Dedicated GL thread lifecycle, `TextureView` alpha blending over third-party apps, transparent pixel hit-testing/click-through, and 60/20/10/0 FPS throttling.
    2. **Real-Time Streaming Audio ASR & TTS Lip-Sync Concurrency** (`Week 1 Day 4`, `Week 2 Day 9`):
       - `apps/android/app/src/main/java/com/neko/secretary/voice/VoicePipeline.kt`
       - *Why*: Low-latency audio capture buffering, streaming WebSocket framing, offline Sherpa-ONNX fallback, and RMS audio envelope driving Live2D mouth parameters.
    3. **Hybrid Scheduler Edge Cases & Offline Idempotent Sync** (`Week 1 Day 6–7`, `Week 2 Day 14`):
       - `packages/core-hub/src/scheduler/scheduler.ts`, `packages/core-hub/src/proactive/engine.ts`
       - `apps/android/app/src/main/java/com/neko/secretary/sync/TransportClient.kt`
       - *Why*: `fire_key` deduplication, timezone/quiet-hours/reboot catch-up windows, Room offline queue replay, and `AlarmManager` fallback coordination.
    4. **Multi-PC Multi-Agent Orchestrator, Native OS Activity Hooks & L2 SafetyGate** (`Week 3 Day 15–18`):
       - `packages/pc-worker/internal/adapter/adapter.go`, `packages/pc-worker/internal/activity/activity.go`
       - `packages/core-hub/src/orchestrator/registry.ts`, `packages/core-hub/src/server.ts`
       - `apps/android/app/src/main/java/com/neko/secretary/actions/SafetyGateClient.kt`
       - *Why*: Windows/Linux native idle-input hooks, PTY/JSON-stream wrapping for `t3code`/`dsh`/`codex`/`claude-code`, parent-child `agent_threads` lineage, and suspending/resuming L2 `SafetyGate` Diff approvals across devices.
- **Rule of Thumb**: Start every day with `Hydrogen High`; switch to `Opus 5.5 Medium` on any `🟣 [Opus 5.5 Medium]` block in `docs/ROADMAP.md` or whenever a bug persists after 2 retries.

