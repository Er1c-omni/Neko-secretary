import type { TodayScript } from "@neko/protocol";

export interface DecisionInput {
  urgency: number;      // 0..100
  relevance: number;    // 0..100
  value: number;        // 0..100
  interruption: number; // 0..100
  fatigue: number;      // 0..100
  inQuietHours: boolean;
  isDriving: boolean;
  stealthOffice: boolean;
  threshold?: number;
}

export interface DecisionOutcome {
  allow: boolean;
  score: number;
  channel: "live2d_bubble" | "stealth_capsule" | "voice_minimal" | "suppressed";
  stealthOverride: boolean;
  reason: string;
}

export interface SedentaryFusionSignal {
  phoneStillMinutes: number;
  recentStepsInWindow: number;
  pcRecentlyActive: boolean;
  phoneScreenOn: boolean;
  todayScript: TodayScript;
}

const WEIGHTS = {
  urgency: 0.32,
  relevance: 0.28,
  value: 0.25,
  interruption: 0.25,
  fatigue: 0.2,
} as const;

export class ProactiveEngine {
  evaluateDecision(input: DecisionInput): DecisionOutcome {
    const score =
      WEIGHTS.urgency * input.urgency +
      WEIGHTS.relevance * input.relevance +
      WEIGHTS.value * input.value -
      WEIGHTS.interruption * input.interruption -
      WEIGHTS.fatigue * input.fatigue;

    const threshold = input.threshold ?? 25;
    const isL0Critical = input.urgency >= 95;

    if (input.inQuietHours && !isL0Critical) {
      return {
        allow: false,
        score,
        channel: "suppressed",
        stealthOverride: input.stealthOffice,
        reason: "quiet_hours_guard",
      };
    }

    if (input.isDriving) {
      return {
        allow: isL0Critical || score >= threshold + 15,
        score,
        channel: isL0Critical || score >= threshold + 15 ? "voice_minimal" : "suppressed",
        stealthOverride: false,
        reason: "driving_guard",
      };
    }

    if (score < threshold && !isL0Critical) {
      return {
        allow: false,
        score,
        channel: "suppressed",
        stealthOverride: input.stealthOffice,
        reason: "below_score_threshold",
      };
    }

    return {
      allow: true,
      score,
      channel: input.stealthOffice ? "stealth_capsule" : "live2d_bubble",
      stealthOverride: input.stealthOffice,
      reason: isL0Critical ? "l0_critical_override" : "score_passed",
    };
  }

  /**
   * 3-source sedentary detection:
   * Avoids false positives when the phone is simply resting on a desk while the user stepped away.
   */
  evaluateSedentary(signal: SedentaryFusionSignal): boolean {
    const workingScript = signal.todayScript === "office" || signal.todayScript === "wfh";
    if (!workingScript) {
      return false;
    }
    const phoneStill = signal.phoneStillMinutes >= 45;
    const lowSteps = signal.recentStepsInWindow < 30;
    const userPresent = signal.pcRecentlyActive || signal.phoneScreenOn;
    return phoneStill && lowSteps && userPresent;
  }
}
