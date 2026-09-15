import { NextRequest, NextResponse } from "next/server";

import { BASE_SECURITY_HEADERS, buildContentSecurityPolicy } from "@/lib/security-headers";

const ALLOWED_AUTH_ROUTES = new Set([
  "/auth/login",
  "/auth/logout",
  "/auth/callback",
  "/auth/backchannel-logout",
]);
const FIXED_LOGIN_DESTINATION = "/dashboard";
const FIXED_LOGOUT_DESTINATION = "/";

export type AuthMiddleware = (request: NextRequest) => Promise<NextResponse>;

export async function secureProxy(
  request: NextRequest,
  authenticate: AuthMiddleware,
): Promise<NextResponse> {
  const nonce = Buffer.from(crypto.randomUUID()).toString("base64");
  const contentSecurityPolicy = buildContentSecurityPolicy(
    nonce,
    process.env.NODE_ENV !== "production",
  );
  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-nonce", nonce);
  requestHeaders.set("Content-Security-Policy", contentSecurityPolicy);

  const authenticatedRequest = new NextRequest(request, { headers: requestHeaders });
  const authRoute = request.nextUrl.pathname.startsWith("/auth/");
  let response: NextResponse;
  if (authRoute && !ALLOWED_AUTH_ROUTES.has(request.nextUrl.pathname)) {
    response = new NextResponse(null, { status: 404 });
  } else if (request.nextUrl.pathname === "/auth/login"
      && request.nextUrl.searchParams.has("returnTo")
      && request.nextUrl.searchParams.get("returnTo") !== FIXED_LOGIN_DESTINATION) {
    response = new NextResponse(null, { status: 400 });
  } else if (request.nextUrl.pathname === "/auth/logout"
      && request.nextUrl.searchParams.has("returnTo")
      && request.nextUrl.searchParams.get("returnTo") !== FIXED_LOGOUT_DESTINATION) {
    response = new NextResponse(null, { status: 400 });
  } else {
    response = await authenticate(authenticatedRequest);
  }
  response.headers.set("Content-Security-Policy", contentSecurityPolicy);
  for (const [name, value] of Object.entries(BASE_SECURITY_HEADERS)) {
    response.headers.set(name, value);
  }
  return response;
}
