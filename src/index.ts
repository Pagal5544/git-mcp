
import OAuthProvider from "@cloudflare/workers-oauth-provider";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { McpAgent } from "agents/mcp";
import { z } from "zod";
import type { Env } from "./types";
import { GitHubHandler } from "./github-handler";

export class GitMcp extends McpAgent<
  Env,
  Record<string, never>,
  Record<string, never>
> {
  server = new McpServer({
    name: "git-mcp",
    version: "2.0.0",
  });

  async init() {
    const base = `https://api.github.com/repos/${this.env.GITHUB_OWNER}/${this.env.GITHUB_REPO}`;

    const headers = {
      Authorization: `Bearer ${this.env.GITHUB_TOKEN}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "git-mcp",
    };

    // Common GitHub API helper
    const api = async (path: string, init: RequestInit = {}) => {
      return fetch(`https://api.github.com${path}`, {
        ...init,
        headers: {
          ...headers,
          ...(init.headers as Record<string, string> | undefined),
        },
      });
    };

    const repoApi = (path: string, init: RequestInit = {}) =>
      api(
        `/repos/${this.env.GITHUB_OWNER}/${this.env.GITHUB_REPO}${path}`,
        init,
      );

    const result = (value: unknown) => ({
      content: [
        {
          type: "text" as const,
          text:
            typeof value === "string"
              ? value
              : JSON.stringify(value, null, 2),
        },
      ],
    });

    const fail = (status: number, body: string) => ({
      content: [
        {
          type: "text" as const,
          text: `GitHub API error ${status}: ${body}`,
        },
      ],
      isError: true,
    });

    const readJson = async (r: Response) => {
      const body = await r.text();
      if (!r.ok) return { error: fail(r.status, body) };
      try {
        return { data: JSON.parse(body) as any };
      } catch {
        return { error: fail(r.status, body || "Invalid JSON response") };
      }
    };

    // Encode each file-path segment while preserving directory separators.
    const encodePath = (path: string) =>
      path
        .split("/")
        .filter(Boolean)
        .map((part) => encodeURIComponent(part))
        .join("/");

    // UTF-8-safe base64 encoding for GitHub Contents API.
    const encodeBase64 = (value: string) => {
      const bytes = new TextEncoder().encode(value);
      let binary = "";
      const chunkSize = 8192;

      for (let i = 0; i < bytes.length; i += chunkSize) {
        binary += String.fromCharCode(
          ...bytes.subarray(i, i + chunkSize),
        );
      }

      return btoa(binary);
    };

    const decodeBase64 = (value: string) => {
      const binary = atob(value.replace(/\s/g, ""));
      const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
      return new TextDecoder().decode(bytes);
    };

    const getFile = async (path: string, ref?: string) => {
      const query = ref ? `?ref=${encodeURIComponent(ref)}` : "";
      return repoApi(
        `/contents/${encodePath(path)}${query}`,
      );
    };

    // --------------------------------------------------
    // REPOSITORY INFORMATION
    // --------------------------------------------------

    this.server.tool(
      "github_repo_info",
      "Get information about the configured GitHub repository",
      {},
      async () => {
        const r = await repoApi("");
        const j = await readJson(r);
        return j.error ?? result(j.data);
      },
    );

    this.server.tool(
      "github_get_file",
      "Read a file from the configured repository and branch",
      {
        path: z.string().min(1),
        ref: z.string().optional(),
      },
      async ({ path, ref }) => {
        const r = await getFile(path, ref);
        const j = await readJson(r);
        if (j.error) return j.error;

        if (j.data.type !== "file") {
          return fail(400, "The specified path is not a file.");
        }

        return result({
          path: j.data.path,
          sha: j.data.sha,
          size: j.data.size,
          html_url: j.data.html_url,
          content: decodeBase64(j.data.content ?? ""),
        });
      },
    );

    this.server.tool(
      "github_list_directory",
      "List files and directories in a repository path",
      {
        path: z.string().optional(),
        ref: z.string().optional(),
      },
      async ({ path, ref }) => {
        const p = path ? `/contents/${encodePath(path)}` : "/contents";
        const query = ref ? `?ref=${encodeURIComponent(ref)}` : "";
        const r = await repoApi(`${p}${query}`);
        const j = await readJson(r);
        if (j.error) return j.error;

        const items = Array.isArray(j.data) ? j.data : [j.data];

        return result(
          items.map((item: any) => ({
            name: item.name,
            path: item.path,
            type: item.type,
            size: item.size,
            sha: item.sha,
            html_url: item.html_url,
          })),
        );
      },
    );

    // --------------------------------------------------
    // CREATE / UPDATE / DELETE FILES
    // Each operation creates a GitHub commit.
    // --------------------------------------------------

    this.server.tool(
      "github_create_file",
      "Create a new file and commit it to a branch",
      {
        path: z.string().min(1),
        content: z.string(),
        message: z.string().min(1),
        branch: z.string().min(1),
      },
      async ({ path, content, message, branch }) => {
        const r = await repoApi(
          `/contents/${encodePath(path)}`,
          {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              message,
              content: encodeBase64(content),
              branch,
            }),
          },
        );

        const j = await readJson(r);
        return j.error
          ? j.error
          : result({
              success: true,
              operation: "create",
              path: j.data.content?.path,
              commit: j.data.commit?.sha,
              commit_url: j.data.commit?.html_url,
            });
      },
    );

    this.server.tool(
      "github_update_file",
      "Update an existing file and commit the changes",
      {
        path: z.string().min(1),
        content: z.string(),
        message: z.string().min(1),
        branch: z.string().min(1),
      },
      async ({ path, content, message, branch }) => {
        // Retrieve the current SHA required by GitHub.
        const existing = await getFile(path, branch);
        const current = await readJson(existing);
        if (current.error) return current.error;

        if (current.data.type !== "file") {
          return fail(400, "The specified path is not a file.");
        }

        const r = await repoApi(
          `/contents/${encodePath(path)}`,
          {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              message,
              content: encodeBase64(content),
              sha: current.data.sha,
              branch,
            }),
          },
        );

        const j = await readJson(r);
        return j.error
          ? j.error
          : result({
              success: true,
              operation: "update",
              path: j.data.content?.path,
              commit: j.data.commit?.sha,
              commit_url: j.data.commit?.html_url,
            });
      },
    );

    this.server.tool(
      "github_delete_file",
      "Delete an existing file and commit the deletion",
      {
        path: z.string().min(1),
        message: z.string().min(1),
        branch: z.string().min(1),
      },
      async ({ path, message, branch }) => {
        const existing = await getFile(path, branch);
        const current = await readJson(existing);
        if (current.error) return current.error;

        if (current.data.type !== "file") {
          return fail(400, "The specified path is not a file.");
        }

        const r = await repoApi(
          `/contents/${encodePath(path)}`,
          {
            method: "DELETE",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              message,
              sha: current.data.sha,
              branch,
            }),
          },
        );

        const j = await readJson(r);
        return j.error
          ? j.error
          : result({
              success: true,
              operation: "delete",
              path,
              commit: j.data.commit?.sha,
              commit_url: j.data.commit?.html_url,
            });
      },
    );

    // --------------------------------------------------
    // BRANCHES
    // --------------------------------------------------

    this.server.tool(
      "github_list_branches",
      "List branches in the configured repository",
      {
        per_page: z.number().int().min(1).max(100).optional(),
      },
      async ({ per_page }) => {
        const r = await repoApi(
          `/branches?per_page=${per_page ?? 30}`,
        );
        const j = await readJson(r);
        if (j.error) return j.error;

        return result(
          j.data.map((b: any) => ({
            name: b.name,
            commit_sha: b.commit?.sha,
            protected: b.protected,
          })),
        );
      },
    );

    this.server.tool(
      "github_create_branch",
      "Create a new branch from an existing branch",
      {
        branch_name: z.string().min(1),
        base_branch: z.string().min(1),
      },
      async ({ branch_name, base_branch }) => {
        if (
          branch_name.startsWith("/") ||
          branch_name.endsWith("/") ||
          branch_name.includes("..") ||
          /\s/.test(branch_name)
        ) {
          return fail(400, "Invalid branch name.");
        }

        const source = await repoApi(
          `/git/ref/heads/${encodeURIComponent(base_branch)}`,
        );
        const sourceJson = await readJson(source);
        if (sourceJson.error) return sourceJson.error;

        const r = await repoApi("/git/refs", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            ref: `refs/heads/${branch_name}`,
            sha: sourceJson.data.object.sha,
          }),
        });

        const j = await readJson(r);
        return j.error
          ? j.error
          : result({
              success: true,
              branch: j.data.ref,
              sha: j.data.object?.sha,
            });
      },
    );

    // --------------------------------------------------
    // PULL REQUESTS
    // --------------------------------------------------

    this.server.tool(
      "github_create_pull_request",
      "Create a pull request between two branches",
      {
        title: z.string().min(1),
        head: z.string().min(1),
        base: z.string().min(1),
        body: z.string().optional(),
        draft: z.boolean().optional(),
      },
      async ({ title, head, base: targetBase, body, draft }) => {
        const r = await repoApi("/pulls", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            title,
            head,
            base: targetBase,
            body: body ?? "",
            draft: draft ?? false,
          }),
        });

        const j = await readJson(r);
        return j.error
          ? j.error
          : result({
              success: true,
              number: j.data.number,
              title: j.data.title,
              state: j.data.state,
              html_url: j.data.html_url,
              head: j.data.head?.ref,
              base: j.data.base?.ref,
            });
      },
    );

    // --------------------------------------------------
    // ISSUES
    // --------------------------------------------------

    this.server.tool(
      "github_list_issues",
      "List repository issues",
      {
        state: z.enum(["open", "closed", "all"]).optional(),
        per_page: z.number().int().min(1).max(100).optional(),
      },
      async ({ state, per_page }) => {
        const r = await repoApi(
          `/issues?state=${state ?? "open"}&per_page=${per_page ?? 20}`,
        );
        const j = await readJson(r);
        if (j.error) return j.error;

        // GitHub's issues endpoint can also return pull requests.
        return result(
          j.data
            .filter((item: any) => !item.pull_request)
            .map((item: any) => ({
              number: item.number,
              title: item.title,
              state: item.state,
              html_url: item.html_url,
              user: item.user?.login,
              labels: item.labels?.map((label: any) => label.name),
            })),
        );
      },
    );

    this.server.tool(
      "github_create_issue",
      "Create a repository issue",
      {
        title: z.string().min(1),
        body: z.string().optional(),
        labels: z.array(z.string()).optional(),
      },
      async ({ title, body, labels }) => {
        const r = await repoApi("/issues", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            title,
            body: body ?? "",
            labels: labels ?? [],
          }),
        });

        const j = await readJson(r);
        return j.error
          ? j.error
          : result({
              success: true,
              number: j.data.number,
              title: j.data.title,
              html_url: j.data.html_url,
            });
      },
    );

    // --------------------------------------------------
    // GITHUB ACTIONS WORKFLOWS
    // --------------------------------------------------

    this.server.tool(
      "github_list_workflows",
      "List GitHub Actions workflows",
      {},
      async () => {
        const r = await repoApi("/actions/workflows");
        const j = await readJson(r);
        return j.error
          ? j.error
          : result(j.data.workflows);
      },
    );

    this.server.tool(
      "github_trigger_workflow",
      "Trigger a workflow_dispatch event",
      {
        workflow_id: z.string().min(1),
        ref: z.string().min(1),
      },
      async ({ workflow_id, ref }) => {
        const r = await repoApi(
          `/actions/workflows/${encodeURIComponent(workflow_id)}/dispatches`,
          {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ ref }),
          },
        );

        if (r.status !== 204) {
          return fail(r.status, await r.text());
        }

        return result({
          success: true,
          workflow_id,
          ref,
          message: "Workflow dispatch accepted by GitHub.",
        });
      },
    );

    this.server.tool(
      "github_recent_workflow_runs",
      "List recent GitHub Actions workflow runs",
      {
        per_page: z.number().int().min(1).max(100).optional(),
      },
      async ({ per_page }) => {
        const r = await repoApi(
          `/actions/runs?per_page=${per_page ?? 10}`,
        );
        const j = await readJson(r);
        if (j.error) return j.error;

        return result(
          j.data.workflow_runs.map((run: any) => ({
            id: run.id,
            name: run.name,
            status: run.status,
            conclusion: run.conclusion,
            html_url: run.html_url,
            created_at: run.created_at,
            head_branch: run.head_branch,
          })),
        );
      },
    );
  }
}

export default new OAuthProvider({
  apiHandler: GitMcp.serve("/mcp"),
  apiRoute: "/mcp",
  authorizeEndpoint: "/authorize",
  clientRegistrationEndpoint: "/register",
  defaultHandler: GitHubHandler as any,
  tokenEndpoint: "/token",
});
