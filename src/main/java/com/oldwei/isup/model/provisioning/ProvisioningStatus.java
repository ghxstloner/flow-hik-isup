package com.oldwei.isup.model.provisioning;

public final class ProvisioningStatus {

    public static final String SYNCED = "synced";
    public static final String DELETED = "deleted";
    /**
     * User provisioning succeeded, but the photo enrollment failed.
     * Returned with HTTP 200 (not 500) so callers can treat the photo
     * rejection as a soft warning: the access-control user still works.
     */
    public static final String PARTIAL = "partial";
    public static final String FAILED = "failed";
    public static final String NOT_FOUND = "not_found";
    public static final String OFFLINE = "offline";
    public static final String FEATURE_DISABLED = "feature_disabled";
    public static final String NOT_IMPLEMENTED = "not_implemented";
    public static final String VALIDATION_ERROR = "validation_error";
    public static final String UNAUTHORIZED = "unauthorized";

    private ProvisioningStatus() {
    }
}
