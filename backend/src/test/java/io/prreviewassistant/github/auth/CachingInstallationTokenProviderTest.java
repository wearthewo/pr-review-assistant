package io.prreviewassistant.github.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class CachingInstallationTokenProviderTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");
    private static final Duration REFRESH_SKEW = Duration.ofMinutes(5);

    @Test
    void reusesSufficientlyValidToken() {
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger requests = new AtomicInteger();
        CachingInstallationTokenProvider provider = provider(clock, installationId -> {
            requests.incrementAndGet();
            return token("first", clock.instant().plus(Duration.ofHours(1)));
        });

        assertThat(provider.tokenFor(11).value()).isEqualTo("first");
        assertThat(provider.tokenFor(11).value()).isEqualTo("first");
        assertThat(requests).hasValue(1);
    }

    @Test
    void refreshesTokenInsideSafetyWindow() {
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger requests = new AtomicInteger();
        CachingInstallationTokenProvider provider = provider(clock, installationId ->
                token("token-" + requests.incrementAndGet(), clock.instant().plus(Duration.ofMinutes(10))));

        assertThat(provider.tokenFor(11).value()).isEqualTo("token-1");
        clock.advance(Duration.ofMinutes(6));

        assertThat(provider.tokenFor(11).value()).isEqualTo("token-2");
        assertThat(requests).hasValue(2);
    }

    @Test
    void neverReturnsExpiredToken() {
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger requests = new AtomicInteger();
        CachingInstallationTokenProvider provider = provider(clock, installationId ->
                token("token-" + requests.incrementAndGet(), clock.instant().plus(Duration.ofMinutes(6))));

        provider.tokenFor(11);
        clock.advance(Duration.ofMinutes(7));

        assertThat(provider.tokenFor(11).value()).isEqualTo("token-2");
        assertThat(requests).hasValue(2);
    }

    @Test
    void isolatesTokensByInstallation() {
        MutableClock clock = new MutableClock(NOW);
        Map<Long, AtomicInteger> requests = new ConcurrentHashMap<>();
        CachingInstallationTokenProvider provider = provider(clock, installationId -> {
            requests.computeIfAbsent(installationId, ignored -> new AtomicInteger()).incrementAndGet();
            return token("installation-" + installationId, clock.instant().plus(Duration.ofHours(1)));
        });

        assertThat(provider.tokenFor(11).value()).isEqualTo("installation-11");
        assertThat(provider.tokenFor(22).value()).isEqualTo("installation-22");
        assertThat(provider.tokenFor(11).value()).isEqualTo("installation-11");
        assertThat(requests.get(11L)).hasValue(1);
        assertThat(requests.get(22L)).hasValue(1);
    }

    @Test
    void concurrentRequestsShareOneRefresh() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        CountDownLatch callersReady = new CountDownLatch(8);
        CountDownLatch startCallers = new CountDownLatch(1);
        CachingInstallationTokenProvider provider = provider(clock, installationId -> {
            requests.incrementAndGet();
            requestStarted.countDown();
            await(releaseRequest);
            return token("shared", clock.instant().plus(Duration.ofHours(1)));
        });
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<InstallationAccessToken>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                results.add(executor.submit(() -> {
                    callersReady.countDown();
                    await(startCallers);
                    return provider.tokenFor(11);
                }));
            }
            assertThat(callersReady.await(2, TimeUnit.SECONDS)).isTrue();
            startCallers.countDown();
            assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            releaseRequest.countDown();

            for (Future<InstallationAccessToken> result : results) {
                assertThat(result.get(2, TimeUnit.SECONDS).value()).isEqualTo("shared");
            }
            assertThat(requests).hasValue(1);
        } finally {
            releaseRequest.countDown();
            startCallers.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedRefreshCanRecoverOnNextAttempt() {
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger requests = new AtomicInteger();
        CachingInstallationTokenProvider provider = provider(clock, installationId -> {
            if (requests.incrementAndGet() == 1) {
                throw GitHubException.transientFailure();
            }
            return token("recovered", clock.instant().plus(Duration.ofHours(1)));
        });

        assertThatThrownBy(() -> provider.tokenFor(11))
                .isInstanceOfSatisfying(GitHubException.class,
                        exception -> assertThat(exception.type()).isEqualTo(GitHubErrorType.TRANSIENT_FAILURE));
        assertThat(provider.tokenFor(11).value()).isEqualTo("recovered");
        assertThat(requests).hasValue(2);
    }

    private CachingInstallationTokenProvider provider(MutableClock clock, InstallationTokenRequester requester) {
        return new CachingInstallationTokenProvider(requester, clock, REFRESH_SKEW);
    }

    private InstallationAccessToken token(String value, Instant expiresAt) {
        return new InstallationAccessToken(value, expiresAt);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating test", exception);
        }
    }
}
