package com.oldwei.isup.model.provisioning;

import lombok.Data;

@Data
public class UserSyncRequest {
    private String correlationId;
    private String tenantDb;
    private Integer laravelDeviceId;
    private String deviceSerial;
    private ProvisioningEmployee employee;
    private ProvisioningAccess access;
    private ProvisioningPhoto photo;
}
