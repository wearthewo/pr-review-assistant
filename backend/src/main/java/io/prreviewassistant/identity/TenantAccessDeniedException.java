package io.prreviewassistant.identity;

public final class TenantAccessDeniedException extends RuntimeException {
    public TenantAccessDeniedException() {
        super("TENANT_ACCESS_DENIED");
    }
}
