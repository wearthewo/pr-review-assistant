const MAX_CALLBACK_VALUE_LENGTH = 512;
const RESULT_PATHS = new Map([
  ["CONNECTED", "/dashboard?connection=connected"],
  ["ALREADY_CONNECTED", "/dashboard?connection=connected"],
  ["NO_ELIGIBLE_INSTALLATION", "/dashboard?connection=no-installation"],
  ["MULTIPLE_INSTALLATIONS_UNSUPPORTED", "/dashboard?connection=multiple"],
  ["OWNERSHIP_CONFLICT", "/dashboard?connection=conflict"],
]);

export function isTrustedMutationRequest(request: Request, applicationOrigin: string): boolean {
  const origin = request.headers.get("origin");
  const fetchSite = request.headers.get("sec-fetch-site");
  return origin === applicationOrigin && (fetchSite === "same-origin" || fetchSite === "same-site");
}

export function validateGitHubAuthorizationUrl(value: unknown, expectedOrigin: string): URL {
  if (typeof value !== "string" || value.length > 4096) {
    throw new Error("GitHub authorization response is invalid");
  }
  const url = new URL(value);
  if (url.origin !== expectedOrigin || url.pathname !== "/login/oauth/authorize"
      || url.username || url.password || url.hash) {
    throw new Error("GitHub authorization target is not trusted");
  }
  return url;
}

export function redirectToGitHubAuthorization(destination: URL): Response {
  return Response.redirect(destination, 303);
}

export function validateCallbackValue(value: string | null): string {
  if (!value || value.length > MAX_CALLBACK_VALUE_LENGTH || /[\u0000-\u001f\u007f]/.test(value)) {
    throw new Error("GitHub callback input is invalid");
  }
  return value;
}

export function parseGitHubCallback(searchParams: URLSearchParams): { code: string; state: string } {
  const codes = searchParams.getAll("code");
  const states = searchParams.getAll("state");
  if (codes.length !== 1 || states.length !== 1 || searchParams.has("error")) {
    throw new Error("GitHub callback input is invalid");
  }
  return { code: validateCallbackValue(codes[0]), state: validateCallbackValue(states[0]) };
}

export function dashboardPathForConnectionResult(result: unknown): string {
  return typeof result === "string" ? RESULT_PATHS.get(result) ?? "/dashboard?connection=failed"
    : "/dashboard?connection=failed";
}

export function connectionMessage(value: string | null): readonly [string, string] | null {
  switch (value) {
    case "connected": return ["GitHub connected", "Your verified workspace membership is now active."];
    case "no-installation": return ["No eligible installation found", "Install the GitHub App on your personal account, then try again."];
    case "multiple": return ["More than one installation matched", "Selection is not available yet, so no workspace was connected."];
    case "conflict": return ["Workspace already has an owner", "No ownership change was made."];
    case "failed": return ["GitHub connection failed", "No workspace access was granted. Start the connection again."];
    case "setup": return ["Installation received", "Sign in and use Connect GitHub so ownership can be verified securely."];
    default: return null;
  }
}
