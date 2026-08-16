package com.yupi.springbootinit.service;

import com.yupi.springbootinit.model.dto.chart.GenChartByAiRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.service.impl.ChartGenerationServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChartGenerationServiceTest {

    @Test
    void createPendingChart_savesWaitChartForLoginUser() {
        ChartGenerationServiceImpl service = new ChartGenerationServiceImpl();
        ChartService chartService = mock(ChartService.class);
        ArgumentCaptor<Chart> chartCaptor = ArgumentCaptor.forClass(Chart.class);

        when(chartService.save(chartCaptor.capture())).thenAnswer(invocation -> {
            Chart chart = chartCaptor.getValue();
            chart.setId(100L);
            return true;
        });
        ReflectionTestUtils.setField(service, "chartService", chartService);

        GenChartByAiRequest request = new GenChartByAiRequest();
        request.setName("门店经营分析");
        request.setGoal("分析门店销售额和毛利率表现");
        request.setChartType("柱状图");
        User loginUser = new User();
        loginUser.setId(1L);
        MultipartFile file = new MockMultipartFile(
                "file", "retail.csv", "text/csv", "date,sales\n2026-01-01,100".getBytes(StandardCharsets.UTF_8));

        Chart chart = service.createPendingChart(file, request, loginUser);

        assertEquals(100L, chart.getId());
        assertEquals(1L, chart.getUserId());
        assertEquals("wait", chart.getStatus());
        assertEquals("门店经营分析", chart.getName());
        assertEquals("分析门店销售额和毛利率表现", chart.getGoal());
        assertEquals("柱状图", chart.getChartType());
        assertTrue(chart.getChartData().contains("sales"));
    }
}
