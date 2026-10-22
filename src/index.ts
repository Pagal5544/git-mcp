
import OAuthProvider from "@cloudflare/workers-oauth-provider";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { McpAgent } from "agents/mcp";
import { z } from "zod";
import type { Env } from "./types";
import { GitHubHandler } from "./github-handler";
import type { Props } from "./utils";

export class GitMcp extends McpAgent<
  Env,
  Record<string, never>,
  Props
> {
  server = new McpServer({
    name: "git-mcp",
    version: "2.0.0",
  });

  async init() {
    const sessionProps = this.props;
    const accessToken = sessionProps?.accessToken;
    const sessionLogin = sessionProps?.login;
    if (!accessToken || !sessionLogin) {
      throw new Error("Missing GitHub OAuth session identity or access token. Reconnect with GitHub OAuth.");
    }

    const headers = {
      Authorization: `Bearer ${accessToken}`,
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

    const repoApiFor = (owner?: string, repo?: string) => {
      const targetOwner = owner?.trim() || sessionLogin;
      const targetRepo = repo?.trim() || this.env.GITHUB_REPO;
      if (
        !/^[A-Za-z0-9_.-]+$/.test(targetOwner) ||
        !/^[A-Za-z0-9_.-]+$/.test(targetRepo) ||
        targetOwner === "." || targetOwner === ".." ||
        targetRepo === "." || targetRepo === ".."
      ) {
        throw new Error("Invalid GitHub owner or repository name.");
      }
      return (path: string, init: RequestInit = {}) =>
        api(
          `/repos/${encodeURIComponent(targetOwner)}/${encodeURIComponent(targetRepo)}${path}`,
          init,
        );
    };

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

    const getFile = async (
      path: string,
      ref?: string,
      owner?: string,
      repo?: string,
    ) => {
      const query = ref ? `?ref=${encodeURIComponent(ref)}` : "";
      return repoApiFor(owner, repo)(
        `/contents/${encodePath(path)}${query}`,
      );
    };

    // --------------------------------------------------
    // REPOSITORY INFORMATION
    // --------------------------------------------------

    this.server.tool(
      "github_repo_info",
      "Get information about the configured GitHub repository",
      { owner: z.string().min(1).max(100).optional(), repo: z.string().min(1).max(100).optional() },
      async ({ owner, repo }) => {
        const r = await repoApiFor(owner, repo)("");
        const j = await readJson(r);
        return j.error ?? result(j.data);
      },
    );

    this.server.tool(
      "github_authenticated_user",
      "Show which GitHub account is authorized for the current MCP OAuth session",
      {},
      async () => {
        const r = await api("/user");
        const j = await readJson(r);
        if (j.error) return j.error;
        return result({
          login: j.data.login,
          name: j.data.name,
          html_url: j.data.html_url,
          session_login: sessionLogin,
          matches_session: String(j.data.login ?? "").toLowerCase() === sessionLogin.toLowerCase(),
        });
      },
    );

    // --------------------------------------------------
    // CREATE A NEW REPOSITORY FOR THE AUTHENTICATED USER
    // --------------------------------------------------
    this.server.tool(
      "github_create_repository",
      "Create a new GitHub repository for the authenticated account. Returns its owner, name, URL and default branch; use the owner/repo inputs on other tools to work in it.",
      {
        name: z.string().trim().min(1).max(100).regex(/^[A-Za-z0-9_.-]+$/).refine((name) => name !== "." && name !== ".." && !name.toLowerCase().endsWith(".git"), "Invalid repository name."),
        description: z.string().max(350).optional(),
        private: z.boolean().default(true),
        auto_init: z.boolean().default(true),
        gitignore_template: z.string().max(100).optional(),
        license_template: z.string().max(100).optional(),
      },
      async ({ name, description, private: isPrivate, auto_init, gitignore_template, license_template }) => {
        const viewerResponse = await api("/user");
        const viewer = await readJson(viewerResponse);
        if (viewer.error) return viewer.error;
        const authenticatedOwner = String(viewer.data.login ?? "");
        if (authenticatedOwner.toLowerCase() !== sessionLogin.toLowerCase()) {
          return fail(403, `OAuth session is for "${sessionLogin}", but GitHub authenticated "${authenticatedOwner}". Refusing to create a repository for a mismatched session.`);
        }

        const body: Record<string, unknown> = {
          name,
          private: isPrivate,
          auto_init,
          ...(description !== undefined ? { description } : {}),
          ...(gitignore_template ? { gitignore_template } : {}),
          ...(license_template ? { license_template } : {}),
        };
        const response = await api("/user/repos", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        });
        const created = await readJson(response);
        if (created.error) return created.error;

        return result({
          success: true,
          owner: created.data.owner?.login,
          repo: created.data.name,
          full_name: created.data.full_name,
          private: created.data.private,
          default_branch: created.data.default_branch,
          html_url: created.data.html_url,
          clone_url: created.data.clone_url,
          message: "Repository created. Pass owner and repo to file, branch, issue, pull-request and workflow tools to work in this repository.",
        });
      },
    );

    this.server.tool(
      "github_get_file",
      "Read a file from the configured repository and branch",
      {
        owner: z.string().min(1).max(100).optional(),
        repo: z.string().min(1).max(100).optional(),
        path: z.string().min(1),
        ref: z.string().optional(),
      },
      async ({ path, ref, owner, repo }) => {
        const r = await getFile(path, ref, owner, repo);
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
        owner: z.string().min(1).max(100).optional(),
        repo: z.string().min(1).max(100).optional(),
        path: z.string().optional(),
        ref: z.string().optional(),
      },
      async ({ path, ref, owner, repo }) => {
        const p = path ? `/contents/${encodePath(path)}` : "/contents";
        const query = ref ? `?ref=${encodeURIComponent(ref)}` : "";
        const r = await repoApiFor(owner, repo)(`${p}${query}`);
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
        owner: z.string().min(1).max(100).optional(),
        repo: z.string().min(1).max(100).optional(),
        path: z.string().min(1),
        content: z.string(),
        message: z.string().min(1),
        branch: z.string().min(1),
      },
      async ({ path, content, message, branch, owner, repo }) => {
        const r = await repoApiFor(owner, repo)(
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
        owner: z.string().min(1).max(100).optional(),
        repo: z.string().min(1).max(100).optional(),
        path: z.string().min(1),
        content: z.string(),
        message: z.string().min(1),
        branch: z.string().min(1),
      },
      async ({ path, content, message, branch, owner, repo }) => {
        // Retrieve the current SHA required by GitHub.
        const existing = await getFile(path, branch, owner, repo);
        const current = await readJson(existing);
        if (current.error) return current.error;

        if (current.data.type !== "file") {
          return fail(400, "The specified path is not a file.");
        }

        const r = await repoApiFor(owner, repo)(
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
        owner: z.string().min(1).max(100).optional(),
        repo: z.string().min(1).max(100).optional(),
        path: z.string().min(1),
        message: z.string().min(1),
        branch: z.string().min(1),
      },
      async ({ path, message, branch, owner, repo }) => {
        const existing = await getFile(path, branch, owner, repo);
        const current = await readJson(existing);
        if (current.error) return current.error;

        if (current.data.type !== "file") {
          return fail(400, "The specified path is not a file.");
        }

        const r = await repoApiFor(owner, repo)(
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
        owner: z.string().min(1).max(100).optional(),
        repo: z.string().min(1).max(100).optional(),
        per_page: z.number().int().min(1).max(100).optional(),
      },
      async ({ per_page, owner, repo }) => {
        const r = await repoApiFor(owner, repo)(
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
        owner: z.string().min(1).max(100).optional(),
        repo: z.string().min(1).max(100).optional(),
        branch_name: z.string().min(1),
        base_branch: z.string().min(1),
      },
      async ({ branch_name, base_branch, owner, repo }) => {
        if (
          branch_name.startsWith("/") ||
          branch_name.endsWith("/") ||
          branch_name.includes("..") ||
          /\s/.test(branch_name)
        ) {
          return fail(400, "Invalid branch name.");
        }

        const source = await repoApiFor(owner, repo)(
          `/git/ref/heads/${encodeURIComponent(base_branch)}`,
        );
        const sourceJson = await readJson(source);
        if (sourceJson.error) return sourceJson.error;

        const r = await repoApiFor(owner, repo)("/git/refs", {
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
        owner: z.string().min(1).max(100).optional(),
        repo: z.string().min(1).max(100).optional(),
        title: z.string().min(1),
        head: z.string().min(1),
        base: z.string().min(1),
        body: z.string().optional(),
        draft: z.boolean().optional(),
      },
      async ({ title, head, base: targetBase, body, draft, owner, repo }) => {
        const r = await repoApiFor(owner, repo)("/pulls", {
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
        owner: z.string().min(1).max(100).optional(),
        repo: z.string().min(1).max(100).optional(),
        state: z.enum(["open", "closed", "all"]).optional(),
        per_page: z.number().int().min(1).max(100).optional(),
      },
      async ({ state, per_page, owner, repo }) => {
        const r = await repoApiFor(owner, repo)(
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
        owner: z.string().min(1).max(100).optional(),
        repo: z.string().min(1).max(100).optional(),
        title: z.string().min(1),
        body: z.string().optional(),
        labels: z.array(z.string()).optional(),
      },
      async ({ title, body, labels, owner, repo }) => {
        const r = await repoApiFor(owner, repo)("/issues", {
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
      { owner: z.string().min(1).max(100).optional(), repo: z.string().min(1).max(100).optional() },
      async ({ owner, repo }) => {
        const r = await repoApiFor(owner, repo)("/actions/workflows");
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
        owner: z.string().min(1).max(100).optional(),
        repo: z.string().min(1).max(100).optional(),
        workflow_id: z.string().min(1),
        ref: z.string().min(1),
      },
      async ({ workflow_id, ref, owner, repo }) => {
        const r = await repoApiFor(owner, repo)(
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
        owner: z.string().min(1).max(100).optional(),
        repo: z.string().min(1).max(100).optional(),
        per_page: z.number().int().min(1).max(100).optional(),
      },
      async ({ per_page, owner, repo }) => {
        const r = await repoApiFor(owner, repo)(
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
