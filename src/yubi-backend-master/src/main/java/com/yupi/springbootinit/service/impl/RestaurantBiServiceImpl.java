package com.yupi.springbootinit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yupi.springbootinit.mapper.AlertEventMapper;
import com.yupi.springbootinit.mapper.AlertRuleMapper;
import com.yupi.springbootinit.mapper.CampaignMetricMapper;
import com.yupi.springbootinit.mapper.DishDailyMetricMapper;
import com.yupi.springbootinit.mapper.RestaurantImportLogMapper;
import com.yupi.springbootinit.mapper.StoreCostDailyMetricMapper;
import com.yupi.springbootinit.mapper.StoreDailyMetricMapper;
import com.yupi.springbootinit.mapper.StoreMapper;
import com.yupi.springbootinit.mapper.StoreStaffDailyMetricMapper;
import com.yupi.springbootinit.mapper.StoreUserRoleMapper;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.entity.restaurant.AlertEvent;
import com.yupi.springbootinit.model.entity.restaurant.AlertRule;
import com.yupi.springbootinit.model.entity.restaurant.CampaignMetric;
import com.yupi.springbootinit.model.entity.restaurant.DishDailyMetric;
import com.yupi.springbootinit.model.entity.restaurant.RestaurantImportLog;
import com.yupi.springbootinit.model.entity.restaurant.Store;
import com.yupi.springbootinit.model.entity.restaurant.StoreCostDailyMetric;
import com.yupi.springbootinit.model.entity.restaurant.StoreDailyMetric;
import com.yupi.springbootinit.model.entity.restaurant.StoreStaffDailyMetric;
import com.yupi.springbootinit.model.entity.restaurant.StoreUserRole;
import com.yupi.springbootinit.service.RestaurantBiService;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.service.UserService;
import com.yupi.springbootinit.utils.CsvTableUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;
import javax.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
public class RestaurantBiServiceImpl implements RestaurantBiService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Resource
    private StoreMapper storeMapper;

    @Resource
    private StoreUserRoleMapper storeUserRoleMapper;

    @Resource
    private StoreDailyMetricMapper storeDailyMetricMapper;

    @Resource
    private StoreCostDailyMetricMapper storeCostDailyMetricMapper;

    @Resource
    private StoreStaffDailyMetricMapper storeStaffDailyMetricMapper;

    @Resource
    private RestaurantImportLogMapper restaurantImportLogMapper;

    @Resource
    private DishDailyMetricMapper dishDailyMetricMapper;

    @Resource
    private CampaignMetricMapper campaignMetricMapper;

    @Resource
    private AlertRuleMapper alertRuleMapper;

    @Resource
    private AlertEventMapper alertEventMapper;

    @Resource
    private UserService userService;

    @Resource
    private ChartService chartService;

    @Override
    public Map<String, Object> getHqOverview(User loginUser, Map<String, Object> requestBody) {
        LocalDate[] range = parseDateRange(requestBody);
        AccessScope scope = resolveScope(loginUser, requestBody);
        List<StoreDailyMetric> rows = listStoreDailyMetrics(scope.storeIds, range[0], range[1]);

        Map<Long, BigDecimal> mergedStoreRevenue = mergeStoreRevenueWithDishFallback(scope.storeIds, range[0], range[1]);
        BigDecimal totalRevenue = BigDecimal.ZERO;
        for (BigDecimal v : mergedStoreRevenue.values()) {
            totalRevenue = totalRevenue.add(v);
        }
        BigDecimal avgMargin = avg(rows, "grossMarginRate");
        BigDecimal avgTicket = avg(rows, "avgTicket");
        BigDecimal avgTurnover = avg(rows, "tableTurnover");
        BigDecimal avgPrepTime = avg(rows, "prepTimeAvgMin");
        BigDecimal avgCancelRate = avg(rows, "cancelRate");

        List<Map<String, Object>> trend = buildDailyTrend(rows);
        List<Map<String, Object>> storeRank = buildStoreRankFromMergedRevenue(mergedStoreRevenue, scope.storeIds);
        List<AlertEvent> alerts = listAlertEventsByScope(scope, range[0], range[1]);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("role", "hq");
        data.put("scope", buildScopeInfo(scope));
        data.put("period", range[0] + " ~ " + range[1]);
        Map<String, Object> summary = buildSummary(totalRevenue, avgMargin, avgTicket, avgTurnover, avgPrepTime, avgCancelRate);
        fillDishVolumeKpis(summary, scope.storeIds, range);
        data.put("summary", summary);
        data.put("healthScore", calcHealthScore(avgMargin, avgTurnover, avgCancelRate, avgPrepTime));
        data.put("trend", trend);
        data.put("storeRank", storeRank);
        data.put("alerts", toAlertList(alerts));
        return data;
    }

    @Override
    public Map<String, Object> getStoreOverview(User loginUser, Map<String, Object> requestBody) {
        LocalDate[] range = parseDateRange(requestBody);
        AccessScope scope = resolveScope(loginUser, requestBody);
        Long storeId = toLong(requestBody.get("storeId"));
        if (storeId == null && !scope.storeIds.isEmpty()) {
            storeId = scope.storeIds.get(0);
        }
        if (storeId == null || !scope.storeIds.contains(storeId)) {
            return emptyStoreOverview(range);
        }
        List<Long> oneStore = Collections.singletonList(storeId);
        List<StoreDailyMetric> rows = listStoreDailyMetrics(oneStore, range[0], range[1]);
        List<DishDailyMetric> dishRows = listDishMetrics(oneStore, range[0], range[1]);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("storeId", storeId);
        result.put("period", range[0] + " ~ " + range[1]);
        Map<String, Object> kpi = buildSummary(sum(rows, "revenue"), avg(rows, "grossMarginRate"), avg(rows, "avgTicket"),
                avg(rows, "tableTurnover"), avg(rows, "prepTimeAvgMin"), avg(rows, "cancelRate"));
        long orderVolume = dishRows.stream().mapToLong(r -> nullSafeLong(r.getSalesQty())).sum();
        long skuDistinct = dishRows.stream().map(DishDailyMetric::getDishName).filter(StringUtils::isNotBlank).distinct().count();
        kpi.put("orderCount", orderVolume);
        kpi.put("pileCount", skuDistinct);
        result.put("kpi", kpi);
        result.put("trend", buildDailyTrend(rows));
        result.put("dishTop", buildDishTop(dishRows));
        result.put("alerts", toAlertList(listAlertEventsByStore(storeId, range[0], range[1])));
        return result;
    }

    @Override
    public Map<String, Object> getDishAnalysis(User loginUser, Map<String, Object> requestBody) {
        LocalDate[] range = parseDateRange(requestBody);
        AccessScope scope = resolveScope(loginUser, requestBody);
        List<DishDailyMetric> rows = listDishMetrics(scope.storeIds, range[0], range[1]);
        List<Map<String, Object>> aggregated = aggregateDishRows(rows);

        List<Map<String, Object>> hot = aggregated.stream()
                .sorted((a, b) -> nullSafeBigDecimal((BigDecimal) b.get("revenue"))
                        .compareTo(nullSafeBigDecimal((BigDecimal) a.get("revenue"))))
                .limit(8)
                .collect(Collectors.toList());

        List<Map<String, Object>> lowEfficiency = aggregated.stream()
                .filter(item -> nullSafeBigDecimal((BigDecimal) item.get("grossMarginRate")).compareTo(new BigDecimal("0.50")) < 0
                        || nullSafeBigDecimal((BigDecimal) item.get("refundRate")).compareTo(new BigDecimal("0.08")) > 0)
                .sorted((a, b) -> nullSafeBigDecimal((BigDecimal) b.get("refundRate"))
                        .compareTo(nullSafeBigDecimal((BigDecimal) a.get("refundRate"))))
                .limit(8)
                .collect(Collectors.toList());

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("period", range[0] + " ~ " + range[1]);
        data.put("hotDishes", hot);
        data.put("lowEfficiencyDishes", lowEfficiency);
        data.put("categoryMix", buildCategoryMix(rows));
        return data;
    }

    private List<Map<String, Object>> aggregateDishRows(List<DishDailyMetric> rows) {
        Map<String, DishAgg> grouped = new LinkedHashMap<String, DishAgg>();
        for (DishDailyMetric row : rows) {
            String name = StringUtils.defaultIfBlank(row.getDishName(), "未命名菜品");
            DishAgg agg = grouped.containsKey(name) ? grouped.get(name) : new DishAgg();
            agg.dishName = name;
            agg.categoryName = StringUtils.defaultIfBlank(row.getCategoryName(), agg.categoryName);
            agg.salesQty += nullSafeLong(row.getSalesQty());
            BigDecimal revenue = nullSafeBigDecimal(row.getRevenue());
            agg.revenue = agg.revenue.add(revenue);
            agg.marginWeighted = agg.marginWeighted.add(nullSafeBigDecimal(row.getGrossMarginRate()).multiply(revenue));
            agg.refundWeighted = agg.refundWeighted.add(
                    nullSafeBigDecimal(row.getRefundRate()).multiply(BigDecimal.valueOf(Math.max(1L, nullSafeLong(row.getSalesQty())))));
            grouped.put(name, agg);
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (DishAgg agg : grouped.values()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("dishName", agg.dishName);
            item.put("categoryName", agg.categoryName);
            item.put("salesQty", agg.salesQty);
            item.put("revenue", round(agg.revenue, 2));
            item.put("grossMarginRate", round(safeDivide(agg.marginWeighted, agg.revenue), 4));
            item.put("refundRate", round(safeDivide(agg.refundWeighted, BigDecimal.valueOf(Math.max(1L, agg.salesQty))), 4));
            result.add(item);
        }
        return result;
    }

    @Override
    public Map<String, Object> getCampaignReview(User loginUser, Map<String, Object> requestBody) {
        LocalDate[] range = parseDateRange(requestBody);
        AccessScope scope = resolveScope(loginUser, requestBody);
        List<CampaignMetric> rows = listCampaignMetrics(scope.storeIds, range[0], range[1]);

        BigDecimal totalSpend = BigDecimal.ZERO;
        long totalExposure = 0L;
        long totalClick = 0L;
        long totalOrder = 0L;
        BigDecimal weightedRoi = BigDecimal.ZERO;
        for (CampaignMetric row : rows) {
            BigDecimal spend = nullSafeBigDecimal(row.getSpendAmount());
            totalSpend = totalSpend.add(spend);
            totalExposure += nullSafeLong(row.getExposureCount());
            totalClick += nullSafeLong(row.getClickCount());
            totalOrder += nullSafeLong(row.getOrderCount());
            weightedRoi = weightedRoi.add(nullSafeBigDecimal(row.getRoi()).multiply(spend));
        }
        BigDecimal roi = totalSpend.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : weightedRoi.divide(totalSpend, 4, RoundingMode.HALF_UP);
        BigDecimal ctr = totalExposure == 0 ? BigDecimal.ZERO
                : new BigDecimal(totalClick).divide(new BigDecimal(totalExposure), 4, RoundingMode.HALF_UP);
        BigDecimal cvr = totalClick == 0 ? BigDecimal.ZERO
                : new BigDecimal(totalOrder).divide(new BigDecimal(totalClick), 4, RoundingMode.HALF_UP);

        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("totalSpend", round(totalSpend, 2));
        summary.put("totalExposure", totalExposure);
        summary.put("totalClick", totalClick);
        summary.put("totalOrder", totalOrder);
        summary.put("roi", round(roi, 4));
        summary.put("ctr", round(ctr, 4));
        summary.put("cvr", round(cvr, 4));

        Map<String, List<CampaignMetric>> byChannel = rows.stream()
                .collect(Collectors.groupingBy(CampaignMetric::getChannelName));
        List<Map<String, Object>> channelStats = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, List<CampaignMetric>> entry : byChannel.entrySet()) {
            BigDecimal channelSpend = BigDecimal.ZERO;
            BigDecimal channelWeightedRoi = BigDecimal.ZERO;
            long channelOrder = 0L;
            for (CampaignMetric item : entry.getValue()) {
                BigDecimal spend = nullSafeBigDecimal(item.getSpendAmount());
                channelSpend = channelSpend.add(spend);
                channelWeightedRoi = channelWeightedRoi.add(nullSafeBigDecimal(item.getRoi()).multiply(spend));
                channelOrder += nullSafeLong(item.getOrderCount());
            }
            BigDecimal channelRoi = channelSpend.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : channelWeightedRoi.divide(channelSpend, 4, RoundingMode.HALF_UP);
            Map<String, Object> stat = new LinkedHashMap<String, Object>();
            stat.put("channel", entry.getKey());
            stat.put("spend", round(channelSpend, 2));
            stat.put("roi", round(channelRoi, 4));
            stat.put("orders", channelOrder);
            channelStats.add(stat);
        }
        channelStats.sort(new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return nullSafeBigDecimal((BigDecimal) b.get("roi")).compareTo(nullSafeBigDecimal((BigDecimal) a.get("roi")));
            }
        });

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("period", range[0] + " ~ " + range[1]);
        data.put("summary", summary);
        data.put("channelStats", channelStats);
        data.put("dailyTrend", buildCampaignTrend(rows));
        return data;
    }

    @Override
    public Map<String, Object> listAlertRules() {
        QueryWrapper<AlertRule> qw = new QueryWrapper<AlertRule>();
        qw.eq("isDelete", 0);
        qw.orderByDesc("enabled");
        qw.orderByAsc("severity");
        List<AlertRule> rules = alertRuleMapper.selectList(qw);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("rules", rules);
        return data;
    }

    @Override
    public Map<String, Object> listAlertEvents(User loginUser, Map<String, Object> requestBody) {
        LocalDate[] range = parseDateRange(requestBody);
        AccessScope scope = resolveScope(loginUser, requestBody);
        List<AlertEvent> events = listAlertEventsByScope(scope, range[0], range[1]);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("period", range[0] + " ~ " + range[1]);
        data.put("events", toAlertList(events));
        return data;
    }

    @Override
    public Map<String, Object> buildCostLossMock(User loginUser, Map<String, Object> requestBody) {
        LocalDate[] range = parseDateRange(requestBody);
        AccessScope scope = resolveScope(loginUser, requestBody);
        List<StoreDailyMetric> storeRows = listStoreDailyMetrics(scope.storeIds, range[0], range[1]);
        List<StoreCostDailyMetric> costRows = listCostMetrics(scope.storeIds, range[0], range[1]);
        BigDecimal totalRevenue = sum(storeRows, "revenue");
        BigDecimal ingredientCost = BigDecimal.ZERO;
        BigDecimal wasteAmount = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        for (StoreCostDailyMetric row : costRows) {
            ingredientCost = ingredientCost.add(nullSafeBigDecimal(row.getIngredientCost()));
            wasteAmount = wasteAmount.add(nullSafeBigDecimal(row.getWasteAmount()));
            commission = commission.add(nullSafeBigDecimal(row.getPlatformCommission()));
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("beta", false);
        data.put("module", "cost-loss");
        data.put("tips", costRows.isEmpty() ? "当前日期范围暂无成本数据" : "基于真实成本导入数据计算");
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(mockRow("商品进货成本率", formatPercent(safeDivide(ingredientCost, totalRevenue)),
                "阈值 <= 35%", safeDivide(ingredientCost, totalRevenue).compareTo(new BigDecimal("0.35")) > 0 ? "P1" : "P2"));
        rows.add(mockRow("报损率", formatPercent(safeDivide(wasteAmount, totalRevenue)),
                "阈值 <= 3.5%", safeDivide(wasteAmount, totalRevenue).compareTo(new BigDecimal("0.035")) > 0 ? "P1" : "P2"));
        rows.add(mockRow("平台佣金占比", formatPercent(safeDivide(commission, totalRevenue)),
                "阈值 <= 14%", safeDivide(commission, totalRevenue).compareTo(new BigDecimal("0.14")) > 0 ? "P2" : "P3"));
        data.put("items", rows);
        return data;
    }

    @Override
    public Map<String, Object> buildStaffEfficiencyMock(User loginUser, Map<String, Object> requestBody) {
        LocalDate[] range = parseDateRange(requestBody);
        AccessScope scope = resolveScope(loginUser, requestBody);
        List<StoreStaffDailyMetric> rows = listStaffMetrics(scope.storeIds, range[0], range[1]);
        BigDecimal lunchPlanned = BigDecimal.ZERO;
        BigDecimal lunchActual = BigDecimal.ZERO;
        BigDecimal dinnerPrepTotal = BigDecimal.ZERO;
        BigDecimal dinnerOrderTotal = BigDecimal.ZERO;
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalActualStaff = BigDecimal.ZERO;
        for (StoreStaffDailyMetric row : rows) {
            if ("lunch".equalsIgnoreCase(StringUtils.defaultString(row.getShiftType()))) {
                lunchPlanned = lunchPlanned.add(BigDecimal.valueOf(nullSafeLong(row.getPlannedStaff())));
                lunchActual = lunchActual.add(BigDecimal.valueOf(nullSafeLong(row.getActualStaff())));
            }
            if ("dinner".equalsIgnoreCase(StringUtils.defaultString(row.getShiftType()))) {
                BigDecimal order = BigDecimal.valueOf(Math.max(1L, nullSafeLong(row.getOrdersCount())));
                dinnerPrepTotal = dinnerPrepTotal.add(nullSafeBigDecimal(row.getAvgPrepTimeMin()).multiply(order));
                dinnerOrderTotal = dinnerOrderTotal.add(order);
            }
            totalRevenue = totalRevenue.add(nullSafeBigDecimal(row.getRevenue()));
            totalActualStaff = totalActualStaff.add(BigDecimal.valueOf(Math.max(1L, nullSafeLong(row.getActualStaff()))));
        }
        BigDecimal utilization = safeDivide(lunchActual, lunchPlanned);
        BigDecimal dinnerPrepAvg = safeDivide(dinnerPrepTotal, dinnerOrderTotal);
        BigDecimal perCapitaOutput = safeDivide(totalRevenue, totalActualStaff);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("beta", false);
        data.put("module", "staff-efficiency");
        data.put("tips", rows.isEmpty() ? "当前日期范围暂无排班数据" : "基于真实排班导入数据计算");
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        items.add(mockRow("午高峰人力利用率", formatPercent(utilization), "目标 >= 85%",
                utilization.compareTo(new BigDecimal("0.85")) < 0 ? "P2" : "P3"));
        items.add(mockRow("晚高峰拣货与收银响应", round(dinnerPrepAvg, 2) + " 分钟", "目标 <= 18 分钟",
                dinnerPrepAvg.compareTo(new BigDecimal("18")) > 0 ? "P1" : "P3"));
        items.add(mockRow("人均产出", round(perCapitaOutput, 2) + " 元/班次", "目标 >= 920 元/班次",
                perCapitaOutput.compareTo(new BigDecimal("920")) < 0 ? "P2" : "P3"));
        data.put("items", items);
        return data;
    }

    @Override
    public Map<String, Object> buildForecastMock(User loginUser, Map<String, Object> requestBody) {
        LocalDate[] range = parseDateRange(requestBody);
        AccessScope scope = resolveScope(loginUser, requestBody);
        List<StoreDailyMetric> rows = listStoreDailyMetrics(scope.storeIds, range[0], range[1]);
        Map<LocalDate, BigDecimal> revenueByDay = new LinkedHashMap<LocalDate, BigDecimal>();
        for (StoreDailyMetric row : rows) {
            LocalDate day = row.getStatDate();
            BigDecimal existing = revenueByDay.containsKey(day) ? revenueByDay.get(day) : BigDecimal.ZERO;
            revenueByDay.put(day, existing.add(nullSafeBigDecimal(row.getRevenue())));
        }
        List<LocalDate> keys = new ArrayList<LocalDate>(revenueByDay.keySet());
        Collections.sort(keys);
        List<BigDecimal> hist = keys.stream().map(revenueByDay::get).collect(Collectors.toList());
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("beta", false);
        data.put("module", "forecast");
        data.put("tips", hist.isEmpty() ? "当前日期范围暂无可预测数据" : "基于历史营收趋势的自动预测");
        List<Map<String, Object>> points = new ArrayList<Map<String, Object>>();
        int n = hist.size();
        BigDecimal base = BigDecimal.ZERO;
        if (n > 0) {
            int from = Math.max(0, n - 7);
            BigDecimal total = BigDecimal.ZERO;
            for (int i = from; i < n; i++) {
                total = total.add(hist.get(i));
            }
            base = total.divide(new BigDecimal(n - from), 2, RoundingMode.HALF_UP);
        }
        LocalDate startDay = keys.isEmpty() ? LocalDate.now() : keys.get(keys.size() - 1);
        for (int i = 1; i <= 7; i++) {
            LocalDate day = startDay.plusDays(i);
            BigDecimal trendFactor = BigDecimal.ONE.add(new BigDecimal(i).multiply(new BigDecimal("0.01")));
            BigDecimal baseline = round(base.multiply(trendFactor), 2);
            BigDecimal lower = round(baseline.multiply(new BigDecimal("0.94")), 2);
            BigDecimal upper = round(baseline.multiply(new BigDecimal("1.06")), 2);
            points.add(mockForecast(day.format(DATE_FORMATTER), baseline, lower, upper));
        }
        data.put("weeklyRevenueForecast", points);
        return data;
    }

    @Override
    public Map<String, Object> getAvailableDateRange(User loginUser, Map<String, Object> requestBody) {
        AccessScope scope = resolveScope(loginUser, requestBody);
        LocalDate minDate = null;
        LocalDate maxDate = null;

        LocalDate[] storeRange = queryStoreDateRange(scope.storeIds);
        LocalDate[] dishRange = queryDishDateRange(scope.storeIds);
        LocalDate[] campaignRange = queryCampaignDateRange(scope.storeIds);

        minDate = pickMin(minDate, storeRange[0]);
        minDate = pickMin(minDate, dishRange[0]);
        minDate = pickMin(minDate, campaignRange[0]);
        maxDate = pickMax(maxDate, storeRange[1]);
        maxDate = pickMax(maxDate, dishRange[1]);
        maxDate = pickMax(maxDate, campaignRange[1]);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("hasData", minDate != null && maxDate != null);
        data.put("minDate", minDate == null ? null : minDate.format(DATE_FORMATTER));
        data.put("maxDate", maxDate == null ? null : maxDate.format(DATE_FORMATTER));
        data.put("scope", buildScopeInfo(scope));
        return data;
    }

    @Override
    public Map<String, Object> getGeoScope(User loginUser, Map<String, Object> requestBody) {
        LocalDate[] range = parseDateRange(requestBody);
        AccessScope scope = resolveScope(loginUser, requestBody);
        List<Long> storeIds = scope.storeIds == null ? Collections.<Long>emptyList() : scope.storeIds;
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("period", range[0] + " ~ " + range[1]);
        data.put("scope", buildScopeInfo(scope));
        if (storeIds.isEmpty()) {
            data.put("regions", Collections.emptyList());
            data.put("provinceHeat", Collections.emptyList());
            return data;
        }

        Map<Long, Store> storeInfoMap = loadStoreInfoMap(storeIds);
        Map<Long, BigDecimal> revenueByStore = mergeStoreRevenueWithDishFallback(storeIds, range[0], range[1]);

        // region -> stats
        Map<String, RegionAgg> regionAgg = new LinkedHashMap<String, RegionAgg>();
        List<Map<String, Object>> stores = new ArrayList<Map<String, Object>>();
        for (Long storeId : storeIds) {
            Store store = storeInfoMap.get(storeId);
            String dashboardDistrict = StringUtils.defaultIfBlank(resolvePanzhihuaDashboardDistrict(store), "未分区");
            String city = store == null ? null : store.getCity();
            String storeName = store == null ? null : store.getStoreName();
            BigDecimal revenue = revenueByStore.containsKey(storeId) ? revenueByStore.get(storeId) : BigDecimal.ZERO;
            RegionAgg agg = regionAgg.containsKey(dashboardDistrict) ? regionAgg.get(dashboardDistrict) : new RegionAgg(dashboardDistrict);
            agg.storeCount += 1;
            agg.revenue = agg.revenue.add(revenue);
            if (StringUtils.isNotBlank(city)) {
                agg.cities.add(city);
                String province = inferProvinceByCity(city);
                if (StringUtils.isNotBlank(province)) {
                    agg.provinces.add(province);
                }
            }
            regionAgg.put(dashboardDistrict, agg);

            // 返回全量门店明细（含0销售门店），供前端地图点位渲染
            Map<String, Object> storeItem = new LinkedHashMap<String, Object>();
            storeItem.put("storeId", storeId);
            storeItem.put("storeName", storeName);
            storeItem.put("storeCode", store == null ? null : store.getStoreCode());
            storeItem.put("district", dashboardDistrict);
            storeItem.put("region", dashboardDistrict);
            storeItem.put("city", city);
            storeItem.put("revenue", round(revenue, 2));
            stores.add(storeItem);
        }

        List<Map<String, Object>> regions = new ArrayList<Map<String, Object>>();
        Map<String, BigDecimal> revenueByProvince = new LinkedHashMap<String, BigDecimal>();
        for (RegionAgg agg : regionAgg.values()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("region", agg.region);
            item.put("revenue", round(agg.revenue, 2));
            item.put("storeCount", agg.storeCount);
            item.put("cities", new ArrayList<String>(agg.cities));
            item.put("provinces", new ArrayList<String>(agg.provinces));
            regions.add(item);
            // 将区域营收映射到其覆盖省份（用于大屏省份着色）
            for (String p : agg.provinces) {
                BigDecimal current = revenueByProvince.containsKey(p) ? revenueByProvince.get(p) : BigDecimal.ZERO;
                revenueByProvince.put(p, current.add(agg.revenue));
            }
        }
        regions.sort((a, b) -> nullSafeBigDecimal((BigDecimal) b.get("revenue")).compareTo(nullSafeBigDecimal((BigDecimal) a.get("revenue"))));

        List<Map<String, Object>> provinceHeat = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, BigDecimal> entry : revenueByProvince.entrySet()) {
            Map<String, Object> p = new LinkedHashMap<String, Object>();
            p.put("name", entry.getKey());
            p.put("value", round(entry.getValue(), 2));
            provinceHeat.add(p);
        }
        provinceHeat.sort((a, b) -> nullSafeBigDecimal((BigDecimal) b.get("value")).compareTo(nullSafeBigDecimal((BigDecimal) a.get("value"))));

        data.put("regions", regions);
        data.put("provinceHeat", provinceHeat);
        data.put("stores", stores);
        return data;
    }

    private String inferProvinceByCity(String city) {
        String c = StringUtils.defaultString(city).trim();
        if (c.isEmpty()) return null;
        // 直辖市
        if (c.contains("北京")) return "北京";
        if (c.contains("上海")) return "上海";
        if (c.contains("天津")) return "天津";
        if (c.contains("重庆")) return "重庆";
        if (c.contains("广州") || c.contains("深圳") || c.contains("佛山") || c.contains("东莞")) return "广东";
        if (c.contains("成都")) return "四川";
        if (c.contains("杭州") || c.contains("宁波")) return "浙江";
        if (c.contains("南京") || c.contains("苏州")) return "江苏";
        if (c.contains("武汉")) return "湖北";
        if (c.contains("长沙")) return "湖南";
        if (c.contains("西安")) return "陕西";
        // 若传入就是省份名（如“广东省/四川省”）
        if (c.endsWith("省")) return c.substring(0, c.length() - 1);
        if (c.endsWith("市")) return c.substring(0, c.length() - 1);
        return null;
    }

    private static class RegionAgg {
        private final String region;
        private BigDecimal revenue = BigDecimal.ZERO;
        private int storeCount = 0;
        private final Set<String> cities = new HashSet<String>();
        private final Set<String> provinces = new HashSet<String>();
        private RegionAgg(String region) {
            this.region = region;
        }
    }

    @Override
    public Map<String, Object> importFromChartData(User loginUser, Long chartId, String chartData) {
        if (StringUtils.isBlank(chartData) && chartId != null && chartId > 0) {
            Chart chart = chartService.getById(chartId);
            if (chart != null) {
                chartData = chart.getChartData();
            }
        }
        CsvTableUtils.Table table = CsvTableUtils.parse(chartData);
        List<List<String>> rows = table.getRows();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (table.getHeaders().isEmpty() || rows.isEmpty()) {
            result.put("chartId", chartId);
            result.put("storeDailyUpsert", 0);
            result.put("dishDailyUpsert", 0);
            result.put("campaignUpsert", 0);
            result.put("message", "no_data");
            return result;
        }

        int dateIdx = table.indexOfHeaderLike("date", "日期", "statDate", "day");
        int storeIdIdx = table.indexOfHeaderLike("storeId", "门店id", "门店ID");
        int storeCodeIdx = table.indexOfHeaderLike("storeCode", "门店编码");
        int storeNameIdx = table.indexOfHeaderLike("storeName", "门店名称", "门店");
        int regionIdx = table.indexOfHeaderLike("region", "区域");
        int cityIdx = table.indexOfHeaderLike("city", "城市");

        int revenueIdx = table.indexOfHeaderLike("revenue", "营收", "营业额", "sales", "gmv", "成交额");
        int marginIdx = table.indexOfHeaderLike("grossMarginRate", "毛利率", "毛利");
        int avgTicketIdx = table.indexOfHeaderLike("avgTicket", "客单价");
        int turnoverIdx = table.indexOfHeaderLike("tableTurnover", "翻台率", "翻台");
        int prepIdx = table.indexOfHeaderLike("prepTimeAvgMin", "出餐时长", "出餐");
        int cancelIdx = table.indexOfHeaderLike("cancelRate", "退单率", "取消率");

        int dishNameIdx = table.indexOfHeaderLike("dishName", "菜品", "菜品名称");
        int categoryIdx = table.indexOfHeaderLike("categoryName", "类目", "品类");
        int salesQtyIdx = table.indexOfHeaderLike("salesQty", "销量", "订单数");
        int dishRevenueIdx = table.indexOfHeaderLike("dishRevenue", "菜品销售额");
        int dishMarginIdx = table.indexOfHeaderLike("dishGrossMarginRate", "菜品毛利率");
        int refundIdx = table.indexOfHeaderLike("refundRate", "退款率", "退单率");

        int campaignNameIdx = table.indexOfHeaderLike("campaignName", "活动", "活动名称");
        int channelIdx = table.indexOfHeaderLike("channelName", "渠道");
        int spendIdx = table.indexOfHeaderLike("spendAmount", "花费", "投放消耗", "消耗");
        int exposureIdx = table.indexOfHeaderLike("exposureCount", "曝光");
        int clickIdx = table.indexOfHeaderLike("clickCount", "点击");
        int orderIdx = table.indexOfHeaderLike("orderCount", "下单");
        int cvrIdx = table.indexOfHeaderLike("conversionRate", "转化率");
        int roiIdx = table.indexOfHeaderLike("roi", "投产比", "roi值");

        int storeDailyUpsert = 0;
        int dishDailyUpsert = 0;
        int campaignUpsert = 0;
        int invalidStoreRows = 0;
        List<Map<String, Object>> invalidStoreDetails = new ArrayList<Map<String, Object>>();
        for (int idx = 0; idx < rows.size(); idx++) {
            List<String> row = rows.get(idx);
            LocalDate statDate = parseDateSafe(getCell(row, dateIdx), LocalDate.now());
            Long storeId = resolveOrUpsertStore(row, storeIdIdx, storeCodeIdx, storeNameIdx, regionIdx, cityIdx);
            if (storeId == null) {
                invalidStoreRows++;
                if (invalidStoreDetails.size() < 5) {
                    invalidStoreDetails.add(buildInvalidRow(idx + 2, "缺少门店标识（storeId/storeCode/storeName）"));
                }
                continue;
            }

            if (hasAnyValue(row, revenueIdx, marginIdx, avgTicketIdx, turnoverIdx, prepIdx, cancelIdx)) {
                upsertStoreDaily(statDate, storeId,
                        parseDecimal(getCell(row, revenueIdx), BigDecimal.ZERO),
                        parseDecimal(getCell(row, marginIdx), BigDecimal.ZERO),
                        parseDecimal(getCell(row, avgTicketIdx), BigDecimal.ZERO),
                        parseDecimal(getCell(row, turnoverIdx), BigDecimal.ZERO),
                        parseDecimal(getCell(row, prepIdx), BigDecimal.ZERO),
                        parseDecimal(getCell(row, cancelIdx), BigDecimal.ZERO));
                storeDailyUpsert++;
            }

            if (StringUtils.isNotBlank(getCell(row, dishNameIdx))) {
                upsertDishDaily(statDate, storeId,
                        trimCell(getCell(row, dishNameIdx)),
                        trimCell(getCell(row, categoryIdx)),
                        parseLong(getCell(row, salesQtyIdx), 0L),
                        parseDecimal(getCell(row, dishRevenueIdx), parseDecimal(getCell(row, revenueIdx), BigDecimal.ZERO)),
                        parseDecimal(getCell(row, dishMarginIdx), parseDecimal(getCell(row, marginIdx), BigDecimal.ZERO)),
                        parseDecimal(getCell(row, refundIdx), BigDecimal.ZERO));
                dishDailyUpsert++;
            }

            if (StringUtils.isNotBlank(getCell(row, campaignNameIdx))) {
                upsertCampaign(statDate, storeId,
                        trimCell(getCell(row, campaignNameIdx)),
                        trimCell(getCell(row, channelIdx)),
                        parseDecimal(getCell(row, spendIdx), BigDecimal.ZERO),
                        parseLong(getCell(row, exposureIdx), 0L),
                        parseLong(getCell(row, clickIdx), 0L),
                        parseLong(getCell(row, orderIdx), 0L),
                        parseDecimal(getCell(row, cvrIdx), BigDecimal.ZERO),
                        parseDecimal(getCell(row, roiIdx), BigDecimal.ZERO));
                campaignUpsert++;
            }
        }

        result.put("chartId", chartId);
        result.put("storeDailyUpsert", storeDailyUpsert);
        result.put("dishDailyUpsert", dishDailyUpsert);
        result.put("campaignUpsert", campaignUpsert);
        result.put("invalidStoreRows", invalidStoreRows);
        result.put("invalidStoreDetails", invalidStoreDetails);
        result.put("message", "ok");
        return result;
    }

    @Override
    public Map<String, Object> importCostData(User loginUser, String csvData, String importMode) {
        CsvTableUtils.Table table = CsvTableUtils.parse(csvData);
        List<List<String>> rows = table.getRows();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (table.getHeaders().isEmpty() || rows.isEmpty()) {
            result.put("upsert", 0);
            result.put("message", "no_data");
            return result;
        }
        int dateIdx = table.indexOfHeaderLike("date", "日期", "statDate", "day");
        int storeIdIdx = table.indexOfHeaderLike("storeId", "门店id", "门店ID");
        int storeCodeIdx = table.indexOfHeaderLike("storeCode", "门店编码");
        int storeNameIdx = table.indexOfHeaderLike("storeName", "门店名称", "门店");
        int regionIdx = table.indexOfHeaderLike("region", "区域");
        int cityIdx = table.indexOfHeaderLike("city", "城市");
        int ingredientIdx = table.indexOfHeaderLike("ingredientCost", "食材成本");
        int packagingIdx = table.indexOfHeaderLike("packagingCost", "包装成本");
        int energyIdx = table.indexOfHeaderLike("energyCost", "能耗成本", "水电成本");
        int laborIdx = table.indexOfHeaderLike("laborCost", "人工成本");
        int wasteAmountIdx = table.indexOfHeaderLike("wasteAmount", "报损金额");
        int wasteRateIdx = table.indexOfHeaderLike("wasteRate", "报损率");
        int inventoryLossRateIdx = table.indexOfHeaderLike("inventoryLossRate", "库存损耗率");
        int commissionIdx = table.indexOfHeaderLike("platformCommission", "平台佣金");

        Set<Long> importedStoreIds = new HashSet<Long>();
        LocalDate minDate = null;
        LocalDate maxDate = null;
        for (List<String> row : rows) {
            LocalDate statDate = parseDateSafe(getCell(row, dateIdx), LocalDate.now());
            Long storeId = resolveOrUpsertStore(row, storeIdIdx, storeCodeIdx, storeNameIdx, regionIdx, cityIdx);
            if (storeId == null) {
                continue;
            }
            importedStoreIds.add(storeId);
            minDate = pickMin(minDate, statDate);
            maxDate = pickMax(maxDate, statDate);
        }
        if ("replace_range".equalsIgnoreCase(importMode)) {
            clearCostByRange(importedStoreIds, minDate, maxDate);
        }
        int upsert = 0;
        int invalidStoreRows = 0;
        List<Map<String, Object>> invalidStoreDetails = new ArrayList<Map<String, Object>>();
        for (int idx = 0; idx < rows.size(); idx++) {
            List<String> row = rows.get(idx);
            LocalDate statDate = parseDateSafe(getCell(row, dateIdx), LocalDate.now());
            Long storeId = resolveOrUpsertStore(row, storeIdIdx, storeCodeIdx, storeNameIdx, regionIdx, cityIdx);
            if (storeId == null) {
                invalidStoreRows++;
                if (invalidStoreDetails.size() < 5) {
                    invalidStoreDetails.add(buildInvalidRow(idx + 2, "缺少门店标识（storeId/storeCode/storeName）"));
                }
                continue;
            }
            importedStoreIds.add(storeId);
            minDate = pickMin(minDate, statDate);
            maxDate = pickMax(maxDate, statDate);
            if (!hasAnyValue(row, ingredientIdx, packagingIdx, energyIdx, laborIdx, wasteAmountIdx, wasteRateIdx,
                    inventoryLossRateIdx, commissionIdx)) {
                continue;
            }
            upsertCostDaily(statDate, storeId,
                    parseDecimal(getCell(row, ingredientIdx), BigDecimal.ZERO),
                    parseDecimal(getCell(row, packagingIdx), BigDecimal.ZERO),
                    parseDecimal(getCell(row, energyIdx), BigDecimal.ZERO),
                    parseDecimal(getCell(row, laborIdx), BigDecimal.ZERO),
                    parseDecimal(getCell(row, wasteAmountIdx), BigDecimal.ZERO),
                    parseDecimal(getCell(row, wasteRateIdx), BigDecimal.ZERO),
                    parseDecimal(getCell(row, inventoryLossRateIdx), BigDecimal.ZERO),
                    parseDecimal(getCell(row, commissionIdx), BigDecimal.ZERO));
            upsert++;
        }
        result.put("upsert", upsert);
        result.put("invalidStoreRows", invalidStoreRows);
        result.put("invalidStoreDetails", invalidStoreDetails);
        result.put("message", "ok");
        String storeMeta = "stores=" + importedStoreIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        recordImportLog(loginUser, "cost", importMode, rows.size(), upsert, invalidStoreRows, minDate, maxDate, "ok", storeMeta);
        return result;
    }

    @Override
    public Map<String, Object> importStaffData(User loginUser, String csvData, String importMode) {
        CsvTableUtils.Table table = CsvTableUtils.parse(csvData);
        List<List<String>> rows = table.getRows();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (table.getHeaders().isEmpty() || rows.isEmpty()) {
            result.put("upsert", 0);
            result.put("message", "no_data");
            return result;
        }
        int dateIdx = table.indexOfHeaderLike("date", "日期", "statDate", "day");
        int storeIdIdx = table.indexOfHeaderLike("storeId", "门店id", "门店ID");
        int storeCodeIdx = table.indexOfHeaderLike("storeCode", "门店编码");
        int storeNameIdx = table.indexOfHeaderLike("storeName", "门店名称", "门店");
        int regionIdx = table.indexOfHeaderLike("region", "区域");
        int cityIdx = table.indexOfHeaderLike("city", "城市");
        int shiftTypeIdx = table.indexOfHeaderLike("shiftType", "班次");
        int plannedIdx = table.indexOfHeaderLike("plannedStaff", "计划人数");
        int actualIdx = table.indexOfHeaderLike("actualStaff", "实到人数");
        int workHoursIdx = table.indexOfHeaderLike("workHours", "工时");
        int overtimeIdx = table.indexOfHeaderLike("overtimeHours", "加班工时");
        int absentIdx = table.indexOfHeaderLike("absentCount", "缺勤人数");
        int ordersIdx = table.indexOfHeaderLike("ordersCount", "订单数");
        int revenueIdx = table.indexOfHeaderLike("revenue", "营收", "营业额");
        int prepIdx = table.indexOfHeaderLike("avgPrepTimeMin", "平均出餐时长", "出餐时长");

        Set<Long> importedStoreIds = new HashSet<Long>();
        LocalDate minDate = null;
        LocalDate maxDate = null;
        for (List<String> row : rows) {
            LocalDate statDate = parseDateSafe(getCell(row, dateIdx), LocalDate.now());
            Long storeId = resolveOrUpsertStore(row, storeIdIdx, storeCodeIdx, storeNameIdx, regionIdx, cityIdx);
            if (storeId == null) {
                continue;
            }
            importedStoreIds.add(storeId);
            minDate = pickMin(minDate, statDate);
            maxDate = pickMax(maxDate, statDate);
        }
        if ("replace_range".equalsIgnoreCase(importMode)) {
            clearStaffByRange(importedStoreIds, minDate, maxDate);
        }
        int upsert = 0;
        int invalidStoreRows = 0;
        List<Map<String, Object>> invalidStoreDetails = new ArrayList<Map<String, Object>>();
        for (int idx = 0; idx < rows.size(); idx++) {
            List<String> row = rows.get(idx);
            LocalDate statDate = parseDateSafe(getCell(row, dateIdx), LocalDate.now());
            Long storeId = resolveOrUpsertStore(row, storeIdIdx, storeCodeIdx, storeNameIdx, regionIdx, cityIdx);
            if (storeId == null) {
                invalidStoreRows++;
                if (invalidStoreDetails.size() < 5) {
                    invalidStoreDetails.add(buildInvalidRow(idx + 2, "缺少门店标识（storeId/storeCode/storeName）"));
                }
                continue;
            }
            importedStoreIds.add(storeId);
            minDate = pickMin(minDate, statDate);
            maxDate = pickMax(maxDate, statDate);
            String shiftType = StringUtils.defaultIfBlank(trimCell(getCell(row, shiftTypeIdx)), "all_day");
            if (!hasAnyValue(row, plannedIdx, actualIdx, workHoursIdx, overtimeIdx, absentIdx, ordersIdx, revenueIdx, prepIdx)) {
                continue;
            }
            upsertStaffDaily(statDate, storeId, shiftType,
                    parseLong(getCell(row, plannedIdx), 0L),
                    parseLong(getCell(row, actualIdx), 0L),
                    parseDecimal(getCell(row, workHoursIdx), BigDecimal.ZERO),
                    parseDecimal(getCell(row, overtimeIdx), BigDecimal.ZERO),
                    parseLong(getCell(row, absentIdx), 0L),
                    parseLong(getCell(row, ordersIdx), 0L),
                    parseDecimal(getCell(row, revenueIdx), BigDecimal.ZERO),
                    parseDecimal(getCell(row, prepIdx), BigDecimal.ZERO));
            upsert++;
        }
        result.put("upsert", upsert);
        result.put("invalidStoreRows", invalidStoreRows);
        result.put("invalidStoreDetails", invalidStoreDetails);
        result.put("message", "ok");
        String storeMeta = "stores=" + importedStoreIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        recordImportLog(loginUser, "staff", importMode, rows.size(), upsert, invalidStoreRows, minDate, maxDate, "ok", storeMeta);
        return result;
    }

    @Override
    public Map<String, Object> listImportLogs(User loginUser, Map<String, Object> requestBody) {
        QueryWrapper<RestaurantImportLog> qw = new QueryWrapper<RestaurantImportLog>();
        qw.eq("isDelete", 0);
        if (!userService.isAdmin(loginUser)) {
            qw.eq("userId", loginUser.getId());
        }
        qw.orderByDesc("id");
        qw.last("limit 10");
        List<RestaurantImportLog> logs = restaurantImportLogMapper.selectList(qw);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("records", logs);
        return data;
    }

    @Override
    public Map<String, Object> rollbackLatestImport(User loginUser, String importType) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        if (!"cost".equals(importType) && !"staff".equals(importType)) {
            data.put("rolledBack", 0);
            data.put("message", "invalid_import_type");
            return data;
        }
        QueryWrapper<RestaurantImportLog> qw = new QueryWrapper<RestaurantImportLog>();
        qw.eq("isDelete", 0);
        qw.eq("importType", importType);
        qw.eq("status", "ok");
        if (!userService.isAdmin(loginUser)) {
            qw.eq("userId", loginUser.getId());
        }
        qw.orderByDesc("id");
        qw.last("limit 1");
        RestaurantImportLog latest = restaurantImportLogMapper.selectOne(qw);
        if (latest == null) {
            data.put("rolledBack", 0);
            data.put("message", "no_batch");
            return data;
        }
        if ("rolled_back".equalsIgnoreCase(latest.getStatus())) {
            data.put("rolledBack", 0);
            data.put("logId", latest.getId());
            data.put("message", "already_rolled_back");
            return data;
        }
        return rollbackByLog(loginUser, latest, data);
    }

    @Override
    public Map<String, Object> rollbackImportByLogId(User loginUser, Long logId) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        if (logId == null || logId <= 0) {
            data.put("rolledBack", 0);
            data.put("message", "invalid_log_id");
            return data;
        }
        RestaurantImportLog log = restaurantImportLogMapper.selectById(logId);
        if (log == null || log.getIsDelete() != null && log.getIsDelete() == 1) {
            data.put("rolledBack", 0);
            data.put("message", "log_not_found");
            return data;
        }
        if (!userService.isAdmin(loginUser) && (log.getUserId() == null || !log.getUserId().equals(loginUser.getId()))) {
            data.put("rolledBack", 0);
            data.put("message", "no_permission");
            return data;
        }
        if ("rolled_back".equalsIgnoreCase(log.getStatus())) {
            data.put("rolledBack", 0);
            data.put("logId", log.getId());
            data.put("message", "already_rolled_back");
            return data;
        }
        return rollbackByLog(loginUser, log, data);
    }

    private Map<String, Object> rollbackByLog(User loginUser, RestaurantImportLog latest, Map<String, Object> data) {
        Set<Long> storeIds = parseStoreIdsFromMessage(latest.getMessage());
        int affected = 0;
        if ("cost".equals(latest.getImportType())) {
            QueryWrapper<StoreCostDailyMetric> delQw = new QueryWrapper<StoreCostDailyMetric>();
            delQw.eq("isDelete", 0);
            if (!storeIds.isEmpty()) {
                delQw.in("storeId", storeIds);
            }
            if (latest.getStartDate() != null) {
                delQw.ge("statDate", latest.getStartDate());
            }
            if (latest.getEndDate() != null) {
                delQw.le("statDate", latest.getEndDate());
            }
            affected = storeCostDailyMetricMapper.delete(delQw);
        } else if ("staff".equals(latest.getImportType())) {
            QueryWrapper<StoreStaffDailyMetric> delQw = new QueryWrapper<StoreStaffDailyMetric>();
            delQw.eq("isDelete", 0);
            if (!storeIds.isEmpty()) {
                delQw.in("storeId", storeIds);
            }
            if (latest.getStartDate() != null) {
                delQw.ge("statDate", latest.getStartDate());
            }
            if (latest.getEndDate() != null) {
                delQw.le("statDate", latest.getEndDate());
            }
            affected = storeStaffDailyMetricMapper.delete(delQw);
        } else {
            data.put("rolledBack", 0);
            data.put("message", "invalid_import_type");
            return data;
        }
        latest.setStatus("rolled_back");
        latest.setMessage(StringUtils.defaultIfBlank(latest.getMessage(), "") + ";rollbackRows=" + affected);
        restaurantImportLogMapper.updateById(latest);
        data.put("rolledBack", affected);
        data.put("logId", latest.getId());
        data.put("message", "ok");
        return data;
    }

    private Map<String, Object> emptyStoreOverview(LocalDate[] range) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("storeId", null);
        data.put("period", range[0] + " ~ " + range[1]);
        data.put("kpi", new LinkedHashMap<String, Object>());
        data.put("trend", new ArrayList<Map<String, Object>>());
        data.put("dishTop", new ArrayList<Map<String, Object>>());
        data.put("alerts", new ArrayList<Map<String, Object>>());
        return data;
    }

    private AccessScope resolveScope(User loginUser, Map<String, Object> requestBody) {
        String requestedRegion = trimToNull(requestBody.get("region"));
        Long requestedStoreId = toLong(requestBody.get("storeId"));
        QueryWrapper<StoreUserRole> qw = new QueryWrapper<StoreUserRole>();
        qw.eq("userId", loginUser.getId());
        qw.eq("isDelete", 0);
        List<StoreUserRole> roles = storeUserRoleMapper.selectList(qw);
        if ((roles == null || roles.isEmpty()) && userService.isAdmin(loginUser)) {
            return allScope(requestedRegion, requestedStoreId);
        }
        if (roles == null || roles.isEmpty()) {
            return new AccessScope("store_manager", Collections.<Long>emptyList(), null);
        }

        boolean hasHq = false;
        Set<String> regions = new HashSet<String>();
        Set<Long> storeIds = new HashSet<Long>();
        for (StoreUserRole role : roles) {
            if ("hq_manager".equals(role.getRoleCode())) {
                hasHq = true;
            }
            if ("region_manager".equals(role.getRoleCode()) && StringUtils.isNotBlank(role.getRegion())) {
                regions.add(role.getRegion());
            }
            if (role.getStoreId() != null && role.getStoreId() > 0) {
                storeIds.add(role.getStoreId());
            }
        }
        if (hasHq) {
            return allScope(requestedRegion, requestedStoreId);
        }
        if (!regions.isEmpty()) {
            if (StringUtils.isNotBlank(requestedRegion) && !regions.contains(requestedRegion)) {
                return new AccessScope("region_manager", Collections.<Long>emptyList(), requestedRegion);
            }
            QueryWrapper<Store> sw = new QueryWrapper<Store>();
            sw.eq("isDelete", 0);
            sw.in("region", regions);
            // 对区域经理：如果前端明确指定了区域，必须在其可管理区域内进一步收敛
            if (StringUtils.isNotBlank(requestedRegion) && regions.contains(requestedRegion)) {
                sw.eq("region", requestedRegion);
            }
            List<Store> stores = storeMapper.selectList(sw);
            List<Long> ids = stores.stream().map(Store::getId).collect(Collectors.toList());
            if (requestedStoreId != null) {
                ids = ids.stream().filter(id -> id.equals(requestedStoreId)).collect(Collectors.toList());
            }
            return new AccessScope("region_manager", ids, requestedRegion);
        }
        List<Long> ids = new ArrayList<Long>(storeIds);
        if (requestedStoreId != null) {
            ids = ids.stream().filter(id -> id.equals(requestedStoreId)).collect(Collectors.toList());
        }
        return new AccessScope("store_manager", ids, requestedRegion);
    }

    private AccessScope allScope(String requestedRegion, Long requestedStoreId) {
        QueryWrapper<Store> sw = new QueryWrapper<Store>();
        sw.eq("isDelete", 0);
        if (StringUtils.isNotBlank(requestedRegion)) {
            sw.eq("region", requestedRegion);
        }
        if (requestedStoreId != null && requestedStoreId > 0) {
            sw.eq("id", requestedStoreId);
        }
        List<Long> ids = storeMapper.selectList(sw).stream().map(Store::getId).collect(Collectors.toList());
        return new AccessScope("hq_manager", ids, requestedRegion);
    }

    private List<StoreDailyMetric> listStoreDailyMetrics(List<Long> storeIds, LocalDate start, LocalDate end) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Collections.emptyList();
        }
        QueryWrapper<StoreDailyMetric> qw = new QueryWrapper<StoreDailyMetric>();
        qw.eq("isDelete", 0);
        qw.in("storeId", storeIds);
        qw.ge("statDate", start);
        qw.le("statDate", end);
        return storeDailyMetricMapper.selectList(qw);
    }

    private List<DishDailyMetric> listDishMetrics(List<Long> storeIds, LocalDate start, LocalDate end) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Collections.emptyList();
        }
        QueryWrapper<DishDailyMetric> qw = new QueryWrapper<DishDailyMetric>();
        qw.eq("isDelete", 0);
        qw.in("storeId", storeIds);
        qw.ge("statDate", start);
        qw.le("statDate", end);
        return dishDailyMetricMapper.selectList(qw);
    }

    /** 按门店汇总菜品表营收（同一 CSV 常有多行 SKU，门店日表可能未写入或仅部分店有行） */
    private Map<Long, BigDecimal> aggregateDishRevenueByStore(List<Long> storeIds, LocalDate start, LocalDate end) {
        List<DishDailyMetric> dishRows = listDishMetrics(storeIds, start, end);
        Map<Long, BigDecimal> map = new HashMap<Long, BigDecimal>();
        for (DishDailyMetric row : dishRows) {
            if (row == null || row.getStoreId() == null) {
                continue;
            }
            BigDecimal cur = map.containsKey(row.getStoreId()) ? map.get(row.getStoreId()) : BigDecimal.ZERO;
            map.put(row.getStoreId(), cur.add(nullSafeBigDecimal(row.getRevenue())));
        }
        return map;
    }

    /**
     * 门店日指标为主；若某店在日表无营收（或为 0）则用菜品汇总补齐，避免大屏「多数区县销售额为 0」。
     * 两者皆有则取较大值，缓解日表仅写入末行导致的低估。
     */
    private Map<Long, BigDecimal> mergeStoreRevenueWithDishFallback(List<Long> storeIds, LocalDate start, LocalDate end) {
        Map<Long, BigDecimal> out = new LinkedHashMap<Long, BigDecimal>();
        if (storeIds == null || storeIds.isEmpty()) {
            return out;
        }
        List<StoreDailyMetric> sdRows = listStoreDailyMetrics(storeIds, start, end);
        Map<Long, BigDecimal> sd = new HashMap<Long, BigDecimal>();
        for (StoreDailyMetric row : sdRows) {
            if (row == null || row.getStoreId() == null) {
                continue;
            }
            BigDecimal cur = sd.containsKey(row.getStoreId()) ? sd.get(row.getStoreId()) : BigDecimal.ZERO;
            sd.put(row.getStoreId(), cur.add(nullSafeBigDecimal(row.getRevenue())));
        }
        Map<Long, BigDecimal> dish = aggregateDishRevenueByStore(storeIds, start, end);
        for (Long id : storeIds) {
            BigDecimal a = sd.containsKey(id) ? sd.get(id) : BigDecimal.ZERO;
            BigDecimal b = dish.containsKey(id) ? dish.get(id) : BigDecimal.ZERO;
            BigDecimal best;
            if (a.compareTo(BigDecimal.ZERO) <= 0) {
                best = b;
            } else if (b.compareTo(BigDecimal.ZERO) <= 0) {
                best = a;
            } else {
                best = a.compareTo(b) >= 0 ? a : b;
            }
            out.put(id, best);
        }
        return out;
    }

    private List<CampaignMetric> listCampaignMetrics(List<Long> storeIds, LocalDate start, LocalDate end) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Collections.emptyList();
        }
        QueryWrapper<CampaignMetric> qw = new QueryWrapper<CampaignMetric>();
        qw.eq("isDelete", 0);
        qw.in("storeId", storeIds);
        qw.ge("statDate", start);
        qw.le("statDate", end);
        return campaignMetricMapper.selectList(qw);
    }

    private List<StoreCostDailyMetric> listCostMetrics(List<Long> storeIds, LocalDate start, LocalDate end) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Collections.emptyList();
        }
        QueryWrapper<StoreCostDailyMetric> qw = new QueryWrapper<StoreCostDailyMetric>();
        qw.eq("isDelete", 0);
        qw.in("storeId", storeIds);
        qw.ge("statDate", start);
        qw.le("statDate", end);
        return storeCostDailyMetricMapper.selectList(qw);
    }

    private List<StoreStaffDailyMetric> listStaffMetrics(List<Long> storeIds, LocalDate start, LocalDate end) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Collections.emptyList();
        }
        QueryWrapper<StoreStaffDailyMetric> qw = new QueryWrapper<StoreStaffDailyMetric>();
        qw.eq("isDelete", 0);
        qw.in("storeId", storeIds);
        qw.ge("statDate", start);
        qw.le("statDate", end);
        return storeStaffDailyMetricMapper.selectList(qw);
    }

    private LocalDate[] queryStoreDateRange(List<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return new LocalDate[]{null, null};
        }
        QueryWrapper<StoreDailyMetric> qw = new QueryWrapper<StoreDailyMetric>();
        qw.select("MIN(statDate) AS minDate", "MAX(statDate) AS maxDate");
        qw.eq("isDelete", 0);
        qw.in("storeId", storeIds);
        qw.last("limit 1");
        List<Map<String, Object>> rows = storeDailyMetricMapper.selectMaps(qw);
        if (rows == null || rows.isEmpty()) {
            return new LocalDate[]{null, null};
        }
        Map<String, Object> row = rows.get(0);
        return new LocalDate[]{toLocalDate(row.get("minDate")), toLocalDate(row.get("maxDate"))};
    }

    private LocalDate[] queryDishDateRange(List<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return new LocalDate[]{null, null};
        }
        QueryWrapper<DishDailyMetric> qw = new QueryWrapper<DishDailyMetric>();
        qw.select("MIN(statDate) AS minDate", "MAX(statDate) AS maxDate");
        qw.eq("isDelete", 0);
        qw.in("storeId", storeIds);
        qw.last("limit 1");
        List<Map<String, Object>> rows = dishDailyMetricMapper.selectMaps(qw);
        if (rows == null || rows.isEmpty()) {
            return new LocalDate[]{null, null};
        }
        Map<String, Object> row = rows.get(0);
        return new LocalDate[]{toLocalDate(row.get("minDate")), toLocalDate(row.get("maxDate"))};
    }

    private LocalDate[] queryCampaignDateRange(List<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return new LocalDate[]{null, null};
        }
        QueryWrapper<CampaignMetric> qw = new QueryWrapper<CampaignMetric>();
        qw.select("MIN(statDate) AS minDate", "MAX(statDate) AS maxDate");
        qw.eq("isDelete", 0);
        qw.in("storeId", storeIds);
        qw.last("limit 1");
        List<Map<String, Object>> rows = campaignMetricMapper.selectMaps(qw);
        if (rows == null || rows.isEmpty()) {
            return new LocalDate[]{null, null};
        }
        Map<String, Object> row = rows.get(0);
        return new LocalDate[]{toLocalDate(row.get("minDate")), toLocalDate(row.get("maxDate"))};
    }

    private List<AlertEvent> listAlertEventsByScope(AccessScope scope, LocalDate start, LocalDate end) {
        if (scope.storeIds == null || scope.storeIds.isEmpty()) {
            return Collections.emptyList();
        }
        QueryWrapper<AlertEvent> qw = new QueryWrapper<AlertEvent>();
        qw.eq("isDelete", 0);
        qw.in("storeId", scope.storeIds);
        qw.ge("statDate", start);
        qw.le("statDate", end);
        qw.orderByDesc("statDate");
        qw.last("limit 50");
        return alertEventMapper.selectList(qw);
    }

    private List<AlertEvent> listAlertEventsByStore(Long storeId, LocalDate start, LocalDate end) {
        QueryWrapper<AlertEvent> qw = new QueryWrapper<AlertEvent>();
        qw.eq("isDelete", 0);
        qw.eq("storeId", storeId);
        qw.ge("statDate", start);
        qw.le("statDate", end);
        qw.orderByDesc("statDate");
        qw.last("limit 20");
        return alertEventMapper.selectList(qw);
    }

    private List<Map<String, Object>> buildDailyTrend(List<StoreDailyMetric> rows) {
        Map<LocalDate, List<StoreDailyMetric>> grouped = rows.stream()
                .collect(Collectors.groupingBy(StoreDailyMetric::getStatDate));
        List<LocalDate> dates = new ArrayList<LocalDate>(grouped.keySet());
        Collections.sort(dates);
        List<Map<String, Object>> trend = new ArrayList<Map<String, Object>>();
        for (LocalDate date : dates) {
            List<StoreDailyMetric> dayRows = grouped.get(date);
            BigDecimal revenue = sum(dayRows, "revenue");
            BigDecimal margin = avg(dayRows, "grossMarginRate");
            BigDecimal turnover = avg(dayRows, "tableTurnover");
            BigDecimal profit = revenue.multiply(margin);
            BigDecimal cashflow = profit.multiply(new BigDecimal("0.8"));
            Map<String, Object> point = new LinkedHashMap<String, Object>();
            point.put("date", date.format(DATE_FORMATTER));
            point.put("revenue", round(revenue, 2));
            point.put("profit", round(profit, 2));
            point.put("cashflow", round(cashflow, 2));
            point.put("tableTurnover", round(turnover, 2));
            trend.add(point);
        }
        return trend;
    }

    private List<Map<String, Object>> buildStoreRankFromMergedRevenue(Map<Long, BigDecimal> revenueByStore, List<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Store> storeInfoMap = loadStoreInfoMap(storeIds);
        List<Map<String, Object>> rank = new ArrayList<Map<String, Object>>();
        for (Long storeId : storeIds) {
            BigDecimal rev = revenueByStore.containsKey(storeId) ? revenueByStore.get(storeId) : BigDecimal.ZERO;
            Store store = storeInfoMap.get(storeId);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("storeId", storeId);
            item.put("storeName", store == null ? null : store.getStoreName());
            item.put("storeCode", store == null ? null : store.getStoreCode());
            item.put("district", resolvePanzhihuaDashboardDistrict(store));
            item.put("region", store == null ? null : store.getRegion());
            item.put("revenue", round(rev, 2));
            rank.add(item);
        }
        rank.sort(new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                BigDecimal va = (BigDecimal) a.get("revenue");
                BigDecimal vb = (BigDecimal) b.get("revenue");
                return vb.compareTo(va);
            }
        });
        return rank;
    }

    private Map<Long, Store> loadStoreInfoMap(List<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Collections.emptyMap();
        }
        QueryWrapper<Store> qw = new QueryWrapper<Store>();
        qw.eq("isDelete", 0);
        qw.in("id", storeIds);
        List<Store> stores = storeMapper.selectList(qw);
        Map<Long, Store> map = new HashMap<Long, Store>();
        for (Store store : stores) {
            map.put(store.getId(), store);
        }
        return map;
    }

    private List<Map<String, Object>> buildDishTop(List<DishDailyMetric> dishRows) {
        return dishRows.stream()
                .sorted(new Comparator<DishDailyMetric>() {
                    @Override
                    public int compare(DishDailyMetric a, DishDailyMetric b) {
                        return nullSafeBigDecimal(b.getRevenue()).compareTo(nullSafeBigDecimal(a.getRevenue()));
                    }
                })
                .limit(10)
                .map(this::toDishView)
                .collect(Collectors.toList());
    }

    private Map<String, Object> toDishView(DishDailyMetric item) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("dishName", item.getDishName());
        row.put("categoryName", item.getCategoryName());
        row.put("salesQty", nullSafeLong(item.getSalesQty()));
        row.put("revenue", round(nullSafeBigDecimal(item.getRevenue()), 2));
        row.put("grossMarginRate", round(nullSafeBigDecimal(item.getGrossMarginRate()), 4));
        row.put("refundRate", round(nullSafeBigDecimal(item.getRefundRate()), 4));
        row.put("storeId", item.getStoreId());
        return row;
    }

    private List<Map<String, Object>> buildCategoryMix(List<DishDailyMetric> rows) {
        Map<String, BigDecimal> byCategory = new HashMap<String, BigDecimal>();
        for (DishDailyMetric row : rows) {
            String category = StringUtils.defaultIfBlank(row.getCategoryName(), "未分类");
            BigDecimal total = byCategory.containsKey(category) ? byCategory.get(category) : BigDecimal.ZERO;
            byCategory.put(category, total.add(nullSafeBigDecimal(row.getRevenue())));
        }
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, BigDecimal> entry : byCategory.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("category", entry.getKey());
            item.put("revenue", round(entry.getValue(), 2));
            list.add(item);
        }
        list.sort(new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return ((BigDecimal) b.get("revenue")).compareTo((BigDecimal) a.get("revenue"));
            }
        });
        return list;
    }

    private List<Map<String, Object>> buildCampaignTrend(List<CampaignMetric> rows) {
        Map<LocalDate, List<CampaignMetric>> grouped = rows.stream()
                .collect(Collectors.groupingBy(CampaignMetric::getStatDate));
        List<LocalDate> dates = new ArrayList<LocalDate>(grouped.keySet());
        Collections.sort(dates);
        List<Map<String, Object>> trend = new ArrayList<Map<String, Object>>();
        for (LocalDate date : dates) {
            List<CampaignMetric> dayRows = grouped.get(date);
            BigDecimal spend = BigDecimal.ZERO;
            long order = 0L;
            BigDecimal weightedRoi = BigDecimal.ZERO;
            for (CampaignMetric row : dayRows) {
                BigDecimal daySpend = nullSafeBigDecimal(row.getSpendAmount());
                spend = spend.add(daySpend);
                order += nullSafeLong(row.getOrderCount());
                weightedRoi = weightedRoi.add(nullSafeBigDecimal(row.getRoi()).multiply(daySpend));
            }
            BigDecimal roi = spend.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : weightedRoi.divide(spend, 4, RoundingMode.HALF_UP);
            Map<String, Object> point = new LinkedHashMap<String, Object>();
            point.put("date", date.format(DATE_FORMATTER));
            point.put("spend", round(spend, 2));
            point.put("orders", order);
            point.put("roi", round(roi, 4));
            trend.add(point);
        }
        return trend;
    }

    private List<Map<String, Object>> toAlertList(List<AlertEvent> events) {
        if (events == null) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (AlertEvent event : events) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", event.getId());
            row.put("storeId", event.getStoreId());
            row.put("severity", event.getSeverity());
            row.put("metricCode", event.getMetricCode());
            row.put("metricValue", round(nullSafeBigDecimal(event.getMetricValue()), 4));
            row.put("thresholdValue", round(nullSafeBigDecimal(event.getThresholdValue()), 4));
            row.put("status", event.getStatus());
            row.put("message", event.getMessage());
            row.put("statDate", event.getStatDate() == null ? null : event.getStatDate().format(DATE_FORMATTER));
            list.add(row);
        }
        return list;
    }

    private Map<String, Object> buildScopeInfo(AccessScope scope) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("roleCode", scope.roleCode);
        data.put("region", scope.region);
        data.put("storeCount", scope.storeIds == null ? 0 : scope.storeIds.size());
        return data;
    }

    private Map<String, Object> buildSummary(BigDecimal revenue, BigDecimal margin, BigDecimal avgTicket,
            BigDecimal turnover, BigDecimal prepTime, BigDecimal cancelRate) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("revenue", round(revenue, 2));
        summary.put("grossMarginRate", round(margin, 4));
        summary.put("avgTicket", round(avgTicket, 2));
        summary.put("tableTurnover", round(turnover, 2));
        summary.put("prepTimeAvgMin", round(prepTime, 2));
        summary.put("cancelRate", round(cancelRate, 4));
        return summary;
    }

    /** 从菜品日表汇总动销件数、在售 SKU 数（去重品名），供大屏 KPI 与门店视角对齐 */
    private void fillDishVolumeKpis(Map<String, Object> summary, List<Long> storeIds, LocalDate[] range) {
        if (summary == null) {
            return;
        }
        if (storeIds == null || storeIds.isEmpty()) {
            summary.put("orderCount", 0L);
            summary.put("pileCount", 0L);
            return;
        }
        List<DishDailyMetric> dishRows = listDishMetrics(storeIds, range[0], range[1]);
        long orderVolume = dishRows.stream().mapToLong(r -> nullSafeLong(r.getSalesQty())).sum();
        long skuDistinct = dishRows.stream().map(DishDailyMetric::getDishName).filter(StringUtils::isNotBlank).distinct().count();
        summary.put("orderCount", orderVolume);
        summary.put("pileCount", skuDistinct);
    }

    private int calcHealthScore(BigDecimal margin, BigDecimal turnover, BigDecimal cancelRate, BigDecimal prepTime) {
        double marginScore = clamp(margin.doubleValue() / 0.70);
        double turnoverScore = clamp(turnover.doubleValue() / 5.00);
        double cancelScore = clamp((0.08 - cancelRate.doubleValue()) / 0.08);
        double prepScore = clamp((25.0 - prepTime.doubleValue()) / 25.0);
        return (int) Math.round((marginScore * 0.35 + turnoverScore * 0.30 + cancelScore * 0.20 + prepScore * 0.15) * 100);
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private BigDecimal sum(List<StoreDailyMetric> rows, String metric) {
        BigDecimal total = BigDecimal.ZERO;
        for (StoreDailyMetric row : rows) {
            if ("revenue".equals(metric)) {
                total = total.add(nullSafeBigDecimal(row.getRevenue()));
            }
        }
        return total;
    }

    private BigDecimal avg(List<StoreDailyMetric> rows, String metric) {
        if (rows == null || rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (StoreDailyMetric row : rows) {
            BigDecimal value = BigDecimal.ZERO;
            if ("grossMarginRate".equals(metric)) {
                value = nullSafeBigDecimal(row.getGrossMarginRate());
            } else if ("avgTicket".equals(metric)) {
                value = nullSafeBigDecimal(row.getAvgTicket());
            } else if ("tableTurnover".equals(metric)) {
                value = nullSafeBigDecimal(row.getTableTurnover());
            } else if ("prepTimeAvgMin".equals(metric)) {
                value = nullSafeBigDecimal(row.getPrepTimeAvgMin());
            } else if ("cancelRate".equals(metric)) {
                value = nullSafeBigDecimal(row.getCancelRate());
            }
            total = total.add(value);
            count++;
        }
        if (count == 0) {
            return BigDecimal.ZERO;
        }
        return total.divide(new BigDecimal(count), 6, RoundingMode.HALF_UP);
    }

    private LocalDate[] parseDateRange(Map<String, Object> requestBody) {
        String start = trimToNull(requestBody.get("startDate"));
        String end = trimToNull(requestBody.get("endDate"));
        LocalDate endDate = StringUtils.isBlank(end) ? LocalDate.now() : LocalDate.parse(end, DATE_FORMATTER);
        LocalDate startDate = StringUtils.isBlank(start) ? endDate.minusDays(6) : LocalDate.parse(start, DATE_FORMATTER);
        if (startDate.isAfter(endDate)) {
            LocalDate temp = startDate;
            startDate = endDate;
            endDate = temp;
        }
        return new LocalDate[]{startDate, endDate};
    }

    private String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal round(BigDecimal value, int scale) {
        return nullSafeBigDecimal(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal safeDivide(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return nullSafeBigDecimal(numerator).divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private String formatPercent(BigDecimal rate) {
        return round(nullSafeBigDecimal(rate).multiply(new BigDecimal("100")), 2) + "%";
    }

    private BigDecimal nullSafeBigDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private long nullSafeLong(Long value) {
        return value == null ? 0L : value;
    }

    private Map<String, Object> mockRow(String metric, String current, String threshold, String level) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("metric", metric);
        row.put("current", current);
        row.put("threshold", threshold);
        row.put("level", level);
        return row;
    }

    private Map<String, Object> mockForecast(String day, BigDecimal baseline, BigDecimal lower, BigDecimal upper) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("day", day);
        row.put("baseline", baseline);
        row.put("lower", lower);
        row.put("upper", upper);
        return row;
    }

    private boolean hasAnyValue(List<String> row, int... idxList) {
        if (row == null || idxList == null) {
            return false;
        }
        for (int idx : idxList) {
            if (idx >= 0 && idx < row.size() && StringUtils.isNotBlank(row.get(idx))) {
                return true;
            }
        }
        return false;
    }

    private String getCell(List<String> row, int idx) {
        if (row == null || idx < 0 || idx >= row.size()) {
            return "";
        }
        return StringUtils.defaultString(row.get(idx));
    }

    private String trimCell(String raw) {
        String value = StringUtils.defaultString(raw).trim();
        return value.isEmpty() ? null : value;
    }

    private BigDecimal parseDecimal(String raw, BigDecimal dft) {
        Double v = CsvTableUtils.tryParseNumber(raw);
        if (v == null) {
            return dft;
        }
        return BigDecimal.valueOf(v);
    }

    private long parseLong(String raw, long dft) {
        Double v = CsvTableUtils.tryParseNumber(raw);
        if (v == null) {
            return dft;
        }
        return Math.round(v);
    }

    private LocalDate parseDateSafe(String raw, LocalDate dft) {
        if (StringUtils.isBlank(raw)) {
            return dft;
        }
        String text = raw.trim();
        try {
            return LocalDate.parse(text, DATE_FORMATTER);
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(text);
        } catch (Exception ignored) {
        }
        if (text.contains("/")) {
            String normalized = text.replace('/', '-');
            try {
                return LocalDate.parse(normalized);
            } catch (Exception ignored) {
            }
        }
        return dft;
    }

    private LocalDate toLocalDate(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate) {
            return (LocalDate) raw;
        }
        try {
            return LocalDate.parse(String.valueOf(raw), DATE_FORMATTER);
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(String.valueOf(raw));
        } catch (Exception ignored) {
        }
        return null;
    }

    private LocalDate pickMin(LocalDate current, LocalDate candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        return candidate.isBefore(current) ? candidate : current;
    }

    private LocalDate pickMax(LocalDate current, LocalDate candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        return candidate.isAfter(current) ? candidate : current;
    }

    private Long resolveStoreId(List<String> row, int storeIdIdx, int storeCodeIdx, int storeNameIdx, Long defaultStoreId) {
        String idText = getCell(row, storeIdIdx).trim();
        if (StringUtils.isNotBlank(idText)) {
            try {
                return Long.parseLong(idText);
            } catch (Exception ignored) {
            }
        }
        String code = trimCell(getCell(row, storeCodeIdx));
        if (StringUtils.isNotBlank(code)) {
            QueryWrapper<Store> qw = new QueryWrapper<Store>();
            qw.eq("isDelete", 0);
            qw.eq("storeCode", code);
            Store store = storeMapper.selectOne(qw);
            if (store != null) {
                return store.getId();
            }
        }
        String name = trimCell(getCell(row, storeNameIdx));
        if (StringUtils.isNotBlank(name)) {
            QueryWrapper<Store> qw = new QueryWrapper<Store>();
            qw.eq("isDelete", 0);
            qw.eq("storeName", name);
            Store store = storeMapper.selectOne(qw);
            if (store != null) {
                return store.getId();
            }
        }
        return defaultStoreId;
    }

    private Long resolveOrUpsertStore(List<String> row, int storeIdIdx, int storeCodeIdx, int storeNameIdx,
            int regionIdx, int cityIdx) {
        Long storeId = resolveStoreId(row, storeIdIdx, storeCodeIdx, storeNameIdx, null);
        String code = trimCell(getCell(row, storeCodeIdx));
        String name = trimCell(getCell(row, storeNameIdx));
        String region = trimCell(getCell(row, regionIdx));
        String city = trimCell(getCell(row, cityIdx));

        if (StringUtils.isBlank(region) || "未分区".equals(region)) {
            String fromName = inferPanzhihuaDistrictFromText(name);
            if (StringUtils.isNotBlank(fromName)) {
                region = fromName;
            }
        }
        if (StringUtils.isBlank(region) || "未分区".equals(region)) {
            String fromCode = inferPanzhihuaDistrictFromStoreCode(code);
            if (StringUtils.isNotBlank(fromCode)) {
                region = fromCode;
            }
        }
        if (StringUtils.isBlank(region)) {
            region = guessRegionByCode(code);
        }
        // 常见数据错误：region 被模板统一写成「东区」，但门店编码仍为 PZH-W*/RH*/MY*/YB*
        String fromCodeStrict = inferPanzhihuaDistrictFromStoreCode(code);
        if (StringUtils.isNotBlank(fromCodeStrict) && "东区".equals(region) && !"东区".equals(fromCodeStrict)) {
            region = fromCodeStrict;
        }
        if (StringUtils.isBlank(city)) {
            city = guessCityByCode(code);
        }
        if (StringUtils.isBlank(name) && StringUtils.isNotBlank(code)) {
            name = code + "门店";
        }

        Store existing = null;
        if (storeId != null && storeId > 0) {
            QueryWrapper<Store> idQw = new QueryWrapper<Store>();
            idQw.eq("id", storeId);
            idQw.eq("isDelete", 0);
            existing = storeMapper.selectOne(idQw);
        } else if (StringUtils.isNotBlank(code)) {
            QueryWrapper<Store> codeQw = new QueryWrapper<Store>();
            codeQw.eq("storeCode", code);
            codeQw.eq("isDelete", 0);
            existing = storeMapper.selectOne(codeQw);
            if (existing != null) {
                storeId = existing.getId();
            }
        }

        if (existing == null) {
            // 数据质量保护：门店主键信息缺失时不再回退默认门店，避免误归集
            if (storeId == null && StringUtils.isBlank(code) && StringUtils.isBlank(name)) {
                return null;
            }
            Store toCreate = new Store();
            if (storeId != null && storeId > 0) {
                toCreate.setId(storeId);
            }
            toCreate.setStoreCode(StringUtils.defaultIfBlank(code, "AUTO_" + (storeId == null ? System.currentTimeMillis() : storeId)));
            toCreate.setStoreName(StringUtils.defaultIfBlank(name, "自动导入门店"));
            toCreate.setRegion(StringUtils.defaultIfBlank(region, "未分区"));
            toCreate.setCity(city);
            toCreate.setStatus("open");
            storeMapper.insert(toCreate);
            return toCreate.getId();
        }

        boolean needUpdate = false;
        if (StringUtils.isNotBlank(name) && !StringUtils.equals(name, existing.getStoreName())) {
            existing.setStoreName(name);
            needUpdate = true;
        }
        if (StringUtils.isNotBlank(region) && !StringUtils.equals(region, existing.getRegion())) {
            existing.setRegion(region);
            needUpdate = true;
        }
        if (StringUtils.isNotBlank(city) && !StringUtils.equals(city, existing.getCity())) {
            existing.setCity(city);
            needUpdate = true;
        }
        if (needUpdate) {
            storeMapper.updateById(existing);
        }
        return existing.getId();
    }

    private String inferPanzhihuaDistrictFromStoreCode(String storeCode) {
        String c = StringUtils.defaultString(storeCode).trim().toUpperCase(Locale.ROOT);
        if (!c.startsWith("PZH-")) {
            return null;
        }
        if (c.startsWith("PZH-E")) {
            return "东区";
        }
        if (c.startsWith("PZH-W")) {
            return "西区";
        }
        if (c.startsWith("PZH-RH")) {
            return "仁和区";
        }
        if (c.startsWith("PZH-MY")) {
            return "米易县";
        }
        if (c.startsWith("PZH-YB")) {
            return "盐边县";
        }
        return null;
    }

    private String inferPanzhihuaDistrictFromText(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        String t = StringUtils.defaultString(text).replace("攀枝花市", "").replace("攀枝花", "").replaceAll("\\s+", "");
        if (t.contains("盐边县") || t.contains("盐边")) {
            return "盐边县";
        }
        if (t.contains("米易县") || t.contains("米易")) {
            return "米易县";
        }
        if (t.contains("仁和区") || t.contains("仁和")) {
            return "仁和区";
        }
        if (t.contains("西区")) {
            return "西区";
        }
        if (t.contains("东区")) {
            return "东区";
        }
        return null;
    }

    private String inferPanzhihuaDistrictFromDemoNumericId(Long storeId) {
        if (storeId == null) {
            return null;
        }
        long n = storeId.longValue();
        if (n >= 1L && n <= 3L) {
            return "东区";
        }
        if (n <= 6L) {
            return "西区";
        }
        if (n <= 9L) {
            return "仁和区";
        }
        if (n <= 12L) {
            return "米易县";
        }
        if (n <= 15L) {
            return "盐边县";
        }
        return null;
    }

    private String resolvePanzhihuaDashboardDistrict(Store store) {
        if (store == null) {
            return null;
        }
        String byCode = inferPanzhihuaDistrictFromStoreCode(store.getStoreCode());
        if (StringUtils.isNotBlank(byCode)) {
            return byCode;
        }
        String byName = inferPanzhihuaDistrictFromText(store.getStoreName());
        if (StringUtils.isNotBlank(byName)) {
            return byName;
        }
        String byReg = inferPanzhihuaDistrictFromText(store.getRegion());
        if (StringUtils.isNotBlank(byReg)) {
            return byReg;
        }
        return inferPanzhihuaDistrictFromDemoNumericId(store.getId());
    }

    private String guessRegionByCode(String storeCode) {
        String code = StringUtils.defaultString(storeCode).toUpperCase();
        if (code.startsWith("BJ")) return "华北";
        if (code.startsWith("SH")) return "华东";
        if (code.startsWith("GZ")) return "华南";
        if (code.startsWith("SZ")) return "华南";
        if (code.startsWith("CD")) return "西南";
        return null;
    }

    private String guessCityByCode(String storeCode) {
        String code = StringUtils.defaultString(storeCode).toUpperCase();
        if (code.startsWith("BJ")) return "北京";
        if (code.startsWith("SH")) return "上海";
        if (code.startsWith("GZ")) return "广州";
        if (code.startsWith("SZ")) return "深圳";
        if (code.startsWith("CD")) return "成都";
        return null;
    }

    private void upsertStoreDaily(LocalDate statDate, Long storeId, BigDecimal revenue, BigDecimal grossMarginRate,
            BigDecimal avgTicket, BigDecimal tableTurnover, BigDecimal prepTimeAvgMin, BigDecimal cancelRate) {
        QueryWrapper<StoreDailyMetric> qw = new QueryWrapper<StoreDailyMetric>();
        qw.eq("statDate", statDate);
        qw.eq("storeId", storeId);
        qw.eq("isDelete", 0);
        StoreDailyMetric existing = storeDailyMetricMapper.selectOne(qw);
        if (existing == null) {
            existing = new StoreDailyMetric();
            existing.setStatDate(statDate);
            existing.setStoreId(storeId);
        }
        existing.setRevenue(revenue);
        existing.setGrossMarginRate(grossMarginRate);
        existing.setAvgTicket(avgTicket);
        existing.setTableTurnover(tableTurnover);
        existing.setPrepTimeAvgMin(prepTimeAvgMin);
        existing.setCancelRate(cancelRate);
        if (existing.getId() == null) {
            storeDailyMetricMapper.insert(existing);
        } else {
            storeDailyMetricMapper.updateById(existing);
        }
    }

    private void upsertDishDaily(LocalDate statDate, Long storeId, String dishName, String categoryName, Long salesQty,
            BigDecimal revenue, BigDecimal grossMarginRate, BigDecimal refundRate) {
        QueryWrapper<DishDailyMetric> qw = new QueryWrapper<DishDailyMetric>();
        qw.eq("statDate", statDate);
        qw.eq("storeId", storeId);
        qw.eq("dishName", dishName);
        qw.eq("isDelete", 0);
        DishDailyMetric existing = dishDailyMetricMapper.selectOne(qw);
        if (existing == null) {
            existing = new DishDailyMetric();
            existing.setStatDate(statDate);
            existing.setStoreId(storeId);
            existing.setDishName(dishName);
        }
        existing.setCategoryName(categoryName);
        existing.setSalesQty(salesQty);
        existing.setRevenue(revenue);
        existing.setGrossMarginRate(grossMarginRate);
        existing.setRefundRate(refundRate);
        if (existing.getId() == null) {
            dishDailyMetricMapper.insert(existing);
        } else {
            dishDailyMetricMapper.updateById(existing);
        }
    }

    private void upsertCampaign(LocalDate statDate, Long storeId, String campaignName, String channelName,
            BigDecimal spendAmount, Long exposureCount, Long clickCount, Long orderCount,
            BigDecimal conversionRate, BigDecimal roi) {
        QueryWrapper<CampaignMetric> qw = new QueryWrapper<CampaignMetric>();
        qw.eq("statDate", statDate);
        qw.eq("storeId", storeId);
        qw.eq("campaignName", campaignName);
        qw.eq("channelName", channelName);
        qw.eq("isDelete", 0);
        CampaignMetric existing = campaignMetricMapper.selectOne(qw);
        if (existing == null) {
            existing = new CampaignMetric();
            existing.setStatDate(statDate);
            existing.setStoreId(storeId);
            existing.setCampaignName(campaignName);
            existing.setChannelName(channelName);
        }
        existing.setSpendAmount(spendAmount);
        existing.setExposureCount(exposureCount);
        existing.setClickCount(clickCount);
        existing.setOrderCount(orderCount);
        existing.setConversionRate(conversionRate);
        existing.setRoi(roi);
        if (existing.getId() == null) {
            campaignMetricMapper.insert(existing);
        } else {
            campaignMetricMapper.updateById(existing);
        }
    }

    private void upsertCostDaily(LocalDate statDate, Long storeId, BigDecimal ingredientCost, BigDecimal packagingCost,
            BigDecimal energyCost, BigDecimal laborCost, BigDecimal wasteAmount, BigDecimal wasteRate,
            BigDecimal inventoryLossRate, BigDecimal platformCommission) {
        QueryWrapper<StoreCostDailyMetric> qw = new QueryWrapper<StoreCostDailyMetric>();
        qw.eq("statDate", statDate);
        qw.eq("storeId", storeId);
        qw.eq("isDelete", 0);
        StoreCostDailyMetric existing = storeCostDailyMetricMapper.selectOne(qw);
        if (existing == null) {
            existing = new StoreCostDailyMetric();
            existing.setStatDate(statDate);
            existing.setStoreId(storeId);
        }
        existing.setIngredientCost(ingredientCost);
        existing.setPackagingCost(packagingCost);
        existing.setEnergyCost(energyCost);
        existing.setLaborCost(laborCost);
        existing.setWasteAmount(wasteAmount);
        existing.setWasteRate(wasteRate);
        existing.setInventoryLossRate(inventoryLossRate);
        existing.setPlatformCommission(platformCommission);
        if (existing.getId() == null) {
            storeCostDailyMetricMapper.insert(existing);
        } else {
            storeCostDailyMetricMapper.updateById(existing);
        }
    }

    private void upsertStaffDaily(LocalDate statDate, Long storeId, String shiftType, Long plannedStaff, Long actualStaff,
            BigDecimal workHours, BigDecimal overtimeHours, Long absentCount, Long ordersCount, BigDecimal revenue,
            BigDecimal avgPrepTimeMin) {
        QueryWrapper<StoreStaffDailyMetric> qw = new QueryWrapper<StoreStaffDailyMetric>();
        qw.eq("statDate", statDate);
        qw.eq("storeId", storeId);
        qw.eq("shiftType", shiftType);
        qw.eq("isDelete", 0);
        StoreStaffDailyMetric existing = storeStaffDailyMetricMapper.selectOne(qw);
        if (existing == null) {
            existing = new StoreStaffDailyMetric();
            existing.setStatDate(statDate);
            existing.setStoreId(storeId);
            existing.setShiftType(shiftType);
        }
        existing.setPlannedStaff(plannedStaff);
        existing.setActualStaff(actualStaff);
        existing.setWorkHours(workHours);
        existing.setOvertimeHours(overtimeHours);
        existing.setAbsentCount(absentCount);
        existing.setOrdersCount(ordersCount);
        existing.setRevenue(revenue);
        existing.setAvgPrepTimeMin(avgPrepTimeMin);
        if (existing.getId() == null) {
            storeStaffDailyMetricMapper.insert(existing);
        } else {
            storeStaffDailyMetricMapper.updateById(existing);
        }
    }

    private void clearCostByRange(Set<Long> storeIds, LocalDate minDate, LocalDate maxDate) {
        if (storeIds == null || storeIds.isEmpty() || minDate == null || maxDate == null) {
            return;
        }
        QueryWrapper<StoreCostDailyMetric> qw = new QueryWrapper<StoreCostDailyMetric>();
        qw.in("storeId", storeIds);
        qw.ge("statDate", minDate);
        qw.le("statDate", maxDate);
        storeCostDailyMetricMapper.delete(qw);
    }

    private void clearStaffByRange(Set<Long> storeIds, LocalDate minDate, LocalDate maxDate) {
        if (storeIds == null || storeIds.isEmpty() || minDate == null || maxDate == null) {
            return;
        }
        QueryWrapper<StoreStaffDailyMetric> qw = new QueryWrapper<StoreStaffDailyMetric>();
        qw.in("storeId", storeIds);
        qw.ge("statDate", minDate);
        qw.le("statDate", maxDate);
        storeStaffDailyMetricMapper.delete(qw);
    }

    private void recordImportLog(User loginUser, String importType, String importMode, int totalRows, int successRows,
            int invalidRows, LocalDate minDate, LocalDate maxDate, String status) {
        recordImportLog(loginUser, importType, importMode, totalRows, successRows, invalidRows, minDate, maxDate,
                status, "");
    }

    private void recordImportLog(User loginUser, String importType, String importMode, int totalRows, int successRows,
            int invalidRows, LocalDate minDate, LocalDate maxDate, String status, String messageText) {
        RestaurantImportLog log = new RestaurantImportLog();
        log.setUserId(loginUser == null ? null : loginUser.getId());
        log.setImportType(importType);
        log.setImportMode(StringUtils.defaultIfBlank(importMode, "overwrite"));
        log.setTotalRows(totalRows);
        log.setSuccessRows(successRows);
        log.setInvalidRows(invalidRows);
        log.setStartDate(minDate);
        log.setEndDate(maxDate);
        log.setStatus(status);
        log.setMessage(StringUtils.defaultIfBlank(messageText, "ok"));
        restaurantImportLogMapper.insert(log);
    }

    private Set<Long> parseStoreIdsFromMessage(String messageText) {
        if (StringUtils.isBlank(messageText) || !messageText.startsWith("stores=")) {
            return new HashSet<Long>();
        }
        String idsPart = messageText.substring("stores=".length()).split(";")[0];
        if (StringUtils.isBlank(idsPart)) {
            return new HashSet<Long>();
        }
        return Arrays.stream(idsPart.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .map(this::toLong)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
    }

    private Map<String, Object> buildInvalidRow(int rowNumber, String reason) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("rowNumber", rowNumber);
        item.put("reason", reason);
        return item;
    }

    private static class AccessScope {
        private final String roleCode;
        private final List<Long> storeIds;
        private final String region;

        private AccessScope(String roleCode, List<Long> storeIds, String region) {
            this.roleCode = roleCode;
            this.storeIds = storeIds;
            this.region = region;
        }
    }

    private static class DishAgg {
        private String dishName;
        private String categoryName;
        private long salesQty;
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal marginWeighted = BigDecimal.ZERO;
        private BigDecimal refundWeighted = BigDecimal.ZERO;
    }
}
