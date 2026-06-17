package com.oldwei.isup.controller;

import com.oldwei.isup.model.Device;
import com.oldwei.isup.model.R;
import com.oldwei.isup.service.DeviceCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * Device registry API.
 */
@Slf4j
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {
    
    private final DeviceCacheService deviceCacheService;

    /**
     * List registered devices.
     */
    @GetMapping
    public R<List<Device>> getDevices(
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) Integer isOnline) {
        List<Device> devices = deviceCacheService.list(d -> {
            boolean match = true;
            if (deviceId != null && !deviceId.isEmpty()) {
                match = d.getDeviceId().contains(deviceId);
            }
            if (match && isOnline != null) {
                match = isOnline.equals(d.getIsOnline());
            }
            return match;
        });
        return R.ok(devices);
    }

    /**
     * Get one registered device.
     */
    @GetMapping("/{deviceId}")
    public R<Device> getDevice(@PathVariable String deviceId) {
        Optional<Device> deviceOpt = deviceCacheService.getByDeviceId(deviceId);
        return deviceOpt.map(R::ok)
                .orElse(R.fail("Device does not exist."));
    }
}
