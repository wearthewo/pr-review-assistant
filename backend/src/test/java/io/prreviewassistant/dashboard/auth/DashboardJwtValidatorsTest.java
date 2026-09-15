package io.prreviewassistant.dashboard.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class DashboardJwtValidatorsTest {
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final String ISSUER = "https://tenant.auth0.com/";
    private static final String AUDIENCE = "https://api.example";
    private RSAKey signingKey;
    private NimbusJwtDecoder decoder;

    @BeforeEach
    void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) signingKey.toPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(DashboardJwtValidators.forIssuerAndAudience(
                ISSUER, AUDIENCE, Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @Test
    void validRs256IdentityIsAccepted() throws Exception {
        assertThat(decoder.decode(token(signingKey, ISSUER, AUDIENCE,
                NOW.minusSeconds(10), NOW.plusSeconds(300))).getSubject()).isEqualTo("auth0|user-1");
    }

    @Test
    void invalidSignatureAndUnsignedTokenAreRejected() throws Exception {
        RSAKey attacker = new RSAKeyGenerator(2048).keyID("attacker").generate();
        assertThatThrownBy(() -> decoder.decode(token(attacker, ISSUER, AUDIENCE,
                NOW.minusSeconds(10), NOW.plusSeconds(300)))).isInstanceOf(JwtException.class);
        PlainJWT unsigned = new PlainJWT(new JWTClaimsSet.Builder().issuer(ISSUER).subject("auth0|user-1")
                .audience(AUDIENCE).expirationTime(Date.from(NOW.plusSeconds(300))).build());
        assertThatThrownBy(() -> decoder.decode(unsigned.serialize())).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredNotYetValidWrongIssuerAndWrongAudienceTokensAreRejected() throws Exception {
        assertRejected(token(signingKey, ISSUER, AUDIENCE, NOW.minusSeconds(600), NOW.minusSeconds(120)));
        assertRejected(token(signingKey, ISSUER, AUDIENCE, NOW.plusSeconds(120), NOW.plusSeconds(600)));
        assertRejected(token(signingKey, "https://attacker.example/", AUDIENCE,
                NOW.minusSeconds(10), NOW.plusSeconds(300)));
        assertRejected(token(signingKey, ISSUER, "https://other-api.example",
                NOW.minusSeconds(10), NOW.plusSeconds(300)));
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private static String token(
            RSAKey key, String issuer, String audience, Instant notBefore, Instant expiresAt) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .subject("auth0|user-1")
                        .audience(audience)
                        .issueTime(Date.from(NOW.minusSeconds(20)))
                        .notBeforeTime(Date.from(notBefore))
                        .expirationTime(Date.from(expiresAt))
                        .build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
