import fs from "node:fs";
import path from "node:path";
import { createVitest } from "vitest/node";

const output = process.argv[2];
if (!output) throw new Error("Expected a discovery report path");
const config = path.resolve("test/vitest/vitest.ui-browser.config.ts");
const reports = [];
for (const options of [{ config }, { config: false, projects: [config] }]) {
  const ctx = await createVitest({
    ...options,
    project: ["chromium"],
    watch: false,
    reporters: [],
    configLoader: "runner",
    api: { port: 0, strictPort: true },
  });
  try {
    const specifications = await ctx.globTestSpecifications();
    reports.push({
      projects: ctx.projects.map((project) => ({
        name: project.name,
        root: project.config.root,
        setupFiles: project.config.setupFiles,
      })),
      files: specifications.map((specification) => specification.moduleId).sort(),
    });
  } finally {
    await ctx.close();
  }
}
fs.writeFileSync(output, JSON.stringify(reports));
