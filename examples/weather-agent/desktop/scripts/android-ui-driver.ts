#!/usr/bin/env node

import { existsSync } from "node:fs";
import { spawn, spawnSync } from "node:child_process";
import { homedir } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, "../../../..");
const androidProject = resolve(repositoryRoot, "examples/weather-agent/android");
const driverProject = resolve(repositoryRoot, "drivers/android-uiautomator2");
const gradlew = resolve(androidProject, process.platform === "win32" ? "gradlew.bat" : "gradlew");
const driverBuild = resolve(repositoryRoot, "drivers/android-uiautomator2/host/build/outputs/apk");
const hostApk = resolve(driverBuild, "debug/host-debug.apk");
const testApk = resolve(driverBuild, "androidTest/debug/host-debug-androidTest.apk");
const instrumentation = "io.github.pumpkinredbean.appagent.uidriver.test/androidx.test.runner.AndroidJUnitRunner";
const driverPackages = ["io.github.pumpkinredbean.appagent.uidriver.test", "io.github.pumpkinredbean.appagent.uidriver"];

const { command, options } = parseArguments(process.argv.slice(2));
const androidHome = options.androidHome
  ?? process.env.ANDROID_HOME
  ?? process.env.ANDROID_SDK_ROOT
  ?? [
    resolve(homedir(), "Library/Android/sdk"),
    resolve(homedir(), "Android/Sdk"),
    "/opt/homebrew/share/android-commandlinetools",
  ].find(existsSync);
const adb = options.adb
  ?? process.env.ADB
  ?? (androidHome && existsSync(resolve(androidHome, "platform-tools/adb"))
    ? resolve(androidHome, "platform-tools/adb")
    : "adb");
const serial = options.serial ?? process.env.ANDROID_SERIAL;
const targetPackage = options.target ?? process.env.ANDROID_TARGET_PACKAGE;
const hostPort = integer(options.hostPort ?? process.env.ANDROID_UI_HOST_PORT ?? "18766", "host port");
const devicePort = integer(options.devicePort ?? process.env.ANDROID_UI_DEVICE_PORT ?? "8766", "device port");

try {
  switch (command) {
    case "build":
      build();
      break;
    case "install":
      install();
      break;
    case "start":
      await start();
      break;
    case "status":
      await status();
      break;
    case "stop":
      await stop();
      break;
    default:
      usage();
      process.exitCode = command === "help" ? 0 : 1;
  }
} catch (error) {
  console.error(`Android UI driver: ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
}

function build() {
  if (!androidHome) throw new Error("Android SDK not found; set ANDROID_HOME or pass --android-home=<path>");
  run(gradlew, [":host:assembleDebug", ":host:assembleDebugAndroidTest"], {
    cwd: driverProject,
    env: { ...process.env, ANDROID_HOME: androidHome, ANDROID_SDK_ROOT: androidHome },
  });
  console.log(`Built ${hostApk}`);
  console.log(`Built ${testApk}`);
}

function install() {
  if (!existsSync(hostApk) || !existsSync(testApk)) build();
  runAdb(["install", "-r", hostApk]);
  runAdb(["install", "-r", testApk]);
  console.log("UiAutomator2 driver APKs installed.");
}

async function start() {
  if (!targetPackage) {
    throw new Error("--target=<application-id> or ANDROID_TARGET_PACKAGE is required");
  }
  const packageCheck = runAdb(["shell", "pm", "path", targetPackage], { capture: true });
  if (!packageCheck.stdout.includes("package:")) throw new Error(`Target package is not installed: ${targetPackage}`);

  removeForward();
  runAdb(["forward", `tcp:${hostPort}`, `tcp:${devicePort}`], { capture: true });
  for (const packageName of driverPackages) {
    runAdb(["shell", "am", "force-stop", packageName], { allowFailure: true, capture: true });
  }

  console.log(`Target app scope: ${targetPackage}`);
  console.log(`Bridge: http://127.0.0.1:${hostPort}`);
  console.log("Keep this process running. Press Ctrl+C to stop the driver.");

  const child = spawn(adb, [
    ...serialArguments(),
    "shell",
    "am",
    "instrument",
    "-w",
    "-r",
    "-e",
    "targetPackage",
    targetPackage,
    "-e",
    "port",
    String(devicePort),
    instrumentation,
  ], { stdio: "inherit" });

  const shutdown = async () => {
    await requestShutdown().catch(() => {});
    if (!child.killed) child.kill("SIGTERM");
    removeForward();
  };
  process.once("SIGINT", () => shutdown().finally(() => process.exit(130)));
  process.once("SIGTERM", () => shutdown().finally(() => process.exit(143)));

  const exitCode = await new Promise((resolveExit, reject) => {
    child.once("error", reject);
    child.once("exit", (code) => resolveExit(code ?? 1));
  });
  removeForward();
  if (exitCode !== 0) throw new Error(`instrumentation exited with status ${exitCode}`);
}

async function status() {
  const response = await fetch(`http://127.0.0.1:${hostPort}/healthz`, {
    signal: AbortSignal.timeout(2_000),
  });
  const body = await response.json();
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${JSON.stringify(body)}`);
  console.log(JSON.stringify(body, null, 2));
}

async function stop() {
  await requestShutdown().catch(() => {});
  for (const packageName of driverPackages) {
    runAdb(["shell", "am", "force-stop", packageName], { allowFailure: true, capture: true });
  }
  removeForward();
  console.log("UiAutomator2 driver stopped.");
}

async function requestShutdown() {
  await fetch(`http://127.0.0.1:${hostPort}/shutdown`, {
    method: "POST",
    signal: AbortSignal.timeout(2_000),
  });
}

function removeForward() {
  runAdb(["forward", "--remove", `tcp:${hostPort}`], { allowFailure: true, capture: true });
}

interface CommandOptions {
  readonly cwd?: string;
  readonly env?: NodeJS.ProcessEnv;
  readonly capture?: boolean;
  readonly allowFailure?: boolean;
}

function runAdb(args: readonly string[], options: CommandOptions = {}) {
  return run(adb, [...serialArguments(), ...args], options);
}

function serialArguments(): string[] {
  return serial ? ["-s", serial] : [];
}

function run(
  executable: string,
  args: readonly string[],
  { cwd, env, capture = false, allowFailure = false }: CommandOptions = {},
) {
  const result = spawnSync(executable, args, {
    cwd,
    env,
    encoding: "utf8",
    stdio: capture ? "pipe" : "inherit",
  });
  if (result.error) throw result.error;
  if (result.status !== 0 && !allowFailure) {
    const details = capture ? (result.stderr || result.stdout).trim() : "";
    throw new Error(`${executable} exited with status ${result.status}${details ? `: ${details}` : ""}`);
  }
  return result;
}

function parseArguments(args: readonly string[]): { command: string; options: Record<string, string> } {
  const parsed: { command: string; options: Record<string, string> } = {
    command: args[0] ?? "help",
    options: {},
  };
  for (const argument of args.slice(1)) {
    const match = argument.match(/^--([a-zA-Z][a-zA-Z0-9-]*)=(.*)$/);
    if (!match) throw new Error(`Invalid option: ${argument}`);
    const key = match[1]!.replace(/-([a-z])/g, (_match, letter: string) => letter.toUpperCase());
    parsed.options[key] = match[2] ?? "";
  }
  return parsed;
}

function integer(value: string, label: string): number {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65_535) throw new Error(`Invalid ${label}: ${value}`);
  return parsed;
}

function usage(): void {
  console.log(`Usage:
  npm run ui:driver:build
  npm run ui:driver:install -- --serial=<adb-serial>
  npm run ui:driver -- start --target=<application-id> [--serial=<adb-serial>]
  npm run ui:driver:status
  npm run ui:driver:stop

Environment alternatives:
  ANDROID_TARGET_PACKAGE, ANDROID_SERIAL, ANDROID_HOME, ANDROID_UI_HOST_PORT, ANDROID_UI_DEVICE_PORT, ADB`);
}
