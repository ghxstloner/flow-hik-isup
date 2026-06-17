package com.oldwei.isup.model.provisioning;

import lombok.Data;

import java.util.Map;

@Data
public class RawIsapiRequest {
    private String method;
    private String path;
    private Map<String, Object> body;
}
