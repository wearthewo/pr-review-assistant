import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import { NextRequest } from "next/server";

import DashboardPage from "@/app/dashboard/page";
import HomePage from "@/app/page";
import { DisplayText } from "@/components/display-text";
import { resolveBackendOrigin } from "@/lib/backend-origin-validation";
import { BASE_SECURITY_HEADERS, buildContentSecurityPolicy } from "@/lib/security-headers";
import { proxy } from "@/proxy";

const frontendRoot = process.cwd();

test("root and dashboard foundations render semantic content", () => {
  const home = renderToStaticMarkup(<HomePage />);
  const dashboard = renderToStaticMarkup(<DashboardPage />);

  assert.match(home, /<main/);
  assert.match(home, /Signal for the changes that matter/);
  assert.match(dashboard, /Dashboard shell/);
  assert.match(dashboard, /Authentication and tenant authorization are not implemented yet/);
  assert.doesNotMatch(dashboard, /tenantId|fake tenant/i);
});

test("untrusted display text is escaped and remains inert", () => {
  const malicious = '<img src=x onerror="alert(1)"><script>steal()</script>';
  const rendered = renderToStaticMarkup(<DisplayText value={malicious} />);

  assert.doesNotMatch(rendered, /<img|<script/);
  assert.match(rendered, /&lt;img/);
  assert.match(rendered, /&lt;script&gt;/);
});

test("production CSP is nonce-based and denies active untrusted capabilities", () => {
  const policy = buildContentSecurityPolicy("fixed-test-nonce", false);

  assert.match(policy, /script-src 'self' 'nonce-fixed-test-nonce' 'strict-dynamic'/);
  assert.match(policy, /object-src 'none'/);
  assert.match(policy, /frame-ancestors 'none'/);
  assert.match(policy, /base-uri 'self'/);
  assert.match(policy, /form-action 'self'/);
  assert.doesNotMatch(policy, /'unsafe-inline'|'unsafe-eval'/);
});

test("development CSP permits tooling eval without permitting inline scripts", () => {
  const policy = buildContentSecurityPolicy("fixed-test-nonce", true);

  assert.match(policy, /'unsafe-eval'/);
  assert.doesNotMatch(policy, /'unsafe-inline'/);
  assert.doesNotMatch(policy, /upgrade-insecure-requests/);
});

test("baseline response headers prevent sniffing, broad referrers, capabilities, and framing", () => {
  assert.equal(BASE_SECURITY_HEADERS["X-Content-Type-Options"], "nosniff");
  assert.equal(BASE_SECURITY_HEADERS["X-Frame-Options"], "DENY");
  assert.equal(BASE_SECURITY_HEADERS["Referrer-Policy"], "strict-origin-when-cross-origin");
  assert.match(BASE_SECURITY_HEADERS["Permissions-Policy"], /camera=\(\)/);
});

test("proxy attaches the nonce CSP and baseline security headers to application responses", () => {
  const response = proxy(new NextRequest("https://frontend.example/dashboard"));
  const policy = response.headers.get("Content-Security-Policy");

  assert.ok(policy);
  assert.match(policy, /'nonce-[A-Za-z0-9+/=]+'/);
  assert.equal(response.headers.get("X-Content-Type-Options"), "nosniff");
  assert.equal(response.headers.get("X-Frame-Options"), "DENY");
  assert.equal(response.headers.get("Referrer-Policy"), "strict-origin-when-cross-origin");
  assert.ok(response.headers.get("Permissions-Policy"));
});

test("production backend origin is required and HTTPS-only", () => {
  assert.throws(() => resolveBackendOrigin(undefined, "production"), /BACKEND_BASE_URL is required/);
  assert.throws(() => resolveBackendOrigin("http://backend.example", "production"), /HTTPS/);
  assert.equal(resolveBackendOrigin("https://backend.example", "production"), "https://backend.example");
});

test("backend origin rejects credentials and URL components outside an origin", () => {
  assert.throws(() => resolveBackendOrigin("https://user:pass@backend.example", "production"), /credentials/);
  assert.throws(() => resolveBackendOrigin("https://backend.example/api", "production"), /origin/);
  assert.throws(() => resolveBackendOrigin("file:///tmp/socket", "production"), /HTTP or HTTPS/);
});

test("safe local backend origin exists only outside production", () => {
  assert.equal(resolveBackendOrigin(undefined, "development"), "http://127.0.0.1:8080");
  assert.equal(resolveBackendOrigin(undefined, "test"), "http://127.0.0.1:8080");
});

test("server boundary is protected and no client-public secret variable is declared", () => {
  const boundary = readFileSync(join(frontendRoot, "src/lib/server/backend-api.ts"), "utf8");
  const example = readFileSync(join(frontendRoot, "../.env.example"), "utf8");

  assert.match(boundary, /import "server-only"/);
  assert.doesNotMatch(boundary, /NEXT_PUBLIC_/);
  assert.doesNotMatch(example, /NEXT_PUBLIC_.*(?:SECRET|TOKEN|KEY)/);
});

test("production frontend source contains no raw HTML or dynamic code execution", () => {
  const productionFiles = [
    "src/app/page.tsx",
    "src/app/dashboard/page.tsx",
    "src/app/error.tsx",
    "src/app/global-error.tsx",
    "src/components/display-text.tsx",
  ];
  const source = productionFiles
    .map((file) => readFileSync(join(frontendRoot, file), "utf8"))
    .join("\n");

  assert.doesNotMatch(source, /dangerouslySetInnerHTML|\beval\s*\(|new\s+Function\b/);
});
