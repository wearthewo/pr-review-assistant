package io.prreviewassistant.github.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class CachingInstallationTokenProvider implements InstallationTokenProvider {

    private final InstallationTokenRequester requester;
    private final Clock clock;
    private final Duration refreshSkew;
    private final ConcurrentMap<Long, InstallationAccessToken> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, CompletableFuture<InstallationAccessToken>> refreshes = new ConcurrentHashMap<>();

    public CachingInstallationTokenProvider(
            InstallationTokenRequester requester,
            Clock clock,
            Duration refreshSkew) {
        this.requester = requester;
        this.clock = clock;
        this.refreshSkew = refreshSkew;
    }

    @Override
    public InstallationAccessToken tokenFor(long installationId) {
        validateInstallationId(installationId);
        InstallationAccessToken cached = cache.get(installationId);
        if (isSufficientlyValid(cached)) {
            return cached;
        }

        CompletableFuture<InstallationAccessToken> pending = new CompletableFuture<>();
        CompletableFuture<InstallationAccessToken> active = refreshes.putIfAbsent(installationId, pending);
        if (active != null) {
            return await(active);
        }

        try {
            InstallationAccessToken refreshed = requester.request(installationId);
            if (!refreshed.expiresAt().isAfter(clock.instant())) {
                throw GitHubException.malformedResponse();
            }
            cache.put(installationId, refreshed);
            pending.complete(refreshed);
            return refreshed;
        } catch (RuntimeException exception) {
            pending.completeExceptionally(exception);
            throw exception;
        } finally {
            refreshes.remove(installationId, pending);
        }
    }

    private boolean isSufficientlyValid(InstallationAccessToken token) {
        Instant safeUntil = clock.instant().plus(refreshSkew);
        return token != null && token.expiresAt().isAfter(safeUntil);
    }

    private InstallationAccessToken await(CompletableFuture<InstallationAccessToken> refresh) {
        try {
            return refresh.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw GitHubException.transientFailure();
        }
    }

    private void validateInstallationId(long installationId) {
        if (installationId <= 0) {
            throw GitHubException.invalidInstallationId();
        }
    }
}
