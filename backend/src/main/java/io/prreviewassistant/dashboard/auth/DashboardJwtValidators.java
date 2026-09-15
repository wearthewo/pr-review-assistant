package io.prreviewassistant.dashboard.auth;

import java.time.Clock;
import java.util.List;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

final class DashboardJwtValidators {
    private DashboardJwtValidators() {
    }

    static OAuth2TokenValidator<Jwt> forIssuerAndAudience(String issuer, String audience) {
        return forIssuerAndAudience(issuer, audience, Clock.systemUTC());
    }

    static OAuth2TokenValidator<Jwt> forIssuerAndAudience(String issuer, String audience, Clock clock) {
        JwtTimestampValidator timestamp = new JwtTimestampValidator();
        timestamp.setClock(clock);
        timestamp.setAllowEmptyExpiryClaim(false);
        OAuth2TokenValidator<Jwt> issuerValidator = new JwtIssuerValidator(issuer);
        OAuth2TokenValidator<Jwt> intendedAudience = new JwtClaimValidator<List<String>>(
                "aud", audiences -> audiences != null && audiences.contains(audience));
        return new DelegatingOAuth2TokenValidator<>(timestamp, issuerValidator, intendedAudience);
    }
}
