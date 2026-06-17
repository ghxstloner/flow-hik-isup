package com.oldwei.isup.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hik.features")
public class HikFeatureProperties {

    private Feature cms = enabled(true);
    private Feature alarm = enabled(false);
    private Feature stream = enabled(false);
    private Feature storage = enabled(false);
    private Feature voice = enabled(false);
    private Feature playback = enabled(false);
    private Feature channelSync = enabled(false);
    private Feature provisioning = enabled(false);
    private Feature rawIsapi = enabled(false);

    private static Feature enabled(boolean enabled) {
        Feature feature = new Feature();
        feature.setEnabled(enabled);
        return feature;
    }

    @Data
    public static class Feature {
        private boolean enabled;
    }
}
