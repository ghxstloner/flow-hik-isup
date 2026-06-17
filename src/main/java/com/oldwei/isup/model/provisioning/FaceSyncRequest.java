package com.oldwei.isup.model.provisioning;

import lombok.Data;

@Data
public class FaceSyncRequest {
    private String correlationId;
    private ProvisioningPhoto photo;
}
