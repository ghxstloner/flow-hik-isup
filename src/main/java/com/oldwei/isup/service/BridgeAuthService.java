package com.oldwei.isup.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Shared-token guard for internal bridge APIs.
 */
@Service
public class BridgeAuthService {

    private final String token;

    public BridgeAuthService(@Value("${hik.bridge.token:${flow.bridge.token:}}") String token) {
        this.token = token;
    }

    public boolean isAuthorized(String headerToken) {
        return StringUtils.isNotBlank(token) && StringUtils.equals(token, headerToken);
    }
}
