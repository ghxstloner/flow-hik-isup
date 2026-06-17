package com.oldwei.isup.model.provisioning;

import lombok.Data;

@Data
public class ProvisioningAccess {
    private String userType;
    private String beginTime;
    private String endTime;
    private String doorRight;
    private String planTemplateNo;
}
