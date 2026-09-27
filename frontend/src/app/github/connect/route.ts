import { isTrustedMutationRequest, redirectToGitHubAuthorization } from "@/lib/github-connection-core";
import { applicationOrigin, beginGitHubConnection } from "@/lib/server/github-connection";

export async function POST(request: Request) {
  if (!isTrustedMutationRequest(request, applicationOrigin())) {
    return new Response(null, { status: 403 });
  }
  try {
    const destination = await beginGitHubConnection();
    if (destination === null) return new Response(null, { status: 401 });
    return redirectToGitHubAuthorization(destination);
  } catch {
    return Response.redirect(new URL("/dashboard?connection=failed", applicationOrigin()), 303);
  }
}
