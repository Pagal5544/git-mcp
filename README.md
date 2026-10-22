# GIT MCP — GitHub Repository Assistant

A GitHub-connected Model Context Protocol (MCP) server running on Cloudflare Workers. It lets an MCP client inspect a configured repository, read and manage files, work with branches, create issues and pull requests, and run GitHub Actions workflows.

## Production endpoints

- MCP URL: https://git-mcp.dolatrammpi.workers.dev/mcp
- OAuth callback: https://git-mcp.dolatrammpi.workers.dev/callback
- Cloudflare Worker: `git-mcp`
- Repository: `Pagal5544/git-mcp`
- OAuth implementation / intended Cloudflare deployment branch: `feat/github-oauth-mcp`

The production Worker is cloud-hosted. Termux does not need to be running and the phone does not need to stay online for the production endpoint to operate.

## Cloudflare setup and deployment

The full step-by-step instructions for Worker configuration, variables and secrets, GitHub OAuth callback, deployment, verification, and troubleshooting are in **[CLOUDFLARE_SETUP.md](CLOUDFLARE_SETUP.md)**.

Important: verify Cloudflare's Git integration and active deployment before assuming a commit to a branch has reached Production. Keep secrets out of source code and commits.

## MCP tool reference

The OAuth implementation in `feat/github-oauth-mcp` registers these 14 tools.

### Repository and file access

| Tool | Purpose | Inputs |
|---|---|---|
| `github_repo_info` | Get configured repository metadata | None |
| `github_get_file` | Read a file and return its metadata | `path`, optional `ref` |
| `github_list_directory` | List files/directories | Optional `path`, `ref` |

### File changes

Each file operation commits to the selected branch.

| Tool | Purpose | Inputs |
|---|---|---|
| `github_create_file` | Create and commit a file | `path`, `content`, `message`, `branch` |
| `github_update_file` | Update and commit a file | `path`, `content`, `message`, `branch` |
| `github_delete_file` | Delete and commit a file | `path`, `message`, `branch` |

Safety: these operations change GitHub directly. Verify repository, branch, and path before writing or deleting. Prefer a test branch for experiments.

### Branches and pull requests

| Tool | Purpose | Inputs |
|---|---|---|
| `github_list_branches` | List branches and latest commit SHAs | Optional `per_page` |
| `github_create_branch` | Create a branch from a base branch | `branch_name`, `base_branch` |
| `github_create_pull_request` | Open a pull request | `title`, `head`, `base`; optional `body`, `draft` |

### Issues and GitHub Actions

| Tool | Purpose | Inputs |
|---|---|---|
| `github_list_issues` | List issues (excluding pull requests) | Optional `state`, `per_page` |
| `github_create_issue` | Create an issue | `title`; optional `body`, `labels` |
| `github_list_workflows` | List Actions workflows | None |
| `github_trigger_workflow` | Dispatch a workflow | `workflow_id`, `ref` |
| `github_recent_workflow_runs` | List recent workflow runs | Optional `per_page` |

## Connect to ChatGPT or another MCP client

1. Add a custom MCP server/connector using the MCP URL above.
2. Choose OAuth authentication when prompted.
3. Complete GitHub authorization in the browser.
4. Confirm the client discovers the expected tools.
5. Run read-only checks first: `github_repo_info`, `github_list_workflows`, and `github_recent_workflow_runs`.
6. Verify write access separately by creating a small test file on the intended test branch and reading it back with `github_get_file`.

Tool discovery confirms only that tools are listed. Successful read calls confirm read access. A successful create-file operation followed by a read-back confirms the write path.

## Configuration and security

- Wrangler configuration: `wrangler.jsonc`
- TypeScript entry point: `src/index.ts`
- Required binding/resource settings and variable names are documented in [CLOUDFLARE_SETUP.md](CLOUDFLARE_SETUP.md).
- Never commit `GITHUB_TOKEN`, `GITHUB_OAUTH_CLIENT_SECRET`, `COOKIE_ENCRYPTION_KEY`, or `MCP_AUTH_TOKEN` values.
- If any secret is exposed, rotate it immediately.
- A passing GitHub Actions type-check does not prove that Cloudflare Production deployed the intended commit. Verify the active deployment in the Cloudflare Dashboard.

## Branches

- `main` is the GitHub default branch.
- `feat/github-oauth-mcp` contains the OAuth-enabled Worker implementation and its setup documentation.

A push to `main` does not necessarily deploy the OAuth implementation unless Cloudflare is configured to deploy that branch. Check Cloudflare's Git integration before assuming a push has reached Production.
