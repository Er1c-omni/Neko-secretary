import type { AssistantMode } from "./events.ts";

export type CharacterStateId =
  | "PEEK_IDLE"
  | "LIFE_GREETING"
  | "LIFE_WATER"
  | "LIFE_STANDUP"
  | "ANTI_PROCRASTINATE"
  | "SECRETARY_ACTIVE"
  | "SECRETARY_ALERT"
  | "SLEEPY_NIGHT"
  | "DOCK_STANDBY"
  | "STEALTH_OFFICE";

export interface Live2DDirective {
  expression: string;
  motion: string;
  costume: string;
}

export interface TtsDirective {
  enabledIfAllowed: boolean;
  cacheKey: string | null;
  dynamicText: string | null;
}

export interface ActionButton {
  id: string;
  label: string;
  feedbackMotion?: string;
}

export interface CharacterDirective {
  directiveId: string;
  mode: AssistantMode;
  state: CharacterStateId;
  stealthOverride: boolean;
  text: string;
  live2d: Live2DDirective;
  tts: TtsDirective;
  actions: ActionButton[];
  createdAt: number;
}
