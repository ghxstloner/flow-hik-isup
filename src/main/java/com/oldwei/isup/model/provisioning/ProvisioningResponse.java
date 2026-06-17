package com.oldwei.isup.model.provisioning;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProvisioningResponse {
    private String correlationId;
    private String deviceId;
    private String employeeNo;
    private Boolean userSynced;
    private Boolean photoSynced;
    private Boolean deleted;
    private String bridgeStatus;
}
