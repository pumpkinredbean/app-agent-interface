#!/usr/bin/env node

import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, relative, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const ignoredDirectories = new Set([".git", ".gradle", "build", "dist", "node_modules"]);
const failures = [];

function walk(directory, predicate) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    if (ignoredDirectories.has(entry.name)) return [];
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) return walk(path, predicate);
    return predicate(path) ? [path] : [];
  });
}

function text(path) {
  return readFileSync(path, "utf8");
}

function fail(message) {
  failures.push(message);
}

for (const markdown of walk(root, (path) => path.endsWith(".md"))) {
  const contents = text(markdown);
  const links = contents.matchAll(/!?\[[^\]]*\]\(([^)]+)\)/g);
  for (const match of links) {
    let target = match[1].trim().replace(/^<|>$/g, "");
    if (/^(?:https?:|mailto:|#)/.test(target)) continue;
    target = target.split("#", 1)[0];
    if (!target) continue;
    const destination = resolve(dirname(markdown), decodeURIComponent(target));
    if (!existsSync(destination)) {
      const line = contents.slice(0, match.index).split("\n").length;
      fail(`${relative(root, markdown)}:${line} links to missing ${target}`);
    }
  }
}

const rootReadme = text(resolve(root, "README.md"));
const settings = text(resolve(root, "settings.gradle.kts"));
for (const match of settings.matchAll(/projectDir = file\("(packages\/[^"]+)"\)/g)) {
  const layoutEntry = `${match[1].replace("packages/", "")}/`;
  if (!rootReadme.includes(layoutEntry)) fail(`README repository layout omits ${match[1]}/`);
}
for (const entry of readdirSync(resolve(root, "examples"), { withFileTypes: true })) {
  if (entry.isDirectory() && !rootReadme.includes(`examples/${entry.name}/`)) {
    fail(`README repository layout omits examples/${entry.name}/`);
  }
}

const rootBuild = text(resolve(root, "build.gradle.kts"));
const developmentVersion = rootBuild.match(/getOrElse\("([0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT)"\)/)?.[1];
if (!developmentVersion) fail("Cannot find the root Kotlin -SNAPSHOT development version");

if (developmentVersion) {
  const exampleBuilds = walk(resolve(root, "examples"), (path) => /build\.gradle(?:\.kts)?$/.test(path));
  for (const build of exampleBuilds) {
    for (const match of text(build).matchAll(/io\.github\.pumpkinredbean:[^:"]+:([^"]+)/g)) {
      if (match[1] !== developmentVersion) {
        fail(`${relative(root, build)} uses ${match[1]} instead of ${developmentVersion}`);
      }
    }
  }
  const kotlinGuide = text(resolve(root, "docs/sdk/kotlin-sdk.md"));
  if (!kotlinGuide.includes(`\`${developmentVersion}\``)) {
    fail(`docs/sdk/kotlin-sdk.md does not state development version ${developmentVersion}`);
  }
}

const changelog = text(resolve(root, "CHANGELOG.md"));
if (!changelog.includes("## Unreleased")) fail("CHANGELOG.md has no Unreleased section");
const latestRelease = changelog.match(/^## ([0-9]+\.[0-9]+\.[0-9]+) - /m)?.[1];
if (!latestRelease) {
  fail("CHANGELOG.md has no dated release");
} else {
  for (const path of ["README.md", "docs/sdk/kotlin-sdk.md", "docs/sdk/distribution.md"]) {
    if (!text(resolve(root, path)).includes(`\`${latestRelease}\``)) {
      fail(`${path} does not reference latest release ${latestRelease}`);
    }
  }
}

if (failures.length > 0) {
  process.stderr.write(`${failures.map((failure) => `- ${failure}`).join("\n")}\n`);
  process.exit(1);
}

process.stdout.write(`Documentation checks passed (${developmentVersion}; latest release ${latestRelease}).\n`);
