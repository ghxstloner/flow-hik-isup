package com.oldwei.isup.runner;

import com.oldwei.isup.config.HikIsupProperties;
import com.oldwei.isup.config.HikFeatureProperties;
import com.oldwei.isup.sdk.service.*;
import com.oldwei.isup.sdk.service.impl.FPREVIEW_NEWLINK_CB_FILE;
import com.oldwei.isup.sdk.service.impl.FRegisterCallBack;
import com.oldwei.isup.sdk.structure.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartedUpRunner implements ApplicationRunner, DisposableBean {

    private final HikIsupProperties hikIsupProperties;
    private final HikFeatureProperties hikFeatureProperties;
    private final ObjectProvider<HCISUPCMS> hcisupcmsProvider;
    private final ObjectProvider<IHikISUPStream> hikISUPStreamProvider;
    private final FRegisterCallBack fRegisterCallBack;
    private final ObjectProvider<FPREVIEW_NEWLINK_CB_FILE> fnNewLinkCBProvider;
    private final ObjectProvider<VOICETALK_NEWLINK_CB> voiceCallBackProvider;
    private final ObjectProvider<IHikISUPStorage> hikISUPStorageProvider;
    private final ObjectProvider<IHikISUPAlarm> hikISUPAlarmProvider;
    private final ObjectProvider<EHomeSSStorageCallBack> pssStorageCallbackProvider;
    private final ObjectProvider<EHomeSSMsgCallBack> pssMessageCallbackProvider;
    private final ObjectProvider<EHomeMsgCallBack> alarmMsgCallBackProvider;
    private final ObjectProvider<PLAYBACK_NEWLINK_CB> playbackNewlinkCallbackHandlerProvider;

    @Override
    public void run(ApplicationArguments args) {
        if (hikFeatureProperties.getStorage().isEnabled()) {
            startStorageServer();
        } else {
            log.info("Picture storage listener is disabled.");
        }

        if (hikFeatureProperties.getAlarm().isEnabled()) {
            startAlarmServer();
        } else {
            log.info("Alarm listener is disabled.");
        }

        if (hikFeatureProperties.getStream().isEnabled()) {
            startPreviewStreamServer();
        } else {
            log.info("Preview streaming listener is disabled.");
        }

        if (hikFeatureProperties.getVoice().isEnabled()) {
            startVoiceTalkServer();
        } else {
            log.info("Voice talk listener is disabled.");
        }

        if (hikFeatureProperties.getPlayback().isEnabled()) {
            startPlaybackServer();
        } else {
            log.info("Playback listener is disabled.");
        }

        if (hikFeatureProperties.getCms().isEnabled()) {
            startCmsServer();
        } else {
            log.warn("CMS registration listener is disabled.");
        }

        log.info("Application startup completed.");
    }

    private void startStorageServer() {
        IHikISUPStorage hikISUPStorage = hikISUPStorageProvider.getIfAvailable();
        EHomeSSStorageCallBack pssStorageCallback = pssStorageCallbackProvider.getIfAvailable();
        EHomeSSMsgCallBack pssMessageCallback = pssMessageCallbackProvider.getIfAvailable();
        if (hikISUPStorage == null || pssStorageCallback == null || pssMessageCallback == null) {
            log.warn("Picture storage listener was requested but its SDK dependencies are unavailable.");
            return;
        }

        log.info("Starting picture storage listener.");
        // Public picture storage address advertised to devices when external NAT is used.
        NET_EHOME_IPADDRESS ipaddress = new NET_EHOME_IPADDRESS();
        String ServerIP = hikIsupProperties.getPicServer().getIp();
        System.arraycopy(ServerIP.getBytes(), 0, ipaddress.szIP, 0, ServerIP.length());
        ipaddress.wPort = Short.parseShort(hikIsupProperties.getPicServer().getPort());
        ipaddress.write();
        boolean b = hikISUPStorage.NET_ESS_SetSDKInitCfg(3, ipaddress.getPointer());
        if (!b) {
            log.error("NET_ESS_SetSDKInitCfg failed, error code: {}", hikISUPStorage.NET_ESS_GetLastError());
        }
        NET_EHOME_SS_LISTEN_PARAM pSSListenParam = new NET_EHOME_SS_LISTEN_PARAM();
        String SSIP = hikIsupProperties.getPicServer().getListenIp();
        System.arraycopy(SSIP.getBytes(), 0, pSSListenParam.struAddress.szIP, 0, SSIP.length());
        pSSListenParam.struAddress.wPort = Short.parseShort(hikIsupProperties.getPicServer().getListenPort());
        // Storage credentials are placeholders until storage is explicitly enabled and configured.
        String strKMS_UserName = "test";
        System.arraycopy(strKMS_UserName.getBytes(), 0, pSSListenParam.szKMS_UserName, 0, strKMS_UserName.length());
        String strKMS_Password = "12345";
        System.arraycopy(strKMS_Password.getBytes(), 0, pSSListenParam.szKMS_Password, 0, strKMS_Password.length());
        String strAccessKey = "test";
        System.arraycopy(strAccessKey.getBytes(), 0, pSSListenParam.szAccessKey, 0, strAccessKey.length());
        String strSecretKey = "12345";
        System.arraycopy(strSecretKey.getBytes(), 0, pSSListenParam.szSecretKey, 0, strSecretKey.length());
        pSSListenParam.byHttps = 0;
        pSSListenParam.fnSSMsgCb = pssMessageCallback;
        pSSListenParam.fnSStorageCb = pssStorageCallback;
        pSSListenParam.bySecurityMode = 1;
        pSSListenParam.write();
        int SsHandle = hikISUPStorage.NET_ESS_StartListen(pSSListenParam);
        if (SsHandle == -1) {
            int err = hikISUPStorage.NET_ESS_GetLastError();
            log.error("NET_ESS_StartListen failed, error code: {}", err);
            hikISUPStorage.NET_ESS_Fini();
            return;
        }
        String SsListenInfo = new String(pSSListenParam.struAddress.szIP).trim() + "_" + pSSListenParam.struAddress.wPort;
        log.info("Picture storage listener started: {}", SsListenInfo);
    }

    private void startAlarmServer() {
        IHikISUPAlarm hikISUPAlarm = hikISUPAlarmProvider.getIfAvailable();
        EHomeMsgCallBack alarmMsgCallBack = alarmMsgCallBackProvider.getIfAvailable();
        if (hikISUPAlarm == null || alarmMsgCallBack == null) {
            log.warn("Alarm listener was requested but its SDK dependencies are unavailable.");
            return;
        }

        log.info("Starting alarm listener.");
        NET_EHOME_ALARM_LISTEN_PARAM net_ehome_alarm_listen_param = new NET_EHOME_ALARM_LISTEN_PARAM();
        System.arraycopy(hikIsupProperties.getAlarmServer().getListenIp().getBytes(),
                0, net_ehome_alarm_listen_param.struAddress.szIP,
                0, hikIsupProperties.getAlarmServer().getListenIp().length());
        if (Short.parseShort(hikIsupProperties.getAlarmServer().getType()) == 2) {
            net_ehome_alarm_listen_param.struAddress.wPort = Short.parseShort(hikIsupProperties.getAlarmServer().getListenTcpPort());
            net_ehome_alarm_listen_param.byProtocolType = 2;
        } else {
            net_ehome_alarm_listen_param.struAddress.wPort = Short.parseShort(hikIsupProperties.getAlarmServer().getListenUdpPort());
            net_ehome_alarm_listen_param.byProtocolType = 1;
        }
        net_ehome_alarm_listen_param.fnMsgCb = alarmMsgCallBack;
        net_ehome_alarm_listen_param.byUseCmsPort = 0;
        net_ehome_alarm_listen_param.write();

        int AlarmHandle = hikISUPAlarm.NET_EALARM_StartListen(net_ehome_alarm_listen_param);
        log.info("AlarmHandle: {}", AlarmHandle);
        if (AlarmHandle < 0) {
            log.error("NET_EALARM_StartListen failed, error code: {}", hikISUPAlarm.NET_EALARM_GetLastError());
            hikISUPAlarm.NET_EALARM_Fini();
            return;
        }
        String AlarmListenInfo = new String(net_ehome_alarm_listen_param.struAddress.szIP).trim() + "_" + net_ehome_alarm_listen_param.struAddress.wPort;
        log.info("Alarm listener started: {}", AlarmListenInfo);
    }

    private void startPreviewStreamServer() {
        IHikISUPStream hikISUPStream = hikISUPStreamProvider.getIfAvailable();
        FPREVIEW_NEWLINK_CB_FILE fnNewLinkCB = fnNewLinkCBProvider.getIfAvailable();
        if (hikISUPStream == null || fnNewLinkCB == null) {
            log.warn("Preview streaming listener was requested but its SDK dependencies are unavailable.");
            return;
        }

        log.info("Starting preview streaming listener.");
        NET_EHOME_LISTEN_PREVIEW_CFG netEhomeListenPreviewCfg = new NET_EHOME_LISTEN_PREVIEW_CFG();
        System.arraycopy(hikIsupProperties.getSmsServer().getListenIp().getBytes(), 0, netEhomeListenPreviewCfg.struIPAdress.szIP, 0, hikIsupProperties.getSmsServer().getListenIp().length());
        netEhomeListenPreviewCfg.struIPAdress.wPort = Short.parseShort(hikIsupProperties.getSmsServer().getListenPort());

        netEhomeListenPreviewCfg.fnNewLinkCB = fnNewLinkCB;
        netEhomeListenPreviewCfg.pUser = null;
        netEhomeListenPreviewCfg.byLinkMode = 0;
        netEhomeListenPreviewCfg.write();
        int lListenHandle = hikISUPStream.NET_ESTREAM_StartListenPreview(netEhomeListenPreviewCfg);
        log.info("lListenHandle: {}", lListenHandle);
        if (lListenHandle == -1) {
            hikISUPStream.NET_ESTREAM_Fini();
            log.error("Preview streaming listener failed, error code: {}", hikISUPStream.NET_ESTREAM_GetLastError());
        } else {
            String StreamListenInfo = new String(netEhomeListenPreviewCfg.struIPAdress.szIP).trim() + "_" + netEhomeListenPreviewCfg.struIPAdress.wPort;
            log.info("Preview streaming listener started: {}", StreamListenInfo);
        }
    }

    private void startVoiceTalkServer() {
        IHikISUPStream hikISUPStream = hikISUPStreamProvider.getIfAvailable();
        VOICETALK_NEWLINK_CB voiceCallBack = voiceCallBackProvider.getIfAvailable();
        if (hikISUPStream == null || voiceCallBack == null) {
            log.warn("Voice talk listener was requested but its SDK dependencies are unavailable.");
            return;
        }

        log.info("Starting voice talk listener.");
        NET_EHOME_LISTEN_VOICETALK_CFG net_ehome_listen_voicetalk_cfg = new NET_EHOME_LISTEN_VOICETALK_CFG();
        net_ehome_listen_voicetalk_cfg.struIPAdress.szIP = hikIsupProperties.getVoiceSmsServer().getListenIp().getBytes();
        net_ehome_listen_voicetalk_cfg.struIPAdress.wPort = Short.parseShort(hikIsupProperties.getVoiceSmsServer().getPort());
        net_ehome_listen_voicetalk_cfg.fnNewLinkCB = voiceCallBack;
        net_ehome_listen_voicetalk_cfg.byLinkMode = 0;
        net_ehome_listen_voicetalk_cfg.write();
        int VoicelServHandle = hikISUPStream.NET_ESTREAM_StartListenVoiceTalk(net_ehome_listen_voicetalk_cfg);
        if (VoicelServHandle == -1) {
            log.error("NET_ESTREAM_StartListenVoiceTalk failed, error code: {}", hikISUPStream.NET_ESTREAM_GetLastError());
            hikISUPStream.NET_ESTREAM_Fini();
            return;
        }
        String VoiceStreamListenInfo = new String(net_ehome_listen_voicetalk_cfg.struIPAdress.szIP).trim() + "_" + net_ehome_listen_voicetalk_cfg.struIPAdress.wPort;
        log.info("Voice talk listener started: {}", VoiceStreamListenInfo);
    }

    private void startPlaybackServer() {
        IHikISUPStream hikISUPStream = hikISUPStreamProvider.getIfAvailable();
        PLAYBACK_NEWLINK_CB playbackNewlinkCallbackHandler = playbackNewlinkCallbackHandlerProvider.getIfAvailable();
        if (hikISUPStream == null || playbackNewlinkCallbackHandler == null) {
            log.warn("Playback listener was requested but its SDK dependencies are unavailable.");
            return;
        }

        log.info("Starting playback listener.");
        NET_EHOME_PLAYBACK_LISTEN_PARAM struPlayBackListen = new NET_EHOME_PLAYBACK_LISTEN_PARAM();
        System.arraycopy(hikIsupProperties.getSmsBackServer().getListenIp().getBytes(), 0, struPlayBackListen.struIPAdress.szIP, 0, hikIsupProperties.getSmsBackServer().getListenIp().length());
        struPlayBackListen.struIPAdress.wPort = Short.parseShort(hikIsupProperties.getSmsBackServer().getListenPort());
        struPlayBackListen.fnNewLinkCB = playbackNewlinkCallbackHandler;
        struPlayBackListen.byLinkMode = 0;

        int m_lPlayBackListenHandle = hikISUPStream.NET_ESTREAM_StartListenPlayBack(struPlayBackListen);
        if (m_lPlayBackListenHandle < -1) {
            log.error("NET_ESTREAM_StartListenPlayBack failed, error code: {}", hikISUPStream.NET_ESTREAM_GetLastError());
            hikISUPStream.NET_ESTREAM_Fini();
            return;
        }
        String BackStreamListenInfo = new String(struPlayBackListen.struIPAdress.szIP).trim() + "_" + struPlayBackListen.struIPAdress.wPort;
        log.info("Playback listener started: {}", BackStreamListenInfo);
    }

    private void startCmsServer() {
        HCISUPCMS hcisupcms = hcisupcmsProvider.getIfAvailable();
        if (hcisupcms == null) {
            log.warn("CMS registration listener was requested but the CMS SDK is unavailable.");
            return;
        }

        log.info("Starting CMS registration listener.");
        NET_EHOME_CMS_LISTEN_PARAM struCMSListenPara = new NET_EHOME_CMS_LISTEN_PARAM();
        System.arraycopy(hikIsupProperties.getCmsServer().getIp().getBytes(), 0, struCMSListenPara.struAddress.szIP, 0, hikIsupProperties.getCmsServer().getIp().length());
        struCMSListenPara.struAddress.wPort = Short.parseShort(hikIsupProperties.getCmsServer().getPort());
        struCMSListenPara.fnCB = fRegisterCallBack;
        struCMSListenPara.write();
        int CmsHandle = hcisupcms.NET_ECMS_StartListen(struCMSListenPara);
        if (CmsHandle < 0) {
            hcisupcms.NET_ECMS_Fini();
            log.error("NET_ECMS_StartListen failed, error code: {}", hcisupcms.NET_ECMS_GetLastError());
        } else {
            String CmsListenInfo = new String(struCMSListenPara.struAddress.szIP).trim() + "_" + struCMSListenPara.struAddress.wPort;
            log.info("register service: {}, NET_ECMS_StartListen succeed!", CmsListenInfo);
        }
    }

    @Override
    public void destroy() {
        IHikISUPStream hikISUPStream = hikISUPStreamProvider.getIfAvailable();
        if (hikISUPStream != null) {
            hikISUPStream.NET_ESTREAM_Fini();
        }
        HCISUPCMS hcisupcms = hcisupcmsProvider.getIfAvailable();
        if (hcisupcms != null) {
            hcisupcms.NET_ECMS_Fini();
        }
        IHikISUPAlarm hikISUPAlarm = hikISUPAlarmProvider.getIfAvailable();
        if (hikISUPAlarm != null) {
            hikISUPAlarm.NET_EALARM_Fini();
        }
        IHikISUPStorage hikISUPStorage = hikISUPStorageProvider.getIfAvailable();
        if (hikISUPStorage != null) {
            hikISUPStorage.NET_ESS_Fini();
        }
    }
}
