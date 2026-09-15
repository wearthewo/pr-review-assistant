const MAX_VALUE_LENGTH = 2048;

export interface AuthEnvironment {
  domain: string;
  clientId: string;
  clientSecret: string;
  secret: string;
  audience: string;
  appBaseUrl: string;
  production: boolean;
}

export function resolveAuthEnvironment(environment: NodeJS.ProcessEnv): AuthEnvironment {
  const production = environment.NODE_ENV === "production";
  const domain = required(environment.AUTH0_DOMAIN, "AUTH0_DOMAIN");
  const clientId = required(environment.AUTH0_CLIENT_ID, "AUTH0_CLIENT_ID");
  const clientSecret = required(environment.AUTH0_CLIENT_SECRET, "AUTH0_CLIENT_SECRET");
  const secret = required(environment.AUTH0_SECRET, "AUTH0_SECRET");
  const audience = required(environment.AUTH0_AUDIENCE, "AUTH0_AUDIENCE");
  const appBaseUrl = validateApplicationOrigin(
    required(environment.APP_BASE_URL, "APP_BASE_URL"),
    production,
  );

  if (!/^[A-Za-z0-9.-]+$/.test(domain) || domain.includes("..")) {
    throw new Error("AUTH0_DOMAIN must be a hostname without a scheme or path");
  }
  if (!/^[a-fA-F0-9]{64}$/.test(secret)) {
    throw new Error("AUTH0_SECRET must be a 32-byte hex value");
  }
  if (audience.length > 512 || /[\u0000-\u001f\u007f]/.test(audience)) {
    throw new Error("AUTH0_AUDIENCE is invalid");
  }

  return { domain, clientId, clientSecret, secret, audience, appBaseUrl, production };
}

function required(value: string | undefined, name: string): string {
  const normalized = value?.trim();
  if (!normalized || normalized.length > MAX_VALUE_LENGTH) {
    throw new Error(`${name} is required and must be bounded`);
  }
  return normalized;
}

function validateApplicationOrigin(value: string, production: boolean): string {
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error("APP_BASE_URL must be a valid absolute URL");
  }
  const localHttp = parsed.protocol === "http:"
    && (parsed.hostname === "127.0.0.1" || parsed.hostname === "localhost")
    && !production;
  if (parsed.protocol !== "https:" && !localHttp) {
    throw new Error("APP_BASE_URL must use HTTPS outside local development");
  }
  if (parsed.username || parsed.password || parsed.pathname !== "/" || parsed.search || parsed.hash) {
    throw new Error("APP_BASE_URL must be a credential-free origin");
  }
  return parsed.origin;
}
