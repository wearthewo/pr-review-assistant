package io.prreviewassistant.dashboard.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DashboardAuthPropertiesTest {
    @Test
    void completeHttpsConfigurationIsAcceptedAndRedacted() {
        DashboardAuthProperties properties = new DashboardAuthProperties(
                "https://tenant.auth0.com/", "https://api.example", 
                "https://tenant.auth0.com/.well-known/jwks.json");

        assertThat(properties.configured()).isTrue();
        assertThat(properties.toString()).contains("configured=true", "values=<redacted>")
                .doesNotContain("tenant.auth0.com", "api.example");
    }

    @Test
    void blankConfigurationFailsClosedAndPartialOrUnsafeConfigurationIsRejected() {
        assertThat(new DashboardAuthProperties("", "", "").configured()).isFalse();
        assertThatThrownBy(() -> new DashboardAuthProperties(
                "https://tenant.auth0.com/", "", "https://tenant.auth0.com/.well-known/jwks.json"))
                .hasMessage("dashboard authentication configuration is incomplete");
        assertThatThrownBy(() -> new DashboardAuthProperties(
                "https://tenant.auth0.com/", "audience", "https://attacker.example/jwks.json"))
                .hasMessage("dashboard authentication URI is invalid");
        assertThatThrownBy(() -> new DashboardAuthProperties(
                "http://tenant.auth0.com/", "audience", "http://tenant.auth0.com/jwks.json"))
                .hasMessage("dashboard authentication URI is invalid");
    }
}
