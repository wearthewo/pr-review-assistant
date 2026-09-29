import { expect, test, type BrowserContext, type Page } from "@playwright/test";

const APP = "http://127.0.0.1:3100";
const FIXTURE = "http://127.0.0.1:3101";
const TENANT_A = "11111111-1111-4111-8111-111111111111";
const TENANT_B = "22222222-2222-4222-8222-222222222222";
const FOREIGN_TENANT = "ffffffff-ffff-4fff-8fff-ffffffffffff";

test("real unauthenticated routes expose no dashboard business data or browser credentials", async ({ page }) => {
  for (const path of ["/dashboard", "/dashboard/repositories", "/dashboard/reviews", "/dashboard/usage"]) {
    await page.goto(`${APP}${path}`);
    await expect(page.getByRole("heading", { name: "Sign in to your dashboard" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Sign in securely" })).toHaveAttribute("href", "/auth/login");
    const source = await page.content();
    expect(source).not.toContain("11111111-1111-4111-8111-111111111111");
    expect(source).not.toContain("Authorization: Bearer");
  }
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length })))
    .toEqual({ local: 0, session: 0 });
});

test("real responses retain fresh strict CSP headers and narrow CSRF behavior", async ({ request }) => {
  const first = await request.get(`${APP}/dashboard`);
  const second = await request.get(`${APP}/dashboard`);
  const firstCsp = first.headers()["content-security-policy"] ?? "";
  const secondCsp = second.headers()["content-security-policy"] ?? "";
  expect(firstCsp).toContain("frame-ancestors 'none'");
  expect(firstCsp).toContain("object-src 'none'");
  expect(firstCsp).toContain("base-uri 'self'");
  expect(firstCsp).toContain("form-action 'self'");
  expect(firstCsp).not.toContain("'unsafe-inline'");
  expect(firstCsp).not.toContain("'unsafe-eval'");
  expect(firstCsp).not.toBe(secondCsp);
  expect(first.headers()["x-content-type-options"]).toBe("nosniff");
  expect(first.headers()["x-frame-options"]).toBe("DENY");
  expect(first.headers()["referrer-policy"]).toBe("strict-origin-when-cross-origin");
  expect(first.headers()["permissions-policy"]).toContain("camera=()");

  const foreign = await request.post(`${APP}/github/connect`, {
    headers: { Origin: "https://attacker.invalid", "Sec-Fetch-Site": "cross-site" },
    maxRedirects: 0,
  });
  expect(foreign.status()).toBe(403);
  const missingOrigin = await request.post(`${APP}/github/connect`, { maxRedirects: 0 });
  expect(missingOrigin.status()).toBe(403);
  const sameOrigin = await request.post(`${APP}/github/connect`, {
    headers: { Origin: "https://127.0.0.1:3100", "Sec-Fetch-Site": "same-origin" }, maxRedirects: 0,
  });
  expect(sameOrigin.status()).toBe(401);
  expect((await request.get(`${APP}/github/connect`, { maxRedirects: 0 })).status()).toBe(405);
  const setup = await request.get(`${APP}/github/setup?installation_id=999999`, { maxRedirects: 0 });
  expect(setup.status()).toBe(303);
  expect(setup.headers().location).toBe("https://127.0.0.1:3100/dashboard?connection=setup");
});

test("unbound users cannot bypass onboarding through direct child routes", async ({ page, context }) => {
  await actor(context, "unbound");
  for (const path of ["/dashboard", "/dashboard/repositories", "/dashboard/reviews", "/dashboard/usage"]) {
    await page.goto(`${FIXTURE}${path}?tenant=${FOREIGN_TENANT}`);
    await expect(page.getByRole("heading", { name: "Connect your GitHub workspace next" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Connect GitHub" })).toBeVisible();
    await expect(page.locator("body")).not.toContainText("GitHub repository");
    await expect(page.locator("body")).not.toContainText("Review-analysis quota");
  }
});

test("backend startup is calm and blocks GitHub connection until dashboard recovery", async ({ page, context }) => {
  await actor(context, "starting");
  await page.goto(`${FIXTURE}/dashboard`);
  await expect(page.getByRole("heading", { name: "Starting PullSage" })).toBeVisible();
  await expect(page.getByText("Attempting to reconnect")).toBeVisible();
  await expect(page.getByRole("button", { name: "Connect GitHub" })).toHaveCount(0);
  await expect(page.getByRole("main")).toHaveAttribute("aria-live", "polite");
  await expect(page.getByRole("main")).toHaveAttribute("aria-busy", "true");
  await expect(page.locator("body")).not.toContainText("Render");
});

for (const role of ["owner", "member"] as const) {
  test(`${role.toUpperCase()} can read the coherent read-only dashboard`, async ({ page, context }) => {
    await actor(context, role);
    const tenant = role === "owner" ? TENANT_A : TENANT_B;
    const expectedRepository = role === "owner" ? "#101001" : "#202002";
    const expectedUsage = role === "owner" ? "7 of 50" : "19 of 50";
    await page.goto(`${FIXTURE}/dashboard?tenant=${tenant}`);
    const roleBadge = page.getByText(role.toUpperCase(), { exact: true }).first();
    if (page.viewportSize()!.width <= 720) await expect(roleBadge).toBeAttached();
    else await expect(roleBadge).toBeVisible();
    await page.goto(`${FIXTURE}/dashboard/repositories?tenant=${tenant}`);
    await expect(page.getByText(expectedRepository, { exact: true })).toBeVisible();
    await page.goto(`${FIXTURE}/dashboard/reviews?tenant=${tenant}`);
    await expect(page.getByText("Published to GitHub")).toBeVisible();
    await page.goto(`${FIXTURE}/dashboard/usage?tenant=${tenant}`);
    await expect(page.locator(".quota-total")).toContainText(expectedUsage);
  });
}

test("multi-tenant selection keeps one authorized workspace across navigation", async ({ page, context }) => {
  await actor(context, "multi");
  for (const path of ["/dashboard", "/dashboard/repositories", "/dashboard/reviews", "/dashboard/usage"]) {
    await page.goto(`${FIXTURE}${path}?tenant=${TENANT_B}`);
    await expect(page.locator(".sidebar-account strong")).toHaveText("Workspace 22222222");
    for (const destination of ["/dashboard", "/dashboard/repositories", "/dashboard/reviews", "/dashboard/usage"]) {
      await expect(page.locator(`a[href="${destination}?tenant=${TENANT_B}"]`).first()).toBeAttached();
    }
    await expect(page.locator("body")).not.toContainText("#101001");
  }
  await page.goto(`${FIXTURE}/dashboard?tenant=${TENANT_B}`);
  if (page.viewportSize()!.width <= 720) {
    await page.getByText("Navigation", { exact: true }).click();
  }
  await visibleNavigation(page).getByRole("link", { name: "Repositories" }).click();
  await expect(page).toHaveURL(`${FIXTURE}/dashboard/repositories?tenant=${TENANT_B}`);
  await expect(page.getByText("#202002", { exact: true })).toBeVisible();
});

test("workspace URL tampering fails closed without disclosure", async ({ page, context }) => {
  await actor(context, "owner");
  for (const suffix of [
    `tenant=${FOREIGN_TENANT}`,
    "tenant=not-a-uuid",
    "tenant=",
    `tenant=${TENANT_A}&tenant=${TENANT_B}`,
    `tenant=${FOREIGN_TENANT}&unexpected=value`,
  ]) {
    for (const path of ["/dashboard", "/dashboard/repositories", "/dashboard/reviews", "/dashboard/usage"]) {
      await page.goto(`${FIXTURE}${path}?${suffix}`);
      await expect(page.getByRole("heading", { name: "That workspace cannot be opened." })).toBeVisible();
      await expect(page.locator("body")).not.toContainText("#202002");
    }
  }
});

test("hostile URL input remains inert and browser storage stays empty", async ({ page, context }) => {
  await actor(context, "owner");
  const hostile = encodeURIComponent('<script>window.pwned="token"</script>');
  await page.goto(`${FIXTURE}/dashboard/repositories?tenant=${hostile}`);
  await expect(page.getByRole("heading", { name: "That workspace cannot be opened." })).toBeVisible();
  expect(await page.locator("script").count()).toBe(0);
  expect(await page.evaluate(() => ({
    local: localStorage.length,
    session: sessionStorage.length,
    pwned: (window as typeof window & { pwned?: string }).pwned ?? null,
  }))).toEqual({ local: 0, session: 0, pwned: null });
});

test("navigation, progress, focus structure and responsive layout remain accessible", async ({
  page, context, isMobile,
}) => {
  await actor(context, "owner");
  await page.goto(`${FIXTURE}/dashboard/usage?tenant=${TENANT_A}`);
  await expect(page.getByRole("main")).toBeVisible();
  await expect(page.getByRole("progressbar", { name: "Review-analysis quota used" })).toHaveAttribute("max", "50");
  await expect(page.getByText("Settings", { exact: false }).first()).toHaveAttribute("aria-disabled", "true");
  await expect(page.locator('a[aria-current="page"]', { hasText: "Usage" }).first()).toBeAttached();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  if (isMobile) {
    await page.getByText("Navigation", { exact: true }).click();
    await expect(page.locator(".mobile-header").getByRole("link", { name: "Repositories" })).toBeVisible();
  } else {
    await expect(page.locator(".sidebar").getByRole("link", { name: "Repositories" })).toBeVisible();
  }
});

async function actor(context: BrowserContext, value: "owner" | "member" | "multi" | "unbound" | "starting") {
  await context.addCookies([{ name: "m13g_actor", value, url: FIXTURE, httpOnly: true, sameSite: "Lax" }]);
}

function visibleNavigation(page: Page) {
  return page.viewportSize()!.width <= 720
    ? page.locator(".mobile-header").getByRole("navigation", { name: "Primary" })
    : page.locator(".sidebar").getByRole("navigation", { name: "Primary" });
}
