package io.prreviewassistant.tenant;

public final class TenantOwnershipException extends RuntimeException {
    private final TenantOwnershipError error;

    public TenantOwnershipException(TenantOwnershipError error) {
        super(error.name());
        this.error = error;
    }

    public TenantOwnershipError error() {
        return error;
    }
}
