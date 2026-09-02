package io.prreviewassistant.github.auth;

@FunctionalInterface
public interface InstallationTokenRequester {

    InstallationAccessToken request(long installationId);
}
