package com.oldwei.isup.config;

import com.aizuda.zlm4j.core.ZLMApi;
import com.sun.jna.Native;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "hik.features.stream", name = "enabled", havingValue = "true")
public class ZLMApiServiceConfig {


    @Bean
    public ZLMApi zlmApi() {
        ZLMApi zlmApi = Native.load("mk_api", ZLMApi.class);
        // Initialize the ZLM runtime.
        zlmApi.mk_env_init2(1, 1, 1, null, 0, 0, null, 0, null, null);
        // Start the HTTP server. 0 means failure; non-zero is the bound port.
        short httpPort = zlmApi.mk_http_server_start((short) 7788, 0);
        // Start the RTSP server. 0 means failure; non-zero is the bound port.
        short rtspPort = zlmApi.mk_rtsp_server_start((short) 7554, 0);
        // Start the RTMP server. 0 means failure; non-zero is the bound port.
        short rtmpPort = zlmApi.mk_rtmp_server_start((short) 7935, 0);
        if (httpPort > 0 && rtspPort > 0 && rtmpPort > 0) {
            log.info("ZLM service started - HTTP port: {}, RTSP port: {}, RTMP port: {}",
                    httpPort, rtspPort, rtmpPort);
        } else {
            throw new RuntimeException("Failed to start ZLM service ports.");
        }
        log.info("ZLM API initialized.");
        return zlmApi;
    }
}
