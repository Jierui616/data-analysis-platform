package com.yupi.springbootinit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.springbootinit.mapper.RiskPortraitHitLogMapper;
import com.yupi.springbootinit.mapper.RiskPortraitAnonymousClusterMapper;
import com.yupi.springbootinit.mapper.RiskPortraitMemberCouponLineMapper;
import com.yupi.springbootinit.mapper.RiskPortraitMemberOrderLineMapper;
import com.yupi.springbootinit.mapper.RiskPortraitMemberWatchlistMapper;
import com.yupi.springbootinit.mapper.StoreMapper;
import com.yupi.springbootinit.model.entity.RiskPortraitAnonymousCluster;
import com.yupi.springbootinit.model.entity.RiskPortraitHitLog;
import com.yupi.springbootinit.model.entity.RiskPortraitMemberCouponLine;
import com.yupi.springbootinit.model.entity.RiskPortraitMemberOrderLine;
import com.yupi.springbootinit.model.entity.RiskPortraitMemberWatchlist;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.model.entity.restaurant.Store;
import com.yupi.springbootinit.service.RestaurantBiService;
import com.yupi.springbootinit.service.RiskPortraitService;
import com.yupi.springbootinit.service.UserService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import javax.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RiskPortraitServiceImpl implements RiskPortraitService {

    @Resource
    private RestaurantBiService restaurantBiService;

    @Resource
    private StoreMapper storeMapper;

    @Resource
    private RiskPortraitHitLogMapper riskPortraitHitLogMapper;

    @Resource
    private UserService userService;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private RiskPortraitMemberWatchlistMapper riskPortraitMemberWatchlistMapper;

    @Resource
    private RiskPortraitMemberOrderLineMapper riskPortraitMemberOrderLineMapper;

    @Resource
    private RiskPortraitMemberCouponLineMapper riskPortraitMemberCouponLineMapper;

    @Resource
    private RiskPortraitAnonymousClusterMapper riskPortraitAnonymousClusterMapper;

    /** When false, member watchlist / activity read only from risk_portrait_member_* tables (no synthetic fallback). */
    @Value("${risk-portrait.member-demo-mode:true}")
    private boolean memberDemoMode;

    /** When false, anonymous summary reads only risk_portrait_anonymous_cluster (no synthetic fallback). */
    @Value("${risk-portrait.anonymous-demo-mode:true}")
    private boolean anonymousDemoMode;

    @Override
    public Map<String, Object> getPortraitOverview(User loginUser, Map<String, Object> requestBody) {
        Map<String, Object> body = requestBody == null ? Collections.<String, Object>emptyMap() : requestBody;
        Map<String, Object> hq = restaurantBiService.getHqOverview(loginUser, body);
        Map<String, Object> alertPack = restaurantBiService.listAlertEvents(loginUser, body);

        int storeEvaluated = countStoreRank(hq);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) alertPack.get("events");
        if (events == null) {
            events = Collections.emptyList();
        }

        Set<Object> highRiskStores = new HashSet<Object>();
        int pendingAudits = 0;
        for (Map<String, Object> e : events) {
            String sev = String.valueOf(e.getOrDefault("severity", "")).trim().toUpperCase();
            if ("P1".equals(sev)) {
                highRiskStores.add(e.get("storeId"));
            }
            if ("P1".equals(sev) || "P2".equals(sev)) {
                pendingAudits++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("storeEvaluated", storeEvaluated);
        out.put("highRiskStores", highRiskStores.size());
        out.put("pendingAudits", pendingAudits);
        out.put("lastRefresh", Instant.now().toString());
        return out;
    }

    @Override
    public Map<String, Object> getStorePortrait(User loginUser, Map<String, Object> requestBody) {
        Map<String, Object> body = requestBody == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(requestBody);
        Map<String, Object> overview = restaurantBiService.getStoreOverview(loginUser, body);
        Object sid = overview.get("storeId");
        Long storeIdLong = toLongObject(sid);
        if (storeIdLong == null) {
            return null;
        }
        String storeIdStr = String.valueOf(storeIdLong);

        String storeName = trimToNull(body.get("storeName"));
        if (storeName == null) {
            Store store = storeMapper.selectById(storeIdLong);
            if (store != null) {
                storeName = StringUtils.defaultString(store.getStoreName(), "");
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trend = (List<Map<String, Object>>) overview.getOrDefault("trend", Collections.emptyList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> alerts = (List<Map<String, Object>>) overview.getOrDefault("alerts", Collections.emptyList());
        @SuppressWarnings("unchecked")
        Map<String, Object> kpi = (Map<String, Object>) overview.get("kpi");
        if (kpi == null) {
            kpi = Collections.emptyMap();
        }

        int stability = scoreStabilityFromTrend(trend);
        int trust = scoreTrustFromAlerts(alerts);
        int value = scoreValueFromKpi(kpi);

        String riskLevel = resolveRiskLevel(stability, trust, value);
        List<String> reasons = buildReasons(stability, trust, value, trend, alerts, overview.get("period"));
        List<String> actions = buildActions(riskLevel);
        String windowNote = String.valueOf(overview.getOrDefault("period", ""));

        Map<String, Object> scores = new LinkedHashMap<String, Object>();
        scores.put("stability", stability);
        scores.put("trust", trust);
        scores.put("value", value);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("storeId", storeIdStr);
        out.put("storeName", storeName);
        out.put("scores", scores);
        out.put("riskLevel", riskLevel);
        out.put("reasons", reasons);
        out.put("suggestedActions", actions);
        out.put("windowNote", windowNote);
        return out;
    }

    /** Table {@code risk_portrait_hit_log}: column COMMENTs in MySQL are English; see {@code sql/risk_portrait_schema.sql}. */
    @Override
    public Map<String, Object> pageHitLog(User loginUser, Map<String, Object> requestBody) {
        Map<String, Object> body = requestBody == null ? Collections.<String, Object>emptyMap() : requestBody;
        long current = toLongPrimitive(body.get("current"), 1L);
        long pageSize = toLongPrimitive(body.get("pageSize"), 10L);
        pageSize = Math.min(100L, Math.max(1L, pageSize));
        current = Math.max(1L, current);

        QueryWrapper<RiskPortraitHitLog> qw = new QueryWrapper<RiskPortraitHitLog>();
        qw.eq("isDelete", 0);
        if (!userService.isAdmin(loginUser)) {
            qw.eq("userId", loginUser.getId());
        }
        String scene = trimToNull(body.get("scene"));
        if (scene != null) {
            qw.eq("scene", scene);
        }
        qw.orderByDesc("statTime");

        Page<RiskPortraitHitLog> page = riskPortraitHitLogMapper.selectPage(new Page<RiskPortraitHitLog>(current, pageSize), qw);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        for (RiskPortraitHitLog row : page.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", row.getId());
            item.put("time", row.getStatTime() == null ? "" : sdf.format(row.getStatTime()));
            item.put("subject", StringUtils.defaultString(row.getSubjectType(), "") + ":" + StringUtils.defaultString(row.getSubjectId(), ""));
            item.put("action", row.getAction());
            item.put("rule", StringUtils.isNotBlank(row.getRuleSummary()) ? row.getRuleSummary() : row.getRuleCode());
            item.put("scene", row.getScene());
            records.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("records", records);
        out.put("total", page.getTotal());
        out.put("current", page.getCurrent());
        out.put("pageSize", page.getSize());
        return out;
    }

    /** When {@code persist=true}, inserts {@code risk_portrait_hit_log} (English COMMENT schema in sql/risk_portrait_schema.sql). */
    @Override
    public Map<String, Object> runDecision(User loginUser, Map<String, Object> requestBody) {
        Map<String, Object> body = requestBody == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(requestBody);
        String scene = trimToNull(body.get("scene"));
        if (scene == null) {
            throw new IllegalArgumentException("scene 不能为空");
        }
        boolean persist = !Boolean.FALSE.equals(body.get("persist"));

        Map<String, Object> decision = new LinkedHashMap<String, Object>();
        decision.put("scene", scene);
        List<String> hitRules = new ArrayList<String>();
        List<String> recommendedActions = new ArrayList<String>();
        String riskLevel = "未知";
        String subjectType = StringUtils.defaultString(trimToNull(body.get("subjectType")), "store");
        String subjectId = StringUtils.defaultString(trimToNull(body.get("subjectId")), "");

        if ("store_credit".equals(scene)) {
            if (!"store".equalsIgnoreCase(subjectType)) {
                throw new IllegalArgumentException("store_credit 场景 subjectType 须为 store");
            }
            Long storeId = toLongObject(body.get("subjectId") != null ? body.get("subjectId") : body.get("storeId"));
            if (storeId == null) {
                throw new IllegalArgumentException("门店 subjectId/storeId 不能为空");
            }
            Map<String, Object> req = new LinkedHashMap<String, Object>(body);
            req.put("storeId", storeId);
            Map<String, Object> portrait = getStorePortrait(loginUser, req);
            if (portrait == null) {
                throw new IllegalArgumentException("门店不存在或无权限访问");
            }
            subjectType = "store";
            subjectId = String.valueOf(portrait.get("storeId"));
            riskLevel = String.valueOf(portrait.get("riskLevel"));
            @SuppressWarnings("unchecked")
            Map<String, Object> scores = (Map<String, Object>) portrait.get("scores");
            hitRules.add("门店画像评分：" + String.valueOf(scores));
            @SuppressWarnings("unchecked")
            List<String> sug = (List<String>) portrait.get("suggestedActions");
            if (sug != null) {
                recommendedActions.addAll(sug);
            }
            hitRules.add("风险等级：" + riskLevel);
            decision.put("portrait", portrait);
        } else if ("coupon_control".equals(scene)) {
            riskLevel = "中风险";
            subjectId = StringUtils.isNotBlank(subjectId) ? subjectId : "ALL";
            hitRules.add("券控：高风险限制大额券与叠加；高价值可加到店/复购条件");
            recommendedActions.add("对高风险人群限制大额券、限制跨店核销");
            recommendedActions.add("对高价值人群提升券面并设置到店或复购条件");
            decision.put("note", "会员/匿名明细接入后可输出命中规则列表");
        } else if ("trade_intercept".equals(scene)) {
            riskLevel = "中风险";
            subjectId = StringUtils.isNotBlank(subjectId) ? subjectId : "ALL";
            hitRules.add("交易：异常退款、折扣、核销触发拦截/复核（占位策略）");
            recommendedActions.add("对异常退款与折扣触发复核并留痕");
            decision.put("note", "订单与支付明细接入后可配置阈值与自动拦截");
        } else {
            throw new IllegalArgumentException("不支持的 scene：" + scene);
        }

        decision.put("riskLevel", riskLevel);
        decision.put("hitRules", hitRules);
        decision.put("recommendedActions", recommendedActions);

        if (persist) {
            RiskPortraitHitLog logRow = new RiskPortraitHitLog();
            logRow.setUserId(loginUser.getId());
            logRow.setStatTime(new Date());
            logRow.setSubjectType(subjectType);
            logRow.setSubjectId(subjectId);
            logRow.setScene(scene);
            logRow.setAction(mapDecisionAction(riskLevel));
            logRow.setRuleCode("DECISION:" + scene);
            logRow.setRuleSummary(riskLevel + "｜" + (hitRules.isEmpty() ? "" : hitRules.get(0)));
            try {
                String json = objectMapper.writeValueAsString(decision);
                if (json != null && json.length() > 12000) {
                    json = json.substring(0, 12000) + "...";
                }
                logRow.setDetailJson(json);
            } catch (JsonProcessingException e) {
                logRow.setDetailJson("{}");
            }
            Date now = new Date();
            logRow.setCreateTime(now);
            logRow.setUpdateTime(now);
            logRow.setIsDelete(0);
            riskPortraitHitLogMapper.insert(logRow);
        }
        decision.put("persisted", persist);
        return decision;
    }

    @Override
    public Map<String, Object> getMemberActivityDetails(User loginUser, Map<String, Object> requestBody) {
        Map<String, Object> body = requestBody == null ? Collections.<String, Object>emptyMap() : requestBody;
        String dimension = normalizeMemberDimension(trimToNull(body.get("dimension")));
        String value = trimToNull(body.get("value"));
        if (dimension == null) {
            throw new IllegalArgumentException("dimension 须为 memberId、phone 或 payAccount");
        }
        if (value == null) {
            throw new IllegalArgumentException("查询值不能为空");
        }
        String startDateStr = trimToNull(body.get("startDate"));
        String endDateStr = trimToNull(body.get("endDate"));
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd");
        dayFmt.setLenient(false);
        Calendar endCal = Calendar.getInstance();
        Calendar startCal = Calendar.getInstance();
        try {
            if (endDateStr != null) {
                endCal.setTime(dayFmt.parse(endDateStr));
            }
            if (startDateStr != null) {
                startCal.setTime(dayFmt.parse(startDateStr));
            } else {
                startCal.setTime(endCal.getTime());
                startCal.add(Calendar.DAY_OF_MONTH, -29);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("日期格式须为 yyyy-MM-dd");
        }
        if (startCal.after(endCal)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        SimpleDateFormat tsFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        if (!memberDemoMode) {
            if (!"memberId".equals(dimension)) {
                throw new IllegalArgumentException("导入数据模式下仅支持会员 ID 维度；或开启 risk-portrait.member-demo-mode 使用演示数据。");
            }
            return buildMemberActivityFromImport(value, startCal, endCal, dayFmt, tsFmt, dimension, value);
        }
        int seed = Objects.hash(loginUser.getId(), dimension, value, startDateStr, endDateStr, dayFmt.format(startCal.getTime()), dayFmt.format(endCal.getTime()));
        Random rnd = new Random(seed);
        int nTx = 5 + rnd.nextInt(8);
        int nCp = 3 + rnd.nextInt(6);
        List<Map<String, Object>> transactions = buildSyntheticMemberTransactions(startCal, endCal, rnd, nTx, tsFmt);
        List<Map<String, Object>> couponRedemptions = buildSyntheticMemberCoupons(startCal, endCal, rnd, nCp, tsFmt);
        BigDecimal totalAmt = BigDecimal.ZERO;
        for (Map<String, Object> row : transactions) {
            totalAmt = totalAmt.add(toBigDecimal(row.get("amount")));
        }
        Map<String, Object> period = new LinkedHashMap<String, Object>();
        period.put("startDate", dayFmt.format(startCal.getTime()));
        period.put("endDate", dayFmt.format(endCal.getTime()));
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("orderCount", transactions.size());
        summary.put("totalAmount", totalAmt.setScale(2, RoundingMode.HALF_UP).toPlainString());
        summary.put("couponRedeemCount", couponRedemptions.size());
        summary.put("lastActive", transactions.isEmpty() ? "" : String.valueOf(transactions.get(0).get("time")));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("dimension", dimension);
        out.put("maskedQuery", maskMemberQueryDisplay(dimension, value));
        out.put("period", period);
        out.put("summary", summary);
        out.put("transactions", transactions);
        out.put("couponRedemptions", couponRedemptions);
        out.put("dataSourceNote", "演示数据：便于前后端联调；生产请对接订单中心与券核销明细库。");
        out.put("dataMode", "demo");
        return out;
    }

    @Override
    public Map<String, Object> getMemberWatchlist(User loginUser, Map<String, Object> requestBody) {
        Map<String, Object> body = requestBody == null ? Collections.<String, Object>emptyMap() : requestBody;
        long current = toLongPrimitive(body.get("current"), 1L);
        long pageSize = toLongPrimitive(body.get("pageSize"), 10L);
        pageSize = Math.min(50L, Math.max(1L, pageSize));
        current = Math.max(1L, current);
        String startDateStr = trimToNull(body.get("startDate"));
        String endDateStr = trimToNull(body.get("endDate"));
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd");
        dayFmt.setLenient(false);
        Calendar endCal = Calendar.getInstance();
        Calendar startCal = Calendar.getInstance();
        try {
            if (endDateStr != null) {
                endCal.setTime(dayFmt.parse(endDateStr));
            }
            if (startDateStr != null) {
                startCal.setTime(dayFmt.parse(startDateStr));
            } else {
                startCal.setTime(endCal.getTime());
                startCal.add(Calendar.DAY_OF_MONTH, -29);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("日期格式须为 yyyy-MM-dd");
        }
        if (startCal.after(endCal)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        SimpleDateFormat tsFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String riskFilter = trimToNull(body.get("riskLevel"));
        String storeKeyword = trimToNull(body.get("storeKeyword"));
        if (!memberDemoMode) {
            return buildMemberWatchlistFromImport(startCal, endCal, dayFmt, tsFmt, riskFilter, storeKeyword, current, pageSize);
        }
        List<Map<String, Object>> pool = buildMemberWatchlistPool(loginUser.getId(), startCal, endCal, 80, dayFmt, tsFmt);
        List<Map<String, Object>> filtered = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : pool) {
            if (riskFilter != null && !riskFilter.equals(row.get("riskLevel"))) {
                continue;
            }
            if (storeKeyword != null) {
                String st = String.valueOf(row.get("topStore")).toLowerCase();
                if (!st.contains(storeKeyword.toLowerCase())) {
                    continue;
                }
            }
            filtered.add(row);
        }
        Collections.sort(filtered, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                int c = Integer.compare(memberRiskOrder(String.valueOf(a.get("riskLevel"))),
                        memberRiskOrder(String.valueOf(b.get("riskLevel"))));
                if (c != 0) {
                    return c;
                }
                return String.valueOf(b.get("lastActive")).compareTo(String.valueOf(a.get("lastActive")));
            }
        });
        long total = filtered.size();
        int from = (int) ((current - 1) * pageSize);
        List<Map<String, Object>> page = new ArrayList<Map<String, Object>>();
        if (from < total) {
            int to = (int) Math.min(from + pageSize, total);
            page.addAll(filtered.subList(from, to));
        }
        Map<String, Object> period = new LinkedHashMap<String, Object>();
        period.put("startDate", dayFmt.format(startCal.getTime()));
        period.put("endDate", dayFmt.format(endCal.getTime()));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("records", page);
        out.put("total", total);
        out.put("current", current);
        out.put("pageSize", pageSize);
        out.put("period", period);
        out.put("dataSourceNote", "演示名单：便于总部联调；生产请对接数仓会员风险关注列表。");
        out.put("dataMode", "demo");
        return out;
    }

    private Map<String, Object> buildMemberWatchlistFromImport(Calendar startCal, Calendar endCal, SimpleDateFormat dayFmt,
            SimpleDateFormat tsFmt, String riskFilter, String storeKeyword, long current, long pageSize) {
        QueryWrapper<RiskPortraitMemberWatchlist> qw = new QueryWrapper<RiskPortraitMemberWatchlist>();
        qw.apply("periodStart <= {0}", dayFmt.format(endCal.getTime()));
        qw.apply("periodEnd >= {0}", dayFmt.format(startCal.getTime()));
        if (riskFilter != null) {
            qw.eq("riskLevel", riskFilter);
        }
        if (storeKeyword != null) {
            qw.like("topStore", storeKeyword);
        }
        qw.last("ORDER BY FIELD(riskLevel,'高风险','中风险','低风险'), lastActive DESC");
        Page<RiskPortraitMemberWatchlist> pg =
                riskPortraitMemberWatchlistMapper.selectPage(new Page<RiskPortraitMemberWatchlist>(current, pageSize), qw);
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        for (RiskPortraitMemberWatchlist row : pg.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("memberId", row.getMemberId());
            item.put("displayRef", row.getDisplayRef());
            item.put("riskLevel", row.getRiskLevel());
            item.put("tags", parseTagsCell(row.getTags()));
            item.put("orderCount", row.getOrderCount() == null ? 0 : row.getOrderCount());
            item.put("couponRedeemCount", row.getCouponRedeemCount() == null ? 0 : row.getCouponRedeemCount());
            item.put("refundHint", StringUtils.defaultString(row.getRefundHint(), ""));
            item.put("lastActive", row.getLastActive() == null ? "" : tsFmt.format(row.getLastActive()));
            item.put("topStore", StringUtils.defaultString(row.getTopStore(), ""));
            records.add(item);
        }
        Map<String, Object> period = new LinkedHashMap<String, Object>();
        period.put("startDate", dayFmt.format(startCal.getTime()));
        period.put("endDate", dayFmt.format(endCal.getTime()));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("records", records);
        out.put("total", pg.getTotal());
        out.put("current", pg.getCurrent());
        out.put("pageSize", pg.getSize());
        out.put("period", period);
        out.put("dataMode", "import");
        String note = pg.getTotal() == 0
                ? "暂无导入数据。请先写入表 risk_portrait_member_watchlist，或将 risk-portrait.member-demo-mode 设为 true 使用演示数据。"
                : "数据来源：导入表 risk_portrait_member_watchlist（演示模式已关闭）。";
        out.put("dataSourceNote", note);
        return out;
    }

    private Map<String, Object> buildMemberActivityFromImport(String memberId, Calendar startCal, Calendar endCal, SimpleDateFormat dayFmt,
            SimpleDateFormat tsFmt, String dimension, String rawValue) {
        Calendar s = (Calendar) startCal.clone();
        s.set(Calendar.HOUR_OF_DAY, 0);
        s.set(Calendar.MINUTE, 0);
        s.set(Calendar.SECOND, 0);
        s.set(Calendar.MILLISECOND, 0);
        Calendar e = (Calendar) endCal.clone();
        e.set(Calendar.HOUR_OF_DAY, 23);
        e.set(Calendar.MINUTE, 59);
        e.set(Calendar.SECOND, 59);
        e.set(Calendar.MILLISECOND, 999);
        Date startTs = s.getTime();
        Date endTs = e.getTime();

        QueryWrapper<RiskPortraitMemberOrderLine> oq = new QueryWrapper<RiskPortraitMemberOrderLine>();
        oq.eq("memberId", memberId);
        oq.between("txnTime", startTs, endTs);
        oq.orderByDesc("txnTime");
        List<RiskPortraitMemberOrderLine> orders = riskPortraitMemberOrderLineMapper.selectList(oq);

        QueryWrapper<RiskPortraitMemberCouponLine> cq = new QueryWrapper<RiskPortraitMemberCouponLine>();
        cq.eq("memberId", memberId);
        cq.between("txnTime", startTs, endTs);
        cq.orderByDesc("txnTime");
        List<RiskPortraitMemberCouponLine> coupons = riskPortraitMemberCouponLineMapper.selectList(cq);

        List<Map<String, Object>> transactions = new ArrayList<Map<String, Object>>();
        BigDecimal totalAmt = BigDecimal.ZERO;
        for (RiskPortraitMemberOrderLine o : orders) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("time", o.getTxnTime() == null ? "" : tsFmt.format(o.getTxnTime()));
            row.put("storeName", StringUtils.defaultString(o.getStoreName(), ""));
            BigDecimal amt = o.getAmount() == null ? BigDecimal.ZERO : o.getAmount();
            row.put("amount", amt.setScale(2, RoundingMode.HALF_UP).toPlainString());
            totalAmt = totalAmt.add(amt);
            row.put("channel", StringUtils.defaultString(o.getChannel(), ""));
            row.put("orderNoMasked", StringUtils.defaultString(o.getOrderNoMasked(), ""));
            transactions.add(row);
        }
        List<Map<String, Object>> couponRedemptions = new ArrayList<Map<String, Object>>();
        for (RiskPortraitMemberCouponLine c : coupons) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("time", c.getTxnTime() == null ? "" : tsFmt.format(c.getTxnTime()));
            row.put("storeName", StringUtils.defaultString(c.getStoreName(), ""));
            BigDecimal fv = c.getFaceValue() == null ? BigDecimal.ZERO : c.getFaceValue();
            BigDecimal da = c.getDiscountAmount() == null ? BigDecimal.ZERO : c.getDiscountAmount();
            row.put("couponName", StringUtils.defaultString(c.getCouponName(), ""));
            row.put("faceValue", fv.setScale(2, RoundingMode.HALF_UP).toPlainString());
            row.put("discountAmount", da.setScale(2, RoundingMode.HALF_UP).toPlainString());
            row.put("status", StringUtils.defaultString(c.getStatus(), ""));
            couponRedemptions.add(row);
        }
        String tOrder = transactions.isEmpty() ? "" : String.valueOf(transactions.get(0).get("time"));
        String tCoupon = couponRedemptions.isEmpty() ? "" : String.valueOf(couponRedemptions.get(0).get("time"));
        String lastActive = "";
        if (tOrder.isEmpty()) {
            lastActive = tCoupon;
        } else if (tCoupon.isEmpty()) {
            lastActive = tOrder;
        } else {
            lastActive = tOrder.compareTo(tCoupon) >= 0 ? tOrder : tCoupon;
        }
        Map<String, Object> period = new LinkedHashMap<String, Object>();
        period.put("startDate", dayFmt.format(startCal.getTime()));
        period.put("endDate", dayFmt.format(endCal.getTime()));
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("orderCount", transactions.size());
        summary.put("totalAmount", totalAmt.setScale(2, RoundingMode.HALF_UP).toPlainString());
        summary.put("couponRedeemCount", couponRedemptions.size());
        summary.put("lastActive", lastActive);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("dimension", dimension);
        out.put("maskedQuery", maskMemberQueryDisplay(dimension, rawValue));
        out.put("period", period);
        out.put("summary", summary);
        out.put("transactions", transactions);
        out.put("couponRedemptions", couponRedemptions);
        out.put("dataMode", "import");
        String note = orders.isEmpty() && coupons.isEmpty()
                ? "暂无订单/券明细，请导入 risk_portrait_member_order_line / risk_portrait_member_coupon_line。"
                : "数据来源：导入表（演示模式已关闭）。";
        out.put("dataSourceNote", note);
        return out;
    }

    private List<String> parseTagsCell(String raw) {
        if (StringUtils.isBlank(raw)) {
            return Collections.emptyList();
        }
        String s = raw.trim();
        if (s.startsWith("[")) {
            try {
                return objectMapper.readValue(s, new TypeReference<List<String>>() {});
            } catch (JsonProcessingException e) {
                // fall through to comma split
            }
        }
        List<String> out = new ArrayList<String>();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> getAnonymousPortraitSummary(User loginUser, Map<String, Object> requestBody) {
        Map<String, Object> body = requestBody == null ? Collections.<String, Object>emptyMap() : requestBody;
        String weak = trimToNull(body.get("weakSignal"));
        long current = toLongPrimitive(body.get("current"), 1L);
        long pageSize = toLongPrimitive(body.get("pageSize"), 10L);
        pageSize = Math.min(50L, Math.max(1L, pageSize));
        current = Math.max(1L, current);
        if (!anonymousDemoMode) {
            return buildAnonymousPortraitFromImport(weak, current, pageSize);
        }
        int seed = Objects.hash(loginUser.getId(), weak == null ? "" : weak);
        Random rnd = new Random(seed);
        List<Map<String, Object>> all = generateSyntheticAnonymousClusters(seed, rnd);
        all = filterAnonymousClustersByWeak(all, weak);
        sortAnonymousClusterMaps(all);
        long total = all.size();
        int from = (int) ((current - 1) * pageSize);
        List<Map<String, Object>> pageList = new ArrayList<Map<String, Object>>();
        if (from < total) {
            int to = (int) Math.min(from + pageSize, total);
            pageList.addAll(all.subList(from, to));
        }
        Map<String, Object> desensitization = buildDefaultDesensitizationPack(seed);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("clusters", pageList);
        out.put("total", total);
        out.put("current", current);
        out.put("pageSize", pageSize);
        out.put("desensitization", desensitization);
        out.put("weakSignalFilter", weak == null ? "" : weak);
        out.put("aggregationNote", "当前为样例数据，供联调与界面演示；正式使用请关闭匿名演示模式并导入业务数据。");
        out.put("dataMode", "demo");
        return out;
    }

    private Map<String, Object> buildAnonymousPortraitFromImport(String weak, long current, long pageSize) {
        SimpleDateFormat tsFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        QueryWrapper<RiskPortraitAnonymousCluster> qw = new QueryWrapper<RiskPortraitAnonymousCluster>();
        if (weak != null) {
            qw.and(w -> w.like("anonymousBucketId", weak).or().like("linkedWeakSignals", weak).or().like("topStoreHint", weak));
        }
        qw.last("ORDER BY FIELD(riskTag,'高风险','中风险','低风险'), lastSeen DESC");
        Page<RiskPortraitAnonymousCluster> pg =
                riskPortraitAnonymousClusterMapper.selectPage(new Page<RiskPortraitAnonymousCluster>(current, pageSize), qw);
        List<Map<String, Object>> clusters = new ArrayList<Map<String, Object>>();
        for (RiskPortraitAnonymousCluster row : pg.getRecords()) {
            Map<String, Object> c = new LinkedHashMap<String, Object>();
            c.put("anonymousBucketId", row.getAnonymousBucketId());
            c.put("linkedWeakSignals", parseTagsCell(row.getLinkedWeakSignals()));
            c.put("eventCount7d", row.getEventCount7d() == null ? 0 : row.getEventCount7d());
            c.put("riskTag", StringUtils.defaultString(row.getRiskTag(), ""));
            c.put("lastSeen", row.getLastSeen() == null ? "" : tsFmt.format(row.getLastSeen()));
            c.put("topStoreHint", StringUtils.defaultString(row.getTopStoreHint(), ""));
            clusters.add(c);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("clusters", clusters);
        out.put("total", pg.getTotal());
        out.put("current", pg.getCurrent());
        out.put("pageSize", pg.getSize());
        out.put("desensitization", buildDefaultDesensitizationPack(0L));
        out.put("weakSignalFilter", weak == null ? "" : weak);
        String note = pg.getTotal() == 0
                ? "暂无数据。请导入匿名客群数据，或联系管理员开启演示模式。"
                : "数据来自已导入的匿名客群明细。";
        out.put("aggregationNote", note);
        out.put("dataMode", "import");
        return out;
    }

    private static List<Map<String, Object>> generateSyntheticAnonymousClusters(int seed, Random rnd) {
        List<Map<String, Object>> clusters = new ArrayList<Map<String, Object>>();
        String[] riskTags = new String[] {"低风险", "中风险", "高风险"};
        String[] stores = new String[] {
                "东门广场店", "河西万达店", "南站枢纽店", "高新区店", "步行街店",
                "攀枝花东区万达广场店", "攀枝花东区万象城店", "攀枝花西区清香坪店",
                "攀枝花仁和广场店", "米易安宁河谷店", "盐边桐子林店",
        };
        SimpleDateFormat tsFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        int n = 35;
        for (int i = 0; i < n; i++) {
            Map<String, Object> c = new LinkedHashMap<String, Object>();
            String bucket = "anon_" + Integer.toHexString((seed + i * 131) & 0xffffff);
            c.put("anonymousBucketId", bucket);
            List<String> signals = new ArrayList<String>();
            signals.add("dev_" + maskTail(Integer.toHexString(rnd.nextInt()), 4));
            signals.add("ch_wx_" + maskTail(Integer.toHexString(rnd.nextInt()), 3));
            if (rnd.nextBoolean()) {
                signals.add("sess_" + maskTail(Integer.toHexString(rnd.nextInt()), 5));
            }
            c.put("linkedWeakSignals", signals);
            c.put("eventCount7d", 20 + rnd.nextInt(180));
            c.put("riskTag", riskTags[rnd.nextInt(riskTags.length)]);
            Calendar lc = Calendar.getInstance();
            lc.add(Calendar.HOUR, -(rnd.nextInt(72) + 1));
            c.put("lastSeen", tsFmt.format(lc.getTime()));
            c.put("topStoreHint", stores[rnd.nextInt(stores.length)]);
            clusters.add(c);
        }
        return clusters;
    }

    private static List<Map<String, Object>> filterAnonymousClustersByWeak(List<Map<String, Object>> all, String weak) {
        if (weak == null) {
            return all;
        }
        String w = weak.toLowerCase();
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> c : all) {
            if (String.valueOf(c.get("anonymousBucketId")).toLowerCase().contains(w)) {
                out.add(c);
                continue;
            }
            if (String.valueOf(c.get("topStoreHint")).toLowerCase().contains(w)) {
                out.add(c);
                continue;
            }
            @SuppressWarnings("unchecked")
            List<String> sig = (List<String>) c.get("linkedWeakSignals");
            boolean hit = false;
            if (sig != null) {
                for (String s : sig) {
                    if (s != null && s.toLowerCase().contains(w)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) {
                out.add(c);
            }
        }
        return out;
    }

    private static void sortAnonymousClusterMaps(List<Map<String, Object>> list) {
        Collections.sort(list, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                int c = Integer.compare(memberRiskOrder(String.valueOf(a.get("riskTag"))),
                        memberRiskOrder(String.valueOf(b.get("riskTag"))));
                if (c != 0) {
                    return c;
                }
                return String.valueOf(b.get("lastSeen")).compareTo(String.valueOf(a.get("lastSeen")));
            }
        });
    }

    private Map<String, Object> buildDefaultDesensitizationPack(long sampleSeed) {
        List<Map<String, Object>> rules = new ArrayList<Map<String, Object>>();
        rules.add(ruleRow("设备相关", "只保存加工后的编号，不保存原始设备识别信息。"));
        rules.add(ruleRow("支付相关", "只显示账号头尾几位作示意，完整账号不保存。"));
        rules.add(ruleRow("手机号", "不保存完整号码；需要统计时只使用大致号段。"));
        rules.add(ruleRow("网络位置", "只记录到城市，不记录街道和精确坐标。"));
        Map<String, Object> sampleMasked = new LinkedHashMap<String, Object>();
        int h = (int) sampleSeed;
        sampleMasked.put("device", "dev_***" + maskTail(Integer.toHexString(h), 4));
        sampleMasked.put("payToken", "tok_***" + maskTail(Integer.toHexString(h ^ 31), 2));
        Map<String, Object> desensitization = new LinkedHashMap<String, Object>();
        desensitization.put("retentionSuggestionDays", 90);
        desensitization.put("purposeStatement", "仅用于风险判断和优惠发券，不用于向具体个人做营销。");
        desensitization.put("rules", rules);
        desensitization.put("sampleMasked", sampleMasked);
        return desensitization;
    }

    private static Map<String, Object> ruleRow(String field, String policy) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("field", field);
        m.put("policy", policy);
        return m;
    }

    private static String maskTail(String s, int keep) {
        if (s == null || s.isEmpty()) {
            return "****";
        }
        if (s.length() <= keep) {
            return s;
        }
        return s.substring(s.length() - keep);
    }

    private static String normalizeMemberDimension(String raw) {
        if (raw == null) {
            return null;
        }
        String d = raw.trim();
        if (d.equalsIgnoreCase("memberId") || d.equalsIgnoreCase("member_id")) {
            return "memberId";
        }
        if (d.equalsIgnoreCase("phone") || d.equalsIgnoreCase("phoneNumber") || d.equalsIgnoreCase("mobile")) {
            return "phone";
        }
        if (d.equalsIgnoreCase("payAccount") || d.equalsIgnoreCase("pay_account") || d.equalsIgnoreCase("payment")) {
            return "payAccount";
        }
        return null;
    }

    private static String maskMemberQueryDisplay(String dimension, String value) {
        if ("phone".equals(dimension) && value.length() >= 11) {
            return value.substring(0, 3) + "****" + value.substring(value.length() - 2);
        }
        if ("phone".equals(dimension) && value.length() >= 7) {
            return value.substring(0, 2) + "****" + value.substring(value.length() - 1);
        }
        if ("payAccount".equals(dimension) && value.length() > 6) {
            return value.substring(0, 4) + "***" + value.substring(value.length() - 2);
        }
        if ("memberId".equals(dimension) && value.length() > 4) {
            return value.substring(0, 2) + "…" + value.substring(value.length() - 2);
        }
        return "***";
    }

    private static int memberRiskOrder(String riskLevel) {
        if ("高风险".equals(riskLevel)) {
            return 0;
        }
        if ("中风险".equals(riskLevel)) {
            return 1;
        }
        return 2;
    }

    private static List<Map<String, Object>> buildMemberWatchlistPool(long userId, Calendar start, Calendar end, int poolSize,
            SimpleDateFormat dayFmt, SimpleDateFormat tsFmt) {
        int seed = Objects.hash(userId, dayFmt.format(start.getTime()), dayFmt.format(end.getTime()));
        Random rnd = new Random(seed);
        String[] risks = new String[] {"高风险", "中风险", "低风险"};
        String[][] tagSets = new String[][] {{"高频核销"}, {"异常退款"}, {"跨店密集", "高频核销"}, {"新客高券"}, {"羊毛党模式"}, {}};
        String[] stores = new String[] {"东门广场店", "河西万达店", "南站枢纽店", "步行街店", "高新区店"};
        long span = Math.max(1L, end.getTimeInMillis() - start.getTimeInMillis());
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < poolSize; i++) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            int midNum = 100000 + (rnd.nextInt(800000) + i * 37) % 800000;
            String memberId = "M" + midNum;
            row.put("memberId", memberId);
            String tail = memberId.substring(Math.max(0, memberId.length() - 2));
            row.put("displayRef", "会员 …" + tail);
            row.put("riskLevel", risks[rnd.nextInt(risks.length)]);
            List<String> tags = new ArrayList<String>();
            String[] pick = tagSets[rnd.nextInt(tagSets.length)];
            for (String t : pick) {
                tags.add(t);
            }
            row.put("tags", tags);
            row.put("orderCount", 3 + rnd.nextInt(40));
            row.put("couponRedeemCount", rnd.nextInt(22));
            row.put("refundHint", rnd.nextDouble() < 0.22 ? "偏高" : "正常");
            Date la = new Date(start.getTimeInMillis() + (long) (rnd.nextDouble() * span));
            row.put("lastActive", tsFmt.format(la));
            row.put("topStore", stores[rnd.nextInt(stores.length)]);
            list.add(row);
        }
        return list;
    }

    private static List<Map<String, Object>> buildSyntheticMemberTransactions(Calendar start, Calendar end, Random rnd, int n,
            SimpleDateFormat tsFmt) {
        String[] channels = new String[] {"微信支付", "支付宝", "银联", "储值卡"};
        String[] stores = new String[] {"东门广场店", "河西万达店", "南站枢纽店", "步行街店"};
        long spanMs = Math.max(1L, end.getTimeInMillis() - start.getTimeInMillis());
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < n; i++) {
            long at = start.getTimeInMillis() + (long) (rnd.nextDouble() * spanMs);
            Date when = new Date(at);
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("time", tsFmt.format(when));
            row.put("storeName", stores[rnd.nextInt(stores.length)]);
            BigDecimal amt = BigDecimal.valueOf(18 + rnd.nextDouble() * 220).setScale(2, RoundingMode.HALF_UP);
            row.put("amount", amt.toPlainString());
            row.put("channel", channels[rnd.nextInt(channels.length)]);
            row.put("orderNoMasked", "ORD***" + Integer.toHexString(rnd.nextInt() & 0xfff));
            list.add(row);
        }
        list.sort((a, b) -> String.valueOf(b.get("time")).compareTo(String.valueOf(a.get("time"))));
        return list;
    }

    private static List<Map<String, Object>> buildSyntheticMemberCoupons(Calendar start, Calendar end, Random rnd, int n,
            SimpleDateFormat tsFmt) {
        String[] names = new String[] {"满减券", "新客券", "到店礼", "复购券", "品类券"};
        String[] status = new String[] {"已核销", "已核销", "已过期", "未使用"};
        String[] stores = new String[] {"东门广场店", "河西万达店", "南站枢纽店"};
        long spanMs = Math.max(1L, end.getTimeInMillis() - start.getTimeInMillis());
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < n; i++) {
            long at = start.getTimeInMillis() + (long) (rnd.nextDouble() * spanMs);
            Date when = new Date(at);
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("time", tsFmt.format(when));
            row.put("storeName", stores[rnd.nextInt(stores.length)]);
            row.put("couponName", names[rnd.nextInt(names.length)]);
            row.put("faceValue", BigDecimal.valueOf(5 + rnd.nextInt(50)).setScale(0, RoundingMode.HALF_UP).toPlainString());
            row.put("discountAmount", BigDecimal.valueOf(3 + rnd.nextDouble() * 40).setScale(2, RoundingMode.HALF_UP).toPlainString());
            row.put("status", status[rnd.nextInt(status.length)]);
            list.add(row);
        }
        list.sort((a, b) -> String.valueOf(b.get("time")).compareTo(String.valueOf(a.get("time"))));
        return list;
    }

    private static long toLongPrimitive(Object v, long def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String mapDecisionAction(String riskLevel) {
        if ("高风险".equals(riskLevel)) {
            return "REVIEW";
        }
        if ("低风险".equals(riskLevel)) {
            return "PASS";
        }
        return "REVIEW";
    }

    private static int countStoreRank(Map<String, Object> hq) {
        Object rank = hq.get("storeRank");
        if (!(rank instanceof List)) {
            return 0;
        }
        return ((List<?>) rank).size();
    }

    private static String trimToNull(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Long toLongObject(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Long) {
            return (Long) v;
        }
        if (v instanceof Integer) {
            return ((Integer) v).longValue();
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 日销售额波动越小，稳定性分越高（0–100） */
    private static int scoreStabilityFromTrend(List<Map<String, Object>> trend) {
        if (trend == null || trend.isEmpty()) {
            return 55;
        }
        List<BigDecimal> rev = new ArrayList<BigDecimal>();
        for (Map<String, Object> row : trend) {
            rev.add(toBigDecimal(row.get("revenue")));
        }
        BigDecimal mean = average(rev);
        if (mean.compareTo(BigDecimal.ZERO) <= 0) {
            return 50;
        }
        BigDecimal var = variance(rev, mean);
        BigDecimal cv = sqrt(var).divide(mean, 6, RoundingMode.HALF_UP);
        double cvDouble = cv.doubleValue();
        double clampedCv = Math.min(1.2, Math.max(0, cvDouble));
        int score = (int) Math.round(100.0 * (1.0 - clampedCv / 1.2));
        return clamp(score, 30, 98);
    }

    /** 预警越多、级别越高，可信度分越低 */
    private static int scoreTrustFromAlerts(List<Map<String, Object>> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return 85;
        }
        int weight = 0;
        for (Map<String, Object> a : alerts) {
            String sev = String.valueOf(a.getOrDefault("severity", "")).trim().toUpperCase();
            if ("P1".equals(sev)) {
                weight += 18;
            } else if ("P2".equals(sev)) {
                weight += 10;
            } else if ("P3".equals(sev)) {
                weight += 4;
            } else {
                weight += 3;
            }
        }
        int score = 100 - Math.min(75, weight);
        return clamp(score, 25, 95);
    }

    /** 窗口内营收与毛利率综合 */
    private static int scoreValueFromKpi(Map<String, Object> kpi) {
        BigDecimal revenue = toBigDecimal(kpi.get("revenue"));
        BigDecimal margin = toBigDecimal(kpi.get("grossMarginRate"));
        double r = revenue.doubleValue();
        int revPart = (int) Math.round(Math.min(55, 18 + Math.log10(Math.max(1, r) + 1) * 14));
        double m = margin.doubleValue();
        if (m > 1) {
            m = m / 100.0;
        }
        int marginPart = (int) Math.round(Math.min(45, m * 70));
        return clamp(revPart + marginPart, 30, 99);
    }

    private static String resolveRiskLevel(int stability, int trust, int value) {
        int minCore = Math.min(stability, trust);
        if (minCore < 42 || trust < 38) {
            return "高风险";
        }
        if (minCore < 62 || value < 40) {
            return "中风险";
        }
        return "低风险";
    }

    private static List<String> buildReasons(int stability, int trust, int value, List<Map<String, Object>> trend,
            List<Map<String, Object>> alerts, Object period) {
        List<String> list = new ArrayList<String>();
        list.add("画像口径与经营指挥大屏一致：门店日指标 + 预警事件，窗口 " + String.valueOf(period));
        if (trend != null && trend.size() >= 3) {
            list.add("稳定性：基于窗口内日销售额离散程度（共 " + trend.size() + " 个有效日点）");
        } else {
            list.add("稳定性：有效日销数据较少，分数偏保守");
        }
        if (alerts == null || alerts.isEmpty()) {
            list.add("可信度：当前窗口内无预警事件");
        } else {
            list.add("可信度：窗口内预警 " + alerts.size() + " 条，已按级别加权扣分");
        }
        list.add(String.format("价值：综合窗口营收与毛利率（稳定性分 %d，可信度分 %d，价值分 %d）", stability, trust, value));
        return list;
    }

    private static List<String> buildActions(String riskLevel) {
        List<String> list = new ArrayList<String>();
        if ("高风险".equals(riskLevel)) {
            list.add("铺货额度：建议收紧或暂停提额，转人工审批");
            list.add("账期：建议缩短并提高对账频次");
            list.add("券与促销：限制大额券、叠加与跨店核销，优先排查预警指标");
        } else if ("中风险".equals(riskLevel)) {
            list.add("铺货额度：维持或小幅收紧，观察 1–2 个对账周期");
            list.add("账期：维持或略缩短，关注预警是否反复出现");
        } else {
            list.add("铺货额度：在合规前提下可考虑小幅提额或延长账期试点");
            list.add("账期：可按既有优质客户政策执行");
        }
        return list;
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        if (v instanceof Number) {
            return BigDecimal.valueOf(((Number) v).doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal s = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            s = s.add(v);
        }
        return s.divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal variance(List<BigDecimal> values, BigDecimal mean) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal acc = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            BigDecimal d = v.subtract(mean);
            acc = acc.add(d.multiply(d));
        }
        return acc.divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal sqrt(BigDecimal x) {
        if (x.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        double d = Math.sqrt(x.doubleValue());
        return BigDecimal.valueOf(d);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
