package com.oldwei.isup.model.provisioning;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RawIsapiResponse {
    private String deviceId;
    private String method;
    private String path;
    private String status;
    private String rawResponse;
    private String sdkError;
}
