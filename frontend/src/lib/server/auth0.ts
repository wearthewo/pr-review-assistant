import "server-only";

import { Auth0Client } from "@auth0/nextjs-auth0/server";

import { loadAuthEnvironment } from "@/lib/server/auth-environment";

const environment = loadAuthEnvironment();

export const auth0 = new Auth0Client({
  domain: environment.domain,
  clientId: environment.clientId,
  clientSecret: environment.clientSecret,
  secret: environment.secret,
  appBaseUrl: environment.appBaseUrl,
  authorizationParameters: {
    audience: environment.audience,
    scope: "openid offline_access",
  },
  signInReturnToPath: "/dashboard",
  enableAccessTokenEndpoint: false,
  enableConnectAccountEndpoint: false,
  enableTelemetry: false,
  tokenRefreshBuffer: 60,
  session: {
    rolling: true,
    inactivityDuration: 60 * 60,
    absoluteDuration: 8 * 60 * 60,
    cookie: {
      path: "/",
      sameSite: "lax",
      secure: environment.production,
    },
  },
  transactionCookie: {
    path: "/",
    sameSite: "lax",
    secure: environment.production,
    maxAge: 60 * 10,
  },
  beforeSessionSaved: async (session) => ({
    ...session,
    user: { sub: session.user.sub },
  }),
});
