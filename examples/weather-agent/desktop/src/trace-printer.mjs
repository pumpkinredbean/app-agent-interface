const TOOL_LABELS = {
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

export function createTracePrinter({ stream = process.stdout, useColor = stream.isTTY && !process.env.NO_COLOR } = {}) {
  const color = (code, value) => useColor ? `\u001b[${code}m${value}\u001b[0m` : value;
  const muted = (value) => color("2", value);
  const accent = (value) => color("36", value);
  const success = (value) => color("32", value);
  const danger = (value) => color("31", value);
  const write = (value = "") => stream.write(`${value}\n`);

  const row = (event, label, message, tone = "normal") => {
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
            `${toolLabel(event.call.name)} failed · ${event.result.error?.code ?? "TOOL_CALL_FAILED"} · ${event.result.error?.message ?? "Unknown app error"}`,
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
      if (debug) write(danger(error.stack ?? error.message));
    },
  };
}

export function toolLabel(tool) {
  return TOOL_LABELS[tool] ?? tool;
}

export function formatArguments(tool, args = {}) {
  if (tool === "weather_get_current") return args.location ?? "Location not specified";
  if (tool === "weather_get_forecast") {
    return `${args.location ?? "Location not specified"} · ${args.days ?? 3} days`;
  }
  if (tool === "weather_compare_locations") {
    return Array.isArray(args.locations) ? args.locations.join(" ↔ ") : "Locations not specified";
  }
  if (tool === "weather_alert_list") return "All local alert rules";
  if (tool === "weather_alert_create") {
    return `${args.location ?? "Location not specified"} · ${args.date ?? "Date not specified"} · ${args.condition ?? "Condition not specified"}`;
  }
  if (tool === "weather_alert_delete") return args.alertId ?? "Alert not specified";
  if (tool === "mobile_observe") return `${args.scope ?? "all"} semantic context`;
  if (tool === "mobile_screenshot") return shortId(args.snapshotId) || "Current semantic snapshot";
  if (tool === "mobile_tap") return uiTarget(args);
  if (tool === "mobile_long_press") return `${uiTarget(args)} · ${args.durationMs ?? 650}ms`;
  if (tool === "mobile_multi_tap") {
    return `${Array.isArray(args.positions) ? args.positions.length : 0} positions · exact order`;
  }
  if (tool === "mobile_input_text") {
    return `${args.nodeId ?? "Text field"} · ${String(args.text ?? "").length} characters`;
  }
  if (tool === "mobile_swipe") {
    return `(${args.start?.x ?? "?"}, ${args.start?.y ?? "?"}) → (${args.end?.x ?? "?"}, ${args.end?.y ?? "?"})`;
  }
  if (tool === "mobile_press") return args.key ?? "Restricted key";
  if (tool === "mobile_wait") return `Wait for “${args.query ?? "UI state"}”`;
  return Object.entries(args).map(([key, value]) => `${key}=${String(value)}`).join(", ") || "No arguments";
}

export function summarizeResult(tool, result = {}) {
  if (tool === "weather_get_current" && result.location && result.weather) {
    return `${result.location.name} · ${result.weather.temperatureCelsius}°C · ${result.weather.condition}`;
  }
  if (tool === "weather_get_forecast") {
    return `${result.location?.name ?? "Forecast"} · ${result.forecastDays ?? result.days?.length ?? 0} days`;
  }
  if (tool === "weather_compare_locations") return `${result.locationCount ?? result.locations?.length ?? 0} locations compared`;
  if (tool === "weather_alert_list") return `${result.alertCount ?? result.alerts?.length ?? 0} local alerts`;
  if (tool === "weather_alert_create") return `Alert scheduled · ${shortId(result.alert?.alertId)}`;
  if (tool === "weather_alert_delete") return "Local weather alert deleted";
  if (tool === "mobile_observe") return semanticSummary(result);
  if (tool === "mobile_screenshot") {
    return `Screenshot attached · ${result.screen?.width ?? "?"}×${result.screen?.height ?? "?"}`;
  }
  if (["mobile_tap", "mobile_long_press", "mobile_input_text", "mobile_swipe", "mobile_press"].includes(tool)) {
    return result.performed ? `Action applied · ${semanticSummary(result.after)}` : "UI action was not applied";
  }
  if (tool === "mobile_multi_tap") {
    return `${result.completed ?? 0}/${result.requested ?? 0} positions tapped · ${semanticSummary(result.after)}`;
  }
  if (tool === "mobile_wait") return `UI condition matched · ${semanticSummary(result)}`;
  return "ToolResult received";
}

function semanticSummary(snapshot = {}) {
  const content = Array.isArray(snapshot.content) ? snapshot.content.length : 0;
  const controls = Array.isArray(snapshot.controls) ? snapshot.controls.length : 0;
  return `${content} readable · ${controls} interactive${snapshot.truncated ? " · truncated" : ""}`;
}

function uiTarget(args = {}) {
  if (args.nodeId) return args.nodeId;
  if (Number.isInteger(args.x) && Number.isInteger(args.y)) return `(${args.x}, ${args.y})`;
  return "UI target";
}

function providerLabel(provider = "") {
  if (provider.startsWith("vercel-ai/codex-cli:")) return `Codex ${provider.split(":").at(-1)}`;
  if (provider.startsWith("vercel-ai/github-copilot-sdk:")) return `GitHub Copilot ${provider.split(":").at(-1)}`;
  if (provider.startsWith("claude-agent-sdk:")) return `Claude ${provider.split(":").at(-1)}`;
  return provider;
}

function shortId(value = "") {
  return value.slice(0, 8);
}

function formatDuration(value = 0) {
  if (value < 1_000) return `${value}ms`;
  return `${(value / 1_000).toFixed(1)}s`;
}
