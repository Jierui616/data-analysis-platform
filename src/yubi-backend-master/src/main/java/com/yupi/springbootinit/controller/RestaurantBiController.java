package com.yupi.springbootinit.controller;

import com.yupi.springbootinit.common.BaseResponse;
import com.yupi.springbootinit.common.ResultUtils;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.service.RestaurantBiService;
import com.yupi.springbootinit.service.UserService;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/restaurant")
public class RestaurantBiController {

    @Resource
    private RestaurantBiService restaurantBiService;

    @Resource
    private UserService userService;

    @PostMapping("/overview/hq")
    public BaseResponse<Map<String, Object>> hqOverview(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(restaurantBiService.getHqOverview(loginUser, defaultBody(body)));
    }

    @PostMapping("/overview/store")
    public BaseResponse<Map<String, Object>> storeOverview(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(restaurantBiService.getStoreOverview(loginUser, defaultBody(body)));
    }

    @PostMapping("/dish/analysis")
    public BaseResponse<Map<String, Object>> dishAnalysis(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(restaurantBiService.getDishAnalysis(loginUser, defaultBody(body)));
    }

    @PostMapping("/campaign/review")
    public BaseResponse<Map<String, Object>> campaignReview(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(restaurantBiService.getCampaignReview(loginUser, defaultBody(body)));
    }

    @PostMapping("/alert/rules")
    public BaseResponse<Map<String, Object>> alertRules(HttpServletRequest request) {
        userService.getLoginUser(request);
        return ResultUtils.success(restaurantBiService.listAlertRules());
    }

    @PostMapping("/alert/events")
    public BaseResponse<Map<String, Object>> alertEvents(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(restaurantBiService.listAlertEvents(loginUser, defaultBody(body)));
    }

    @PostMapping("/mock/cost-loss")
    public BaseResponse<Map<String, Object>> mockCostLoss(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(restaurantBiService.buildCostLossMock(loginUser, defaultBody(body)));
    }

    @PostMapping("/mock/staff-efficiency")
    public BaseResponse<Map<String, Object>> mockStaffEfficiency(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(restaurantBiService.buildStaffEfficiencyMock(loginUser, defaultBody(body)));
    }

    @PostMapping("/mock/forecast")
    public BaseResponse<Map<String, Object>> mockForecast(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(restaurantBiService.buildForecastMock(loginUser, defaultBody(body)));
    }

    @PostMapping("/date-range")
    public BaseResponse<Map<String, Object>> dateRange(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(restaurantBiService.getAvailableDateRange(loginUser, defaultBody(body)));
    }

    @PostMapping("/geo-scope")
    public BaseResponse<Map<String, Object>> geoScope(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(restaurantBiService.getGeoScope(loginUser, defaultBody(body)));
    }

    @PostMapping("/import/from-chart")
    public BaseResponse<Map<String, Object>> importFromChart(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        body = defaultBody(body);
        User loginUser = userService.getLoginUser(request);
        Long chartId = toLong(body.get("chartId"));
        String chartData = body == null ? null : String.valueOf(body.getOrDefault("chartData", ""));
        return ResultUtils.success(restaurantBiService.importFromChartData(loginUser, chartId, chartData));
    }

    @PostMapping("/import/cost")
    public BaseResponse<Map<String, Object>> importCost(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        body = defaultBody(body);
        User loginUser = userService.getLoginUser(request);
        String csvData = String.valueOf(body.getOrDefault("csvData", ""));
        String importMode = String.valueOf(body.getOrDefault("importMode", "overwrite"));
        return ResultUtils.success(restaurantBiService.importCostData(loginUser, csvData, importMode));
    }

    @PostMapping("/import/staff")
    public BaseResponse<Map<String, Object>> importStaff(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        body = defaultBody(body);
        User loginUser = userService.getLoginUser(request);
        String csvData = String.valueOf(body.getOrDefault("csvData", ""));
        String importMode = String.valueOf(body.getOrDefault("importMode", "overwrite"));
        return ResultUtils.success(restaurantBiService.importStaffData(loginUser, csvData, importMode));
    }

    @PostMapping("/import/logs")
    public BaseResponse<Map<String, Object>> importLogs(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(restaurantBiService.listImportLogs(loginUser, defaultBody(body)));
    }

    @PostMapping("/import/rollback-latest")
    public BaseResponse<Map<String, Object>> rollbackLatestImport(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        body = defaultBody(body);
        User loginUser = userService.getLoginUser(request);
        Long logId = toLong(body.get("logId"));
        if (logId != null && logId > 0) {
            return ResultUtils.success(restaurantBiService.rollbackImportByLogId(loginUser, logId));
        }
        String importType = String.valueOf(body.getOrDefault("importType", "")).trim();
        return ResultUtils.success(restaurantBiService.rollbackLatestImport(loginUser, importType));
    }

    private Map<String, Object> defaultBody(Map<String, Object> body) {
        return body == null ? new HashMap<String, Object>() : body;
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
