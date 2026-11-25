export type MemoryCategory = "profile" | "episodic" | "semantic" | "procedural";

export type MemorySourceType = "voice" | "screenshot" | "chat" | "auto_extract";

export interface MemoryRecord {
  id: string;
  category: MemoryCategory;
  title: string | null;
  content: string;
  tags: string[];
  sourceType: MemorySourceType;
  sourceRef: string | null;
  confidence: number;
  importance: number;
  validUntil: number | null;
  createdAt: number;
  updatedAt: number;
  isDeleted: boolean;
}

export type ProspectiveKind =
  | "goal"
  | "deadline"
  | "commitment"
  | "subscription"
  | "inventory"
  | "habit";

export type ProspectiveStatus = "active" | "done" | "snoozed" | "archived";

export interface ProspectiveItem {
  id: string;
  kind: ProspectiveKind;
  title: string;
  details: Record<string, unknown>;
  dueAt: number | null;
  recurrenceRule: string | null;
  priority: number;
  status: ProspectiveStatus;
  createdAt: number;
  updatedAt: number;
}

export interface WorldStateEntry<T = unknown> {
  key: string;
  value: T;
  updatedAt: number;
  ttlSec: number | null;
}

export type FeedbackUserAction = "accepted" | "snoozed" | "dismissed" | "ignored";

export interface ProactiveFeedbackRecord {
  id: string;
  jobId: string | null;
  intent: string;
  decisionScore: number;
  contextSnapshot: Record<string, unknown>;
  userAction: FeedbackUserAction;
  latencySec: number | null;
  createdAt: number;
}
