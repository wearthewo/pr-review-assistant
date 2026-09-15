import "server-only";

import { resolveBackendOrigin, type RuntimeEnvironment } from "@/lib/backend-origin-validation";

function runtimeEnvironment(value: string | undefined): RuntimeEnvironment {
  if (value === "production" || value === "test") {
    return value;
  }
  return "development";
}

export function getBackendOrigin(): string {
  return resolveBackendOrigin(
    process.env.BACKEND_BASE_URL,
    runtimeEnvironment(process.env.NODE_ENV),
  );
}

export function createBackendUrl(path: `/${string}`): URL {
  if (path.startsWith("//")) {
    throw new Error("Backend API path must be application-relative");
  }
  const origin = getBackendOrigin();
  const url = new URL(path, `${origin}/`);
  if (url.origin !== origin) {
    throw new Error("Backend API URL escaped the configured origin");
  }
  return url;
}
