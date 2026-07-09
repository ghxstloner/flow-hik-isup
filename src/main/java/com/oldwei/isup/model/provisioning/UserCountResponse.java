package com.oldwei.isup.model.provisioning;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserCountResponse {
    private String deviceId;
    private Integer userCount;
    private String source;
    private String rawTotalField;
    private String bridgeStatus;
    private String rawResponse;
    private String sdkError;
}
