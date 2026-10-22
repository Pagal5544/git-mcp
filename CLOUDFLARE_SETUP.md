# Cloudflare Setup & Deployment

This guide describes the Cloudflare Worker setup for Git MCP. Use the values in your own Cloudflare/GitHub accounts. Never commit secret values.

## Production endpoints

- MCP endpoint: https://git-mcp.dolatrammpi.workers.dev/mcp
- OAuth callback: https://git-mcp.dolatrammpi.workers.dev/callback
- Worker name: `git-mcp`
- Entry point: `src/index.ts`

## 1. Prerequisites

- Access to the Cloudflare account that owns the `git-mcp` Worker.
- Access to the GitHub OAuth App used by this Worker.
- Node.js and npm for local development.
- Wrangler CLI, invoked through the project's npm scripts.

## 2. Review `wrangler.jsonc`

The configuration documented by this project includes:

- Entry point `src/index.ts`
- Compatibility date `2026-10-14` and `nodejs_compat`
- Observability enabled and `keep_vars: true`
- Durable Object binding `MCP_OBJECT` → `GitMcp`
- KV binding `OAUTH_KV` → the existing `git-mcp-oauth` namespace
- Text variables `GITHUB_OWNER`, `GITHUB_REPO`, and `GITHUB_OAUTH_CLIENT_ID`

Before deploying, confirm the Durable Object class and KV namespace ID in `wrangler.jsonc` match resources in your Cloudflare account. Do not create a replacement KV namespace casually; OAuth/session state may depend on the existing namespace.

## 3. Configure text variables

In Cloudflare Dashboard, open **Workers & Pages → git-mcp → Settings → Variables and Secrets**. Under the **Production** environment, confirm these non-secret values:

- `GITHUB_OWNER`: `Pagal5544`
- `GITHUB_REPO`: `git-mcp`
- `GITHUB_OAUTH_CLIENT_ID`: Client ID from the GitHub OAuth App

Configure Preview separately if you use it; Production and Preview settings should not be assumed to match. Keep text variables aligned with `wrangler.jsonc`.

## 4. Configure secrets

In the same Cloudflare Worker settings page, add these using the **secret** type (not plain text variables):

- `GITHUB_TOKEN`
- `GITHUB_OAUTH_CLIENT_SECRET`
- `COOKIE_ENCRYPTION_KEY`
- `MCP_AUTH_TOKEN`

Confirm the exact names expected by the current source. Never put their values in README, source code, commits, screenshots, or logs. Secrets can also be managed using Wrangler after authenticating to the intended Cloudflare account; do not paste secrets into chat.

## 5. Configure GitHub OAuth

In GitHub, open **Settings → Developer settings → OAuth Apps**, then select the OAuth App used by this Worker. Set the Authorization callback URL to exactly:

`https://git-mcp.dolatrammpi.workers.dev/callback`

The callback must match the deployed URL exactly. The Client ID must match `GITHUB_OAUTH_CLIENT_ID`; store the corresponding Client Secret in Cloudflare as a secret. The OAuth callback is not the same as the MCP endpoint ending in `/mcp`.

## 6. Connect the MCP client

Add this custom MCP server URL in the compatible client:

`https://git-mcp.dolatrammpi.workers.dev/mcp`

Select OAuth authentication if offered, complete authorization in the browser, then verify tool discovery. Tool discovery proves only that the client can see tools, not that all read/write operations work.

## 7. Local checks and deployment

From the project directory:

```sh
npm install
npm run typecheck
npm run dev
```

After checking the configuration and tests, deploy with:

```sh
npm run deploy
```

Only deploy from a trusted development environment authenticated to the correct Cloudflare account. Review `wrangler.jsonc`, especially resource bindings and `keep_vars`, before deploying to Production.

## 8. Verify the deployed Worker

1. In Cloudflare Dashboard, open **Workers & Pages → git-mcp → Deployments** and check that the intended deployment completed.
2. Under **Settings → Variables and Secrets → Production**, confirm the three text variables are present and secrets are configured without revealing their values.
3. In the MCP client, complete OAuth and test read-only calls first, such as repository metadata and workflow listing.
4. Test write access separately by creating a disposable file on a test branch, then read it back and check its commit.
5. Test every authorized GitHub account separately. Confirm each session uses its own correct identity/token.

A passing GitHub Actions type-check does not prove that Production is serving the latest commit. Tool discovery alone does not prove write access.

## Troubleshooting

- **401 / Bad credentials:** Check that the OAuth session or token is valid and mapped to the intended account. Reauthorize if appropriate; do not share tokens.
- **403 / Forbidden:** Check the active account's repository access and OAuth permissions.
- **Redirect URI mismatch:** Compare the OAuth App callback URL character-for-character with the callback above.
- **Variables missing after deploy:** Check the active environment (Production vs Preview), deployed version, `wrangler.jsonc`, and Wrangler logs.
- **Account/session mix-up:** Reauthorize and verify token-to-session isolation. Confirm which GitHub account is active before testing writes.
- **Local checks pass but endpoint is stale:** Check Cloudflare's deployment status and which Git branch is connected to Production.

## Security

- Never commit values for `GITHUB_TOKEN`, `GITHUB_OAUTH_CLIENT_SECRET`, `COOKIE_ENCRYPTION_KEY`, or `MCP_AUTH_TOKEN`.
- Rotate any secret that may have been exposed.
- Avoid destructive tests against production branches; use a disposable branch and test file.
- This guide documents expected setup steps. Confirm resource IDs, account settings, and active Production deployment in Cloudflare before changing or deploying anything.
