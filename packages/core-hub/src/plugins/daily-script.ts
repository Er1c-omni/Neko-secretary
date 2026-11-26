import type {
  AssistantMode,
  CharacterDirective,
  ProspectiveItem,
  TodayScript,
} from "@neko/protocol";
import type { MemoryRepository } from "../memory/repository.ts";
import type { PersonaRenderer } from "../persona/renderer.ts";
import type { HybridScheduler } from "../scheduler/scheduler.ts";

export interface ScriptProfile {
  script: TodayScript;
  label: string;
  mode: AssistantMode;
  stealthOffice: boolean;
  ttsAllowed: boolean;
  greetingLine: string;
}

export interface MorningBriefingResult {
  dateKey: string;
  script: TodayScript;
  mode: AssistantMode;
  stealthOffice: boolean;
  ttsAllowed: boolean;
  upcomingDeadlines: ProspectiveItem[];
  activeJobsCount: number;
  briefingText: string;
  directive: CharacterDirective;
}

const SCRIPT_PROFILES: Record<TodayScript, ScriptProfile> = {
  office: {
    script: "office",
    label: "上班/实验室",
    mode: "life",
    stealthOffice: true,
    ttsAllowed: false,
    greetingLine: "早安！今天切换至职场防社死静音模式，我会乖乖缩成微徽章不打扰你。",
  },
  wfh: {
    script: "wfh",
    label: "居家办公",
    mode: "life",
    stealthOffice: false,
    ttsAllowed: true,
    greetingLine: "早安主人~ 居家办公模式已就绪，温和语音提醒和喝水陪伴已开启！",
  },
  interview: {
    script: "interview",
    label: "面试/考试日",
    mode: "secretary",
    stealthOffice: true,
    ttsAllowed: false,
    greetingLine: "指挥官早安。今日为面试/考试日，已切至静默秘书态，仅保留关键死线与考前速览。",
  },
  holiday: {
    script: "holiday",
    label: "休假放松",
    mode: "life",
    stealthOffice: false,
    ttsAllowed: true,
    greetingLine: "放假啦~ 今天降低所有工作打扰，好好休息享受假期喵！",
  },
};

export function formatLocalDateKey(now: number = Date.now(), timeZone: string = "Asia/Shanghai"): string {
  try {
    const fmt = new Intl.DateTimeFormat("en-CA", {
      timeZone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    });
    return fmt.format(new Date(now));
  } catch {
    return new Date(now).toISOString().slice(0, 10);
  }
}

export class DailyScriptPlugin {
  private readonly memory: MemoryRepository;
  private readonly persona: PersonaRenderer;
  private readonly scheduler: HybridScheduler;

  constructor(
    memory: MemoryRepository,
    persona: PersonaRenderer,
    scheduler: HybridScheduler,
  ) {
    this.memory = memory;
    this.persona = persona;
    this.scheduler = scheduler;
  }

  shouldPromptTodayScript(now: number = Date.now()): boolean {
    const today = formatLocalDateKey(now);
    const lastDate = this.memory.getWorldState<string>("last_script_date", now)?.value;
    return lastDate !== today;
  }

  buildScriptPromptDirective(now: number = Date.now()): CharacterDirective {
    const mode =
      this.memory.getWorldState<AssistantMode>("assistant_mode", now)?.value ?? "life";
    const stealthOffice =
      this.memory.getWorldState<boolean>("stealth_office", now)?.value ?? false;

    const base = this.persona.render({
      intent: "morning_greeting",
      mode,
      stealthOffice,
      ttsAllowed: false,
      summaryText: "早安！3 秒定调今天状态，选一个今日剧本吧：",
    });

    return {
      ...base,
      directiveId: `daily_script_prompt:${formatLocalDateKey(now)}`,
      actions: [
        { id: "script:office", label: "上班" },
        { id: "script:wfh", label: "居家" },
        { id: "script:interview", label: "面试" },
        { id: "script:holiday", label: "休假" },
      ],
    };
  }

  selectScript(script: TodayScript, now: number = Date.now()): MorningBriefingResult {
    const profile = SCRIPT_PROFILES[script];
    const dateKey = formatLocalDateKey(now);

    this.memory.upsertWorldState("today_script", script);
    this.memory.upsertWorldState("last_script_date", dateKey);
    this.memory.upsertWorldState("assistant_mode", profile.mode);
    this.memory.upsertWorldState("stealth_office", profile.stealthOffice);
    this.memory.upsertWorldState("tts_allowed", profile.ttsAllowed);

    const upcomingDeadlines = this.memory.listProspectiveItems("active").slice(0, 5);
    const activeJobsCount = this.scheduler
      .listJobs()
      .filter((j) => j.status === "active").length;

    const deadlineSummary =
      upcomingDeadlines.length > 0
        ? `今日关注：${upcomingDeadlines
            .slice(0, 3)
            .map((item) => item.title)
            .join("、")}。`
        : "今日暂无紧急死线。";

    const briefingText = `${profile.greetingLine} ${deadlineSummary}`;

    const directive = this.persona.render({
      intent: "morning_greeting",
      mode: profile.mode,
      stealthOffice: profile.stealthOffice,
      ttsAllowed: profile.ttsAllowed,
      summaryText: briefingText,
    });

    return {
      dateKey,
      script,
      mode: profile.mode,
      stealthOffice: profile.stealthOffice,
      ttsAllowed: profile.ttsAllowed,
      upcomingDeadlines,
      activeJobsCount,
      briefingText,
      directive: {
        ...directive,
        directiveId: `morning_briefing:${dateKey}:${script}`,
      },
    };
  }
}
