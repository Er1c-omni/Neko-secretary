import type { MemoryRecord, ProspectiveItem } from "@neko/protocol";
import type { MemoryRepository } from "../memory/repository.ts";

export interface WeeklyStarReport {
  weekKey: string;
  entryCount: number;
  entries: MemoryRecord[];
  weeklySummaryMarkdown: string;
  starBullets: string[];
}

export interface InventoryOrSubscriptionInput {
  kind: "subscription" | "inventory";
  title: string;
  dueAt: number;
  amount?: number | null;
  currency?: string | null;
  note?: string;
}

export function getIsoWeekKey(now: number = Date.now()): string {
  const d = new Date(now);
  const utc = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
  const dayNum = utc.getUTCDay() || 7;
  utc.setUTCDate(utc.getUTCDate() + 4 - dayNum);
  const yearStart = new Date(Date.UTC(utc.getUTCFullYear(), 0, 1));
  const weekNo = Math.ceil(((utc.getTime() - yearStart.getTime()) / 86_400_000 + 1) / 7);
  return `${utc.getUTCFullYear()}-W${String(weekNo).padStart(2, "0")}`;
}

/**
 * Week 2 Day 10-11:
 * - 30-second after-work voice worklog accumulator -> Friday Weekly Report & STAR resume bullets
 * - Subscription renewal & item shelf-life / expiry tracker
 */
export class WorklogInventoryPlugin {
  private readonly memory: MemoryRepository;

  constructor(memory: MemoryRepository) {
    this.memory = memory;
  }

  appendWorklog(
    text: string,
    extraTags: string[] = [],
    now: number = Date.now(),
  ): MemoryRecord {
    const clean = text.trim();
    if (!clean) {
      throw new Error("worklog text is required");
    }
    const weekKey = getIsoWeekKey(now);
    const dateLabel = new Date(now).toISOString().slice(0, 10);
    return this.memory.addMemory({
      category: "episodic",
      title: `工作流水账 ${dateLabel}`,
      content: clean,
      tags: Array.from(new Set(["worklog", weekKey, ...extraTags])),
      sourceType: "voice",
      sourceRef: `worklog:${weekKey}`,
      importance: 0.75,
    });
  }

  generateWeeklyStarReport(now: number = Date.now()): WeeklyStarReport {
    const weekKey = getIsoWeekKey(now);
    const allWorklogs = this.memory
      .listMemories(100)
      .filter((m) => m.tags.includes("worklog") && m.tags.includes(weekKey))
      .reverse();

    const bullets = allWorklogs.map(
      (entry, idx) =>
        `${idx + 1}. [STAR素材] 围绕「${entry.title ?? "日常任务"}」推进执行：${entry.content}`,
    );

    const summaryLines = [
      `## 本周实习/工作周报 (${weekKey})`,
      allWorklogs.length === 0
        ? "- 本周暂未记录语音流水账，下班花 30 秒口述即可自动攒素材。"
        : allWorklogs.map((e) => `- (${new Date(e.createdAt).toISOString().slice(0, 10)}) ${e.content}`).join("\n"),
      "",
      "### 简历 STAR 素材初稿",
      bullets.length === 0 ? "- 暂无" : bullets.join("\n"),
    ];

    return {
      weekKey,
      entryCount: allWorklogs.length,
      entries: allWorklogs,
      weeklySummaryMarkdown: summaryLines.join("\n"),
      starBullets: bullets,
    };
  }

  recordSubscriptionOrInventory(input: InventoryOrSubscriptionInput): ProspectiveItem {
    return this.memory.addProspectiveItem({
      kind: input.kind,
      title: input.title.trim(),
      details: {
        amount: input.amount ?? null,
        currency: input.currency ?? "CNY",
        note: input.note ?? "",
      },
      dueAt: input.dueAt,
      priority: input.kind === "subscription" ? 70 : 60,
    });
  }
}
