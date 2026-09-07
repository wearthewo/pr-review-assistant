package io.prreviewassistant.ai;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic test-only provider. It is never registered as a Spring production bean. */
public final class FakeAiProvider implements AiProvider {
    private final StructuredAiResponse response;
    private final RuntimeException failure;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<StructuredAiRequest> lastRequest = new AtomicReference<>();

    public FakeAiProvider(StructuredAiResponse response) { this(response, null); }
    public FakeAiProvider(RuntimeException failure) { this(null, Objects.requireNonNull(failure)); }
    private FakeAiProvider(StructuredAiResponse response, RuntimeException failure) {
        this.response = response;
        this.failure = failure;
    }

    @Override public StructuredAiResponse generateStructured(StructuredAiRequest request) {
        calls.incrementAndGet();
        lastRequest.set(Objects.requireNonNull(request));
        if (failure != null) throw failure;
        return Objects.requireNonNull(response);
    }

    public int calls() { return calls.get(); }
    public StructuredAiRequest lastRequest() { return lastRequest.get(); }
}
