package io.prreviewassistant.github.auth;

@FunctionalInterface
public interface InstallationTokenProvider {

    InstallationAccessToken tokenFor(long installationId);
}
