package com.oldwei.isup.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HikvisionProvisioningServiceTest {

    @Test
    void parseUserCountUsesNumOfMatchesFirst() {
        HikvisionProvisioningService.UserCountParseResult result = HikvisionProvisioningService.parseUserCount("""
                {
                  "UserInfoSearch": {
                    "numOfMatches": 123,
                    "totalMatches": 456,
                    "UserInfo": [{"employeeNo": "1"}]
                  }
                }
                """);

        assertThat(result.userCount()).isEqualTo(123);
        assertThat(result.rawTotalField()).isEqualTo("numOfMatches");
        assertThat(result.parseError()).isNull();
    }

    @Test
    void parseUserCountFallsBackToTotalMatches() {
        HikvisionProvisioningService.UserCountParseResult result = HikvisionProvisioningService.parseUserCount("""
                {
                  "UserInfoSearch": {
                    "totalMatches": 77,
                    "UserInfo": [{"employeeNo": "1"}]
                  }
                }
                """);

        assertThat(result.userCount()).isEqualTo(77);
        assertThat(result.rawTotalField()).isEqualTo("totalMatches");
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
