import type { JsonObject, JsonValue } from "@pumpkinredbean/app-agent-client";

import type { AgentEvent, Attribution } from "../agent/contracts.js";

const TOOL_LABELS: Readonly<Record<string, string>> = {
  weather_get_current: "Current weather",
  weather_get_forecast: "Daily forecast",
  weather_compare_locations: "Location comparison",
  weather_alert_list: "Saved weather alerts",
  weather_alert_create: "Create weather alert",
  weather_alert_delete: "Delete weather alert",
  mobile_observe: "Observe app screen",
  mobile_screenshot: "Capture screen image",
  mobile_tap: "Tap UI control",
  mobile_long_press: "Long-press UI control",
  mobile_multi_tap: "Tap position sequence",
  mobile_input_text: "Enter text",
  mobile_swipe: "Swipe app screen",
  mobile_press: "Press Android key",
  mobile_wait: "Wait for UI state",
};

const timeFormatter = new Intl.DateTimeFormat("en-GB", {
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hour12: false,
});

export interface TracePrinter {
  open(): void;
  event(event: AgentEvent): void;
  answer(answer: string, attributions?: readonly Attribution[]): void;
  error(error: unknown, options?: { readonly debug?: boolean }): void;
}

export function createTracePrinter({
  stream = process.stdout,
  useColor = stream.isTTY && !process.env.NO_COLOR,
}: {
  stream?: NodeJS.WriteStream;
  useColor?: boolean;
} = {}): TracePrinter {
  const color = (code: string, value: string): string => useColor ? `\u001b[${code}m${value}\u001b[0m` : value;
  const muted = (value: string): string => color("2", value);
  const accent = (value: string): string => color("36", value);
  const success = (value: string): string => color("32", value);
  const danger = (value: string): string => color("31", value);
  const write = (value = ""): void => { stream.write(`${value}\n`); };

  const row = (
    event: Pick<AgentEvent, "at">,
    label: string,
    message: string,
    tone: "danger" | "normal" | "success" = "normal",
  ): void => {
    const timestamp = muted(timeFormatter.format(new Date(event.at)));
    const formattedLabel = tone === "success" ? success(`[${label}]`)
      : tone === "danger" ? danger(`[${label}]`)
        : accent(`[${label}]`);
    write(`${timestamp}  ${formattedLabel} ${message}`);
  };

  return {
    open() {
      write(color("1", "WEATHER DEMO · APP AGENT"));
      write(muted("Execution timeline across the model and mobile app"));
      write(muted("─".repeat(64)));
    },

    event(event) {
      switch (event.type) {
        case "agent.started":
          row(event, "USER", event.userMessage);
          row(event, "AGENT", `${providerLabel(event.provider)} session started`);
          break;
        case "model.started":
          row(
            event,
            "THINK",
            event.toolCallCount > 0
              ? `${providerLabel(event.provider)} is reviewing ${event.toolCallCount} tool result(s)`
              : `${providerLabel(event.provider)} is responding`,
          );
          break;
        case "assistant.message":
          row(event, "AGENT", event.text);
          break;
        case "tool.dispatched":
          row(
            event,
            "TOOL",
            `${toolLabel(event.call.name)} · ${formatArguments(event.call.name, event.call.input)} · #${shortId(event.call.id)}`,
          );
          break;
        case "tool.completed":
          row(
            event,
            "MOBILE",
            `${event.call.name.startsWith("mobile_") ? "Android UI execution" : "App execution"} completed · ${formatDuration(event.durationMs)} · ${summarizeResult(event.call.name, event.result.output)}`,
            "success",
          );
          break;
        case "tool.failed":
          row(
            event,
            "TOOL",
            `${toolLabel(event.call.name)} failed · ${event.result.error.code} · ${event.result.error.message}`,
            "danger",
          );
          break;
        case "agent.completed":
          row(
            event,
            "DONE",
            `Completed in ${formatDuration(event.durationMs)} · ${event.toolCallCount} ToolCall(s)`,
            "success",
          );
          break;
        case "agent.failed":
          row(event, "ERROR", `${event.message} · ${formatDuration(event.durationMs)}`, "danger");
          break;
      }
    },

    answer(answer, attributions = []) {
      write(muted("─".repeat(64)));
      write(color("1", "FINAL RESPONSE"));
      write(answer.trim());
      for (const attribution of attributions) {
        write(muted(`${attribution.text}${attribution.license ? ` (${attribution.license})` : ""} · ${attribution.url}`));
      }
    },

    error(error, { debug = false } = {}) {
      if (!debug) return;
      write(danger(error instanceof Error ? error.stack ?? error.message : String(error)));
    },
  };
}

export function toolLabel(tool: string): string {
  return TOOL_LABELS[tool] ?? tool;
}

export function formatArguments(tool: string, args: JsonObject = {}): string {
  if (tool === "weather_get_current") return text(args.location) ?? "Location not specified";
  if (tool === "weather_get_forecast") {
    return `${text(args.location) ?? "Location not specified"} · ${number(args.days) ?? 3} days`;
  }
  if (tool === "weather_compare_locations") {
    return Array.isArray(args.locations) ? args.locations.map(String).join(" ↔ ") : "Locations not specified";
  }
  if (tool === "weather_alert_list") return "All local alert rules";
  if (tool === "weather_alert_create") {
    return `${text(args.location) ?? "Location not specified"} · ${text(args.date) ?? "Date not specified"} · ${text(args.condition) ?? "Condition not specified"}`;
  }
  if (tool === "weather_alert_delete") return text(args.alertId) ?? "Alert not specified";
  if (tool === "mobile_observe") return `${text(args.scope) ?? "all"} semantic context`;
  if (tool === "mobile_screenshot") return shortId(text(args.snapshotId)) || "Current semantic snapshot";
  if (tool === "mobile_tap") return uiTarget(args);
  if (tool === "mobile_long_press") return `${uiTarget(args)} · ${number(args.durationMs) ?? 650}ms`;
  if (tool === "mobile_multi_tap") return `${Array.isArray(args.positions) ? args.positions.length : 0} positions · exact order`;
  if (tool === "mobile_input_text") return `${text(args.nodeId) ?? "Text field"} · ${text(args.text)?.length ?? 0} characters`;
  if (tool === "mobile_swipe") {
    const start = object(args.start);
    const end = object(args.end);
    return `(${number(start.x) ?? "?"}, ${number(start.y) ?? "?"}) → (${number(end.x) ?? "?"}, ${number(end.y) ?? "?"})`;
  }
  if (tool === "mobile_press") return text(args.key) ?? "Restricted key";
  if (tool === "mobile_wait") return `Wait for “${text(args.query) ?? "UI state"}”`;
  return Object.entries(args).map(([key, value]) => `${key}=${String(value)}`).join(", ") || "No arguments";
}

export function summarizeResult(tool: string, value: JsonValue = {}): string {
  const result = object(value);
  if (tool === "weather_get_current") {
    const location = object(result.location);
    const weather = object(result.weather);
    if (location.name && weather.temperatureCelsius !== undefined && weather.condition) {
      return `${String(location.name)} · ${String(weather.temperatureCelsius)}°C · ${String(weather.condition)}`;
    }
  }
  if (tool === "weather_get_forecast") {
    return `${text(object(result.location).name) ?? "Forecast"} · ${number(result.forecastDays) ?? arrayLength(result.days)} days`;
  }
  if (tool === "weather_compare_locations") return `${number(result.locationCount) ?? arrayLength(result.locations)} locations compared`;
  if (tool === "weather_alert_list") return `${number(result.alertCount) ?? arrayLength(result.alerts)} local alerts`;
  if (tool === "weather_alert_create") return `Alert scheduled · ${shortId(text(object(result.alert).alertId))}`;
  if (tool === "weather_alert_delete") return "Local weather alert deleted";
  if (tool === "mobile_observe") return semanticSummary(result);
  if (tool === "mobile_screenshot") {
    const screen = object(result.screen);
    return `Screenshot attached · ${number(screen.width) ?? "?"}×${number(screen.height) ?? "?"}`;
  }
  if (["mobile_tap", "mobile_long_press", "mobile_input_text", "mobile_swipe", "mobile_press"].includes(tool)) {
    return result.performed ? `Action applied · ${semanticSummary(object(result.after))}` : "UI action was not applied";
  }
  if (tool === "mobile_multi_tap") {
    return `${number(result.completed) ?? 0}/${number(result.requested) ?? 0} positions tapped · ${semanticSummary(object(result.after))}`;
  }
  if (tool === "mobile_wait") return `UI condition matched · ${semanticSummary(result)}`;
  return "ToolResult received";
}

function semanticSummary(snapshot: JsonObject): string {
  const content = arrayLength(snapshot.content);
  const controls = arrayLength(snapshot.controls);
  return `${content} readable · ${controls} interactive${snapshot.truncated ? " · truncated" : ""}`;
}

function uiTarget(args: JsonObject): string {
  if (text(args.nodeId)) return text(args.nodeId) as string;
  if (Number.isInteger(args.x) && Number.isInteger(args.y)) return `(${String(args.x)}, ${String(args.y)})`;
  return "UI target";
}

function providerLabel(provider = ""): string {
  if (provider.startsWith("vercel-ai/codex-cli:")) return `Codex ${provider.split(":").at(-1)}`;
  if (provider.startsWith("vercel-ai/github-copilot-sdk:")) return `GitHub Copilot ${provider.split(":").at(-1)}`;
  if (provider.startsWith("claude-agent-sdk:")) return `Claude ${provider.split(":").at(-1)}`;
  return provider;
}

function shortId(value: string | undefined = ""): string {
  return value?.slice(0, 8) ?? "";
}

function formatDuration(value = 0): string {
  if (value < 1_000) return `${value}ms`;
  return `${(value / 1_000).toFixed(1)}s`;
}

function object(value: JsonValue | undefined): JsonObject {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value : {};
}

function text(value: JsonValue | undefined): string | undefined {
  return typeof value === "string" ? value : undefined;
}

function number(value: JsonValue | undefined): number | undefined {
  return typeof value === "number" ? value : undefined;
}

function arrayLength(value: JsonValue | undefined): number {
  return Array.isArray(value) ? value.length : 0;
}
