# Monitoring configuration

`prometheus-alerts.yml` is the vendor-neutral M21 alert policy for application metrics. It is configuration only: this repository does not deploy Prometheus, an alert manager, or a monitoring vendor.

Before launch, an operator must configure an authenticated collector for the protected backend `/actuator/prometheus` endpoint, provide a separate readiness probe for `/actuator/health/readiness`, import these rules into the selected provider, route `page` and `ticket` severities, and test every route. The collector credential must be stored in the provider secret store and must never be committed.

Metric names are the Prometheus export of the bounded Micrometer names documented in `docs/observability.md`. If a provider rewrites metric names, translate the rules without changing their meaning and record the final mapping in the production change record.
