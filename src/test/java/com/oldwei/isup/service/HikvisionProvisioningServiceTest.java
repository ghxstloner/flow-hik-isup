package com.oldwei.isup.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HikvisionProvisioningServiceTest {

    @Test
    void parseUserCountUsesTotalMatchesBeforePagedNumOfMatches() {
        HikvisionProvisioningService.UserCountParseResult result = HikvisionProvisioningService.parseUserCount("""
                {
                  "UserInfoSearch": {
                    "responseStatusStrg": "MORE",
                    "numOfMatches": 1,
                    "totalMatches": 111,
                    "UserInfo": [{"employeeNo": "1"}]
                  }
                }
                """);

        assertThat(result.userCount()).isEqualTo(111);
        assertThat(result.rawTotalField()).isEqualTo("totalMatches");
        assertThat(result.parseError()).isNull();
    }

    @Test
    void parseUserCountFallsBackToNumOfMatches() {
        HikvisionProvisioningService.UserCountParseResult result = HikvisionProvisioningService.parseUserCount("""
                {
                  "UserInfoSearch": {
                    "numOfMatches": 77,
                    "UserInfo": [{"employeeNo": "1"}]
                  }
                }
                """);

        assertThat(result.userCount()).isEqualTo(77);
        assertThat(result.rawTotalField()).isEqualTo("numOfMatches");
        assertThat(result.parseError()).isNull();
    }

    @Test
    void parseUserCountFallsBackToUserInfoArraySize() {
        HikvisionProvisioningService.UserCountParseResult result = HikvisionProvisioningService.parseUserCount("""
                {
                  "UserInfoSearch": {
                    "UserInfo": [
                      {"employeeNo": "1"},
                      {"employeeNo": "2"}
                    ]
                  }
                }
                """);

        assertThat(result.userCount()).isEqualTo(2);
        assertThat(result.rawTotalField()).isEqualTo("UserInfoSearch.UserInfo.size");
        assertThat(result.parseError()).isNull();
    }

    @Test
    void parseUserCountReturnsNoCountWhenNoTotalIsDetectable() {
        HikvisionProvisioningService.UserCountParseResult result = HikvisionProvisioningService.parseUserCount("""
                {
                  "UserInfoSearch": {
                    "responseStatus": true
                  }
                }
                """);

        assertThat(result.userCount()).isNull();
        assertThat(result.rawTotalField()).isNull();
        assertThat(result.parseError()).isNull();
    }

    @Test
    void parseUserCountReportsInvalidJson() {
        HikvisionProvisioningService.UserCountParseResult result = HikvisionProvisioningService.parseUserCount("{not-json");

        assertThat(result.userCount()).isNull();
        assertThat(result.rawTotalField()).isNull();
        assertThat(result.parseError()).isNotBlank();
    }
}
