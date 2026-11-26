import assert from "node:assert/strict";
import test from "node:test";
import { createEventEnvelope, type ScheduledJobSpec } from "@neko/protocol";
import { WebSocket } from "ws";
import { loadConfig } from "../src/config.ts";
import {
  computeNextRunAt,
  HybridScheduler,
  isInQuietHours,
  parseQuickReminderCommand,
} from "../src/scheduler/scheduler.ts";
import { createHubRuntime } from "../src/server.ts";
import { HubDatabase } from "../src/storage/database.ts";

test("HybridScheduler enforces quiet hours, reboot TTL catch-up guard, and fire_key dedup", () => {
  const db = new HubDatabase(":memory:");
  db.migrate();
  const scheduler = new HybridScheduler(db.db);

  // Cross-midnight quiet hours check (Asia/Shanghai = UTC+8)
  const shanghai0200 = Date.UTC(2026, 10, 25, 18, 0, 0); // 02:00 CST next day
  const shanghai1400 = Date.UTC(2026, 10, 26, 6, 0, 0);  // 14:00 CST
  assert.equal(isInQuietHours(shanghai0200, ["23:00", "07:30"], "Asia/Shanghai"), true);
  assert.equal(isInQuietHours(shanghai1400, ["23:00", "07:30"], "Asia/Shanghai"), false);

  const spec: ScheduledJobSpec = {
    id: "job_water_test",
    version: 1,
    name: "喝水提醒",
    modeScope: ["life"],
    noAgentScript: true,
    timezone: "Asia/Shanghai",
    trigger: { type: "interval", everyMinutes: 60 },
    policy: {
      quietHours: ["23:00", "07:30"],
      deferWhenBusy: true,
      maxFiresPerDay: 8,
      retryOnOfflineTtlMin: 15,
    },
    action: {
      type: "proactive_notify",
      intent: "water_reminder",
      channel: "live2d_bubble",
    },
    status: "active",
  };

  // Due at 02:00 CST -> suppressed by quiet hours, advances next_run_at
  scheduler.upsertJob(spec, shanghai0200 - 1_000);
  assert.equal(scheduler.pollDueJobs(shanghai0200).length, 0);

  // Stale missed slot (2 hours old > 15m TTL) -> skipped cleanly on reboot catch-up
  scheduler.upsertJob(spec, shanghai1400 - 120 * 60_000);
  assert.equal(scheduler.pollDueJobs(shanghai1400).length, 0);

  // Valid due slot at 14:00 CST -> fires once with deterministic fireKey
  const slot = shanghai1400 - 5_000;
  scheduler.upsertJob(spec, slot);
  const fires1 = scheduler.pollDueJobs(shanghai1400);
  assert.equal(fires1.length, 1);
  assert.equal(fires1[0].fireKey, `job_water_test:${slot}`);
  assert.equal(scheduler.pollDueJobs(shanghai1400).length, 0);

  // Daily time trigger computes next future slot
  const timeSpec: ScheduledJobSpec = {
    ...spec,
    id: "job_morning",
    trigger: { type: "time", time: "09:00" },
  };
  const nextMorning = computeNextRunAt(timeSpec, shanghai1400);
  assert.ok(nextMorning && nextMorning > shanghai1400);

  db.close();
});

test("End-to-end proactive water loop: voice -> scheduled_jobs -> directive.push -> feedback -> proactive_feedback & world_state", async () => {
  const parsed = parseQuickReminderCommand("每小时提醒我喝水");
  assert.ok(parsed);
  assert.equal(parsed.spec.id, "job_water_hourly");

  const runtime = createHubRuntime(
    loadConfig({
      HOST: "127.0.0.1",
      PORT: "0",
      DB_PATH: ":memory:",
      HUB_TOKEN: "loop-token",
    }),
  );
  const { host, port } = await runtime.start();

  const ws = new WebSocket(`ws://${host}:${port}/ws`, {
    headers: { Authorization: "Bearer loop-token" },
  });

  const pushedDirectives: Array<{
    directiveId: string;
    state: string;
    text: string;
    actions: Array<{ id: string; label: string }>;
  }> = [];

  await new Promise<void>((resolve, reject) => {
    ws.once("open", () => resolve());
    ws.once("error", reject);
  });

  const pendingRpc = new Map<string, (val: unknown) => void>();
  ws.on("message", (raw) => {
    const msg = JSON.parse(raw.toString()) as {
      id?: string;
      method?: string;
      params?: unknown;
      result?: unknown;
    };
    if (msg.id && pendingRpc.has(msg.id)) {
      pendingRpc.get(msg.id)?.(msg.result);
      pendingRpc.delete(msg.id);
    } else if (msg.method === "directive.push" && msg.params) {
      pushedDirectives.push(msg.params as (typeof pushedDirectives)[number]);
    }
  });

  const callRpc = (id: string, method: string, params: unknown): Promise<unknown> =>
    new Promise((resolve) => {
      pendingRpc.set(id, resolve);
      ws.send(JSON.stringify({ jsonrpc: "2.0", id, method, params }));
    });

  try {
    // 1. Voice transcript: "每小时提醒我喝水"
    const voiceEvt = createEventEnvelope({
      idempotencyKey: "android:voice:water-cmd-1",
      type: "user.voice_transcript",
      source: "android",
      payload: { text: "每小时提醒我喝水", mode: "life" },
    });
    await callRpc("1", "event.ingest", voiceEvt);

    const jobs = runtime.scheduler.listJobs();
    const waterJob = jobs.find((j) => j.spec.id === "job_water_hourly");
    assert.ok(waterJob, "job_water_hourly should be persisted");

    // 2. Advance to a daytime slot (14:00 CST) so quiet hours never interfere regardless of host clock
    const daytimeNow = Date.UTC(2026, 10, 26, 6, 0, 0);
    runtime.scheduler.upsertJob(waterJob.spec, daytimeNow - 1_000);
    const fires = runtime.pollSchedulerNow(daytimeNow);
    assert.equal(fires.length, 1);

    // Wait briefly for WS frame delivery
    await new Promise((r) => setTimeout(r, 50));
    const waterDirective = pushedDirectives.find((d) => d.state === "LIFE_WATER");
    assert.ok(waterDirective, "Expected LIFE_WATER directive.push");
    assert.equal(waterDirective.directiveId, fires[0].fireKey);
    assert.ok(waterDirective.actions.some((a) => a.label === "喝完啦"));
    assert.ok(waterDirective.actions.some((a) => a.label === "5m后"));

    // 3. User clicks [喝完啦] -> writes proactive_feedback & world_state.last_water_at
    const doneEvt = createEventEnvelope({
      idempotencyKey: `android:action:${waterDirective.directiveId}:water_done`,
      type: "user.action_reply",
      source: "android",
      payload: {
        directiveId: waterDirective.directiveId,
        actionId: "water_done:job_water_hourly",
        intent: "water_reminder",
        latencySec: 4,
      },
    });
    await callRpc("2", "event.ingest", doneEvt);

    const feedback = runtime.scheduler.listFeedback(10);
    assert.equal(feedback.length, 1);
    assert.equal(feedback[0].userAction, "water_done");
    assert.equal(feedback[0].jobId, "job_water_hourly");

    const lastWater = runtime.memory.getWorldState<number>("last_water_at");
    assert.ok(lastWater && lastWater.value > 0);

    // 4. User clicks [5m后] -> records snooze feedback & updates water_snooze_until
    const snoozeEvt = createEventEnvelope({
      idempotencyKey: `android:action:${waterDirective.directiveId}:water_snooze_5`,
      type: "user.action_reply",
      source: "android",
      payload: {
        directiveId: waterDirective.directiveId,
        actionId: "water_snooze_5:job_water_hourly",
        intent: "water_reminder",
      },
    });
    await callRpc("3", "event.ingest", snoozeEvt);
    assert.ok(runtime.memory.getWorldState<number>("water_snooze_until"));
  } finally {
    ws.close();
    await runtime.stop();
  }
});
