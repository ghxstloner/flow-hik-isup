package com.oldwei.isup.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.oldwei.isup.model.Device;
import com.oldwei.isup.model.provisioning.ProvisioningAccess;
import com.oldwei.isup.model.provisioning.ProvisioningEmployee;
import com.oldwei.isup.model.provisioning.ProvisioningResponse;
import com.oldwei.isup.model.provisioning.ProvisioningStatus;
import com.oldwei.isup.model.provisioning.UserVerificationResponse;
import com.oldwei.isup.model.provisioning.UserDeleteRequest;
import com.oldwei.isup.model.provisioning.UserSyncRequest;
import com.oldwei.isup.sdk.service.impl.CmsUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class HikvisionProvisioningService {

    private static final String SET_UP_USER_URL = "PUT /ISAPI/AccessControl/UserInfo/SetUp?format=json";
    private static final String SEARCH_USER_URL = "POST /ISAPI/AccessControl/UserInfo/Search?format=json";
    private static final String DEFAULT_BEGIN_TIME = "2020-01-01T00:00:00";
    private static final String DEFAULT_END_TIME = "2037-12-31T23:59:59";

    private final CmsUtil cmsUtil;

    public ProvisioningResponse syncUser(Device device, String employeeNo, UserSyncRequest request) {
        log.info("Hikvision user sync requested: deviceId={}, employeeNo={}, correlationId={}",
                device.getDeviceId(), employeeNo, request.getCorrelationId());

        String payload = buildUserInfoPayload(employeeNo, request);
        CmsUtil.IsapiPassThroughResult result = cmsUtil.passThroughWithStatus(
                device.getLoginId(),
                SET_UP_USER_URL,
                payload
        );

        boolean success = result.isSuccess()
                && StringUtils.isBlank(result.getSdkError())
                && isSuccessfulIsapiResponse(result.getRawResponse());
        return new ProvisioningResponse(
                request.getCorrelationId(),
                device.getDeviceId(),
                employeeNo,
                success,
                false,
                false,
                success ? ProvisioningStatus.SYNCED : ProvisioningStatus.FAILED,
                result.getRawResponse(),
                result.getSdkError()
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
                ProvisioningStatus.NOT_IMPLEMENTED,
                "",
                null
        );
    }

    public UserVerificationResponse verifyUser(Device device, String employeeNo) {
        log.info("Hikvision user verification requested: deviceId={}, employeeNo={}",
                device.getDeviceId(), employeeNo);

        CmsUtil.IsapiPassThroughResult result = cmsUtil.passThroughWithStatus(
                device.getLoginId(),
                SEARCH_USER_URL,
                buildUserSearchPayload(employeeNo)
        );

        boolean transportSuccess = result.isSuccess()
                && StringUtils.isBlank(result.getSdkError())
                && StringUtils.isNotBlank(result.getRawResponse());
        boolean found = transportSuccess && userSearchContainsEmployee(result.getRawResponse(), employeeNo);
        String bridgeStatus = transportSuccess
                ? (found ? ProvisioningStatus.SYNCED : ProvisioningStatus.NOT_FOUND)
                : ProvisioningStatus.FAILED;
        return new UserVerificationResponse(
                device.getDeviceId(),
                employeeNo,
                found,
                bridgeStatus,
                result.getRawResponse(),
                result.getSdkError()
        );
    }

    private String buildUserInfoPayload(String employeeNo, UserSyncRequest request) {
        ProvisioningEmployee employee = request.getEmployee();
        ProvisioningAccess access = request.getAccess();

        Map<String, Object> valid = new LinkedHashMap<>();
        valid.put("enable", true);
        valid.put("beginTime", valueOrDefault(access != null ? access.getBeginTime() : null, DEFAULT_BEGIN_TIME));
        valid.put("endTime", valueOrDefault(access != null ? access.getEndTime() : null, DEFAULT_END_TIME));
        valid.put("timeType", "local");

        Map<String, Object> rightPlan = new LinkedHashMap<>();
        rightPlan.put("doorNo", 1);
        rightPlan.put("planTemplateNo", valueOrDefault(access != null ? access.getPlanTemplateNo() : null, "1"));

        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("employeeNo", employeeNo);
        userInfo.put("name", valueOrDefault(employee != null ? employee.getName() : null, employeeNo));
        userInfo.put("userType", valueOrDefault(access != null ? access.getUserType() : null, "normal"));
        userInfo.put("Valid", valid);
        userInfo.put("doorRight", valueOrDefault(access != null ? access.getDoorRight() : null, "1"));
        userInfo.put("RightPlan", List.of(rightPlan));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("UserInfo", userInfo);

        return JSON.toJSONString(payload);
    }

    private String buildUserSearchPayload(String employeeNo) {
        Map<String, Object> employeeNoItem = new LinkedHashMap<>();
        employeeNoItem.put("employeeNo", employeeNo);

        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("searchID", UUID.randomUUID().toString());
        condition.put("searchResultPosition", 0);
        condition.put("maxResults", 1);
        condition.put("EmployeeNoList", List.of(employeeNoItem));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("UserInfoSearchCond", condition);

        return JSON.toJSONString(payload);
    }

    private String valueOrDefault(String value, String defaultValue) {
        return StringUtils.isNotBlank(value) ? value : defaultValue;
    }

    private boolean isSuccessfulIsapiResponse(String rawResponse) {
        if (StringUtils.isBlank(rawResponse)) {
            return false;
        }

        try {
            JSONObject json = JSON.parseObject(rawResponse);
            Integer statusCode = json.getInteger("statusCode");
            String subStatusCode = json.getString("subStatusCode");
            return Integer.valueOf(1).equals(statusCode) && StringUtils.equalsIgnoreCase("ok", subStatusCode);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean userSearchContainsEmployee(String rawResponse, String employeeNo) {
        if (StringUtils.isBlank(rawResponse)) {
            return false;
        }

        try {
            JSONObject root = JSON.parseObject(rawResponse);
            JSONObject userInfoSearch = root.getJSONObject("UserInfoSearch");
            if (userInfoSearch == null) {
                return false;
            }

            Object userInfo = userInfoSearch.get("UserInfo");
            if (userInfo instanceof JSONArray users) {
                return users.stream().anyMatch(user -> userInfoMatchesEmployee(user, employeeNo));
            }

            return userInfoMatchesEmployee(userInfo, employeeNo);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean userInfoMatchesEmployee(Object userInfo, String employeeNo) {
        if (!(userInfo instanceof JSONObject user)) {
            return false;
        }

        Object rawEmployeeNo = user.get("employeeNo");
        return rawEmployeeNo != null && StringUtils.equals(String.valueOf(rawEmployeeNo).trim(), employeeNo.trim());
    }
}
