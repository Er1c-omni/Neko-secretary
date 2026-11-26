import assert from "node:assert/strict";
import test from "node:test";
import { loadConfig } from "../src/config.ts";
import { DailyScriptPlugin } from "../src/plugins/daily-script.ts";
import { PersonaRenderer } from "../src/persona/renderer.ts";
import { HybridScheduler } from "../src/scheduler/scheduler.ts";
import { createHubRuntime } from "../src/server.ts";
import { HubDatabase } from "../src/storage/database.ts";
import { MemoryRepository } from "../src/memory/repository.ts";

test("DailyScriptPlugin prompts once per day and applies office/wfh/interview profiles", () => {
  const db = new HubDatabase(":memory:");
  db.migrate();
  const memory = new MemoryRepository(db.db);
  const persona = new PersonaRenderer();
  const scheduler = new HybridScheduler(db.db);
  const plugin = new DailyScriptPlugin(memory, persona, scheduler);

  memory.addProspectiveItem({
    kind: "deadline",
    title: "周五组会汇报 PPT",
    priority: 80,
  });

  const morning = Date.UTC(2026, 10, 26, 1, 0, 0); // 09:00 CST
  assert.equal(plugin.shouldPromptTodayScript(morning), true);
  const prompt = plugin.buildScriptPromptDirective(morning);
  assert.equal(prompt.actions.length, 4);

  // Select office -> stealthOffice=true, ttsAllowed=false
  const office = plugin.selectScript("office", morning);
  assert.equal(office.stealthOffice, true);
  assert.equal(office.ttsAllowed, false);
  assert.equal(office.directive.stealthOverride, true);
  assert.equal(office.directive.tts.enabledIfAllowed, false);
  assert.ok(office.briefingText.includes("周五组会汇报 PPT"));
  assert.equal(plugin.shouldPromptTodayScript(morning + 3_600_000), false);

  // Select wfh -> stealthOffice=false, ttsAllowed=true
  const wfh = plugin.selectScript("wfh", morning + 7_200_000);
  assert.equal(wfh.stealthOffice, false);
  assert.equal(wfh.ttsAllowed, true);
  assert.equal(wfh.directive.stealthOverride, false);

  // Next day -> prompts again
  const nextMorning = morning + 86_400_000;
  assert.equal(plugin.shouldPromptTodayScript(nextMorning), true);
  db.close();
});

test("Core Hub /api/briefing/morning and /api/script/select HTTP roundtrip", async () => {
  const runtime = createHubRuntime(
    loadConfig({
      HOST: "127.0.0.1",
      PORT: "0",
      DB_PATH: ":memory:",
      HUB_TOKEN: "script-secret",
    }),
  );
  const { host, port } = await runtime.start();
  try {
    const base = `http://${host}:${port}`;
    const m1 = await fetch(`${base}/api/briefing/morning`, {
      headers: { Authorization: "Bearer script-secret" },
    });
    assert.equal(m1.status, 200);
    const j1 = (await m1.json()) as { needsPrompt: boolean; promptDirective: { actions: unknown[] } };
    assert.equal(j1.needsPrompt, true);
    assert.equal(j1.promptDirective.actions.length, 4);

    const sel = await fetch(`${base}/api/script/select`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer script-secret",
      },
      body: JSON.stringify({ script: "wfh" }),
    });
    assert.equal(sel.status, 200);
    const selJson = (await sel.json()) as { ok: boolean; script: string; ttsAllowed: boolean; stealthOffice: boolean };
    assert.equal(selJson.ok, true);
    assert.equal(selJson.script, "wfh");
    assert.equal(selJson.ttsAllowed, true);
    assert.equal(selJson.stealthOffice, false);
  } finally {
    await runtime.stop();
  }
});
