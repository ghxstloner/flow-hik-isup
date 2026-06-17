package com.oldwei.isup.sdk.service.impl;

import com.oldwei.isup.config.HikFeatureProperties;
import com.oldwei.isup.config.HikIsupProperties;
import com.oldwei.isup.model.Device;
import com.oldwei.isup.sdk.service.DEVICE_REGISTER_CB;
import com.oldwei.isup.sdk.service.HCISUPCMS;
import com.oldwei.isup.sdk.service.IHikISUPAlarm;
import com.oldwei.isup.sdk.service.constant.EHOME_REGISTER_TYPE;
import com.oldwei.isup.sdk.structure.NET_EHOME_DEV_REG_INFO_V12;
import com.oldwei.isup.sdk.structure.NET_EHOME_DEV_SESSIONKEY;
import com.oldwei.isup.sdk.structure.NET_EHOME_SERVER_INFO_V50;
import com.oldwei.isup.service.DeviceCacheService;
import com.sun.jna.Pointer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FRegisterCallBack implements DEVICE_REGISTER_CB {
    private final HikIsupProperties hikIsupProperties;
    private final HikFeatureProperties hikFeatureProperties;
    private final HCISUPCMS hcisupcms;
    private final DeviceCacheService deviceCacheService;
    private final ObjectProvider<IHikISUPAlarm> hikISUPAlarmProvider;

    @Override
    public boolean invoke(int lUserID, int dwDataType, Pointer pOutBuffer, int dwOutLen, Pointer pInBuffer, int dwInLen, Pointer pUser) {
        log.info("Device registration callback received, dwDataType: {}, lUserID: {}", dwDataType, lUserID);
        NET_EHOME_DEV_REG_INFO_V12 strDevRegInfo = new NET_EHOME_DEV_REG_INFO_V12();
        Pointer pDevRegInfo = strDevRegInfo.getPointer();
        switch (dwDataType) {
            case EHOME_REGISTER_TYPE.ENUM_DEV_ON: // 0
                // Device online callback.
                strDevRegInfo.write();
                pDevRegInfo.write(0, pOutBuffer.getByteArray(0, strDevRegInfo.size()), 0, strDevRegInfo.size());
                strDevRegInfo.read();
                NET_EHOME_SERVER_INFO_V50 strEhomeServerInfo = new NET_EHOME_SERVER_INFO_V50();
                strEhomeServerInfo.read();
                if (hikFeatureProperties.getAlarm().isEnabled()) {
                    byte[] byCmsIP = hikIsupProperties.getAlarmServer().getIp().getBytes();
                    System.arraycopy(byCmsIP, 0, strEhomeServerInfo.struUDPAlarmSever.szIP, 0, byCmsIP.length);
                    System.arraycopy(byCmsIP, 0, strEhomeServerInfo.struTCPAlarmSever.szIP, 0, byCmsIP.length);
                    strEhomeServerInfo.dwAlarmServerType = Integer.parseInt(hikIsupProperties.getAlarmServer().getType());
                    strEhomeServerInfo.struTCPAlarmSever.wPort = Short.parseShort(hikIsupProperties.getAlarmServer().getTcpPort());
                    strEhomeServerInfo.struUDPAlarmSever.wPort = Short.parseShort(hikIsupProperties.getAlarmServer().getUdpPort());
                }

                if (hikFeatureProperties.getStorage().isEnabled()) {
                    byte[] byCloudAccessKey = "test".getBytes();
                    System.arraycopy(byCloudAccessKey, 0, strEhomeServerInfo.byClouldAccessKey, 0, byCloudAccessKey.length);
                    byte[] byCloudSecretKey = "12345".getBytes();
                    System.arraycopy(byCloudSecretKey, 0, strEhomeServerInfo.byClouldSecretKey, 0, byCloudSecretKey.length);
                    strEhomeServerInfo.dwClouldPoolId = 1;

                    byte[] bySSIP = hikIsupProperties.getPicServer().getIp().getBytes();
                    System.arraycopy(bySSIP, 0, strEhomeServerInfo.struPictureSever.szIP, 0, bySSIP.length);
                    strEhomeServerInfo.struPictureSever.wPort = Short.parseShort(hikIsupProperties.getPicServer().getPort());
                    strEhomeServerInfo.dwPicServerType = Integer.parseInt(hikIsupProperties.getPicServer().getType());
                }
                strEhomeServerInfo.write();
                dwInLen = strEhomeServerInfo.size();
                pInBuffer.write(0, strEhomeServerInfo.getPointer().getByteArray(0, dwInLen), 0, dwInLen);

                String deviceId = new String(strDevRegInfo.struRegInfo.byDeviceID).trim();
                log.info("Device online, DeviceID: {}, LoginID: {}", deviceId, lUserID);
                deviceCacheService.registerLoginId(lUserID, deviceId);
                Device device = deviceCacheService.getByDeviceId(deviceId).orElse(new Device());
                device.setDeviceId(deviceId);
//                device.setDeviceType(deviceType);
                device.setIsOnline(1);
                device.setLoginId(lUserID);
//                updateDeviceChannels(device, onlineChannelIds);
                deviceCacheService.saveOrUpdate(device);
                break;
            case EHOME_REGISTER_TYPE.ENUM_DEV_OFF:// TODO 1
                log.info("Device offline callback, lUserID: {}", lUserID);
                Optional<Device> deviceOpt = deviceCacheService.getByLoginId(lUserID);
                if (deviceOpt.isEmpty()) {
                    log.warn("No device found for loginId: {}", lUserID);
                } else {
                    Device deviceOffline = deviceOpt.get();
                    deviceCacheService.removeByLoginId(lUserID);
                    log.info("Device {} is offline.", deviceOffline.getDeviceId());
                }
                break;
            case EHOME_REGISTER_TYPE.ENUM_DEV_AUTH:// 3
                // EHome 5.0 device authentication callback.
                strDevRegInfo.write();
                pDevRegInfo.write(0, pOutBuffer.getByteArray(0, strDevRegInfo.size()), 0, strDevRegInfo.size());
                strDevRegInfo.read();
                String szEHomeKey = hikIsupProperties.getIsupKey();
                byte[] bs = szEHomeKey.getBytes();
                pInBuffer.write(0, bs, 0, szEHomeKey.length());
                log.info("EHome 5.0 device auth callback, DeviceID: {}", new String(strDevRegInfo.struRegInfo.byDeviceID).trim());
                break;
            case EHOME_REGISTER_TYPE.ENUM_DEV_SESSIONKEY:// 4
                // EHome 5.0 device session key callback.
                strDevRegInfo.write();
                pDevRegInfo.write(0, pOutBuffer.getByteArray(0, strDevRegInfo.size()), 0, strDevRegInfo.size());
                strDevRegInfo.read();
                NET_EHOME_DEV_SESSIONKEY struSessionKey = new NET_EHOME_DEV_SESSIONKEY();
                System.arraycopy(strDevRegInfo.struRegInfo.byDeviceID, 0, struSessionKey.sDeviceID, 0, strDevRegInfo.struRegInfo.byDeviceID.length);
                System.arraycopy(strDevRegInfo.struRegInfo.bySessionKey, 0, struSessionKey.sSessionKey, 0, strDevRegInfo.struRegInfo.bySessionKey.length);
                struSessionKey.write();
                Pointer pSessionKey = struSessionKey.getPointer();
                hcisupcms.NET_ECMS_SetDeviceSessionKey(pSessionKey);
                log.info("EHome 5.0 device session key callback, DeviceID: {}", new String(strDevRegInfo.struRegInfo.byDeviceID).trim());
                IHikISUPAlarm hikISUPAlarm = hikISUPAlarmProvider.getIfAvailable();
                if (hikFeatureProperties.getAlarm().isEnabled() && hikISUPAlarm != null) {
                    hikISUPAlarm.NET_EALARM_SetDeviceSessionKey(pSessionKey);
                }
                break;
            case EHOME_REGISTER_TYPE.ENUM_DEV_DAS_REQ: // 5 HCISUPCMS.ENUM_DEV_DAS_REQ
                String dasInfo = "{\n" +
                        "    \"Type\":\"DAS\",\n" +
                        "    \"DasInfo\": {\n" +
                        "        \"Address\":\"" + hikIsupProperties.getDasServer().getIp() + "\",\n" +
                        "        \"Domain\":\"\",\n" +
                        "        \"ServerID\":\"\",\n" +
                        "        \"Port\":" + hikIsupProperties.getDasServer().getPort() + ",\n" +
                        "        \"UdpPort\":\n" +
                        "    }\n" +
                        "}";
                byte[] bs1 = dasInfo.getBytes();
                pInBuffer.write(0, bs1, 0, dasInfo.length());
                log.info("EHome 5.0 device DAS request callback: {}", dasInfo);
                break;
            default:
                log.info("FRegisterCallBack default type: {}", dwDataType);
                break;
        }
        return true;
    }
}
