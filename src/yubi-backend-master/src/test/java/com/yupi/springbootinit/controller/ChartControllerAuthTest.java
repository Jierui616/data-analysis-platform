package com.yupi.springbootinit.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.model.dto.chart.ChartQueryRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChartControllerAuthTest {

    @Test
    void getOwnedChartOrThrowRejectsOtherUser() {
        ChartController controller = new ChartController();
        UserService userService = mock(UserService.class);
        ChartService chartService = mock(ChartService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);

        User loginUser = new User();
        loginUser.setId(2L);

        Chart chart = new Chart();
        chart.setId(10L);
        chart.setUserId(1L);

        when(userService.getLoginUser(request)).thenReturn(loginUser);
        when(userService.isAdmin(loginUser)).thenReturn(false);
        when(chartService.getById(10L)).thenReturn(chart);

        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "chartService", chartService);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ReflectionTestUtils.invokeMethod(controller, "getOwnedChartOrThrow", 10L, request));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
    }

    @Test
    void getOwnedChartOrThrowAllowsOwner() {
        ChartController controller = new ChartController();
        UserService userService = mock(UserService.class);
        ChartService chartService = mock(ChartService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);

        User loginUser = new User();
        loginUser.setId(1L);

        Chart chart = new Chart();
        chart.setId(10L);
        chart.setUserId(1L);

        when(userService.getLoginUser(request)).thenReturn(loginUser);
        when(userService.isAdmin(loginUser)).thenReturn(false);
        when(chartService.getById(10L)).thenReturn(chart);

        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "chartService", chartService);

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(controller, "getOwnedChartOrThrow", 10L, request));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void listChartByPageForcesCurrentUserForNonAdmin() {
        ChartController controller = new ChartController();
        UserService userService = mock(UserService.class);
        ChartService chartService = mock(ChartService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);

        User loginUser = new User();
        loginUser.setId(1L);

        ChartQueryRequest queryRequest = new ChartQueryRequest();
        queryRequest.setCurrent(1);
        queryRequest.setPageSize(10);
        queryRequest.setUserId(999L);

        when(userService.getLoginUser(request)).thenReturn(loginUser);
        when(userService.isAdmin(loginUser)).thenReturn(false);
        when(chartService.page(any(Page.class), any())).thenReturn(new Page<Chart>());

        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "chartService", chartService);

        controller.listChartByPage(queryRequest, request);

        assertEquals(1L, queryRequest.getUserId());
    }
}
