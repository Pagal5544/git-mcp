import { env } from "cloudflare:workers";
import type { Env } from "./types";
import type { AuthRequest, OAuthHelpers } from "@cloudflare/workers-oauth-provider";
import { Hono } from "hono";
import { Octokit } from "octokit";
import { fetchUpstreamAuthToken, getUpstreamAuthorizeUrl, type Props } from "./utils";
import {
  addApprovedClient, bindStateToSession, createOAuthState, generateCSRFProtection,
  isClientApproved, OAuthError, renderApprovalDialog, validateCSRFToken, validateOAuthState,
} from "./workers-oauth-utils";

const app = new Hono<{ Bindings: Env & { OAUTH_PROVIDER: OAuthHelpers } }>();

app.get("/authorize", async (c) => {
  let oauthReqInfo;
  try { oauthReqInfo = await c.env.OAUTH_PROVIDER.parseAuthRequest(c.req.raw); }
  catch (error) {
    console.error("GET /authorize parseAuthRequest error:", error);
    return c.text("Invalid or incomplete OAuth authorization request. Start authorization from your MCP client.", 400);
  }
  const { clientId } = oauthReqInfo;
  if (!clientId) return c.text("Invalid request", 400);

  if (await isClientApproved(c.req.raw, clientId, c.env.COOKIE_ENCRYPTION_KEY)) {
    const { stateToken } = await createOAuthState(oauthReqInfo, c.env.OAUTH_KV);
    const { setCookie } = await bindStateToSession(stateToken);
    return redirectToGithub(c.req.raw, stateToken, c.env.GITHUB_OAUTH_CLIENT_ID, setCookie);
  }

  const { token: csrfToken, setCookie } = generateCSRFProtection();
  return renderApprovalDialog(c.req.raw, {
    client: await c.env.OAUTH_PROVIDER.lookupClient(clientId), csrfToken,
    server: { description: "GitHub-authenticated MCP server for repository operations.", logo: "https://avatars.githubusercontent.com/u/314135?s=200&v=4", name: "GitHub MCP Server" },
    setCookie, state: { oauthReqInfo },
  });
});

app.post("/authorize", async (c) => {
  try {
    const formData = await c.req.raw.formData();
    validateCSRFToken(formData, c.req.raw);
    const encodedState = formData.get("state");
    if (!encodedState || typeof encodedState !== "string") return c.text("Missing state in form data", 400);
    let state: { oauthReqInfo?: AuthRequest };
    try { state = JSON.parse(atob(encodedState)); }
    catch { return c.text("Invalid state data", 400); }
    if (!state.oauthReqInfo?.clientId) return c.text("Invalid request", 400);

    const approvedCookie = await addApprovedClient(c.req.raw, state.oauthReqInfo.clientId, c.env.COOKIE_ENCRYPTION_KEY);
    const { stateToken } = await createOAuthState(state.oauthReqInfo, c.env.OAUTH_KV);
    const { setCookie: bindingCookie } = await bindStateToSession(stateToken);
    const headers = new Headers();
    headers.append("Set-Cookie", approvedCookie);
    headers.append("Set-Cookie", bindingCookie);
    return redirectToGithub(c.req.raw, stateToken, c.env.GITHUB_OAUTH_CLIENT_ID, headers);
  } catch (error: any) {
    console.error("POST /authorize error:", error);
    if (error instanceof OAuthError) return error.toResponse();
    return c.text("Internal server error", 500);
  }
});

function redirectToGithub(request: Request, stateToken: string, clientId: string, headers?: HeadersInit) {
  const responseHeaders = new Headers(headers);
  responseHeaders.set("Location", getUpstreamAuthorizeUrl({
    client_id: clientId,
    redirect_uri: new URL("/callback", request.url).href,
    scope: "read:user repo workflow",
    state: stateToken,
    upstream_url: "https://github.com/login/oauth/authorize",
  }));
  return new Response(null, { headers: responseHeaders, status: 302 });
}

app.get("/callback", async (c) => {
  let oauthReqInfo: AuthRequest;
  let clearSessionCookie: string;
  try {
    const result = await validateOAuthState(c.req.raw, c.env.OAUTH_KV);
    oauthReqInfo = result.oauthReqInfo;
    clearSessionCookie = result.clearCookie;
  } catch (error: any) {
    if (error instanceof OAuthError) return error.toResponse();
    console.error("OAuth state validation failed:", error);
    return c.text("Internal server error", 500);
  }
  if (!oauthReqInfo.clientId) return c.text("Invalid OAuth request data", 400);

  const [accessToken, errResponse] = await fetchUpstreamAuthToken({
    client_id: c.env.GITHUB_OAUTH_CLIENT_ID,
    client_secret: c.env.GITHUB_OAUTH_CLIENT_SECRET,
    code: c.req.query("code"),
    redirect_uri: new URL("/callback", c.req.url).href,
    upstream_url: "https://github.com/login/oauth/access_token",
  });
  if (errResponse) return errResponse;

  try {
    const user = await new Octokit({ auth: accessToken }).rest.users.getAuthenticated();
    const { login, name, email } = user.data;
    const { redirectTo } = await c.env.OAUTH_PROVIDER.completeAuthorization({
      metadata: { label: name || login },
      props: { accessToken, email, login, name: name || login } as Props,
      request: oauthReqInfo,
      scope: oauthReqInfo.scope,
      userId: String(login),
    });
    const headers = new Headers({ Location: redirectTo });
    if (clearSessionCookie) headers.append("Set-Cookie", clearSessionCookie);
    return new Response(null, { status: 302, headers });
  } catch (error) {
    console.error("GitHub OAuth callback failed:", error);
    return c.text("Unable to complete GitHub authorization.", 502);
  }
});

export { app as GitHubHandler };
