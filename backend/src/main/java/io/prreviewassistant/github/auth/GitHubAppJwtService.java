package io.prreviewassistant.github.auth;

import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

public final class GitHubAppJwtService implements GitHubAppJwtProvider {

    static final Duration ISSUED_AT_SKEW = Duration.ofSeconds(60);
    static final Duration TOKEN_LIFETIME = Duration.ofMinutes(9);

    private final GitHubAppProperties properties;
    private final PemPrivateKeyLoader privateKeyLoader;
    private final Clock clock;

    public GitHubAppJwtService(
            GitHubAppProperties properties,
            PemPrivateKeyLoader privateKeyLoader,
            Clock clock) {
        this.properties = properties;
        this.privateKeyLoader = privateKeyLoader;
        this.clock = clock;
    }

    @Override
    public GitHubAppJwt createJwt() {
        Instant now = clock.instant();
        RSAPrivateKey privateKey = privateKeyLoader.load(properties.privateKeyPath());
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.id())
                .issueTime(Date.from(now.minus(ISSUED_AT_SKEW)))
                .expirationTime(Date.from(now.plus(TOKEN_LIFETIME)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        try {
            jwt.sign(new RSASSASigner(privateKey));
            return new GitHubAppJwt(jwt.serialize());
        } catch (JOSEException exception) {
            throw GitHubException.jwtGenerationFailed();
        }
    }
}
