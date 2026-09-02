# Backend

This directory is reserved for the future Java 21 and Spring Boot 4.1.x backend. The backend will contain the HTTP/webhook API and review-worker responsibilities described in [the architecture](../docs/architecture.md), while keeping domain logic independent of Spring, PostgreSQL, GitHub, and AI provider SDKs.

M0 intentionally contains no Maven project, Maven Wrapper, source tree, dependencies, Flyway migrations, OpenAPI document, configuration, database access, or placeholder application code. Initialization belongs to a later milestone with explicit acceptance criteria.
