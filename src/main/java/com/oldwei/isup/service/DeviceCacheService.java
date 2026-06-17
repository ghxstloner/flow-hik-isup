package com.oldwei.isup.service;

import com.oldwei.isup.model.Device;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * In-memory device registry.
 * Device state is created on application startup and cleared when the process exits.
 */
@Slf4j
@Service
public class DeviceCacheService {

    /**
     * Device ID to device record.
     */
    private final Map<String, Device> deviceMap = new ConcurrentHashMap<>();

    /**
     * Login handle to device ID.
     */
    private final Map<Integer, String> loginIdToDeviceIdMap = new ConcurrentHashMap<>();

    /**
     * Save or update device state.
     *
     * @param device device record
     */
    public void saveOrUpdate(Device device) {
        if (device == null || device.getDeviceId() == null) {
            log.warn("Device record or device ID is empty; skipping cache update.");
            return;
        }

        deviceMap.put(device.getDeviceId(), device);

        if (device.getLoginId() != null && device.getLoginId() > -1) {
            loginIdToDeviceIdMap.put(device.getLoginId(), device.getDeviceId());
        }

        log.debug("Device cached: {}, channel count: {}", device.getDeviceId(), device.getChannels().size());
    }

    /**
     * Register a device login handle when the device comes online.
     *
     * @param loginId  login handle
     * @param deviceId device ID
     */
    public void registerLoginId(Integer loginId, String deviceId) {
        if (loginId != null && loginId > -1 && deviceId != null) {
            loginIdToDeviceIdMap.put(loginId, deviceId);
            log.info("Device login handle registered: deviceId={}, loginId={}", deviceId, loginId);
        }
    }

    /**
     * Get a device by device ID.
     *
     * @param deviceId device ID
     * @return device record
     */
    public Optional<Device> getByDeviceId(String deviceId) {
        return Optional.ofNullable(deviceMap.get(deviceId));
    }

    /**
     * Get a device ID by login handle.
     *
     * @param loginId login handle
     * @return device ID
     */
    public String getDeviceIdByLoginId(Integer loginId) {
        return loginIdToDeviceIdMap.get(loginId);
    }

    /**
     * Get a device by login handle.
     *
     * @param loginId login handle
     * @return device record
     */
    public Optional<Device> getByLoginId(Integer loginId) {
        String deviceId = loginIdToDeviceIdMap.get(loginId);
        if (deviceId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(deviceMap.get(deviceId));
    }

    /**
     * List all cached devices.
     *
     * @return all cached devices
     */
    public List<Device> listAll() {
        return new ArrayList<>(deviceMap.values());
    }

    /**
     * Query cached devices.
     *
     * @param predicate filter predicate
     * @return matching devices
     */
    public List<Device> list(Predicate<Device> predicate) {
        return deviceMap.values().stream()
                .filter(predicate)
                .collect(Collectors.toList());
    }

    /**
     * Remove a device by device ID.
     *
     * @param deviceId device ID
     */
    public void removeByDeviceId(String deviceId) {
        Device device = deviceMap.remove(deviceId);
        if (device != null) {
            loginIdToDeviceIdMap.entrySet().removeIf(entry -> entry.getValue().equals(deviceId));
            log.debug("Device removed from cache: {}", deviceId);
        }
    }

    /**
     * Remove a device by login handle.
     *
     * @param loginId login handle
     */
    public void removeByLoginId(Integer loginId) {
        String deviceId = loginIdToDeviceIdMap.remove(loginId);
        if (deviceId != null) {
            deviceMap.remove(deviceId);
            log.debug("Removed device for loginId {}: {}", loginId, deviceId);
        }
    }

    /**
     * Clear all cached state.
     */
    public void clear() {
        deviceMap.clear();
        loginIdToDeviceIdMap.clear();
        log.info("Device cache cleared.");
    }

    /**
     * Get the cached device count.
     *
     * @return device count
     */
    public int size() {
        return deviceMap.size();
    }
}
