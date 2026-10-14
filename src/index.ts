import OAuthProvider from "@cloudflare/workers-oauth-provider";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { McpAgent } from "agents/mcp";
import { z } from "zod";
import type { Env } from "./types";
import { GitHubHandler } from "./github-handler";

export class GitMcp extends McpAgent<Env, Record<string, never>, Record<string, never>> {
  server = new McpServer({ name: "git-mcp", version: "1.0.0" });

  async init() {
    const headers = {
      Authorization: `Bearer ${this.env.GITHUB_TOKEN}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "git-mcp"
    };
    this.server.tool("github_repo_info", "Get configured repository information", {}, async () => {
      const r = await fetch(`https://api.github.com/repos/${this.env.GITHUB_OWNER}/${this.env.GITHUB_REPO}`, { headers });
      if (!r.ok) return { content: [{ type: "text" as const, text: `GitHub API error ${r.status}: ${await r.text()}` }], isError: true };
      return { content: [{ type: "text" as const, text: JSON.stringify(await r.json(), null, 2) }] };
    });
    this.server.tool("github_list_workflows", "List GitHub Actions workflows", {}, async () => {
      const r = await fetch(`https://api.github.com/repos/${this.env.GITHUB_OWNER}/${this.env.GITHUB_REPO}/actions/workflows`, { headers });
      if (!r.ok) return { content: [{ type: "text" as const, text: `GitHub API error ${r.status}: ${await r.text()}` }], isError: true };
      const d: any = await r.json();
      return { content: [{ type: "text" as const, text: JSON.stringify(d.workflows, null, 2) }] };
    });
    this.server.tool("github_trigger_workflow", "Trigger a workflow_dispatch event", {
      workflow_id: z.string().min(1), ref: z.string().min(1)
    }, async ({ workflow_id, ref }) => {
      const r = await fetch(`https://api.github.com/repos/${this.env.GITHUB_OWNER}/${this.env.GITHUB_REPO}/actions/workflows/${encodeURIComponent(workflow_id)}/dispatches`, {
        method: "POST", headers: { ...headers, "Content-Type": "application/json" }, body: JSON.stringify({ ref })
      });
      if (r.status !== 204) return { content: [{ type: "text" as const, text: `Dispatch failed ${r.status}: ${await r.text()}` }], isError: true };
      return { content: [{ type: "text" as const, text: `Workflow ${workflow_id} triggered on ${ref}` }] };
    });
    this.server.tool("github_recent_workflow_runs", "List recent workflow runs", {
      per_page: z.number().int().min(1).max(100).optional()
    }, async ({ per_page }) => {
      const r = await fetch(`https://api.github.com/repos/${this.env.GITHUB_OWNER}/${this.env.GITHUB_REPO}/actions/runs?per_page=${per_page ?? 10}`, { headers });
      if (!r.ok) return { content: [{ type: "text" as const, text: `GitHub API error ${r.status}: ${await r.text()}` }], isError: true };
      const d: any = await r.json();
      return { content: [{ type: "text" as const, text: JSON.stringify(d.workflow_runs, null, 2) }] };
    });
  }
}

export default new OAuthProvider({
  apiHandler: GitMcp.serve("/mcp"),
  apiRoute: "/mcp",
  authorizeEndpoint: "/authorize",
  clientRegistrationEndpoint: "/register",
  defaultHandler: GitHubHandler as any,
  tokenEndpoint: "/token"
});
