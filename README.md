# GIT MCP — GitHub Repository Assistant

A GitHub-connected Model Context Protocol (MCP) server running on Cloudflare Workers. It lets an MCP client inspect the configured repository, read and manage files, work with branches, create issues and pull requests, and run GitHub Actions workflows.

## Production endpoint

- **MCP URL:** https://git-mcp.dolatrammpi.workers.dev/mcp
- **OAuth callback:** https://git-mcp.dolatrammpi.workers.dev/callback
- **Cloudflare Worker:** `git-mcp`
- **Repository:** [Pagal5544/git-mcp](https://github.com/Pagal5544/git-mcp)
- **OAuth implementation / intended Cloudflare deployment branch:** `feat/github-oauth-mcp`

The deployed Worker is cloud-hosted. **Termux does not need to be running and the phone does not need to stay online** for the production endpoint to operate.

## MCP tool reference

The OAuth implementation in `feat/github-oauth-mcp` registers these 14 tools.

### Repository and file access

| Tool | Purpose | Inputs |
|---|---|---|
| `github_repo_info` | Get configured repository metadata | None |
| `github_get_file` | Read file content and return its SHA, size and GitHub URL | `path`, optional `ref` |
| `github_list_directory` | List files/directories at a repository path | Optional `path`, `ref` |

### File changes — each operation commits to the selected branch

| Tool | Purpose | Inputs |
|---|---|---|
| `github_create_file` | Create a new file and commit it | `path`, `content`, `message`, `branch` |
| `github_update_file` | Update an existing file and commit it | `path`, `content`, `message`, `branch` |
| `github_delete_file` | Delete a file and commit the deletion | `path`, `message`, `branch` |

**Safety:** these operations change GitHub directly. Verify the repository, branch and target path before writing or deleting. Prefer a test branch for experiments.

### Branches and pull requests

| Tool | Purpose | Inputs |
|---|---|---|
| `github_list_branches` | List branches and their latest commit SHAs | Optional `per_page` (1–100) |
| `github_create_branch` | Create a branch from an existing branch | `branch_name`, `base_branch` |
| `github_create_pull_request` | Open a pull request between branches | `title`, `head`, `base`; optional `body`, `draft` |

### Issues and GitHub Actions

| Tool | Purpose | Inputs |
|---|---|---|
| `github_list_issues` | List issues (excludes pull requests) | Optional `state` (`open`, `closed`, `all`), `per_page` |
| `github_create_issue` | Create an issue | `title`; optional `body`, `labels` |
| `github_list_workflows` | List GitHub Actions workflows | None |
| `github_trigger_workflow` | Dispatch a workflow | `workflow_id`, `ref` |
| `github_recent_workflow_runs` | List recent workflow runs | Optional `per_page` (1–100) |

## Connect to ChatGPT or another MCP client

1. Add a custom MCP connector/server using the MCP URL above.
2. Choose **OAuth** authentication when prompted.
3. Complete the GitHub authorization flow in the browser.
4. Confirm the client discovers the tools listed above.
5. Run read-only checks: `github_repo_info`, `github_list_workflows`, and `github_recent_workflow_runs`.
6. Verify write access separately by creating a small test file on the intended branch and reading it back with `github_get_file`.

A tool list confirms discovery, not end-to-end operation. Successful repository/workflow calls confirm read access. A successful create-file operation followed by a read-back confirms the write path.

## Cloudflare configuration

### Wrangler configuration

The full configuration is in [`wrangler.jsonc`](./wrangler.jsonc). It currently defines:

- Worker name: `git-mcp`
- Entry point: `src/index.ts`
- Compatibility date: `2026-10-14`
- Compatibility flag: `nodejs_compat`
- Observability: enabled
- `keep_vars: true`
- Text variables: `GITHUB_OAUTH_CLIENT_ID`, `GITHUB_OWNER`, `GITHUB_REPO`
- Durable Object binding: `MCP_OBJECT` → `GitMcp`
- KV binding: `OAUTH_KV` → the existing `git-mcp-oauth` namespace

The repository variables are defined in Wrangler so they can be kept aligned with the code-based deployment configuration. Cloudflare Dashboard has also shown a warning to update Wrangler configuration to keep deployments in sync; this file was rewritten to include the three variables and `keep_vars: true`.

**Important:** `keep_vars: true` is intended to preserve Dashboard-defined variables during Wrangler deployments. The three variables are also explicitly listed in `vars`. If a variable still disappears, check the selected environment (Production vs Preview), Cloudflare deployment logs, and the actual deployment configuration.

### Required variables and secrets

**Text variables (not secrets):**

- `GITHUB_OWNER` — `Pagal5544`
- `GITHUB_REPO` — `git-mcp`
- `GITHUB_OAUTH_CLIENT_ID` — the OAuth App's Client ID; configured in `wrangler.jsonc`

**Secrets (keep private; never commit their values):**

- `GITHUB_TOKEN`
- `GITHUB_OAUTH_CLIENT_SECRET`
- `COOKIE_ENCRYPTION_KEY`
- `MCP_AUTH_TOKEN`

**Bindings:**

- KV namespace: `OAUTH_KV`
- Durable Object namespace: `MCP_OBJECT`

The GitHub OAuth App callback URL must match:

`https://git-mcp.dolatrammpi.workers.dev/callback`

Never place GitHub tokens, OAuth client secrets, cookie-encryption keys, or MCP auth tokens in `vars`, README content, source code, or commits. If a secret is accidentally exposed, rotate it.

## Development and deployment

Requirements: Node.js and npm.

```bash
npm install
npm run typecheck
npm run dev
npm run deploy
```

- `npm run typecheck` checks TypeScript.
- `npm run dev` starts a local Wrangler development server.
- `npm run deploy` deploys the Worker using the current Wrangler configuration and credentials.

For the hosted production setup, confirm which branch Cloudflare Git integration deploys. After a push, check GitHub Actions **Worker Type Check** and the Cloudflare deployment status. A successful type-check alone does not prove that the production endpoint is serving the latest commit.

## Current repository verification log

- The GIT MCP read tests succeeded for repository metadata, workflow listing and recent workflow runs.
- A write-path test succeeded: `tests/mcp_write_check_2026-10-15.md` was created on `main`, committed, then read back successfully.
- The pre-existing `tests/test_smoke.py` was left unchanged by that write test.
- `README.md` was updated on both `main` and `feat/github-oauth-mcp`.
- `wrangler.jsonc` was fully rewritten on `feat/github-oauth-mcp` to declare the three text variables, preserve Dashboard variables with `keep_vars: true`, and retain the Durable Object and KV bindings.
- Recent GitHub Actions **Worker Type Check** runs on `feat/github-oauth-mcp` reported `success`.
- **Not yet confirmed by those checks:** whether Cloudflare Production has completed a deployment using the latest `wrangler.jsonc`, and whether the three variables remain visible after that deployment. Confirm these in Cloudflare Deployments and Production Settings.

## Verification checklist

- [ ] OAuth authorization completes successfully.
- [ ] Client discovers the expected 14 tools.
- [ ] `github_repo_info` returns the configured repository.
- [ ] `github_list_workflows` returns the workflows.
- [ ] `github_recent_workflow_runs` returns recent runs.
- [x] File-write smoke test: create a test file and read it back.
- [x] GitHub Actions **Worker Type Check** has recent successful runs on the OAuth branch.
- [ ] Cloudflare Production deploys the intended commit.
- [ ] The three text variables remain present after deployment.
- [ ] Confirm end-to-end calls against the deployed OAuth endpoint after the latest deployment.

## Branches

- `main` is the GitHub default branch.
- `feat/github-oauth-mcp` contains the OAuth-enabled Worker implementation and the Wrangler configuration documented here.

A commit to `main` does not necessarily deploy the OAuth implementation unless Cloudflare is configured to deploy that branch. Check Cloudflare's Git integration before assuming a push has reached Production.
