import type { JsonObject, SuccessfulToolExchange } from "@pumpkinredbean/app-agent-client";

import type { Point, UiAutomatorBridge } from "../bridge/contracts.js";
import type { ProjectedControlNode, UiContent, UiControl, UiHierarchyMetrics } from "../hierarchy/types.js";

export type ObserveScope = "all" | "content" | "controls";

export type MobileObserveInput = { readonly scope?: ObserveScope };
export type MobileScreenshotInput = { readonly snapshotId: string };
export type MobileTapInput =
  | { readonly snapshotId: string; readonly nodeId: string }
  | { readonly snapshotId: string; readonly x: number; readonly y: number };
export type MobileLongPressInput = (
  | { readonly snapshotId: string; readonly nodeId: string }
  | { readonly snapshotId: string; readonly x: number; readonly y: number }
) & { readonly durationMs?: number };
export type MobileMultiTapInput = {
  readonly snapshotId: string;
  readonly positions: readonly Point[];
  readonly intervalMs?: number;
};
export type MobileInputTextInput = { readonly snapshotId: string; readonly nodeId: string; readonly text: string };
export type MobileSwipeInput = {
  readonly snapshotId: string;
  readonly start: Point;
  readonly end: Point;
  readonly durationMs?: number;
};
export type MobilePressInput = { readonly snapshotId: string; readonly key: "BACK" | "ENTER" };
export type MobileWaitInput = { readonly snapshotId: string; readonly query: string; readonly timeoutMs?: number };

export interface AndroidUiToolInputMap {
  readonly mobile_observe: MobileObserveInput;
  readonly mobile_screenshot: MobileScreenshotInput;
  readonly mobile_tap: MobileTapInput;
  readonly mobile_long_press: MobileLongPressInput;
  readonly mobile_multi_tap: MobileMultiTapInput;
  readonly mobile_input_text: MobileInputTextInput;
  readonly mobile_swipe: MobileSwipeInput;
  readonly mobile_press: MobilePressInput;
  readonly mobile_wait: MobileWaitInput;
}

export type AndroidUiToolName = keyof AndroidUiToolInputMap;

export type UiScreen = { readonly width: number; readonly height: number; readonly rotation: number };
export type UiObservation = {
  readonly snapshotId: string;
  readonly packageName: string;
  readonly screen: UiScreen;
  readonly content?: readonly UiContent[];
  readonly controls?: readonly UiControl[];
  readonly truncated?: true;
};
export type UiScreenshot = {
  readonly snapshotId: string;
  readonly screen: UiScreen;
  readonly image: { readonly mediaType: string; readonly data: string };
};
export type UiActionOutput = {
  readonly performed: boolean;
  readonly requested?: number;
  readonly completed?: number;
  readonly characterCount?: number;
  readonly key?: "BACK" | "ENTER";
  readonly after: UiObservation;
};

export interface AndroidUiToolOutputMap {
  readonly mobile_observe: UiObservation;
  readonly mobile_screenshot: UiScreenshot;
  readonly mobile_tap: UiActionOutput;
  readonly mobile_long_press: UiActionOutput;
  readonly mobile_multi_tap: UiActionOutput;
  readonly mobile_input_text: UiActionOutput;
  readonly mobile_swipe: UiActionOutput;
  readonly mobile_press: UiActionOutput;
  readonly mobile_wait: UiObservation;
}

export type AndroidUiToolExchange<Name extends AndroidUiToolName> = SuccessfulToolExchange<AndroidUiToolOutputMap[Name]>;

export interface AndroidUiToolClientOptions {
  readonly allowedPackages?: readonly string[];
  readonly baseUrl?: string;
  readonly bridge?: UiAutomatorBridge;
  readonly fetch?: typeof globalThis.fetch;
  readonly idFactory?: () => string;
}

export interface StoredUiSnapshot {
  readonly id: string;
  readonly createdAt: number;
  readonly packageName: string;
  readonly targetPackage: string;
  readonly width: number;
  readonly height: number;
  readonly rotation: number;
  readonly fingerprint: string;
  readonly content: readonly UiContent[];
  readonly controls: readonly UiControl[];
  readonly nodeMap: ReadonlyMap<string, ProjectedControlNode>;
  readonly metrics: UiHierarchyMetrics;
}

export function asJsonObject(value: object): JsonObject {
  return value as JsonObject;
}
