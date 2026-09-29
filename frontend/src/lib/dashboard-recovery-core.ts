import type { DashboardState } from "@/lib/dashboard-session-core";

export const DASHBOARD_RECOVERY_DELAYS_MS = Object.freeze([
  5_000,
  10_000,
  15_000,
  20_000,
  20_000,
  20_000,
  20_000,
  20_000,
  20_000,
] as const);
export const DASHBOARD_RECOVERY_REQUEST_TIMEOUT_MS = 8_000;

export type DashboardRecoveryProbeResult = "READY" | "RETRYABLE" | "TERMINAL";
export type DashboardRecoveryWait = (delayMs: number, signal: AbortSignal) => Promise<void>;

interface DashboardRecoveryOptions {
  probe(signal: AbortSignal): Promise<DashboardRecoveryProbeResult>;
  onReady(): void;
  onTerminal(): void;
  onExhausted(): void;
  wait?: DashboardRecoveryWait;
  delays?: readonly number[];
}

export interface DashboardRecoveryController {
  cancel(): void;
  done: Promise<void>;
}

export function startDashboardRecovery(options: DashboardRecoveryOptions): DashboardRecoveryController {
  const controller = new AbortController();
  const wait = options.wait ?? waitForDelay;
  const delays = options.delays ?? DASHBOARD_RECOVERY_DELAYS_MS;
  const done = runRecovery(options, delays, wait, controller.signal);
  return { cancel: () => controller.abort(), done };
}

export function dashboardRecoveryStatus(state: DashboardState): 204 | 401 | 409 | 503 {
  if (state.status === "authenticated") return 204;
  if (state.status === "unauthenticated") return 401;
  return state.kind === "BACKEND_STARTING" ? 503 : 409;
}

export async function probeDashboardRecovery(
  fetchImplementation: typeof fetch,
  lifecycleSignal: AbortSignal,
  timeoutSignal: AbortSignal = AbortSignal.timeout(DASHBOARD_RECOVERY_REQUEST_TIMEOUT_MS),
): Promise<DashboardRecoveryProbeResult> {
  const requestSignal = AbortSignal.any([lifecycleSignal, timeoutSignal]);
  try {
    const response = await fetchImplementation("/api/dashboard/recovery", {
      method: "GET",
      headers: { Accept: "application/json" },
      cache: "no-store",
      credentials: "same-origin",
      redirect: "error",
      signal: requestSignal,
    });
    if (response.status === 204) return "READY";
    if (response.status === 502 || response.status === 503 || response.status === 504) return "RETRYABLE";
    return "TERMINAL";
  } catch (error) {
    if (lifecycleSignal.aborted) throw error;
    return "RETRYABLE";
  }
}

async function runRecovery(
  options: DashboardRecoveryOptions,
  delays: readonly number[],
  wait: DashboardRecoveryWait,
  signal: AbortSignal,
): Promise<void> {
  for (const delay of delays) {
    try {
      await wait(delay, signal);
      if (signal.aborted) return;
      const result = await options.probe(signal);
      if (signal.aborted) return;
      if (result === "READY") {
        options.onReady();
        return;
      }
      if (result === "TERMINAL") {
        options.onTerminal();
        return;
      }
    } catch {
      if (signal.aborted) return;
    }
  }
  if (!signal.aborted) options.onExhausted();
}

function waitForDelay(delayMs: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) {
      resolve();
      return;
    }
    const finish = () => {
      clearTimeout(timeout);
      signal.removeEventListener("abort", finish);
      resolve();
    };
    const timeout = setTimeout(finish, delayMs);
    signal.addEventListener("abort", finish, { once: true });
  });
}
