package com.oldwei.isup.model.provisioning;

import lombok.Data;

@Data
public class UserDeleteRequest {
    private String correlationId;
    private String tenantDb;
    private Integer laravelDeviceId;
    private String deviceSerial;
}
