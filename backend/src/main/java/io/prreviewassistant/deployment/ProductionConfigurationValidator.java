package io.prreviewassistant.deployment;

import io.prreviewassistant.dashboard.auth.DashboardAuthProperties;
import io.prreviewassistant.dashboard.github.GitHubConnectionProperties;
import io.prreviewassistant.github.auth.GitHubAppProperties;
import io.prreviewassistant.github.auth.PemPrivateKeyLoader;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("production")
final class ProductionConfigurationValidator implements SmartInitializingSingleton {

    private final DashboardAuthProperties dashboardAuth;
    private final GitHubConnectionProperties githubConnection;
    private final GitHubAppProperties githubApp;
    private final PemPrivateKeyLoader privateKeyLoader;

    ProductionConfigurationValidator(
            DashboardAuthProperties dashboardAuth,
            GitHubConnectionProperties githubConnection,
            GitHubAppProperties githubApp,
            PemPrivateKeyLoader privateKeyLoader) {
        this.dashboardAuth = dashboardAuth;
        this.githubConnection = githubConnection;
        this.githubApp = githubApp;
        this.privateKeyLoader = privateKeyLoader;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!dashboardAuth.configured()) {
            throw new IllegalStateException("production dashboard authentication is not configured");
        }
        if (!githubConnection.configured()) {
            throw new IllegalStateException("production GitHub ownership authorization is not configured");
        }
        if (!"https".equalsIgnoreCase(githubApp.apiBaseUrl().getScheme())
                || !"https".equalsIgnoreCase(githubConnection.oauthBaseUrl().getScheme())
                || !"https".equalsIgnoreCase(githubConnection.callbackUrl().getScheme())) {
            throw new IllegalStateException("production provider endpoints must use HTTPS");
        }
        try {
            privateKeyLoader.load(githubApp.privateKeyPath());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("production GitHub App private key is invalid");
        }
    }
}
