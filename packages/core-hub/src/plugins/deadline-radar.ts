import type {
  AssistantMode,
  CharacterDirective,
  ProspectiveItem,
} from "@neko/protocol";
import type { MemoryRepository } from "../memory/repository.ts";
import type { PersonaRenderer } from "../persona/renderer.ts";
import { wrapUntrustedExternalData } from "../policy/safety-gate.ts";

export const EscalationTiers = {
  H24: "24h",
  H2: "2h",
  M30: "30m",
} as const;

export type EscalationTier = (typeof EscalationTiers)[keyof typeof EscalationTiers];

export interface DeadlineEscalationAlert {
  item: ProspectiveItem;
  tier: EscalationTier;
  remainingMinutes: number;
  quickBrief: PreEventQuickBrief | null;
  directive: CharacterDirective;
}

export interface PreEventQuickBrief {
  itemId: string;
  title: string;
  dueAt: number;
  remainingMinutes: number;
  checklist: string[];
  relatedMemories: Array<{ title: string | null; content: string }>;
  note: string;
}

export interface ExternalNotificationInput {
  packageName: string;
  title: string;
  text: string;
  postedAt?: number;
}

export interface ClassifiedNotificationResult {
  matched: boolean;
  category: "interview_exam" | "delivery" | "subscription" | "ignored";
  createdItem: ProspectiveItem | null;
  wrappedSummary: string;
}

const TIER_WINDOWS_MIN: Array<{ tier: EscalationTier; maxMin: number; minMin: number }> = [
  { tier: "30m", maxMin: 35, minMin: 0 },
  { tier: "2h", maxMin: 130, minMin: 35 },
  { tier: "24h", maxMin: 24 * 60 + 15, minMin: 130 },
];

const INTERVIEW_RE = /(?:面试|笔试|测评|机试|考试|腾讯会议|飞书会议|牛客|赛码|Offer|interview|exam)/i;
const DELIVERY_RE = /(?:快递|取件码|菜鸟|丰巢|顺丰|驿站|已签收|派送中|delivery)/i;
const SUBSCRIPTION_RE = /(?:续费|扣费|会员到期|自动续费|账单|保质期|subscription)/i;

/**
 * Week 2 Day 10-11: Deadline Radar (24h / 2h / 30m gradient escalation) +
 * calm 30-minute Pre-Interview / Pre-Meeting Secretary Quick Brief (NO pop-quiz / 八股文).
 */
export class DeadlineRadarPlugin {
  private readonly memory: MemoryRepository;
  private readonly persona: PersonaRenderer;

  constructor(memory: MemoryRepository, persona: PersonaRenderer) {
    this.memory = memory;
    this.persona = persona;
  }

  ingestExternalNotification(input: ExternalNotificationInput): ClassifiedNotificationResult {
    const combined = `${input.title} ${input.text}`.trim();
    const wrappedSummary = wrapUntrustedExternalData(
      `android_notification:${input.packageName}`,
      combined,
    );

    if (INTERVIEW_RE.test(combined)) {
      const item = this.memory.addProspectiveItem({
        kind: "deadline",
        title: input.title.trim().slice(0, 60) || combined.slice(0, 40),
        details: {
          source: "notification_external",
          packageName: input.packageName,
          rawText: input.text.trim(),
        },
        dueAt: (input.postedAt ?? Date.now()) + 2 * 60 * 60_000,
        priority: 90,
      });
      return { matched: true, category: "interview_exam", createdItem: item, wrappedSummary };
    }

    if (DELIVERY_RE.test(combined)) {
      const item = this.memory.addProspectiveItem({
        kind: "commitment",
        title: input.title.trim().slice(0, 60) || "快递取件提醒",
        details: {
          source: "notification_external",
          packageName: input.packageName,
          rawText: input.text.trim(),
        },
        dueAt: (input.postedAt ?? Date.now()) + 6 * 60 * 60_000,
        priority: 55,
      });
      return { matched: true, category: "delivery", createdItem: item, wrappedSummary };
    }

    if (SUBSCRIPTION_RE.test(combined)) {
      const item = this.memory.addProspectiveItem({
        kind: "subscription",
        title: input.title.trim().slice(0, 60) || "续费/到期提醒",
        details: {
          source: "notification_external",
          packageName: input.packageName,
          rawText: input.text.trim(),
        },
        dueAt: (input.postedAt ?? Date.now()) + 24 * 60 * 60_000,
        priority: 70,
      });
      return { matched: true, category: "subscription", createdItem: item, wrappedSummary };
    }

    return { matched: false, category: "ignored", createdItem: null, wrappedSummary };
  }

  buildPreEventQuickBrief(item: ProspectiveItem, now: number = Date.now()): PreEventQuickBrief {
    const remainingMinutes = item.dueAt
      ? Math.max(0, Math.round((item.dueAt - now) / 60_000))
      : 30;
    const keyword = item.title
      .replace(/[^\p{L}\p{N}\s]/gu, " ")
      .trim()
      .split(/\s+/)
      .find((w) => w.length >= 2);

    const related = keyword
      ? this.memory
          .searchMemories(keyword, 3)
          .map((m) => ({ title: m.title, content: m.content }))
      : [];

    return {
      itemId: item.id,
      title: item.title,
      dueAt: item.dueAt ?? now + 30 * 60_000,
      remainingMinutes,
      checklist: [
        "确认会议链接/考场入口与网络稳定",
        "检查麦克风、摄像头与设备电量",
        "打开简历/笔记放手边，深呼吸放轻松",
      ],
      relatedMemories: related,
      note: "考前/会前 30 分钟轻速览（不做突击抽查，祝发挥顺利）",
    };
  }

  scanEscalations(
    now: number = Date.now(),
    mode: AssistantMode = "secretary",
    stealthOffice: boolean = false,
  ): DeadlineEscalationAlert[] {
    const activeItems = this.memory.listProspectiveItems("active");
    const alerts: DeadlineEscalationAlert[] = [];

    for (const item of activeItems) {
      if (item.dueAt === null) continue;
      const remainingMin = (item.dueAt - now) / 60_000;
      if (remainingMin < 0 || remainingMin > 24 * 60 + 15) continue;

      const matched = TIER_WINDOWS_MIN.find(
        (w) => remainingMin <= w.maxMin && remainingMin >= w.minMin,
      );
      if (!matched) continue;

      const dedupKey = `deadline_fired:${item.id}:${matched.tier}`;
      if (this.memory.getWorldState<boolean>(dedupKey, now)?.value) {
        continue;
      }

      this.memory.upsertWorldState(dedupKey, true, 48 * 3600);
      const isBrief = matched.tier === "30m" && (item.kind === "deadline" || item.kind === "commitment");
      const quickBrief = isBrief ? this.buildPreEventQuickBrief(item, now) : null;

      const summaryText = quickBrief
        ? `[30m 速览] ${item.title} 即将开始（剩 ${quickBrief.remainingMinutes} 分钟）：${quickBrief.checklist[0]}。`
        : `[${matched.tier} 死线雷达] ${item.title} 距离截止还有约 ${Math.round(remainingMin)} 分钟。`;

      const base = this.persona.render({
        intent: "deadline_alert",
        mode,
        stealthOffice,
        ttsAllowed: false,
        summaryText,
      });

      alerts.push({
        item,
        tier: matched.tier,
        remainingMinutes: Math.round(remainingMin),
        quickBrief,
        directive: {
          ...base,
          directiveId: `${dedupKey}:${item.dueAt}`,
          actions: [
            { id: `prospective_done:${item.id}`, label: "已完成" },
            { id: `prospective_ack:${item.id}`, label: "知道了" },
          ],
        },
      });
    }

    return alerts;
  }
}
