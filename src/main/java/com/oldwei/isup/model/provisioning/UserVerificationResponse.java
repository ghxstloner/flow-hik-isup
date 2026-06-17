package com.oldwei.isup.model.provisioning;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserVerificationResponse {
    private String deviceId;
    private String employeeNo;
    private Boolean found;
    private String bridgeStatus;
    private String rawResponse;
    private String sdkError;
}
