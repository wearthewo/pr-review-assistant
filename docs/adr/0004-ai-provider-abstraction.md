# ADR 0004: Isolate AI providers behind an internal abstraction

- Status: Accepted
- Date: 2026-09-02

## Context

The review pipeline will initially use OpenAI, but model APIs expose provider-specific request types, streaming behavior, credentials, limits, errors, and structured-output capabilities. Domain review policy must remain stable, testable, and safe when a provider changes or fails. Model output is probabilistic and untrusted.

## Decision

Define an internal, provider-neutral AI analysis contract owned by the application. The contract accepts bounded review context and policy-relevant options and returns structured candidate findings plus provider-neutral usage and failure information. OpenAI is implemented as an outbound adapter. Domain logic, validation, ranking, and publication do not depend on OpenAI SDK types or provider-specific semantics.

The abstraction is intentionally narrow: it supports the demonstrated review-analysis use case, not a generalized multi-provider framework. It does not imply simultaneous provider routing, automatic failover, or feature parity across vendors.

## Rationale

- Keeps domain policy and tests independent of a remote provider and SDK.
- Centralizes credential handling, timeouts, quotas, content minimization, response validation, and error translation.
- Makes controlled provider doubles possible for deterministic tests.
- Limits migration impact if models, API versions, commercial terms, or data-handling requirements change.
- Ensures all provider output passes the same validation and ranking boundary.

## Consequences

- The internal contract must not collapse into a mirror of the first provider's API.
- Provider-specific capabilities may be unavailable until deliberately represented in the internal contract.
- Adapters need contract tests for valid output, malformed output, throttling, timeout, refusal, and failure mapping.
- The application owns prompt/version policy and must record enough bounded metadata for diagnosis without logging source content or secrets.

## Alternatives considered

- Call the OpenAI SDK directly from domain/application logic: less initial code, but creates pervasive coupling, weaker testability, and inconsistent security/failure handling.
- Adopt a broad third-party AI orchestration framework: adds dependencies and abstractions before requirements justify them and may leak its model into the domain.
- Build multi-provider routing immediately: speculative complexity without an availability, cost, or compliance requirement.

## Revisit when

A second provider, local model, advanced routing policy, or materially different modality has a demonstrated requirement. Extend the internal contract only for concrete use cases and record material changes in an ADR.
