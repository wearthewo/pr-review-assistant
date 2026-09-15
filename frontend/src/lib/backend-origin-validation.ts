const LOCAL_BACKEND_ORIGIN = "http://127.0.0.1:8080";

export type RuntimeEnvironment = "development" | "production" | "test";

export function resolveBackendOrigin(
  configuredValue: string | undefined,
  environment: RuntimeEnvironment,
): string {
  const candidate = configuredValue?.trim();
  if (!candidate) {
    if (environment === "development" || environment === "test") {
      return LOCAL_BACKEND_ORIGIN;
    }
    throw new Error("BACKEND_BASE_URL is required in production");
  }

  let parsed: URL;
  try {
    parsed = new URL(candidate);
  } catch {
    throw new Error("BACKEND_BASE_URL must be a valid absolute URL");
  }

  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new Error("BACKEND_BASE_URL must use HTTP or HTTPS");
  }
  if (environment === "production" && parsed.protocol !== "https:") {
    throw new Error("BACKEND_BASE_URL must use HTTPS in production");
  }
  if (parsed.username || parsed.password) {
    throw new Error("BACKEND_BASE_URL must not contain credentials");
  }
  if (parsed.pathname !== "/" || parsed.search || parsed.hash) {
    throw new Error("BACKEND_BASE_URL must be an origin without path, query, or fragment");
  }

  return parsed.origin;
}
