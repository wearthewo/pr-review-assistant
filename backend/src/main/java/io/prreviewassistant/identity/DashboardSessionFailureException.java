package io.prreviewassistant.identity;

public final class DashboardSessionFailureException extends RuntimeException {
    public enum Stage {
        IDENTITY_MAPPING,
        APPLICATION_USER_PROVISIONING,
        MEMBERSHIP_LOOKUP,
        RESPONSE_MAPPING
    }

    private final Stage stage;

    public DashboardSessionFailureException(Stage stage, Throwable cause) {
        super("DASHBOARD_SESSION_FAILED", cause);
        this.stage = java.util.Objects.requireNonNull(stage, "stage is required");
    }

    public Stage stage() {
        return stage;
    }

    @Override
    public String toString() {
        return "DashboardSessionFailureException[stage=" + stage + "]";
    }
}
