package io.prreviewassistant.review.context;
public record ContextBudgetUsage(int candidates, int filesFetched, int filesRetained,
        long bytesFetched, long bytesRetained, int apiRequests, boolean budgetExhausted) { }
