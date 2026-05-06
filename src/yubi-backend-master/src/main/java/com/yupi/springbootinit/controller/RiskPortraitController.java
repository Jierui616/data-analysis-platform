package com.yupi.springbootinit.controller;

import com.yupi.springbootinit.common.BaseResponse;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.common.ResultUtils;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.service.RiskPortraitService;
import com.yupi.springbootinit.service.UserService;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Risk & credit portrait HTTP API under {@code /api/risk-portrait} (server.servlet.context-path=/api).
 * Hit log rows are stored in {@code risk_portrait_hit_log}; DDL {@code sql/risk_portrait_schema.sql}.
 */
@RestController
@RequestMapping("/risk-portrait")
public class RiskPortraitController {

    @Resource
    private RiskPortraitService riskPortraitService;

    @Resource
    private UserService userService;

    @PostMapping("/overview")
    public BaseResponse<Map<String, Object>> overview(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(riskPortraitService.getPortraitOverview(loginUser, defaultBody(body)));
    }

    @PostMapping("/store/profile")
    public BaseResponse<Map<String, Object>> storeProfile(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        Map<String, Object> data = riskPortraitService.getStorePortrait(loginUser, defaultBody(body));
        if (data == null) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "门店不存在或无权限访问");
        }
        return ResultUtils.success(data);
    }

    /** Paginates {@code risk_portrait_hit_log}; body: current, pageSize, optional scene. */
    @PostMapping("/hit-log/page")
    public BaseResponse<Map<String, Object>> hitLogPage(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(riskPortraitService.pageHitLog(loginUser, defaultBody(body)));
    }

    /** Unified decision; optional persist inserts one row into {@code risk_portrait_hit_log}. */
    @PostMapping("/decision")
    public BaseResponse<Map<String, Object>> decision(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        try {
            return ResultUtils.success(riskPortraitService.runDecision(loginUser, defaultBody(body)));
        } catch (IllegalArgumentException e) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, e.getMessage());
        }
    }

    /** Body: dimension (memberId|phone|payAccount), value, optional startDate/endDate (yyyy-MM-dd). */
    @PostMapping("/member/activity")
    public BaseResponse<Map<String, Object>> memberActivity(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        try {
            return ResultUtils.success(riskPortraitService.getMemberActivityDetails(loginUser, defaultBody(body)));
        } catch (IllegalArgumentException e) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, e.getMessage());
        }
    }

    /** Body: startDate, endDate, optional riskLevel, storeKeyword, current, pageSize. */
    @PostMapping("/member/watchlist")
    public BaseResponse<Map<String, Object>> memberWatchlist(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        try {
            return ResultUtils.success(riskPortraitService.getMemberWatchlist(loginUser, defaultBody(body)));
        } catch (IllegalArgumentException e) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, e.getMessage());
        }
    }

    /** Body: optional weakSignal (partial device / channel hint) to narrow demo clusters. */
    @PostMapping("/anonymous/summary")
    public BaseResponse<Map<String, Object>> anonymousSummary(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(riskPortraitService.getAnonymousPortraitSummary(loginUser, defaultBody(body)));
    }

    private Map<String, Object> defaultBody(Map<String, Object> body) {
        return body == null ? new HashMap<String, Object>() : body;
    }
}
