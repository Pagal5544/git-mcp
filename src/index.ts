
import { McpServer } from "@modelcontextprotocol/server";
import { createMcpHandler } from "agents/mcp/server";
import { z } from "zod";

interface Env {
  GITHUB_TOKEN: string;
  GITHUB_OWNER: string;
  GITHUB_REPO: string;
  MCP_AUTH_TOKEN: string;
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
    throw new Error(
      `GitHub API ${response.status}: ${text.slice(0, 1000)}`
    );
  }

  return text ? JSON.parse(text) : {};
}

function textResult(value: unknown) {
  return {
    content: [
      { type: "text" as const, text: JSON.stringify(value, null, 2) }
    ]
  };
}

function errorResult(error: unknown) {
  return {
    isError: true,
    content: [
      {
        type: "text" as const,
        text: error instanceof Error ? error.message : "Request failed"
      }
    ]
  };
}

function createServer(env: Env) {
  const server = new McpServer({
    name: "git-mcp",
    version: "1.0.0"
  });

  server.registerTool(
    "github_repo_info",
    {
      description: "Read information about the configured GitHub repository.",
      inputSchema: {}
    },
    async () => {
      try {
        return textResult(
          await github(
            env,
            `/repos/${encodeURIComponent(env.GITHUB_OWNER)}/${encodeURIComponent(env.GITHUB_REPO)}`
          )
        );
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "github_list_workflows",
    {
      description: "List workflows in the configured GitHub repository.",
      inputSchema: {}
    },
    async () => {
      try {
        return textResult(
          await github(
            env,
            `/repos/${encodeURIComponent(env.GITHUB_OWNER)}/${encodeURIComponent(env.GITHUB_REPO)}/actions/workflows`
          )
        );
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "github_trigger_workflow",
    {
      description: "Dispatch an existing GitHub Actions workflow by filename.",
      inputSchema: {
        workflow_id: z.string().min(1).max(200),
        ref: z.string().min(1).max(200).default("main")
      }
    },
    async ({ workflow_id, ref }) => {
      try {
        const filename = workflow_id.split("/").pop() ?? "";

        if (!/^[A-Za-z0-9_.-]+\.ya?ml$/i.test(filename) ||
            filename.includes("..")) {
          return errorResult(new Error("Invalid workflow filename."));
        }

        await github(
          env,
          `/repos/${encodeURIComponent(env.GITHUB_OWNER)}/${encodeURIComponent(env.GITHUB_REPO)}/actions/workflows/${encodeURIComponent(filename)}/dispatches`,
          {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ ref })
          }
        );

        return textResult({
          success: true,
          workflow: filename,
          ref
        });
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  server.registerTool(
    "github_recent_workflow_runs",
    {
      description: "List recent GitHub Actions runs.",
      inputSchema: {
        per_page: z.number().int().min(1).max(20).default(5)
      }
    },
    async ({ per_page }) => {
      try {
        return textResult(
          await github(
            env,
            `/repos/${encodeURIComponent(env.GITHUB_OWNER)}/${encodeURIComponent(env.GITHUB_REPO)}/actions/runs?per_page=${per_page}`
          )
        );
      } catch (error) {
        return errorResult(error);
      }
    }
  );

  return server;
}

function isAuthorized(request: Request, env: Env): boolean {
  const expected = env.MCP_AUTH_TOKEN;
  if (!expected || expected.length < 32) return false;

  const authorization = request.headers.get("Authorization") ?? "";
  const supplied = authorization.startsWith("Bearer ")
    ? authorization.slice(7)
    : "";

  if (!supplied || supplied.length !== expected.length) return false;

  let difference = 0;
  for (let i = 0; i < expected.length; i++) {
    difference |= expected.charCodeAt(i) ^ supplied.charCodeAt(i);
  }

  return difference === 0;
}

export default {
  async fetch(
    request: Request,
    env: Env,
    ctx: ExecutionContext
  ): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return Response.json({
        status: "ok",
        service: "git-mcp"
      });
    }

    if (!isAuthorized(request, env)) {
      return Response.json(
        { error: "Unauthorized" },
        {
          status: 401,
          headers: { "Cache-Control": "no-store" }
        }
      );
    }

    return createMcpHandler(() => createServer(env), {
      route: "/mcp"
    })(request, env, ctx);
  }
} satisfies ExportedHandler<Env>;
