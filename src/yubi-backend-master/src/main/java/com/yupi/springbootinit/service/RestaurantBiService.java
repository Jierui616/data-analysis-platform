package com.yupi.springbootinit.service;

import com.yupi.springbootinit.model.entity.User;
import java.util.Map;

public interface RestaurantBiService {

    Map<String, Object> getHqOverview(User loginUser, Map<String, Object> requestBody);

    Map<String, Object> getStoreOverview(User loginUser, Map<String, Object> requestBody);

    Map<String, Object> getDishAnalysis(User loginUser, Map<String, Object> requestBody);

    Map<String, Object> getCampaignReview(User loginUser, Map<String, Object> requestBody);

    Map<String, Object> listAlertRules();

    Map<String, Object> listAlertEvents(User loginUser, Map<String, Object> requestBody);

    Map<String, Object> buildCostLossMock(User loginUser, Map<String, Object> requestBody);

    Map<String, Object> buildStaffEfficiencyMock(User loginUser, Map<String, Object> requestBody);

    Map<String, Object> buildForecastMock(User loginUser, Map<String, Object> requestBody);

    /**
     * 返回当前用户可见数据的日期覆盖范围。
     */
    Map<String, Object> getAvailableDateRange(User loginUser, Map<String, Object> requestBody);

    /**
     * 将 chartData(CSV 文本)结构化同步到餐饮指标表。
     */
    Map<String, Object> importFromChartData(User loginUser, Long chartId, String chartData);

    /**
     * 导入成本损耗 CSV 文本。
     */
    Map<String, Object> importCostData(User loginUser, String csvData, String importMode);

    /**
     * 导入排班人效 CSV 文本。
     */
    Map<String, Object> importStaffData(User loginUser, String csvData, String importMode);

    /**
     * 最近导入记录。
     */
    Map<String, Object> listImportLogs(User loginUser, Map<String, Object> requestBody);

    /**
     * 回滚最近一批导入（按类型）。
     */
    Map<String, Object> rollbackLatestImport(User loginUser, String importType);

    /**
     * 按导入日志 ID 回滚指定批次。
     */
    Map<String, Object> rollbackImportByLogId(User loginUser, Long logId);

    /**
     * 大屏地图用：返回区域-省份/城市覆盖与区域营收热力。
     */
    Map<String, Object> getGeoScope(User loginUser, Map<String, Object> requestBody);
}
