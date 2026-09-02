package io.prreviewassistant.github.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubAppJwtServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:15:30Z");

    @TempDir
    Path tempDirectory;

    @Test
    void createsSignedRs256JwtWithGitHubClaimsAndShortLifetime() throws Exception {
        KeyPair keyPair = TestRsaKeys.generate();
        Path keyPath = TestRsaKeys.writePkcs8(tempDirectory.resolve("app.pem"), keyPair);
        GitHubAppJwtService service = service(keyPath);

        GitHubAppJwt secretJwt = service.createJwt();
        SignedJWT jwt = SignedJWT.parse(secretJwt.value());

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("test-client-id");
        assertThat(jwt.getJWTClaimsSet().getIssueTime().toInstant())
                .isEqualTo(NOW.minus(GitHubAppJwtService.ISSUED_AT_SKEW));
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant())
                .isEqualTo(NOW.plus(GitHubAppJwtService.TOKEN_LIFETIME));
        assertThat(Duration.between(
                jwt.getJWTClaimsSet().getIssueTime().toInstant(),
                jwt.getJWTClaimsSet().getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofMinutes(10));
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) keyPair.getPublic()))).isTrue();
    }

    @Test
    void loadsGitHubGeneratedPkcs1PrivateKeyFormat() throws Exception {
        KeyPair keyPair = TestRsaKeys.generate();
        Path keyPath = TestRsaKeys.writePkcs1(tempDirectory.resolve("github-generated.pem"), keyPair);

        SignedJWT jwt = SignedJWT.parse(service(keyPath).createJwt().value());

        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) keyPair.getPublic()))).isTrue();
    }

    @Test
    void malformedPrivateKeyFailsWithoutExposingMaterial() throws Exception {
        String privateMaterial = "PRIVATE-KEY-MATERIAL-MUST-STAY-SECRET";
        Path keyPath = Files.writeString(tempDirectory.resolve("invalid.pem"), privateMaterial);

        assertThatThrownBy(() -> service(keyPath).createJwt())
                .isInstanceOf(GitHubException.class)
                .extracting(Throwable::toString)
                .asString()
                .doesNotContain(privateMaterial)
                .contains("configuration is invalid");
    }

    @Test
    void missingPrivateKeyFailsAsInvalidConfiguration() {
        assertThatThrownBy(() -> service(tempDirectory.resolve("missing.pem")).createJwt())
                .isInstanceOfSatisfying(GitHubException.class,
                        exception -> assertThat(exception.type())
                                .isEqualTo(GitHubErrorType.INVALID_CONFIGURATION));
    }

    @Test
    void jwtSecretDoesNotAppearInToString() {
        String jwt = "header.payload.signature-secret";

        assertThat(new GitHubAppJwt(jwt).toString())
                .doesNotContain(jwt)
                .contains("REDACTED");
    }

    private GitHubAppJwtService service(Path keyPath) {
        GitHubAppProperties properties = new GitHubAppProperties(
                "test-client-id",
                keyPath,
                URI.create("https://api.github.test"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofMinutes(5));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new GitHubAppJwtService(properties, new PemPrivateKeyLoader(), clock);
    }
}
