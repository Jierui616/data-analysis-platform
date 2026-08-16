package com.yupi.springbootinit.service;

import com.yupi.springbootinit.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.DigestUtils;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserServicePasswordTest {

    @Test
    void hashPassword_generatesDifferentHashesForSameInput() {
        UserServiceImpl service = new UserServiceImpl();

        String first = ReflectionTestUtils.invokeMethod(service, "hashPassword", "abc12345");
        String second = ReflectionTestUtils.invokeMethod(service, "hashPassword", "abc12345");

        assertNotEquals(first, second);
        Boolean matches = ReflectionTestUtils.invokeMethod(service, "matchesPassword", "abc12345", first);
        assertTrue(Boolean.TRUE.equals(matches));
    }

    @Test
    void matchesPassword_acceptsLegacyMd5Hash() {
        UserServiceImpl service = new UserServiceImpl();
        String legacyHash = DigestUtils.md5DigestAsHex(("yupi" + "abc12345").getBytes());

        Boolean matched = ReflectionTestUtils.invokeMethod(service, "matchesPassword", "abc12345", legacyHash);

        assertTrue(Boolean.TRUE.equals(matched));
    }
}
