import { applicationOrigin } from "@/lib/server/github-connection";

export function GET() {
  // GitHub warns that setup_url installation_id is spoofable. Ignore every query value.
  return Response.redirect(new URL("/dashboard?connection=setup", applicationOrigin()), 303);
}
