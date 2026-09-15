import type { NextRequest } from "next/server";

import { secureProxy } from "@/lib/proxy-core";
import { auth0 } from "@/lib/server/auth0";

export function proxy(request: NextRequest) {
  return secureProxy(request, (authenticatedRequest) => auth0.middleware(authenticatedRequest));
}

export const config = {
  matcher: [
    {
      source: "/((?!_next/static|_next/image|favicon.ico|robots.txt|sitemap.xml).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};
