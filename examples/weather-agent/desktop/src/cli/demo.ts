#!/usr/bin/env node

import { runAgent } from "../agent/run-agent.js";
import { createTracePrinter } from "./trace-printer.js";

const args = process.argv.slice(2);
const jsonOnly = args.includes("--json");
const debug = args.includes("--debug");
const providerFlag = args.find((arg) => arg.startsWith("--provider="));
const modelFlag = args.find((arg) => arg.startsWith("--model="));
const userMessage = args.filter((arg) => !arg.startsWith("--")).join(" ")
  || "What is the weather in Seoul right now?";
const printer = jsonOnly ? null : createTracePrinter();

try {
  printer?.open();
  const trace = await runAgent(userMessage, {
    ...(providerFlag?.split("=", 2)[1] ? { providerName: providerFlag.split("=", 2)[1]! } : {}),
    ...(modelFlag?.split("=", 2)[1] ? { model: modelFlag.split("=", 2)[1]! } : {}),
    onEvent: (event) => printer?.event(event),
  });
  if (jsonOnly) {
    console.log(JSON.stringify(trace, null, 2));
  } else {
    printer?.answer(trace.answer, trace.attributions);
  }
} catch (error) {
  if (jsonOnly) console.error(`[agent-error] ${error instanceof Error ? error.stack ?? error.message : String(error)}`);
  else printer?.error(error, { debug });
  process.exitCode = 1;
}
