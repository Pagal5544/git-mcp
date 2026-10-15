# GIT MCP — GitHub Repository Assistant

A GitHub-connected Model Context Protocol (MCP) server running on Cloudflare Workers. It lets an MCP client inspect the configured repository, manage files and branches, create issues and pull requests, and run GitHub Actions workflows.

## Production endpoint

- **MCP URL:** https://git-mcp.dolatrammpi.workers.dev/mcp
- **OAuth callback:** https://git-mcp.dolatrammpi.workers.dev/callback
- **Cloudflare Worker:** `git-mcp`
- **Repository:** [Pagal5544/git-mcp](https://github.com/Pagal5544/git-mcp)
- **OAuth / Cloudflare deployment branch:** `feat/github-oauth-mcp`

The deployed Worker is cloud-hosted. **Termux does not need to be running and the phone does not need to stay online** for the production endpoint to operate.

## MCP tools

The OAuth implementation registers the following 14 tools.

### Repository and file access

| Tool | Purpose | Inputs |
|---|---|---|
| `github_repo_info` | Get configured repository metadata | None |
| `github_get_file` | Read a file and return its content, SHA, size and GitHub URL | `path`, optional `ref` |
| `github_list_directory` | List files/directories at a repository path | Optional `path`, `ref` |

### File changes (create Git commits)

| Tool | Purpose | Inputs |
|---|---|---|
| `github_create_file` | Create a new file and commit it | `path`, `content`, `message`, `branch` |
| `github_update_file` | Update an existing file and commit it | `path`, `content`, `message`, `branch` |
| `github_delete_file` | Delete a file and commit the deletion | `path`, `message`, `branch` |

**Caution:** create/update/delete tools write directly to the selected branch. Confirm the target path and branch before making changes.

### Branches and collaboration

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
2. Select **OAuth** authentication when the client offers an authentication choice.
3. Complete the GitHub authorization flow in the browser.
4. Confirm the client discovers the tools listed above.
5. Run a read-only smoke test first: `github_repo_info`, `github_list_workflows`, and `github_recent_workflow_runs`.
6. To verify write access, create a small test file on a deliberate test branch, then read it back with `github_get_file`. Only test writes on `main` when a direct commit to `main` is intended.

A visible tool list confirms discovery, not necessarily successful GitHub API access. A successful tool call confirms the tested operation; a create-file call followed by a read-back confirms the write path.

## Cloudflare configuration

The OAuth Worker expects these bindings/configuration values:

- Variables: `GITHUB_OWNER`, `GITHUB_REPO`, `GITHUB_OAUTH_CLIENT_ID`
- Secrets: `GITHUB_TOKEN`, `GITHUB_OAUTH_CLIENT_SECRET`, `COOKIE_ENCRYPTION_KEY`, `MCP_AUTH_TOKEN`
- Bindings: KV namespace `OAUTH_KV`, Durable Object namespace `MCP_OBJECT`

Set values in Cloudflare's Worker settings or via Wrangler secrets as appropriate. **Never commit tokens, OAuth secrets, cookie-encryption keys, or MCP auth tokens to Git.** The OAuth callback configured in the GitHub OAuth App must match:

`https://git-mcp.dolatrammpi.workers.dev/callback`

The Worker configuration in `wrangler.jsonc` identifies the KV namespace and Durable Object binding. Keep these bindings aligned with the Cloudflare dashboard.

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

For the hosted production setup, verify that the intended branch is connected to Cloudflare's Git deployment configuration. After a push, check the GitHub Actions **Worker Type Check** workflow and the Cloudflare deployment status. A successful type-check alone does not prove the production endpoint is serving that commit.

## Verification checklist

- [ ] OAuth authorization completes successfully.
- [ ] Client discovers the expected MCP tools.
- [ ] `github_repo_info` returns the configured repository.
- [ ] `github_list_workflows` returns the workflows.
- [ ] `github_recent_workflow_runs` returns recent runs.
- [ ] Write test: create a test file on the intended branch and read it back.
- [ ] GitHub Actions **Worker Type Check** succeeds.
- [ ] Cloudflare deployment reports the intended version as active.

## Repository branches

- `main` is the GitHub default branch.
- `feat/github-oauth-mcp` contains the OAuth-enabled Worker implementation and is the branch documented for Cloudflare production deployment.

Keep this distinction in mind when changing code or documentation: a commit to `main` does not necessarily deploy the OAuth implementation unless Cloudflare is configured to deploy that branch.
