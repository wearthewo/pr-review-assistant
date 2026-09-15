import "server-only";

import { resolveAuthEnvironment, type AuthEnvironment } from "@/lib/auth-environment-validation";

export type { AuthEnvironment };

export function loadAuthEnvironment(
  environment: NodeJS.ProcessEnv = process.env,
): AuthEnvironment {
  return resolveAuthEnvironment(environment);
}
