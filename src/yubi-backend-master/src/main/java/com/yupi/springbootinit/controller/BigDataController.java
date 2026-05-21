package com.yupi.springbootinit.controller;

import com.yupi.springbootinit.common.BaseResponse;
import com.yupi.springbootinit.common.ResultUtils;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.service.UserService;
import com.yupi.springbootinit.service.bigdata.BigDataStackProbeService;
import com.yupi.springbootinit.service.bigdata.MetricSyncService;
import java.util.Map;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 大数据栈运维与状态（ClickHouse / Kafka / MinIO）。
 */
@RestController
@RequestMapping("/bigdata")
public class BigDataController {

    @Resource
    private BigDataStackProbeService bigDataStackProbeService;

    @Resource
    private MetricSyncService metricSyncService;

    @Resource
    private UserService userService;

    @GetMapping("/status")
    public BaseResponse<Map<String, Object>> status(HttpServletRequest request) {
        userService.getLoginUser(request);
        return ResultUtils.success(bigDataStackProbeService.probe());
    }

    @PostMapping("/sync/metrics")
    public BaseResponse<Map<String, Object>> syncMetrics(HttpServletRequest request) {
        userService.getLoginUser(request);
        return ResultUtils.success(metricSyncService.syncStoreDailyMetrics());
    }
}
