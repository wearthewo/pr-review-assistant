package io.prreviewassistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
        "DB_JDBC_URL=jdbc:postgresql://invalid:5432/invalid",
        "DB_USERNAME=test",
        "DB_PASSWORD=test",
        "GITHUB_APP_ID=test-app-id",
        "GITHUB_PRIVATE_KEY_PATH=unused-test-key.pem"
})
@Import(PostgreSqlTestConfiguration.class)
class PrReviewAssistantApplicationTest {

    @Test
    void contextLoads() {
    }
}
