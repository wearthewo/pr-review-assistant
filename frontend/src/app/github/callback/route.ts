import { parseGitHubCallback } from "@/lib/github-connection-core";
import { applicationOrigin, completeGitHubConnection } from "@/lib/server/github-connection";

export async function GET(request: Request) {
  try {
    const source = new URL(request.url);
    const { code, state } = parseGitHubCallback(source.searchParams);
    const path = await completeGitHubConnection(code, state);
    if (path === null) return Response.redirect(new URL("/auth/login", applicationOrigin()), 303);
    return Response.redirect(new URL(path, applicationOrigin()), 303);
  } catch {
    return Response.redirect(new URL("/dashboard?connection=failed", applicationOrigin()), 303);
  }
}
