package io.prreviewassistant.dashboard.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DashboardAuthProperties.class)
public class DashboardSecurityConfiguration {
    @Bean
    SecurityFilterChain dashboardSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/api/webhooks/github")
                        .permitAll()
                        .requestMatchers("/actuator/prometheus").authenticated()
                        .requestMatchers("/api/dashboard/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, exception) ->
                                response.setStatus(401)))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) ->
                        response.setStatus(403)));
        return http.build();
    }

    @Bean
    JwtDecoder dashboardJwtDecoder(DashboardAuthProperties properties) {
        if (!properties.configured()) {
            return token -> {
                throw new BadJwtException("dashboard authentication is not configured");
            };
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(DashboardJwtValidators.forIssuerAndAudience(
                properties.issuer(), properties.audience()));
        return decoder;
    }
}
