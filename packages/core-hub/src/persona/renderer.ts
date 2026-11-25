import { randomUUID } from "node:crypto";
import type { AssistantMode, CharacterDirective, CharacterStateId } from "@neko/protocol";

export type PersonaIntent =
  | "water_reminder"
  | "sedentary_alert"
  | "deadline_alert"
  | "worker_approval"
  | "morning_greeting"
  | "generic_reply";

export interface RenderIntentInput {
  intent: PersonaIntent;
  mode: AssistantMode;
  stealthOffice: boolean;
  ttsAllowed: boolean;
  summaryText?: string;
  metadata?: Record<string, unknown>;
}

export class PersonaRenderer {
  render(input: RenderIntentInput): CharacterDirective {
    const now = Date.now();
    const state = this.resolveState(input.intent, input.mode, input.stealthOffice);
    const costume = input.mode === "secretary" ? "secretary_glasses" : "maid_default";

    let text = input.summaryText ?? "";
    let expression = "smile_gentle";
    let motion = "idle_breath";
    let cacheKey: string | null = null;
    let actions: CharacterDirective["actions"] = [];

    switch (input.intent) {
      case "water_reminder": {
        text =
          input.mode === "secretary"
            ? "日程提示：距离上次补水已过去 1 小时，建议喝水。"
            : "主人，已经盯着屏幕快 1 小时啦，耳朵都要脱水蔫掉咯~";
        expression = "care_cup";
        motion = "hold_water_cup";
        cacheKey = "water_remind_01";
        actions = [
          { id: "water_done", label: "咕咚喝完", feedbackMotion: "happy_ear_wiggle" },
          { id: "water_snooze_5", label: "再写5分钟", feedbackMotion: "puff_cheeks" },
        ];
        break;
      }
      case "sedentary_alert": {
        const mins = Number(input.metadata?.activeMins ?? 60);
        text =
          input.mode === "secretary"
            ? `健康提醒：您已在电脑前连续工作 ${mins} 分钟，建议起身活动 3 分钟。`
            : `主人已经在电脑前黏住 ${mins} 分钟啦！尾巴都要坐扁了，快站起来走两步嘛~`;
        expression = "pout_concerned";
        motion = "tap_screen_remind";
        cacheKey = "sedentary_gentle_01";
        actions = [
          { id: "standup_done", label: "站起来啦", feedbackMotion: "happy_ear_wiggle" },
          { id: "standup_snooze_15", label: "写完这段(15m)", feedbackMotion: "puff_cheeks" },
        ];
        break;
      }
      case "deadline_alert": {
        text = input.summaryText ?? "死线雷达：有一项即将到期的笔试/面试日程，请点击查看考前速览。";
        expression = "focused_alert";
        motion = "hold_clipboard";
        cacheKey = "deadline_alert_01";
        actions = [
          { id: "open_brief", label: "查看速览卡" },
          { id: "ack_deadline", label: "已知晓" },
        ];
        break;
      }
      case "worker_approval": {
        text = input.summaryText ?? "指挥台请求审批：远端电脑 Agent 准备执行高风险操作，请确认。";
        expression = "serious_clipboard";
        motion = "raise_approval_card";
        cacheKey = "approval_needed_01";
        actions = [
          { id: "approve_l2", label: "批准执行" },
          { id: "reject_l2", label: "拒绝" },
        ];
        break;
      }
      case "morning_greeting": {
        text =
          input.mode === "secretary"
            ? "早安，请选择今日工作剧本并查看今日死线简报。"
            : "早安主人~ 今天我们演什么剧本呀？";
        expression = "smile_bright";
        motion = "wave_hello";
        cacheKey = "morning_hello_01";
        actions = [
          { id: "script_office", label: "🏢 去公司搬砖" },
          { id: "script_wfh", label: "🏠 居家/肝论文" },
          { id: "script_interview", label: "⚔️ 面试日" },
          { id: "script_holiday", label: "🛌 放假摆烂" },
        ];
        break;
      }
      default: {
        text = input.summaryText ?? "收到啦，正在处理中~";
        break;
      }
    }

    if (input.stealthOffice) {
      return {
        directiveId: randomUUID(),
        mode: input.mode,
        state: "STEALTH_OFFICE",
        stealthOverride: true,
        text,
        live2d: { expression: "stealth", motion: "paused", costume },
        tts: { enabledIfAllowed: false, cacheKey: null, dynamicText: null },
        actions,
        createdAt: now,
      };
    }

    return {
      directiveId: randomUUID(),
      mode: input.mode,
      state,
      stealthOverride: false,
      text,
      live2d: { expression, motion, costume },
      tts: {
        enabledIfAllowed: input.ttsAllowed,
        cacheKey,
        dynamicText: cacheKey ? null : text,
      },
      actions,
      createdAt: now,
    };
  }

  private resolveState(
    intent: PersonaIntent,
    mode: AssistantMode,
    stealthOffice: boolean,
  ): CharacterStateId {
    if (stealthOffice) {
      return "STEALTH_OFFICE";
    }
    switch (intent) {
      case "water_reminder":
        return "LIFE_WATER";
      case "sedentary_alert":
        return "LIFE_STANDUP";
      case "deadline_alert":
      case "worker_approval":
        return "SECRETARY_ALERT";
      case "morning_greeting":
        return "LIFE_GREETING";
      default:
        return mode === "secretary" ? "SECRETARY_ACTIVE" : "PEEK_IDLE";
    }
  }
}
