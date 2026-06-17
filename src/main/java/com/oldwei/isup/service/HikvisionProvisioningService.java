package com.oldwei.isup.service;

import com.oldwei.isup.model.Device;
import com.oldwei.isup.model.provisioning.ProvisioningResponse;
import com.oldwei.isup.model.provisioning.ProvisioningStatus;
import com.oldwei.isup.model.provisioning.UserDeleteRequest;
import com.oldwei.isup.model.provisioning.UserSyncRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class HikvisionProvisioningService {

    public ProvisioningResponse syncUser(Device device, String employeeNo, UserSyncRequest request) {
        log.info("Hikvision user sync requested: deviceId={}, employeeNo={}, correlationId={}",
                device.getDeviceId(), employeeNo, request.getCorrelationId());

        // TODO: Implement ISUP-session-backed ISAPI user setup and face upload.
        return new ProvisioningResponse(
                request.getCorrelationId(),
                device.getDeviceId(),
                employeeNo,
                false,
                false,
                false,
                ProvisioningStatus.NOT_IMPLEMENTED
        );
    }

    public ProvisioningResponse deleteUser(Device device, String employeeNo, UserDeleteRequest request) {
        log.info("Hikvision user delete requested: deviceId={}, employeeNo={}, correlationId={}",
                device.getDeviceId(), employeeNo, request.getCorrelationId());

        // TODO: Implement ISUP-session-backed ISAPI user delete.
        return new ProvisioningResponse(
                request.getCorrelationId(),
                device.getDeviceId(),
                employeeNo,
                false,
                false,
                false,
                ProvisioningStatus.NOT_IMPLEMENTED
        );
    }
}
