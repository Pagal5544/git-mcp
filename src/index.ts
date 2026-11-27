import OAuthProvider from "@cloudflare/workers-oauth-provider";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { McpAgent } from "agents/mcp";
import { z } from "zod";
import type { Env } from "./types";
import { GitHubHandler } from "./github-handler";
import type { Props } from "./utils";

export class GitMcp extends McpAgent<Env, Record<string, never>, Props> {
  server = new McpServer({ name: "git-mcp", version: "2.1.0" });

  async init() {
    const accessToken = this.props?.accessToken;
    if (!accessToken) {
      throw new Error("GitHub OAuth session is missing its access token. Reconnect this MCP server through GitHub OAuth.");
    }

    const headers = {
      Authorization: `Bearer ${accessToken}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "git-mcp",
    };

    const api = async (path: string, init: RequestInit = {}) =>
      fetch(`https://api.github.com${path}`, {
        ...init,
        headers: { ...headers, ...(init.headers as Record<string, string> | undefined) },
      });

    const repoApiFor = (owner?: string, repo?: string) => {
      const targetOwner = owner?.trim() || this.props.login || this.env.GITHUB_OWNER;
      const targetRepo = repo?.trim() || this.env.GITHUB_REPO;
      if (!/^[A-Za-z0-9_.-]+$/.test(targetOwner) || !/^[A-Za-z0-9_.-]+$/.test(targetRepo) ||
          targetOwner === "." || targetOwner === ".." || targetRepo === "." || targetRepo === "..") {
        throw new Error("Invalid GitHub owner or repository name.");
      }
      return (path: string, init: RequestInit = {}) =>
        api(`/repos/${encodeURIComponent(targetOwner)}/${encodeURIComponent(targetRepo)}${path}`, init);
    };

    const result = (value: unknown) => ({
      content: [{ type: "text" as const, text: typeof value === "string" ? value : JSON.stringify(value, null, 2) }],
    });
    const fail = (status: number, body: string) => ({
      content: [{ type: "text" as const, text: `GitHub API error ${status}: ${body}` }],
      isError: true,
    });
    const readJson = async (response: Response) => {
      const body = await response.text();
      if (!response.ok) return { error: fail(response.status, body) };
      try { return { data: JSON.parse(body) as any }; }
      catch { return { error: fail(response.status, body || "Invalid JSON response") }; }
    };
    const encodePath = (path: string) => path.split("/").filter(Boolean).map(encodeURIComponent).join("/");
    const encodeBase64 = (value: string) => {
      const bytes = new TextEncoder().encode(value);
      let binary = "";
      for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
      return btoa(binary);
    };
    const decodeBase64 = (value: string) => {
      const normalized = value.replace(/\s/g, "");
      const binary = atob(normalized);
      const bytes = Uint8Array.from(binary, c => c.charCodeAt(0));
      return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    };
    const getFile = (path: string, ref?: string, owner?: string, repo?: string) => {
      const query = ref ? `?ref=${encodeURIComponent(ref)}` : "";
      return repoApiFor(owner, repo)(`/contents/${encodePath(path)}${query}`);
    };

    this.server.tool("github_authenticated_user", "Show which GitHub account is authorized for the current MCP OAuth session", {}, async () => {
      const r = await api("/user"); const j = await readJson(r);
      return j.error ?? result({ login: j.data.login, id: j.data.id, name: j.data.name, email: this.props.email });
    });

    this.server.tool("github_repo_info", "Get information about the selected GitHub repository", {
      owner: z.string().min(1).max(100).optional(), repo: z.string().min(1).max(100).optional(),
    }, async ({ owner, repo }) => {
      const r = await repoApiFor(owner, repo)(""); const j = await readJson(r);
      return j.error ?? result(j.data);
    });

    this.server.tool("github_create_repository", "Create a new GitHub repository for the authenticated account", {
      name: z.string().trim().min(1).max(100).regex(/^[A-Za-z0-9_.-]+$/).refine(n => n !== "." && n !== ".." && !n.toLowerCase().endsWith(".git")),
      description: z.string().max(350).optional(), private: z.boolean().default(true), auto_init: z.boolean().default(true),
      gitignore_template: z.string().max(100).optional(), license_template: z.string().max(100).optional(),
    }, async ({ name, description, private: isPrivate, auto_init, gitignore_template, license_template }) => {
      const body = { name, private: isPrivate, auto_init, ...(description !== undefined ? { description } : {}),
        ...(gitignore_template ? { gitignore_template } : {}), ...(license_template ? { license_template } : {}) };
      const r = await api("/user/repos", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
      const j = await readJson(r); if (j.error) return j.error;
      return result({ success: true, owner: j.data.owner?.login, repo: j.data.name, full_name: j.data.full_name,
        private: j.data.private, default_branch: j.data.default_branch, html_url: j.data.html_url, clone_url: j.data.clone_url });
    });

    this.server.tool("github_get_file", "Read a UTF-8 file from a GitHub repository and branch (returns decoded content and blob SHA)", {
      owner: z.string().min(1).max(100).optional(), repo: z.string().min(1).max(100).optional(),
      path: z.string().min(1), ref: z.string().optional(),
    }, async ({ path, ref, owner, repo }) => {
      const response = await getFile(path, ref, owner, repo);
      const j = await readJson(response); if (j.error) return j.error;
      if (j.data.type !== "file") return fail(400, "The specified path is not a file.");
      try {
        let text: string;
        if (typeof j.data.content === "string" && j.data.content.trim()) text = decodeBase64(j.data.content);
        else {
          const raw = await api(`/repos/${encodeURIComponent(owner?.trim() || this.props.login)}/${encodeURIComponent(repo?.trim() || this.env.GITHUB_REPO)}/git/blobs/${j.data.sha}`, {
            headers: { Accept: "application/vnd.github.raw+json" },
          });
          if (!raw.ok) return fail(raw.status, await raw.text());
          text = await raw.text();
        }
        return result({ path: j.data.path, sha: j.data.sha, size: j.data.size, html_url: j.data.html_url, content: text });
      } catch (error) {
        return fail(500, `Unable to decode file content: ${error instanceof Error ? error.message : String(error)}`);
      }
    });

    this.server.tool("github_list_directory", "List files and directories in a repository path", {
      owner: z.string().min(1).max(100).optional(), repo: z.string().min(1).max(100).optional(),
      path: z.string().optional(), ref: z.string().optional(),
    }, async ({ path, ref, owner, repo }) => {
      const p = path ? `/contents/${encodePath(path)}` : "/contents";
      const query = ref ? `?ref=${encodeURIComponent(ref)}` : "";
      const r = await repoApiFor(owner, repo)(`${p}${query}`); const j = await readJson(r);
      if (j.error) return j.error;
      const items = Array.isArray(j.data) ? j.data : [j.data];
      return result(items.map((item: any) => ({ name: item.name, path: item.path, type: item.type, size: item.size, sha: item.sha, html_url: item.html_url })));
    });

    this.server.tool("github_create_file", "Create a new file and commit it to a branch", {
      owner: z.string().min(1).max(100).optional(), repo: z.string().min(1).max(100).optional(),
      path: z.string().min(1), content: z.string(), message: z.string().min(1), branch: z.string().min(1),
    }, async ({ path, content, message, branch, owner, repo }) => {
      const r = await repoApiFor(owner, repo)(`/contents/${encodePath(path)}`, { method: "PUT", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message, content: encodeBase64(content), branch }) });
      const j = await readJson(r);
      return j.error ? j.error : result({ success: true, operation: "create", path: j.data.content?.path, commit: j.data.commit?.sha, commit_url: j.data.commit?.html_url });
    });

    this.server.tool("github_update_file", "Update an existing file and commit the changes", {
      owner: z.string().min(1).max(100).optional(), repo: z.string().min(1).max(100).optional(),
      path: z.string().min(1), content: z.string(), message: z.string().min(1), branch: z.string().min(1),
    }, async ({ path, content, message, branch, owner, repo }) => {
      const existing = await getFile(path, branch, owner, repo); const current = await readJson(existing);
      if (current.error) return current.error;
      if (current.data.type !== "file") return fail(400, "The specified path is not a file.");
      const r = await repoApiFor(owner, repo)(`/contents/${encodePath(path)}`, { method: "PUT", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message, content: encodeBase64(content), sha: current.data.sha, branch }) });
      const j = await readJson(r);
      return j.error ? j.error : result({ success: true, operation: "update", path: j.data.content?.path, commit: j.data.commit?.sha, commit_url: j.data.commit?.html_url });
    });

    this.server.tool("github_delete_file", "Delete an existing file and commit the deletion", {
      owner: z.string().min(1).max(100).optional(), repo: z.string().min(1).max(100).optional(),
      path: z.string().min(1), message: z.string().min(1), branch: z.string().min(1),
    }, async ({ path, message, branch, owner, repo }) => {
      const existing = await getFile(path, branch, owner, repo); const current = await readJson(existing);
      if (current.error) return current.error;
      if (current.data.type !== "file") return fail(400, "The specified path is not a file.");
      const r = await repoApiFor(owner, repo)(`/contents/${encodePath(path)}`, { method: "DELETE", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message, sha: current.data.sha, branch }) });
      const j = await readJson(r);
      return j.error ? j.error : result({ success: true, operation: "delete", path, commit: j.data.commit?.sha, commit_url: j.data.commit?.html_url });
    });

    this.server.tool("github_list_branches", "List branches in a GitHub repository", {
      owner: z.string().min(1).max(100).optional(), repo: z.string().min(1).max(100).optional(), per_page: z.number().int().min(1).max(100).optional(),
    }, async ({ per_page, owner, repo }) => {
      const r = await repoApiFor(owner, repo)(`/branches?per_page=${per_page ?? 30}`); const j = await readJson(r);
      if (j.error) return j.error;
      return result(j.data.map((b: any) => ({ name: b.name, commit_sha: b.commit?.sha, protected: b.protected })));
    });

    this.server.tool("github_create_branch", "Create a new branch from an existing branch", {
      owner: z.string().min(1).max(100).optional(), repo: z.string().min(1).max(100).optional(),
      branch_name: z.string().min(1), base_branch: z.string().min(1),
    }, async ({ branch_name, base_branch, owner, repo }) => {
      if (branch_name.startsWith("/") || branch_name.endsWith("/") || branch_name.includes("..") || /\s/.test(branch_name)) return fail(400, "Invalid branch name.");
      const source = await repoApiFor(owner, repo)(`/git/ref/heads/${encodeURIComponent(base_branch)}`); const sourceJson = await readJson(source);
      if (sourceJson.error) return sourceJson.error;
      const r = await repoApiFor(owner, repo)("/git/refs", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ref: `refs/heads/${branch_name}`, sha: sourceJson.data.object.sha }) });
      const j = await readJson(r);
      return j.error ? j.error : result({ success: true, branch: j.data.ref, sha: j.data.object?.sha });
    });

    this.server.tool("github_create_pull_request", "Create a pull request between two branches", {
      owner: z.string().min(1).max(100).optional(), repo: z.string().min(1).max(100).optional(),
      title: z.string().min(1), head: z.string().min(1), base: z.string().min(1), body: z.string().optional(), draft: z.boolean().optional(),
    }, async ({ title, head, base, body, draft, owner, repo }) => {
      const r = await repoApiFor(owner, repo)("/pulls", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title, head, base, body: body ?? "", draft: draft ?? false }) });
      const j = await readJson(r);
      return j.error ? j.error : result({ success: true, number: j.data.number, title: j.data.title, state: j.data.state, html_url: j.data.html_url, head: j.data.head?.ref, base: j.data.base?.ref });
    });

    this.server.tool("github_list_issues", "List repository issues", {
      owner: z.string().min(1).max(100).optional(), repo: z.string().min(1).max(100).optional(),
      state: z.enum(["open", "closed", "all"]).optional(), per_page: z.number().int().min(1).max(100).optional(),
    }, async ({ state, per_page, owner, repo }) => {
      const r = await repoApiFor(owner, repo)(`/issues?state=${state ?? "open"}&per_page=${per_page ?? 20}`); const j = await readJson(r);
      if (j.error) return j.error;
      return result(j.data.filter((item: any) => !item.pull_request).map((item: any) => ({
        number: item.number, title: item.title, state: item.state, html_url: item.html_url, user: item.user?.login, labels: item.labels?.map((label: any) => label.name),
      })));
    });

    this.server.tool("github_create_issue", "Create a repository issue", {
      owner: z.string().min(1).max(100).optional(), repo: z.string().min(1).max(100).optional(),
      title: z.string().min(1), body: z.string().optional(), labels: z.array(z.string()).optional(),
    }, async ({ title, body, labels, owner, repo }) => {
      const r = await repoApiFor(owner, repo)("/issues", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title, body: body ?? "", labels: labels ?? [] }) });
      const j = await readJson(r);
      return j.error ? j.error : result({ success: true, number: j.data.number, title: j.data.title, html_url: j.data.html_url });
    });

    this.server.tool("github_list_workflows", "List GitHub Actions workflows", {
      owner: z.string().min(1).max(100).optional(), repo: z.string().min(1).max(100).optional(),
    }, async ({ owner, repo }) => {
      const r = await repoApiFor(owner, repo)("/actions/workflows"); const j = await readJson(r);
      return j.error ? j.error : result(j.data.workflows);
    });

    this.server.tool("github_trigger_workflow", "Trigger a workflow_dispatch event", {
      owner: z.string().min(1).max(100).optional(), repo: z.string().min(1).max(100).optional(),
      workflow_id: z.string().min(1), ref: z.string().min(1),
    }, async ({ workflow_id, ref, owner, repo }) => {
      const r = await repoApiFor(owner, repo)(`/actions/workflows/${encodeURIComponent(workflow_id)}/dispatches`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ ref }),
      });
      if (r.status !== 204) return fail(r.status, await r.text());
      return result({ success: true, workflow_id, ref, message: "Workflow dispatch accepted by GitHub." });
    });

    this.server.tool("github_recent_workflow_runs", "List recent GitHub Actions workflow runs", {
      owner: z.string().min(1).max(100).optional(), repo: z.string().min(1).max(100).optional(), per_page: z.number().int().min(1).max(100).optional(),
    }, async ({ per_page, owner, repo }) => {
      const r = await repoApiFor(owner, repo)(`/actions/runs?per_page=${per_page ?? 10}`); const j = await readJson(r);
      if (j.error) return j.error;
      return result(j.data.workflow_runs.map((run: any) => ({ id: run.id, name: run.name, status: run.status, conclusion: run.conclusion, html_url: run.html_url, created_at: run.created_at, head_branch: run.head_branch })));
    });
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
