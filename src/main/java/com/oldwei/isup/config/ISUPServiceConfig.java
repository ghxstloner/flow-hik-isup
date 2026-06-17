package com.oldwei.isup.config;

import com.oldwei.isup.sdk.service.*;
import com.oldwei.isup.sdk.structure.BYTE_ARRAY;
import com.oldwei.isup.util.OsSelect;
import com.sun.jna.Native;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ISUPServiceConfig {

    @Bean
    @ConditionalOnProperty(prefix = "hik.features.alarm", name = "enabled", havingValue = "true")
    public IHikISUPAlarm hikISUPAlarm() {
        IHikISUPAlarm hikISUPAlarm = null;
        synchronized (IHikISUPAlarm.class) {
            String strDllPath = "";
            try {
                //System.setProperty("jna.debug_load", "true");
                if (OsSelect.isWindows())
                    // Windows library path.
                    strDllPath = System.getProperty("user.dir") + "\\sdk\\windows\\HCISUPAlarm.dll";
                else if (OsSelect.isLinux())
                    // Linux library path.
                    strDllPath = System.getProperty("user.dir") + "/sdk/linux/libHCISUPAlarm.so";
                hikISUPAlarm = (IHikISUPAlarm) Native.loadLibrary(strDllPath, IHikISUPAlarm.class);
            } catch (Exception ex) {
                log.error("loadLibrary: {} Error: {}", strDllPath, ex.getMessage());
                return hikISUPAlarm;
            }
        }
        if (OsSelect.isWindows()) {
            BYTE_ARRAY ptrByteArrayCrypto = new BYTE_ARRAY(256);
            String strPathCrypto = System.getProperty("user.dir") + "\\sdk\\windows\\libeay32.dll";
            System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, strPathCrypto.length());
            ptrByteArrayCrypto.write();
            hikISUPAlarm.NET_EALARM_SetSDKInitCfg(0, ptrByteArrayCrypto.getPointer());

            // Configure the libssl path.
            BYTE_ARRAY ptrByteArraySsl = new BYTE_ARRAY(256);
            String strPathSsl = System.getProperty("user.dir") + "\\sdk\\windows\\ssleay32.dll";
            System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, strPathSsl.length());
            ptrByteArraySsl.write();
            hikISUPAlarm.NET_EALARM_SetSDKInitCfg(1, ptrByteArraySsl.getPointer());

            // Initialize the alarm SDK.
            boolean bRet = hikISUPAlarm.NET_EALARM_Init();
            if (!bRet) {
                System.out.println("NET_EALARM_Init failed!");
            }
            // Configure the HCAapSDKCom component directory.
            BYTE_ARRAY ptrByteArrayCom = new BYTE_ARRAY(256);
            String strPathCom = System.getProperty("user.dir") + "\\sdk\\windows\\HCAapSDKCom";
            System.arraycopy(strPathCom.getBytes(), 0, ptrByteArrayCom.byValue, 0, strPathCom.length());
            ptrByteArrayCom.write();
            hikISUPAlarm.NET_EALARM_SetSDKLocalCfg(5, ptrByteArrayCom.getPointer());
        } else if (OsSelect.isLinux()) {
            BYTE_ARRAY ptrByteArrayCrypto = new BYTE_ARRAY(256);
            String strPathCrypto = System.getProperty("user.dir") + "/sdk/linux/libcrypto.so";
            System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, strPathCrypto.length());
            ptrByteArrayCrypto.write();
            hikISUPAlarm.NET_EALARM_SetSDKInitCfg(0, ptrByteArrayCrypto.getPointer());

            // Configure the libssl path.
            BYTE_ARRAY ptrByteArraySsl = new BYTE_ARRAY(256);
            String strPathSsl = System.getProperty("user.dir") + "/sdk/linux/libssl.so";
            System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, strPathSsl.length());
            ptrByteArraySsl.write();
            hikISUPAlarm.NET_EALARM_SetSDKInitCfg(1, ptrByteArraySsl.getPointer());
            // Initialize the alarm SDK.
            boolean bRet = hikISUPAlarm.NET_EALARM_Init();
            if (!bRet) {
                System.out.println("NET_EALARM_Init failed!");
            }
            // Configure the HCAapSDKCom component directory.
            BYTE_ARRAY ptrByteArrayCom = new BYTE_ARRAY(256);
            String strPathCom = System.getProperty("user.dir") + "/sdk/linux/HCAapSDKCom/";
            System.arraycopy(strPathCom.getBytes(), 0, ptrByteArrayCom.byValue, 0, strPathCom.length());
            ptrByteArrayCom.write();
            hikISUPAlarm.NET_EALARM_SetSDKLocalCfg(5, ptrByteArrayCom.getPointer());
        }
        return hikISUPAlarm;

    }

    @Bean
    @ConditionalOnProperty(prefix = "hik.features.storage", name = "enabled", havingValue = "true")
    public IHikISUPStorage hikISUPStorage() {
        IHikISUPStorage hikISUPStorage = null;
        synchronized (IHikISUPStorage.class) {
            String strDllPath = "";
            try {
                //System.setProperty("jna.debug_load", "true");
                if (OsSelect.isWindows())
                    // Windows library path.
                    strDllPath = System.getProperty("user.dir") + "\\sdk\\windows\\HCISUPSS.dll";
                else if (OsSelect.isLinux())
                    // Linux library path.
                    strDllPath = System.getProperty("user.dir") + "/sdk/linux/libHCISUPSS.so";
                hikISUPStorage = (IHikISUPStorage) Native.loadLibrary(strDllPath, IHikISUPStorage.class);
            } catch (Exception ex) {
                System.out.println("loadLibrary: " + strDllPath + " Error: " + ex.getMessage());
                return hikISUPStorage;
            }
        }
        if (OsSelect.isWindows()) {
            String strPathCrypto = System.getProperty("user.dir") + "\\sdk\\windows\\libeay32.dll";
            int iPathCryptoLen = strPathCrypto.getBytes().length;
            BYTE_ARRAY ptrByteArrayCrypto = new BYTE_ARRAY(iPathCryptoLen + 1);
            System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, iPathCryptoLen);
            ptrByteArrayCrypto.write();
            System.out.println(new String(ptrByteArrayCrypto.byValue));
            hikISUPStorage.NET_ESS_SetSDKInitCfg(4, ptrByteArrayCrypto.getPointer());

            // Configure the libssl path.
            String strPathSsl = System.getProperty("user.dir") + "\\sdk\\windows\\ssleay32.dll";
            int iPathSslLen = strPathSsl.getBytes().length;
            BYTE_ARRAY ptrByteArraySsl = new BYTE_ARRAY(iPathSslLen + 1);
            System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, iPathSslLen);
            ptrByteArraySsl.write();
            System.out.println(new String(ptrByteArraySsl.byValue));
            hikISUPStorage.NET_ESS_SetSDKInitCfg(5, ptrByteArraySsl.getPointer());

            // Configure the sqlite3 path.
            String strPathSqlite = System.getProperty("user.dir") + "\\sdk\\windows\\sqlite3.dll";
            int iPathSqliteLen = strPathSqlite.getBytes().length;
            BYTE_ARRAY ptrByteArraySqlite = new BYTE_ARRAY(iPathSqliteLen + 1);
            System.arraycopy(strPathSqlite.getBytes(), 0, ptrByteArraySqlite.byValue, 0, iPathSqliteLen);
            ptrByteArraySqlite.write();
            System.out.println(new String(ptrByteArraySqlite.byValue));
            hikISUPStorage.NET_ESS_SetSDKInitCfg(6, ptrByteArraySqlite.getPointer());
            // Initialize the storage SDK.
            boolean sinit = hikISUPStorage.NET_ESS_Init();
            if (!sinit) {
                log.error("NET_ESS_Init failed, error code: {}", hikISUPStorage.NET_ESS_GetLastError());
            }
        } else if (OsSelect.isLinux()) {
            BYTE_ARRAY ptrByteArrayCrypto = new BYTE_ARRAY(256);
            String strPathCrypto = System.getProperty("user.dir") + "/sdk/linux/libcrypto.so";
            System.out.println(strPathCrypto);
            System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, strPathCrypto.length());
            ptrByteArrayCrypto.write();
            hikISUPStorage.NET_ESS_SetSDKInitCfg(4, ptrByteArrayCrypto.getPointer());

            // Configure the libssl path.
            BYTE_ARRAY ptrByteArraySsl = new BYTE_ARRAY(256);
            String strPathSsl = System.getProperty("user.dir") + "/sdk/linux/libssl.so";
            System.out.println(strPathSsl);
            System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, strPathSsl.length());
            ptrByteArraySsl.write();
            hikISUPStorage.NET_ESS_SetSDKInitCfg(5, ptrByteArraySsl.getPointer());

            // Configure the sqlite3 path.
            BYTE_ARRAY ptrByteArraysplite = new BYTE_ARRAY(256);
            String strPathsplite = System.getProperty("user.dir") + "/sdk/linux/libsqlite3.so";
            System.out.println(strPathsplite);
            System.arraycopy(strPathsplite.getBytes(), 0, ptrByteArraysplite.byValue, 0, strPathsplite.length());
            ptrByteArraysplite.write();
            hikISUPStorage.NET_ESS_SetSDKInitCfg(6, ptrByteArraysplite.getPointer());
            // Initialize the storage SDK.
            boolean sinit = hikISUPStorage.NET_ESS_Init();
            if (!sinit) {
                log.error("NET_ESS_Init failed, error code: {}", hikISUPStorage.NET_ESS_GetLastError());
            }
        }
        // Enable SDK file logging.
        boolean logToFile = hikISUPStorage.NET_ESS_SetLogToFile(3, System.getProperty("user.dir") + "/container/EHomeSDKLog", false);
        return hikISUPStorage;
    }

    /**
     * Initialize the CMS SDK connection.
     *
     * @return
     */
    @Bean
    public HCISUPCMS ihcisupcms() {
        log.info("*************** Initializing HIK ISUP CMS SDK ***************");
        HCISUPCMS hcisupcms = null;
        synchronized (HCISUPCMS.class) {
            String strDllPath = "";
            try {
                if (OsSelect.isWindows())
                    // Windows library path.
                    strDllPath = System.getProperty("user.dir") + "\\sdk\\windows\\HCISUPCMS.dll";
                else if (OsSelect.isLinux())
                    // Linux library path.
                    strDllPath = System.getProperty("user.dir") + "/sdk/linux/libHCISUPCMS.so";
                hcisupcms = (HCISUPCMS) Native.loadLibrary(strDllPath, HCISUPCMS.class);
            } catch (Exception ex) {
                System.out.println("loadLibrary: " + strDllPath + " Error: " + ex.getMessage());
                return hcisupcms;
            }
        }
        // CMS_Init
        if (OsSelect.isWindows()) {
            log.info("*************** Initializing Windows HIK ISUP CMS SDK ***************");
            BYTE_ARRAY ptrByteArrayCrypto = new BYTE_ARRAY(256);
            String strPathCrypto = System.getProperty("user.dir") + "\\sdk\\windows\\libeay32.dll";
            System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, strPathCrypto.length());
            ptrByteArrayCrypto.write();
            hcisupcms.NET_ECMS_SetSDKInitCfg(0, ptrByteArrayCrypto.getPointer());

            // Configure the libssl path.
            BYTE_ARRAY ptrByteArraySsl = new BYTE_ARRAY(256);
            String strPathSsl = System.getProperty("user.dir") + "\\sdk\\windows\\ssleay32.dll";
            System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, strPathSsl.length());
            ptrByteArraySsl.write();
            hcisupcms.NET_ECMS_SetSDKInitCfg(1, ptrByteArraySsl.getPointer());
            // Initialize the registration service.
            boolean binit = hcisupcms.NET_ECMS_Init();
            if (binit) {
                log.info("Windows CMS SDK initialized successfully.");
            }
            // Configure the HCAapSDKCom component directory.
            BYTE_ARRAY ptrByteArrayCom = new BYTE_ARRAY(256);
            String strPathCom = System.getProperty("user.dir") + "\\sdk\\windows\\HCAapSDKCom";
            System.arraycopy(strPathCom.getBytes(), 0, ptrByteArrayCom.byValue, 0, strPathCom.length());
            ptrByteArrayCom.write();
            hcisupcms.NET_ECMS_SetSDKLocalCfg(5, ptrByteArrayCom.getPointer());

        } else if (OsSelect.isLinux()) {
            log.info("*************** Initializing Linux HIK ISUP CMS SDK ***************");
            BYTE_ARRAY ptrByteArrayCrypto = new BYTE_ARRAY(256);
            String strPathCrypto = System.getProperty("user.dir") + "/sdk/linux/libcrypto.so";
            System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, strPathCrypto.length());
            ptrByteArrayCrypto.write();
            hcisupcms.NET_ECMS_SetSDKInitCfg(0, ptrByteArrayCrypto.getPointer());

            // Configure the libssl path.
            BYTE_ARRAY ptrByteArraySsl = new BYTE_ARRAY(256);
            String strPathSsl = System.getProperty("user.dir") + "/sdk/linux/libssl.so";
            System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, strPathSsl.length());
            ptrByteArraySsl.write();
            hcisupcms.NET_ECMS_SetSDKInitCfg(1, ptrByteArraySsl.getPointer());
            // Initialize the registration service.
            boolean binit = hcisupcms.NET_ECMS_Init();
            if (binit) {
                log.info("Linux CMS SDK initialized successfully.");
            } else {
                log.error("Linux CMS SDK initialization failed, error code: {}", hcisupcms.NET_ECMS_GetLastError());
            }
            // Configure the HCAapSDKCom component directory.
            BYTE_ARRAY ptrByteArrayCom = new BYTE_ARRAY(256);
            String strPathCom = System.getProperty("user.dir") + "/sdk/linux/HCAapSDKCom/";
            System.arraycopy(strPathCom.getBytes(), 0, ptrByteArrayCom.byValue, 0, strPathCom.length());
            ptrByteArrayCom.write();
            hcisupcms.NET_ECMS_SetSDKLocalCfg(5, ptrByteArrayCom.getPointer());

        }
        log.info("*************** HIK ISUP CMS SDK initialization completed ***************");
        return hcisupcms;
    }

    @Bean
    @ConditionalOnProperty(prefix = "hik.features.stream", name = "enabled", havingValue = "true")
    public IHikISUPStream hikISUPStream() {
        log.info("*************** Initializing HIK ISUP STREAM SDK ***************");
        IHikISUPStream hikISUPStream = null;
        synchronized (IHikISUPStream.class) {
            String strDllPath = "";
            try {
                if (OsSelect.isWindows())
                    strDllPath = System.getProperty("user.dir") + "\\sdk\\windows\\HCISUPStream.dll";
                else if (OsSelect.isLinux())
                    strDllPath = System.getProperty("user.dir") + "/sdk/linux/libHCISUPStream.so";
                hikISUPStream = (IHikISUPStream) Native.loadLibrary(strDllPath, IHikISUPStream.class);
            } catch (Exception ex) {
                log.error("loadLibrary: {}, Error: {}", strDllPath, ex.getMessage());
                return hikISUPStream;
            }
        }
        if (OsSelect.isWindows()) {
            log.info("*************** Initializing Windows HIK ISUP STREAM SDK ***************");
            BYTE_ARRAY ptrByteArrayCrypto = new BYTE_ARRAY(256);
            String strPathCrypto = System.getProperty("user.dir") + "\\sdk\\windows\\libeay32.dll";
            System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, strPathCrypto.length());
            ptrByteArrayCrypto.write();
            if (!hikISUPStream.NET_ESTREAM_SetSDKInitCfg(0, ptrByteArrayCrypto.getPointer())) {
                log.error("NET_ESTREAM_SetSDKInitCfg 0 failed, error: {}", hikISUPStream.NET_ESTREAM_GetLastError());
            }
            BYTE_ARRAY ptrByteArraySsl = new BYTE_ARRAY(256);
            String strPathSsl = System.getProperty("user.dir") + "\\sdk\\windows\\ssleay32.dll";
            System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, strPathSsl.length());
            ptrByteArraySsl.write();
            if (!hikISUPStream.NET_ESTREAM_SetSDKInitCfg(1, ptrByteArraySsl.getPointer())) {
                log.error("NET_ESTREAM_SetSDKInitCfg 1 failed, error: {}", hikISUPStream.NET_ESTREAM_GetLastError());
            }
            // Initialize the streaming SDK.
            hikISUPStream.NET_ESTREAM_Init();
            // Configure the HCAapSDKCom component directory.
            BYTE_ARRAY ptrByteArrayCom = new BYTE_ARRAY(256);
            String strPathCom = System.getProperty("user.dir") + "\\sdk\\windows\\HCAapSDKCom";
            System.arraycopy(strPathCom.getBytes(), 0, ptrByteArrayCom.byValue, 0, strPathCom.length());
            ptrByteArrayCom.write();
            if (!hikISUPStream.NET_ESTREAM_SetSDKLocalCfg(5, ptrByteArrayCom.getPointer())) {
                log.error("NET_ESTREAM_SetSDKLocalCfg 5 failed, error: {}", hikISUPStream.NET_ESTREAM_GetLastError());
            }
        } else if (OsSelect.isLinux()) {
            log.info("*************** Initializing Linux HIK ISUP STREAM SDK ***************");
            // Configure the libcrypto path.
            BYTE_ARRAY ptrByteArrayCrypto = new BYTE_ARRAY(256);
            String strPathCrypto = System.getProperty("user.dir") + "/sdk/linux/libcrypto.so";
            System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, strPathCrypto.length());
            ptrByteArrayCrypto.write();
            if (!hikISUPStream.NET_ESTREAM_SetSDKInitCfg(0, ptrByteArrayCrypto.getPointer())) {
                log.error("NET_ESTREAM_SetSDKInitCfg 0 failed, error: {}", hikISUPStream.NET_ESTREAM_GetLastError());
            }
            // Configure the libssl path.
            BYTE_ARRAY ptrByteArraySsl = new BYTE_ARRAY(256);
            String strPathSsl = System.getProperty("user.dir") + "/sdk/linux/libssl.so";
            System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, strPathSsl.length());
            ptrByteArraySsl.write();
            if (!hikISUPStream.NET_ESTREAM_SetSDKInitCfg(1, ptrByteArraySsl.getPointer())) {
                log.error("NET_ESTREAM_SetSDKInitCfg 1 failed, error: {}", hikISUPStream.NET_ESTREAM_GetLastError());
            }
            hikISUPStream.NET_ESTREAM_Init();
            // Configure the HCAapSDKCom component directory.
            BYTE_ARRAY ptrByteArrayCom = new BYTE_ARRAY(256);
            String strPathCom = System.getProperty("user.dir") + "/sdk/linux/HCAapSDKCom/";
            System.arraycopy(strPathCom.getBytes(), 0, ptrByteArrayCom.byValue, 0, strPathCom.length());
            ptrByteArrayCom.write();
            if (!hikISUPStream.NET_ESTREAM_SetSDKLocalCfg(5, ptrByteArrayCom.getPointer())) {
                log.error("NET_ESTREAM_SetSDKLocalCfg 5 failed, error: {}", hikISUPStream.NET_ESTREAM_GetLastError());
            }
        }
        hikISUPStream.NET_ESTREAM_SetLogToFile(3, "./container/EHomeSDKLog", false);
        log.info("*************** HIK ISUP STREAM SDK initialization completed ***************");
        return hikISUPStream;
    }

    @Bean
    @ConditionalOnProperty(prefix = "hik.features.channel-sync", name = "enabled", havingValue = "true")
    public IHikNet hikNet() {
        IHikNet hikNet = null;
        synchronized (IHikISUPStream.class) {
            String strDllPath = "";
            try {
                if (OsSelect.isWindows())
                    strDllPath = System.getProperty("user.dir") + "\\sdk\\windows\\netsdk\\HCNetSDK.dll";
                else if (OsSelect.isLinux())
                    strDllPath = System.getProperty("user.dir") + "/sdk/linux/netsdk/libhcnetsdk.so";
                hikNet = (IHikNet) Native.loadLibrary(strDllPath, IHikNet.class);
            } catch (Exception ex) {
                log.error("loadLibrary: {}, Error: {}", strDllPath, ex.getMessage());
                return hikNet;
            }
        }
        hikNet.NET_DVR_Init();
        return hikNet;
    }

}
