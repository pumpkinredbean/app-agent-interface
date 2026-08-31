import type { JsonObject } from "@pumpkinredbean/app-agent-client";

export interface UiDriverState {
  readonly targetPackage: string;
  readonly currentPackage: string | null;
  readonly width: number;
  readonly height: number;
  readonly rotation: number;
}

export interface UiDriverHealth extends UiDriverState {
  readonly ready: boolean;
  readonly runtime: string;
}

export interface UiHierarchyResponse extends UiDriverState {
  readonly xml: string;
}

export interface UiScreenshotResponse extends UiDriverState {
  readonly mediaType: string;
  readonly data: string;
}

export interface Point {
  readonly x: number;
  readonly y: number;
}

export type UiDriverAction =
  | { readonly action: "tap"; readonly x: number; readonly y: number }
  | { readonly action: "long_press"; readonly x: number; readonly y: number; readonly durationMs: number }
  | { readonly action: "multi_tap"; readonly positions: readonly Point[]; readonly intervalMs: number }
  | { readonly action: "input_text"; readonly x: number; readonly y: number; readonly text: string }
  | {
      readonly action: "swipe";
      readonly startX: number;
      readonly startY: number;
      readonly endX: number;
      readonly endY: number;
      readonly durationMs: number;
    }
  | { readonly action: "press"; readonly key: "BACK" | "ENTER" };

export interface UiActionResponse {
  readonly performed: boolean;
  readonly targetPackage?: string;
  readonly currentPackage?: string | null;
  readonly requested?: number;
  readonly completed?: number;
  readonly failedIndex?: number;
}

export interface UiAutomatorBridge {
  health(): Promise<UiDriverHealth>;
  hierarchy(): Promise<UiHierarchyResponse>;
  screenshot(): Promise<UiScreenshotResponse>;
  action(action: UiDriverAction): Promise<UiActionResponse>;
  shutdown?(): Promise<JsonObject>;
}
