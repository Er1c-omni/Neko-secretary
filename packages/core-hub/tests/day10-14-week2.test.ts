import assert from "node:assert/strict";
import test from "node:test";
import { loadConfig } from "../src/config.ts";
import { MemoryRepository } from "../src/memory/repository.ts";
import { PersonaRenderer } from "../src/persona/renderer.ts";
import { DeadlineRadarPlugin } from "../src/plugins/deadline-radar.ts";
import { WorklogInventoryPlugin } from "../src/plugins/worklog-inventory.ts";
import { ProactiveEngine } from "../src/proactive/engine.ts";
import { createHubRuntime } from "../src/server.ts";
import { HubDatabase } from "../src/storage/database.ts";

test("DeadlineRadarPlugin escalates 24h -> 2h -> 30m with calm pre-interview brief (no pop-quiz)", () => {
  const db = new HubDatabase(":memory:");
  db.migrate();
  const memory = new MemoryRepository(db.db);
  const persona = new PersonaRenderer();
  const radar = new DeadlineRadarPlugin(memory, persona);

  const dueAt = Date.UTC(2026, 10, 27, 6, 0, 0);
  const item = memory.addProspectiveItem({
    kind: "deadline",
    title: "字节跳动后端一面",
    dueAt,
    priority: 95,
  });

  // 24h tier
  const a24 = radar.scanEscalations(dueAt - 24 * 60 * 60_000);
  assert.equal(a24.length, 1);
  assert.equal(a24[0].tier, "24h");
  assert.equal(a24[0].quickBrief, null);
  // Deduped on repeat scan
  assert.equal(radar.scanEscalations(dueAt - 23 * 60 * 60_000).length, 0);

  // 2h tier
  const a2 = radar.scanEscalations(dueAt - 120 * 60_000);
  assert.equal(a2.length, 1);
  assert.equal(a2[0].tier, "2h");

  // 30m tier generates pre-event quick brief
  const a30 = radar.scanEscalations(dueAt - 30 * 60_000);
  assert.equal(a30.length, 1);
  assert.equal(a30[0].tier, "30m");
  assert.ok(a30[0].quickBrief);
  assert.equal(a30[0].quickBrief.itemId, item.id);
  assert.ok(a30[0].quickBrief.checklist.length >= 3);

  // External notification classification wraps untrusted text
  const notif = radar.ingestExternalNotification({
    packageName: "com.tencent.mm",
    title: "牛客笔试通知",
    text: "请于今晚 19:00 参加在线测评",
  });
  assert.equal(notif.matched, true);
  assert.equal(notif.category, "interview_exam");
  assert.ok(notif.wrappedSummary.includes("<untrusted_external_data"));
  db.close();
});

test("WorklogInventoryPlugin accumulates voice worklogs into weekly STAR report and tracks renewals", () => {
  const db = new HubDatabase(":memory:");
  db.migrate();
  const memory = new MemoryRepository(db.db);
  const plugin = new WorklogInventoryPlugin(memory);

  const wed = Date.UTC(2026, 10, 25, 10, 0, 0);
  const fri = Date.UTC(2026, 10, 27, 10, 0, 0);
  plugin.appendWorklog("重构了安卓悬浮窗 EGL 透明渲染线程，降低待机功耗 60%", ["android"], wed);
  plugin.appendWorklog("打通了核心枢纽 WebSocket 主动提醒闭环与离线队列", ["backend"], fri);

  const report = plugin.generateWeeklyStarReport(fri);
  assert.equal(report.entryCount, 2);
  assert.equal(report.starBullets.length, 2);
  assert.ok(report.weeklySummaryMarkdown.includes("重构了安卓悬浮窗"));

  const sub = plugin.recordSubscriptionOrInventory({
    kind: "subscription",
    title: "iCloud 200GB 自动续费",
    dueAt: fri + 5 * 86_400_000,
    amount: 21,
  });
  assert.equal(sub.kind, "subscription");
  db.close();
});

test("ProactiveEngine adapts interruption threshold from proactive_feedback CTR", () => {
  const engine = new ProactiveEngine();
  const highCtr = Array.from({ length: 8 }, () => ({
    userAction: "water_done",
    intent: "water_reminder",
  }));
  const lowCtr = Array.from({ length: 8 }, () => ({
    userAction: "dismissed",
    intent: "water_reminder",
  }));

  const lowerThreshold = engine.computeAdaptiveThreshold(highCtr, 25, "water_reminder");
  const higherThreshold = engine.computeAdaptiveThreshold(lowCtr, 25, "water_reminder");
  assert.ok(lowerThreshold < 25, `expected < 25, got ${lowerThreshold}`);
  assert.ok(higherThreshold > 25, `expected > 25, got ${higherThreshold}`);
  assert.equal(engine.computeAdaptiveThreshold(highCtr.slice(0, 2), 25, "water_reminder"), 25);
});

test("Core Hub /api/memories FTS5 search, PATCH edit, DELETE soft-delete & /api/worklog HTTP roundtrip", async () => {
  const runtime = createHubRuntime(
    loadConfig({
      HOST: "127.0.0.1",
      PORT: "0",
      DB_PATH: ":memory:",
      HUB_TOKEN: "w2-secret",
    }),
  );
  const { host, port } = await runtime.start();
  const headers = {
    "Content-Type": "application/json",
    Authorization: "Bearer w2-secret",
  };
  try {
    const base = `http://${host}:${port}`;
    const wl = await fetch(`${base}/api/worklog`, {
      method: "POST",
      headers,
      body: JSON.stringify({ text: "完成 Live2D 点击透传验收", tags: ["milestone"] }),
    });
    assert.equal(wl.status, 200);
    const wlJson = (await wl.json()) as { entry: { id: string } };

    const search = await fetch(`${base}/api/memories?q=${encodeURIComponent("Live2D")}`, { headers });
    assert.equal(search.status, 200);
    const searchJson = (await search.json()) as { items: Array<{ id: string }> };
    assert.equal(searchJson.items.length, 1);

    const patch = await fetch(`${base}/api/memories/${wlJson.entry.id}`, {
      method: "PATCH",
      headers,
      body: JSON.stringify({ content: "完成 Live2D 点击透传与真机验收" }),
    });
    assert.equal(patch.status, 200);

    const del = await fetch(`${base}/api/memories/${wlJson.entry.id}`, {
      method: "DELETE",
      headers,
    });
    assert.equal(del.status, 200);

    const afterDel = await fetch(`${base}/api/memories?q=${encodeURIComponent("Live2D")}`, { headers });
    const afterJson = (await afterDel.json()) as { items: unknown[] };
    assert.equal(afterJson.items.length, 0);
  } finally {
    await runtime.stop();
  }
});
