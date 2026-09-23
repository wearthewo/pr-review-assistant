package io.prreviewassistant.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class RenderPostgresPropertiesTest {

    @Test
    void convertsRenderInternalConnectionReferenceToTlsRequiredJdbcUrl() {
        RenderPostgresProperties properties = new RenderPostgresProperties(
                "postgresql://render_user:secret@dpg-example.internal:5432/pr_review_assistant",
                "render_user", "secret", "pr_review_assistant");

        assertThat(properties.jdbcUrl())
                .isEqualTo("jdbc:postgresql://dpg-example.internal:5432/pr_review_assistant?sslmode=require");
        assertThat(properties.toString()).doesNotContain("secret", "render_user", "dpg-example");
    }

    @Test
    void rejectsMismatchedDatabaseAndUnsafeConnectionUrisWithoutEchoingSecrets() {
        RenderPostgresProperties mismatched = new RenderPostgresProperties(
                "postgresql://render_user:secret@dpg-example.internal:5432/other_database",
                "render_user", "secret", "pr_review_assistant");

        assertThatIllegalArgumentException().isThrownBy(mismatched::jdbcUrl)
                .withMessage("production database configuration is invalid")
                .withMessageNotContaining("secret")
                .withMessageNotContaining("other_database");
        assertThatIllegalArgumentException().isThrownBy(() -> new RenderPostgresProperties(
                "https://render_user:secret@dpg-example.internal/pr_review_assistant",
                "render_user", "secret", "pr_review_assistant").jdbcUrl())
                .withMessage("production database configuration is invalid")
                .withMessageNotContaining("secret");
    }
}
