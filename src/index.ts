
import { McpAgent } from "agents/mcp";
import { z } from "zod";

interface Env {
  GITHUB_TOKEN: string;
  GITHUB_OWNER: string;
  GITHUB_REPO: string;
  MCP_AUTH_TOKEN: string;
}

type ToolResult = {
  content: Array<{ type: "text"; text: string }>;
  isError?: boolean;
};

function result(value: unknown): ToolResult {
  return {
    content: [{ type: "text", text: JSON.stringify(value, null, 2) }]
  };
}

function errorResult(message: string): ToolResult {
  return {
    content: [{ type: "text", text: message }],
    isError: true
  };
}

async function github(
  env: Env,
  path: string,
  init: RequestInit = {}
): Promise<unknown> {
  const response = await fetch(`https://api.github.com${path}`, {
    ...init,
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${env.GITHUB_TOKEN}`,
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "git-mcp-bridge",
      ...(init.headers || {})
    }
  });

  const text = await response.text();

  if (!response.ok) {
    throw new Error(`GitHub API ${response.status}: ${text.slice(0, 1200)}`);
  }

  return text ? JSON.parse(text) : {};
}

export class GitMcpAgent extends McpAgent {
  server = this.createMcpServer({
    name: "git-mcp",
    version: "1.0.0"
  });

  async init() {
    const env = this.env as unknown as Env;

    this.server.tool(
      "github_repo_info",
      "Read basic information about the configured GitHub repository.",
      {},
      async () => {
        try {
          const data = await github(
            env,
            `/repos/${encodeURIComponent(env.GITHUB_OWNER)}/${encodeURIComponent(env.GITHUB_REPO)}`
          );

          return result(data);
        } catch (error) {
          return errorResult(
            error instanceof Error ? error.message : "GitHub request failed"
          );
        }
      }
    );

    this.server.tool(
      "github_list_workflows",
      "List GitHub Actions workflows in the configured repository.",
      {},
      async () => {
        try {
          const data = await github(
            env,
            `/repos/${encodeURIComponent(env.GITHUB_OWNER)}/${encodeURIComponent(env.GITHUB_REPO)}/actions/workflows`
          );

          return result(data);
        } catch (error) {
          return errorResult(
            error instanceof Error ? error.message : "GitHub request failed"
          );
        }
      }
    );

    this.server.tool(
      "github_trigger_workflow",
      "Dispatch an existing workflow by filename. Only use a workflow filename the repository actually contains.",
      {
        workflow_id: z.string().min(1).max(200),
        ref: z.string().min(1).max(200).default("main")
      },
      async ({ workflow_id, ref }) => {
        try {
          const safeWorkflow = workflow_id.split("/").pop() || "";

          if (
            safeWorkflow.includes("..") ||
            !/^[A-Za-z0-9_.-]+\.ya?ml$/i.test(safeWorkflow)
          ) {
            return errorResult("Invalid workflow filename.");
          }

          const body = {
            ref,
            inputs: {}
          };

          await github(
            env,
            `/repos/${encodeURIComponent(env.GITHUB_OWNER)}/${encodeURIComponent(env.GITHUB_REPO)}/actions/workflows/${encodeURIComponent(safeWorkflow)}/dispatches`,
            {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify(body)
            }
          );

          return result({
            success: true,
            workflow: safeWorkflow,
            ref,
            message: "Workflow dispatch request accepted."
          });
        } catch (error) {
          return errorResult(
            error instanceof Error ? error.message : "Workflow dispatch failed"
          );
        }
      }
    );

    this.server.tool(
      "github_recent_workflow_runs",
      "List recent GitHub Actions runs.",
      {
        per_page: z.number().int().min(1).max(20).default(5)
      },
      async ({ per_page }) => {
        try {
          const data = await github(
            env,
            `/repos/${encodeURIComponent(env.GITHUB_OWNER)}/${encodeURIComponent(env.GITHUB_REPO)}/actions/runs?per_page=${per_page}`
          );

          return result(data);
        } catch (error) {
          return errorResult(
            error instanceof Error ? error.message : "Could not list workflow runs"
          );
        }
      }
    );
  }
}

function isAuthorized(request: Request, env: Env): boolean {
  const expected = env.MCP_AUTH_TOKEN;
  if (!expected || expected.length < 32) return false;

  const authorization = request.headers.get("Authorization") || "";
  const supplied = authorization.startsWith("Bearer ")
    ? authorization.slice(7)
    : "";

  if (!supplied || supplied.length !== expected.length) return false;

  let diff = 0;
  for (let i = 0; i < expected.length; i++) {
    diff |= expected.charCodeAt(i) ^ supplied.charCodeAt(i);
  }
  return diff === 0;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return Response.json({ status: "ok", service: "git-mcp" });
    }

    if (!isAuthorized(request, env)) {
      return Response.json(
        { error: "Unauthorized. Supply a valid bearer token." },
        {
          status: 401,
          headers: { "Cache-Control": "no-store" }
        }
      );
    }

    return GitMcpAgent.serve("/mcp").fetch(request, env);
  }
};
