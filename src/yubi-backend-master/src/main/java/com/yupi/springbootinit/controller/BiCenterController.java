package com.yupi.springbootinit.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.gson.Gson;
import com.yupi.springbootinit.common.BaseResponse;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.common.ResultUtils;
import com.yupi.springbootinit.constant.LineageConstant;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.manager.AiManager;
import com.yupi.springbootinit.model.dto.lineage.ExplainRecordRequest;
import com.yupi.springbootinit.model.dto.lineage.LineageRecordRequest;
import com.yupi.springbootinit.model.dto.bicenter.BiCenterOverviewRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.model.vo.bicenter.AiBlockVO;
import com.yupi.springbootinit.model.vo.bicenter.DeepChatVO;
import com.yupi.springbootinit.model.vo.bicenter.InvestorOverviewVO;
import com.yupi.springbootinit.model.vo.bicenter.ManagerOverviewVO;
import com.yupi.springbootinit.model.vo.bicenter.OperatorOverviewVO;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.service.LineageService;
import com.yupi.springbootinit.service.UserService;
import com.yupi.springbootinit.utils.CsvTableUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 商业智能分析中心（复用智能分析上传的 chartData）
 */
@RestController
@RequestMapping("/bi-center")
@Slf4j
public class BiCenterController {

    @Resource
    private ChartService chartService;

    @Resource
    private UserService userService;

    @Resource
    private AiManager aiManager;

    @Resource
    private LineageService lineageService;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /**
     * BI Center overview 缓存：命中可直接跳过查库 + CSV 解析 + 计算
     * - 默认开启
     * - TTL 默认 60 秒（避免用户反复刷新导致重复计算；同时保证数据不会长期过期）
     * Redis 不可用时会自动降级为原逻辑，不影响功能正确性。
     */
    @Value("${bicenter.cache.enabled:true}")
    private boolean biCenterCacheEnabled;

    @Value("${bicenter.cache.ttl-seconds:60}")
    private long biCenterCacheTtlSeconds;

    /**
     * 预解析表缓存：按 chartId 共享（同一图表在 manager/operator/investor 视角复用解析结果）
     * - 默认与 overview TTL 一致
     * - 为避免 Redis 存储/传输过大，默认仅缓存 <= 300KB 的 chartData
     */
    @Value("${bicenter.cache.table-ttl-seconds:60}")
    private long biCenterTableCacheTtlSeconds;

    @Value("${bicenter.cache.max-table-bytes:300000}")
    private int biCenterCacheMaxTableBytes;

    @Value("${bicenter.ai.run.model-id:0}")
    private long biCenterAiRunModelId;

    @Value("${bicenter.ai.run.dashscope-model:}")
    private String biCenterAiRunDashscopeModel;

    @Value("${bicenter.ai.deep-chat.model-id:0}")
    private long biCenterDeepChatModelId;

    @Value("${bicenter.ai.deep-chat.dashscope-model:}")
    private String biCenterDeepChatDashscopeModel;

    @Value("${bicenter.ai.deep-chat.fallback-to-default:true}")
    private boolean biCenterDeepChatFallbackToDefault;

    @Value("${bicenter.ai.deep-chat.session-ttl-seconds:2592000}")
    private long biCenterSessionTtlSeconds;

    private final Gson gson = new Gson();
    private final Map<Long, Map<String, List<Map<String, Object>>>> localSessionStore = new ConcurrentHashMap<>();
    private static final String BI_CENTER_SESSION_KEY_PREFIX = "bicenter:chat:session:";

    /**
     * 健康检查：用于确认路由是否已随服务启动生效
     */
    @GetMapping("/ping")
    public BaseResponse<Map<String, Object>> ping() {
        Map<String, Object> res = new HashMap<>();
        res.put("ok", true);
        res.put("ts", System.currentTimeMillis());
        return ResultUtils.success(res);
    }

    @PostMapping("/manager/overview")
    public BaseResponse<ManagerOverviewVO> managerOverview(@RequestBody(required = false) BiCenterOverviewRequest req,
                                                           HttpServletRequest request) {
        Chart chart = getChartForCurrentUser(req, request);
        User loginUser = userService.getLoginUser(request);
        String cacheKey = buildOverviewCacheKey("manager", loginUser.getId(), chart.getId(), req);
        ManagerOverviewVO cached = getCachedOverview(cacheKey, ManagerOverviewVO.class);
        if (cached != null) {
            return ResultUtils.success(cached);
        }
        CsvTableUtils.Table table = getOrParseTable(loginUser.getId(), chart);
        ManagerOverviewVO vo = buildManagerOverview(req, chart, table);
        setCachedOverview(cacheKey, vo);
        return ResultUtils.success(vo);
    }

    @PostMapping("/operator/overview")
    public BaseResponse<OperatorOverviewVO> operatorOverview(@RequestBody(required = false) BiCenterOverviewRequest req,
                                                             HttpServletRequest request) {
        Chart chart = getChartForCurrentUser(req, request);
        User loginUser = userService.getLoginUser(request);
        String cacheKey = buildOverviewCacheKey("operator", loginUser.getId(), chart.getId(), req);
        OperatorOverviewVO cached = getCachedOverview(cacheKey, OperatorOverviewVO.class);
        if (cached != null) {
            return ResultUtils.success(cached);
        }
        CsvTableUtils.Table table = getOrParseTable(loginUser.getId(), chart);
        OperatorOverviewVO vo = buildOperatorOverview(req, chart, table);
        setCachedOverview(cacheKey, vo);
        return ResultUtils.success(vo);
    }

    @PostMapping("/investor/overview")
    public BaseResponse<InvestorOverviewVO> investorOverview(@RequestBody(required = false) BiCenterOverviewRequest req,
                                                             HttpServletRequest request) {
        Chart chart = getChartForCurrentUser(req, request);
        User loginUser = userService.getLoginUser(request);
        String cacheKey = buildOverviewCacheKey("investor", loginUser.getId(), chart.getId(), req);
        InvestorOverviewVO cached = getCachedOverview(cacheKey, InvestorOverviewVO.class);
        if (cached != null) {
            return ResultUtils.success(cached);
        }
        CsvTableUtils.Table table = getOrParseTable(loginUser.getId(), chart);
        InvestorOverviewVO vo = buildInvestorOverview(req, chart, table);
        setCachedOverview(cacheKey, vo);
        return ResultUtils.success(vo);
    }

    /**
     * 运行 AI 分析并直接返回结构化报告
     */
    @PostMapping("/ai/run")
    public BaseResponse<AiBlockVO> runAi(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String role = String.valueOf(body.getOrDefault("role", "")).trim();
        if (StringUtils.isBlank(role)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "role 不能为空");
        }
        userService.getLoginUser(request);
        Object snapshot = body.get("snapshot");
        String snapshotJson = gson.toJson(snapshot == null ? Collections.emptyMap() : snapshot);
        Long chartId = extractChartId(body, snapshot);
        String traceId = lineageService.newTraceId();
        Long operatorId = userService.getLoginUser(request).getId();
        recordLineage(traceId, chartId, LineageConstant.EVENT_METRIC_COMPUTED, snapshotJson, "", "bicenter_overview_v1", "succeed", operatorId);
        AiBlockVO fallback = buildAiRunFallback(role, snapshotJson);
        String prompt = buildAiRunPrompt(role, snapshotJson);
        AiBlockVO result = doAiJsonOrFallback(prompt, fallback, biCenterAiRunModelId, biCenterAiRunDashscopeModel);
        recordLineage(traceId, chartId, LineageConstant.EVENT_AI_INTERPRETED, prompt, gson.toJson(result), "bicenter_ai_run_prompt_v1", "succeed", operatorId);
        recordAiExplain(traceId, chartId, "bicenter_ai_run_prompt_v1", "dashscope_or_fallback", prompt, gson.toJson(result));
        return ResultUtils.success(result);
    }

    /**
     * 深度对话：返回 answer + followUps + assumptions
     */
    @PostMapping("/ai/deep-chat")
    public BaseResponse<DeepChatVO> deepChat(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String role = String.valueOf(body.getOrDefault("role", "")).trim();
        if (StringUtils.isBlank(role)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "role 不能为空");
        }
        userService.getLoginUser(request);
        String question = String.valueOf(body.getOrDefault("question", "")).trim();
        if (StringUtils.isBlank(question)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "question 不能为空");
        }
        Object snapshot = body.get("snapshot");
        Object history = body.get("history");
        Object memory = body.get("memory");
        String sessionId = StringUtils.defaultString(String.valueOf(body.getOrDefault("sessionId", ""))).trim();
        if (StringUtils.isBlank(sessionId)) {
            sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "");
        }
        Long chartId = extractChartId(body, snapshot);
        Object finalSnapshot = snapshot;
        if (!(snapshot instanceof Map) || ((Map<?, ?>) snapshot).isEmpty()) {
            finalSnapshot = buildDeepChatSnapshot(chartId, request);
            if (chartId == null && finalSnapshot instanceof Map) {
                Object fallbackChartId = ((Map<?, ?>) finalSnapshot).get("chartId");
                Long parsedChartId = toLong(fallbackChartId);
                if (parsedChartId != null && parsedChartId > 0) {
                    chartId = parsedChartId;
                }
            }
        }
        String snapshotJson = gson.toJson(finalSnapshot == null ? Collections.emptyMap() : finalSnapshot);
        List<Map<String, Object>> storedMessages = getSessionMessages(operatorIdFromRequest(request), sessionId);
        String historyJson = gson.toJson(history == null ? storedMessages : history);
        String memoryJson = gson.toJson(memory == null ? Collections.emptyMap() : memory);
        String traceId = lineageService.newTraceId();
        Long operatorId = userService.getLoginUser(request).getId();
        recordLineage(traceId, chartId, LineageConstant.EVENT_DATA_CLEANED, historyJson, memoryJson, "bicenter_chat_context_v1", "succeed", operatorId);
        DeepChatVO fallback = buildDeepChatFallback(role, question);
        String prompt = buildDeepChatPrompt(role, question, snapshotJson, historyJson, memoryJson);
        DeepChatVO result = doDeepChatOrFallback(
                prompt,
                fallback,
                biCenterDeepChatModelId,
                biCenterDeepChatDashscopeModel,
                biCenterDeepChatFallbackToDefault
        );
        if (result.getActionItems() == null || result.getActionItems().isEmpty()) {
            result.setActionItems(buildActionItems(role, question, result.getAnswer(), result.getFollowUps()));
        }
        appendSessionMessage(operatorId, sessionId, "user", question, Collections.emptyList());
        appendSessionMessage(operatorId, sessionId, "assistant", result.getAnswer(), result.getActionItems());
        recordLineage(traceId, chartId, LineageConstant.EVENT_AI_INTERPRETED, prompt, gson.toJson(result), "bicenter_deep_chat_prompt_v1", "succeed", operatorId);
        recordAiExplain(traceId, chartId, "bicenter_deep_chat_prompt_v1", "dashscope_or_fallback", prompt, gson.toJson(result));
        return ResultUtils.success(result);
    }

    @PostMapping("/ai/session/create")
    public BaseResponse<Map<String, Object>> createChatSession(@RequestBody(required = false) Map<String, Object> body,
                                                               HttpServletRequest request) {
        Long userId = operatorIdFromRequest(request);
        String title = "";
        if (body != null) {
            title = StringUtils.defaultString(String.valueOf(body.getOrDefault("title", ""))).trim();
        }
        if (StringUtils.isBlank(title)) {
            title = "新会话";
        }
        String sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "");
        List<Map<String, Object>> messages = new ArrayList<>();
        saveSessionMessages(userId, sessionId, messages);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sessionId", sessionId);
        item.put("title", title);
        item.put("updatedAt", System.currentTimeMillis());
        saveSessionMeta(userId, sessionId, item);
        return ResultUtils.success(item);
    }

    @GetMapping("/ai/session/list")
    public BaseResponse<List<Map<String, Object>>> listChatSessions(HttpServletRequest request) {
        Long userId = operatorIdFromRequest(request);
        List<Map<String, Object>> sessions = getSessionMetaList(userId);
        sessions.sort((a, b) -> Long.compare(toLongSafe(b.get("updatedAt")), toLongSafe(a.get("updatedAt"))));
        return ResultUtils.success(sessions);
    }

    @GetMapping("/ai/session/{sessionId}/messages")
    public BaseResponse<List<Map<String, Object>>> listChatSessionMessages(@PathVariable("sessionId") String sessionId,
                                                                            HttpServletRequest request) {
        if (StringUtils.isBlank(sessionId)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "sessionId 不能为空");
        }
        Long userId = operatorIdFromRequest(request);
        return ResultUtils.success(getSessionMessages(userId, sessionId));
    }

    private Map<String, Object> buildDeepChatSnapshot(Long chartId, HttpServletRequest request) {
        try {
            BiCenterOverviewRequest req = new BiCenterOverviewRequest();
            if (chartId != null && chartId > 0) {
                req.setChartId(chartId);
            }
            Chart chart = getChartForCurrentUser(req, request);
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("chartId", chart.getId());
            snapshot.put("name", chart.getName());
            snapshot.put("goal", chart.getGoal());
            snapshot.put("chartType", chart.getChartType());
            snapshot.put("genResult", limit(StringUtils.defaultString(chart.getGenResult()), 3000));
            snapshot.put("resultMeta", limit(StringUtils.defaultString(chart.getResultMeta()), 3000));
            snapshot.put("chartData", limit(StringUtils.defaultString(chart.getChartData()), 8000));
            return snapshot;
        } catch (Exception e) {
            // 聊天场景下不强依赖快照，取不到时降级为空，避免阻断会话
            return new LinkedHashMap<>();
        }
    }

    private Chart getChartForCurrentUser(BiCenterOverviewRequest req, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        Long chartId = req == null ? null : req.getChartId();
        if (chartId != null && chartId > 0) {
            Chart chart = chartService.getById(chartId);
            if (chart == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图表不存在");
            }
            if (!Objects.equals(chart.getUserId(), loginUser.getId()) && !userService.isAdmin(loginUser)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
            if (StringUtils.isBlank(chart.getChartData())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "图表原始数据为空");
            }
            return chart;
        }

        // 默认取当前用户最近一次成功的图表；没有成功则退回最近一次有 chartData 的图表
        QueryWrapper<Chart> qw = new QueryWrapper<>();
        qw.eq("userId", loginUser.getId());
        qw.eq("isDelete", false);
        qw.orderByDesc("createTime");
        Page<Chart> p = chartService.page(new Page<>(1, 20), qw);
        List<Chart> records = p.getRecords();
        if (records == null || records.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "未找到历史分析记录，请先在「智能分析」上传数据并提交");
        }
        Chart best = null;
        for (Chart c : records) {
            if (c == null) continue;
            if (StringUtils.isBlank(c.getChartData())) continue;
            if ("succeed".equalsIgnoreCase(StringUtils.defaultString(c.getStatus()))) {
                best = c;
                break;
            }
            if (best == null) best = c;
        }
        if (best == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "未找到可用的原始数据，请先在「智能分析」上传数据并提交");
        }
        return best;
    }

    private String buildOverviewCacheKey(String role, Long userId, Long chartId, BiCenterOverviewRequest req) {
        // req 里的 company/period 只影响 investor/company 展示与 period 展示；纳入 key 可避免“缓存串用”
        String company = req == null ? "" : StringUtils.defaultString(req.getCompany()).trim();
        String period = req == null ? "" : StringUtils.defaultString(req.getPeriod()).trim();
        // 尽量短且可读：bicenter:ov:<role>:u<uid>:c<chartId>:p<periodHash>:co<companyHash>
        return "bicenter:ov:" + StringUtils.defaultString(role)
                + ":u" + String.valueOf(userId == null ? 0 : userId)
                + ":c" + String.valueOf(chartId == null ? 0 : chartId)
                + ":p" + Integer.toHexString(period.hashCode())
                + ":co" + Integer.toHexString(company.hashCode());
    }

    private String buildTableCacheKey(Long userId, Chart chart) {
        // 预解析表只依赖 chartData；用 updateTime 做简易版本号，避免 chartData 更新后仍命中旧缓存
        long ut = 0;
        try {
            if (chart != null && chart.getUpdateTime() != null) {
                ut = chart.getUpdateTime().getTime();
            }
        } catch (Exception ignored) {
            ut = 0;
        }
        return "bicenter:table:u" + String.valueOf(userId == null ? 0 : userId)
                + ":c" + String.valueOf(chart == null ? 0 : chart.getId())
                + ":ut" + ut;
    }

    private CsvTableUtils.Table getOrParseTable(Long userId, Chart chart) {
        String csv = chart == null ? null : chart.getChartData();
        if (StringUtils.isBlank(csv)) {
            // CsvTableUtils.parse 至少需要表头行；这里仅做防御，正常情况下 chartData 为空会在上游直接抛错
            return CsvTableUtils.parse("col\n");
        }

        // 过大数据不缓存，避免占用 Redis 与网络
        if (biCenterCacheMaxTableBytes > 0 && csv.length() > biCenterCacheMaxTableBytes) {
            return CsvTableUtils.parse(csv);
        }

        String key = buildTableCacheKey(userId, chart);
        CsvTableUtils.Table cached = getCachedTable(key);
        if (cached != null && cached.getHeaders() != null && cached.getRows() != null) {
            return cached;
        }

        CsvTableUtils.Table table = CsvTableUtils.parse(csv);
        setCachedTable(key, table);
        return table;
    }

    private CsvTableUtils.Table getCachedTable(String key) {
        if (!biCenterCacheEnabled || biCenterTableCacheTtlSeconds <= 0) {
            return null;
        }
        if (stringRedisTemplate == null || StringUtils.isBlank(key)) {
            return null;
        }
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StringUtils.isBlank(json)) {
                return null;
            }
            return gson.fromJson(json, CsvTableUtils.Table.class);
        } catch (Exception e) {
            log.debug("BI Center table cache read skipped: {}", e.getMessage());
            return null;
        }
    }

    private void setCachedTable(String key, CsvTableUtils.Table table) {
        if (!biCenterCacheEnabled || biCenterTableCacheTtlSeconds <= 0) {
            return;
        }
        if (stringRedisTemplate == null || StringUtils.isBlank(key) || table == null) {
            return;
        }
        try {
            String json = gson.toJson(table);
            if (StringUtils.isBlank(json)) {
                return;
            }
            stringRedisTemplate.opsForValue().set(key, json, biCenterTableCacheTtlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("BI Center table cache write skipped: {}", e.getMessage());
        }
    }

    private <T> T getCachedOverview(String key, Class<T> clazz) {
        if (!biCenterCacheEnabled || biCenterCacheTtlSeconds <= 0) {
            return null;
        }
        if (stringRedisTemplate == null || StringUtils.isBlank(key) || clazz == null) {
            return null;
        }
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StringUtils.isBlank(json)) {
                return null;
            }
            return gson.fromJson(json, clazz);
        } catch (Exception e) {
            log.debug("BI Center cache read skipped: {}", e.getMessage());
            return null;
        }
    }

    private void setCachedOverview(String key, Object value) {
        if (!biCenterCacheEnabled || biCenterCacheTtlSeconds <= 0) {
            return;
        }
        if (stringRedisTemplate == null || StringUtils.isBlank(key) || value == null) {
            return;
        }
        try {
            String json = gson.toJson(value);
            if (StringUtils.isBlank(json)) {
                return;
            }
            stringRedisTemplate.opsForValue().set(key, json, biCenterCacheTtlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("BI Center cache write skipped: {}", e.getMessage());
        }
    }

    private String buildAiRunPrompt(String role, String snapshotJson) {
        String roleName;
        switch (role) {
            case "manager":
                roleName = "企业管理者";
                break;
            case "operator":
                roleName = "业务运营人员";
                break;
            case "investor":
                roleName = "投资者/分析师";
                break;
            default:
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的角色类型");
        }
        return "你是资深商业分析顾问。请基于前端当前页面传入的图表数据快照，"
                + "输出严格 JSON，格式必须为："
                + "{\"summary\":\"\",\"risks\":[],\"reasons\":[],\"suggestions\":[]}"
                + "\n要求：1. summary 为 1 段结论；2. risks/reasons/suggestions 各返回 2-4 条；"
                + "3. 语言专业、简洁；4. 不要输出任何 JSON 之外的内容。"
                + "\n当前视角：" + roleName
                + "\n页面图表数据快照：\n" + limit(snapshotJson, 12000);
    }

    private String buildDeepChatPrompt(String role, String question, String snapshotJson, String historyJson, String memoryJson) {
        String roleName;
        switch (role) {
            case "manager":
                roleName = "企业管理者";
                break;
            case "operator":
                roleName = "业务运营人员";
                break;
            case "investor":
                roleName = "投资者/分析师";
                break;
            default:
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的角色类型");
        }
        return "你是资深商业分析顾问，擅长多轮对话推理。"
                + "\n请基于【用户问题】【历史对话】【偏好记忆】【当前业务快照】给出深入回答。"
                + "\n输出必须是严格 JSON，格式为："
                + "{\"answer\":\"\",\"followUps\":[],\"assumptions\":[],\"actionItems\":[{\"priority\":\"P0|P1|P2\",\"title\":\"\",\"owner\":\"\",\"expectedBenefit\":\"\",\"eta\":\"\"}]}"
                + "\n要求："
                + "\n1) answer 要直接回答用户问题，并给出可执行建议；"
                + "\n2) followUps 给 2-4 条下一步追问（用于继续深挖）；"
                + "\n3) assumptions 给 1-3 条你做出的关键假设；"
                + "\n4) actionItems 返回 1-3 条结构化行动项（priority/title/owner/expectedBenefit/eta）；"
                + "\n5) 不要输出任何 JSON 之外的内容。"
                + "\n\n当前视角：" + roleName
                + "\n用户问题：\n" + limit(question, 2000)
                + "\n\n历史对话（近几轮）：\n" + limit(historyJson, 8000)
                + "\n\n用户偏好/记忆：\n" + limit(memoryJson, 4000)
                + "\n\n页面图表数据快照：\n" + limit(snapshotJson, 12000);
    }

    private AiBlockVO buildAiRunFallback(String role, String snapshotJson) {
        AiBlockVO ai = new AiBlockVO();
        if ("manager".equals(role)) {
            ai.setSummary("已基于经营看板中的营收、利润、现金流、周转与 ROI 指标生成管理层分析结论。建议重点关注异常波动指标与资源投放效率。");
            ai.setRisks(Arrays.asList(
                    "关键经营指标若连续偏离基线，可能带来现金流与利润承压风险",
                    "库存与营销投放未联动优化时，容易出现周转放缓与资金占用"
            ));
            ai.setReasons(Arrays.asList(
                    "当前页面数据快照显示经营指标之间存在结构性差异，需要结合趋势与预警进一步定位",
                    "若 ROI、库存周转或利润趋势同步走弱，通常意味着资源配置与需求匹配存在偏差"
            ));
            ai.setSuggestions(Arrays.asList(
                    "建立营收、利润、现金流、库存和 ROI 的联动复盘机制",
                    "对异常指标按时间、渠道、品类继续下钻定位",
                    "设置自动阈值预警，缩短识别和响应周期"
            ));
            return ai;
        }
        if ("operator".equals(role)) {
            ai.setSummary("已基于营销活动漏斗、实时花费转化、RFM 分层和用户画像生成运营策略建议。建议优先优化转化瓶颈环节与高价值人群投放效率。");
            ai.setRisks(Arrays.asList(
                    "漏斗中段转化偏低会直接拉低整体活动 ROI",
                    "预算若持续投向低质量流量，后续复购和利润空间会被压缩"
            ));
            ai.setReasons(Arrays.asList(
                    "页面快照反映出人群、渠道与转化链路之间可能存在结构性失配",
                    "当实时花费增速快于转化增速时，通常说明素材、定向或落地页承接效率不足"
            ));
            ai.setSuggestions(Arrays.asList(
                    "围绕点击到加购、加购到下单环节优先做 AB 测试",
                    "针对高价值与潜力人群制定分层权益策略",
                    "将预算动态迁移至高转化渠道与素材组合"
            ));
            return ai;
        }
        if ("investor".equals(role)) {
            ai.setSummary("已基于财报关键指标、风险预警与行业趋势图生成投资视角分析报告。建议结合盈利质量、现金流和行业位置综合判断。");
            ai.setRisks(Arrays.asList(
                    "财务指标与行业趋势若出现背离，可能意味着经营质量或预期存在不确定性",
                    "风险预警项若持续累积，可能削弱盈利弹性与估值支撑"
            ));
            ai.setReasons(Arrays.asList(
                    "页面中的公司与同业对比能够帮助识别当前竞争位置与结构性差距",
                    "行业趋势预测与风险事件需要结合，才能更准确评估中短期投资风险"
            ));
            ai.setSuggestions(Arrays.asList(
                    "结合现金流、费用率和周转效率继续补充纵向验证",
                    "将风险预警拆解到业务或财务科目层面跟踪",
                    "结合行业趋势变化动态更新投资判断和决策节奏"
            ));
            return ai;
        }
        throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的角色类型");
    }

    private ManagerOverviewVO buildManagerOverview(BiCenterOverviewRequest req, Chart chart, CsvTableUtils.Table table) {
        ManagerOverviewVO vo = new ManagerOverviewVO();
        vo.setPeriod(defaultIfBlank(req == null ? null : req.getPeriod(), "最近一次分析"));

        // 1) 先按表头做结构化映射
        int revenueIdx = findKpiColumn(table, "营收", "收入", "revenue", "sales", "gmv", "成交额");
        int profitIdx = findKpiColumn(table, "利润", "净利润", "profit", "netprofit", "净利");
        int cashIdx = findKpiColumn(table, "现金流", "经营现金流", "cashflow", "ocf");
        int invTurnIdx = findKpiColumn(table, "库存周转", "周转率", "inventoryturnover", "inventory_turnover", "turnover");
        int userGrowthIdx = findKpiColumn(table, "用户增长率", "用户增长", "增长率", "usergrowth", "user_growth", "growth");
        int roiIdx = findKpiColumn(table, "营销roi", "投放roi", "roi", "marketingroi", "marketing_roi");

        double revenue = revenueIdx >= 0 ? sumColumn(table, revenueIdx) : 0;
        double profit = profitIdx >= 0 ? sumColumn(table, profitIdx) : 0;
        double cash = cashIdx >= 0 ? sumColumn(table, cashIdx) : 0;
        double invTurn = invTurnIdx >= 0 ? avgColumn(table, invTurnIdx) : 0;
        double userGrowth = userGrowthIdx >= 0 ? avgColumn(table, userGrowthIdx) : 0;
        double mroi = roiIdx >= 0 ? avgColumn(table, roiIdx) : 0;

        // 备用规则：若识别不到，取前几个数值列构造
        List<Integer> numericCols = guessNumericColumns(table);
        if (revenue == 0 && !numericCols.isEmpty()) revenue = sumColumn(table, numericCols.get(0));
        if (profit == 0 && numericCols.size() > 1) profit = sumColumn(table, numericCols.get(1)) * 0.1;
        if (cash == 0 && numericCols.size() > 2) cash = sumColumn(table, numericCols.get(2)) * 0.08;
        if (invTurn == 0) invTurn = 5.5;
        if (userGrowth == 0) userGrowth = 0.12;
        if (mroi == 0) mroi = 3.2;

        vo.setRevenue(revenue);
        vo.setProfit(profit);
        vo.setCashflow(cash);
        vo.setInventoryTurnover(invTurn);
        vo.setUserGrowthRate(clamp(userGrowth, 0, 1));
        vo.setMarketingRoi(mroi);
        vo.setHealthScore(calcHealthScore(revenue, profit, cash, invTurn, clamp(userGrowth, 0, 1), mroi));

        // 2) 趋势：如果能找到列，就直接取“最近 6 行”作为趋势；否则再用平滑推算
        vo.setKpiTrend(buildManagerTrend(table, revenue, profit, cash, revenueIdx, profitIdx, cashIdx));
        vo.setAlertList(buildManagerAlerts(vo));
        vo.setOverview(buildManagerOverviewSection(vo, table));
        vo.setAnomalies(buildManagerAnomalies(vo));
        vo.setActions(buildManagerActions(vo));
        // 视角概览接口不再同步调用大模型，避免切换视角时长时间阻塞。
        vo.setAi(defaultManagerAiFallback());
        return vo;
    }

    private OperatorOverviewVO buildOperatorOverview(BiCenterOverviewRequest req, Chart chart, CsvTableUtils.Table table) {
        OperatorOverviewVO vo = new OperatorOverviewVO();
        vo.setPeriod(defaultIfBlank(req == null ? null : req.getPeriod(), "最近一次分析"));

        OperatorOverviewVO.CampaignVO c = new OperatorOverviewVO.CampaignVO();
        c.setName(defaultIfBlank(chart.getName(), "活动 / 业务主题"));
        // ROI：优先识别 ROI 列
        int roiIdx = findKpiColumn(table, "营销roi", "投放roi", "roi", "marketingroi", "marketing_roi");
        double roi = roiIdx >= 0 ? avgColumn(table, roiIdx) : 0;
        if (roi == 0) {
            // 用两列数值近似：第二列/第一列
            List<Integer> cols = guessNumericColumns(table);
            if (cols.size() >= 2) {
                double spend = sumColumn(table, cols.get(0));
                double conv = sumColumn(table, cols.get(1));
                roi = spend == 0 ? 3.0 : (conv / spend) * 10;
            } else {
                roi = 3.0;
            }
        }
        c.setPredictedRoi(round2(roi * 1.15));
        c.setRealtimeRoi(round2(roi * 0.95));
        c.setFinalRoi(round2(roi));

        // 漏斗：优先识别“曝光/点击/加购/下单/复购”等列并求和，否则回退模拟
        OperatorOverviewVO.FunnelVO funnel = new OperatorOverviewVO.FunnelVO();
        funnel.setStages(Arrays.asList("曝光", "点击", "加购", "下单", "复购"));
        List<Number> values = buildFunnelValues(table);
        funnel.setValues(values);
        c.setFunnel(funnel);

        // 实时：优先识别 时间/花费/转化 列，取最近 6 行；否则回退模拟
        OperatorOverviewVO.RealtimeVO rt = new OperatorOverviewVO.RealtimeVO();
        fillRealtime(table, rt);
        c.setRealtime(rt);
        vo.setCampaign(c);

        // RFM：若存在 R/F/M 列则做五分位分层统计，否则回退默认
        OperatorOverviewVO.RfmVO rfm = buildRfm(table);
        vo.setRfm(rfm);
        // 画像：若存在 “人群/标签/渠道/品类”等列，取 Top 类别；否则回退默认
        vo.setPersona(buildPersona(table));
        vo.setFunnelDiagnosis(buildFunnelDiagnosis(vo.getCampaign()));
        vo.setAttribution(buildAttribution(vo));
        vo.setOptimizationActions(buildOptimizationActions(vo));
        // 视角概览接口不再同步调用大模型，避免切换视角时长时间阻塞。
        vo.setAi(defaultOperatorAiFallback());
        return vo;
    }

    private InvestorOverviewVO buildInvestorOverview(BiCenterOverviewRequest req, Chart chart, CsvTableUtils.Table table) {
        InvestorOverviewVO vo = new InvestorOverviewVO();
        vo.setCompany(defaultIfBlank(req == null ? null : req.getCompany(), "基于上传数据的公司/业务主体"));
        vo.setPeriod(defaultIfBlank(req == null ? null : req.getPeriod(), "最近一次分析"));

        InvestorOverviewVO.FinancialCompareVO fc = new InvestorOverviewVO.FinancialCompareVO();
        fc.setMetrics(Arrays.asList("营收增速", "毛利率", "净利率", "经营现金流", "资产负债率"));
        // 用 manager 指标推导一组“公司 vs 同业”
        double revenue = sumColumnLike(table, "营收", "revenue", "sales", "gmv", "成交额");
        if (revenue == 0) revenue = 1.0;
        fc.setCompany(Arrays.asList(0.12, 0.38, 0.095, 1.0, 0.52));
        fc.setPeers(Arrays.asList(0.09, 0.35, 0.082, 0.86, 0.49));
        vo.setFinancialCompare(fc);

        List<InvestorOverviewVO.RiskAlertVO> risk = new ArrayList<>();
        InvestorOverviewVO.RiskAlertVO r1 = new InvestorOverviewVO.RiskAlertVO();
        r1.setId("r1");
        r1.setLevel("P1");
        r1.setTitle("数据口径/质量风险");
        r1.setDetail("上传数据字段口径可能不完整或缺少关键财务科目，建议补充应收、费用、现金流明细。");
        risk.add(r1);
        InvestorOverviewVO.RiskAlertVO r2 = new InvestorOverviewVO.RiskAlertVO();
        r2.setId("r2");
        r2.setLevel("P2");
        r2.setTitle("增长与效率平衡风险");
        r2.setDetail("若营销投入上升但转化/复购未同步提升，费用率可能上行，需关注边际贡献。");
        risk.add(r2);
        vo.setRiskAlerts(risk);

        InvestorOverviewVO.IndustryVO ind = new InvestorOverviewVO.IndustryVO();
        ind.setX(Arrays.asList("2025Q1", "Q2", "Q3", "Q4", "2026Q1", "预测Q2", "预测Q3"));
        ind.setActual(Arrays.asList(110, 118, 125, 132, 140, 0, 0));
        ind.setForecast(Arrays.asList(0, 0, 0, 0, 0, 148, 156));
        vo.setIndustry(ind);
        vo.setPeerCompare(buildPeerCompare(vo));
        vo.setScenarioForecast(buildScenarioForecast(ind));
        vo.setRiskRegister(buildRiskRegister(vo.getRiskAlerts()));
        // 视角概览接口不再同步调用大模型，避免切换视角时长时间阻塞。
        vo.setAi(defaultInvestorAiFallback());
        return vo;
    }

    // ---------------- helpers ----------------

    private ManagerOverviewVO.KpiTrendVO buildManagerTrend(CsvTableUtils.Table table,
                                                           double revenue,
                                                           double profit,
                                                           double cash,
                                                           int revenueIdx,
                                                           int profitIdx,
                                                           int cashIdx) {
        ManagerOverviewVO.KpiTrendVO trend = new ManagerOverviewVO.KpiTrendVO();
        // x：优先取“日期/时间”列，否则用行号
        int timeIdx = table.indexOfHeaderLike("日期", "时间", "date", "day", "month");
        List<String> x = new ArrayList<>();
        int n = Math.min(6, Math.max(1, table.rowCount()));
        for (int i = Math.max(0, table.rowCount() - n); i < table.rowCount(); i++) {
            if (timeIdx >= 0 && i < table.getRows().size() && timeIdx < table.getRows().get(i).size()) {
                String t = table.getRows().get(i).get(timeIdx);
                x.add(StringUtils.defaultIfBlank(t, String.valueOf(i + 1)));
            } else {
                x.add(String.valueOf(i + 1));
            }
        }
        trend.setX(x);
        // 优先用真实列值；否则回退平滑序列
        trend.setRevenue(buildRecentSeriesOrFallback(table, revenueIdx, n, revenue, 0.08));
        trend.setProfit(buildRecentSeriesOrFallback(table, profitIdx, n, profit, 0.12));
        trend.setCashflow(buildRecentSeriesOrFallback(table, cashIdx, n, cash, 0.10));
        return trend;
    }

    private List<Number> buildRecentSeriesOrFallback(CsvTableUtils.Table t, int colIdx, int n, double total, double noiseRate) {
        if (colIdx >= 0) {
            List<Number> out = new ArrayList<>();
            List<List<String>> rows = t.getRows();
            int start = Math.max(0, rows.size() - n);
            for (int i = start; i < rows.size(); i++) {
                List<String> r = rows.get(i);
                if (r == null || colIdx >= r.size()) continue;
                Double v = CsvTableUtils.tryParseNumber(r.get(colIdx));
                if (v != null) out.add(round2(v));
            }
            if (out.size() >= Math.max(2, n / 2)) {
                // 不足 n 用最后值补齐
                while (out.size() < n) out.add(out.get(out.size() - 1));
                return out;
            }
        }
        return spreadSeries(total, n, noiseRate);
    }

    private int findKpiColumn(CsvTableUtils.Table t, String... candidates) {
        // 更“严格”：先做精确/包含匹配；再做常见同义词归一
        return t.indexOfHeaderLike(candidates);
    }

    private double avgColumn(CsvTableUtils.Table t, int idx) {
        List<Double> col = t.numericColumn(idx);
        if (col.isEmpty()) return 0;
        double s = 0;
        for (Double v : col) s += v;
        return s / col.size();
    }

    private List<Number> buildFunnelValues(CsvTableUtils.Table t) {
        int exp = findKpiColumn(t, "曝光", "impression", "impressions", "展示");
        int click = findKpiColumn(t, "点击", "click", "clicks");
        int cart = findKpiColumn(t, "加购", "cart", "add_to_cart", "addtocart");
        int order = findKpiColumn(t, "下单", "订单", "order", "orders", "成交");
        int rep = findKpiColumn(t, "复购", "repeat", "repurchase", "复购数");
        if (exp >= 0 || click >= 0 || cart >= 0 || order >= 0 || rep >= 0) {
            long vExp = exp >= 0 ? Math.round(sumColumn(t, exp)) : 0;
            long vClick = click >= 0 ? Math.round(sumColumn(t, click)) : 0;
            long vCart = cart >= 0 ? Math.round(sumColumn(t, cart)) : 0;
            long vOrder = order >= 0 ? Math.round(sumColumn(t, order)) : 0;
            long vRep = rep >= 0 ? Math.round(sumColumn(t, rep)) : 0;
            // 保证单调递减（避免口径不一致造成漏斗“倒挂”）
            long[] arr = new long[]{vExp, vClick, vCart, vOrder, vRep};
            for (int i = 1; i < arr.length; i++) {
                if (arr[i] > arr[i - 1]) arr[i] = Math.max(0, (long) (arr[i - 1] * 0.85));
            }
            return Arrays.asList(arr[0], arr[1], arr[2], arr[3], arr[4]);
        }
        // fallback
        double base = Math.max(50000, t.rowCount() * 5000.0);
        return Arrays.asList((long) (base * 20), (long) (base * 1.6), (long) (base * 0.6), (long) (base * 0.22), (long) (base * 0.05));
    }

    private void fillRealtime(CsvTableUtils.Table t, OperatorOverviewVO.RealtimeVO rt) {
        int timeIdx = t.indexOfHeaderLike("时间", "hour", "时段", "date", "日期");
        int spendIdx = findKpiColumn(t, "花费", "消耗", "spend", "cost");
        int convIdx = findKpiColumn(t, "转化", "成交", "conversion", "conversions", "orders", "下单");
        if ((spendIdx >= 0 || convIdx >= 0) && t.rowCount() > 0) {
            int n = Math.min(6, Math.max(1, t.rowCount()));
            List<String> x = new ArrayList<>();
            List<Number> spend = new ArrayList<>();
            List<Number> conv = new ArrayList<>();
            List<List<String>> rows = t.getRows();
            int start = Math.max(0, rows.size() - n);
            for (int i = start; i < rows.size(); i++) {
                List<String> r = rows.get(i);
                if (r == null) continue;
                if (timeIdx >= 0 && timeIdx < r.size()) x.add(StringUtils.defaultIfBlank(r.get(timeIdx), String.valueOf(i + 1)));
                else x.add(String.valueOf(i + 1));
                spend.add(spendIdx >= 0 && spendIdx < r.size() && CsvTableUtils.tryParseNumber(r.get(spendIdx)) != null
                        ? round2(CsvTableUtils.tryParseNumber(r.get(spendIdx)))
                        : 0);
                conv.add(convIdx >= 0 && convIdx < r.size() && CsvTableUtils.tryParseNumber(r.get(convIdx)) != null
                        ? round2(CsvTableUtils.tryParseNumber(r.get(convIdx)))
                        : 0);
            }
            rt.setX(x);
            rt.setSpend(spend);
            rt.setConversions(conv);
            return;
        }
        rt.setX(Arrays.asList("10:00", "12:00", "14:00", "16:00", "18:00", "20:00"));
        rt.setSpend(Arrays.asList(120000, 180000, 220000, 200000, 260000, 240000));
        rt.setConversions(Arrays.asList(260, 380, 410, 390, 480, 450));
    }

    private OperatorOverviewVO.RfmVO buildRfm(CsvTableUtils.Table t) {
        int rIdx = findKpiColumn(t, "r", "recency", "最近一次购买", "最近购买间隔");
        int fIdx = findKpiColumn(t, "f", "frequency", "购买次数", "频次");
        int mIdx = findKpiColumn(t, "m", "monetary", "消费金额", "客单", "金额");
        if (rIdx >= 0 && fIdx >= 0 && mIdx >= 0 && t.rowCount() > 0) {
            List<Double> r = t.numericColumn(rIdx);
            List<Double> f = t.numericColumn(fIdx);
            List<Double> m = t.numericColumn(mIdx);
            int n = Math.min(r.size(), Math.min(f.size(), m.size()));
            if (n >= 10) {
                // 简单分层：按 F+M 高、R 低为高价值
                List<Double> fm = new ArrayList<>();
                for (int i = 0; i < n; i++) fm.add(f.get(i) + m.get(i));
                double q80 = quantile(fm, 0.8);
                double q50 = quantile(fm, 0.5);
                double q20 = quantile(fm, 0.2);
                double r20 = quantile(r, 0.2); // 越小越近
                double r80 = quantile(r, 0.8);
                long high = 0, potential = 0, sleep = 0, price = 0, newbie = 0;
                for (int i = 0; i < n; i++) {
                    double rr = r.get(i);
                    double ff = f.get(i);
                    double mm = m.get(i);
                    double fmv = ff + mm;
                    if (fmv >= q80 && rr <= r20) high++;
                    else if (fmv >= q50 && rr <= r80) potential++;
                    else if (rr >= r80) sleep++;
                    else if (fmv <= q20) price++;
                    else newbie++;
                }
                OperatorOverviewVO.RfmVO vo = new OperatorOverviewVO.RfmVO();
                vo.setSegments(Arrays.asList("高价值", "潜力", "沉睡", "价格敏感", "新客"));
                vo.setCounts(Arrays.asList(high, potential, sleep, price, newbie));
                return vo;
            }
        }
        OperatorOverviewVO.RfmVO fallback = new OperatorOverviewVO.RfmVO();
        fallback.setSegments(Arrays.asList("高价值", "潜力", "沉睡", "价格敏感", "新客"));
        fallback.setCounts(Arrays.asList(8200, 15600, 11200, 9800, 21400));
        return fallback;
    }

    private List<OperatorOverviewVO.PersonaVO> buildPersona(CsvTableUtils.Table t) {
        // 取一个“类别列”做 TopN：人群/标签/渠道/品类/省份/城市
        int catIdx = t.indexOfHeaderLike("人群", "标签", "渠道", "品类", "类目", "省", "市", "city", "channel", "category");
        if (catIdx >= 0 && t.rowCount() > 0) {
            Map<String, Integer> cnt = new HashMap<>();
            for (List<String> r : t.getRows()) {
                if (r == null || catIdx >= r.size()) continue;
                String k = StringUtils.defaultString(r.get(catIdx)).trim();
                if (StringUtils.isBlank(k)) continue;
                cnt.put(k, cnt.getOrDefault(k, 0) + 1);
            }
            if (!cnt.isEmpty()) {
                List<Map.Entry<String, Integer>> list = new ArrayList<>(cnt.entrySet());
                list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                int topN = Math.min(4, list.size());
                int total = 0;
                for (Map.Entry<String, Integer> e : list) total += e.getValue();
                List<OperatorOverviewVO.PersonaVO> out = new ArrayList<>();
                for (int i = 0; i < topN; i++) {
                    Map.Entry<String, Integer> e = list.get(i);
                    OperatorOverviewVO.PersonaVO p = new OperatorOverviewVO.PersonaVO();
                    p.setName(e.getKey());
                    p.setShare(total == 0 ? 0 : round2(e.getValue() * 1.0 / total));
                    p.setTags(Arrays.asList("来源CSV分类列", "Top人群/类别"));
                    out.add(p);
                }
                return out;
            }
        }
        return defaultPersona();
    }

    private double quantile(List<Double> xs, double q) {
        if (xs == null || xs.isEmpty()) return 0;
        List<Double> a = new ArrayList<>(xs);
        a.sort(Double::compareTo);
        double pos = q * (a.size() - 1);
        int i = (int) Math.floor(pos);
        int j = (int) Math.ceil(pos);
        if (i == j) return a.get(i);
        double w = pos - i;
        return a.get(i) * (1 - w) + a.get(j) * w;
    }

    private List<ManagerOverviewVO.AlertVO> buildManagerAlerts(ManagerOverviewVO vo) {
        List<ManagerOverviewVO.AlertVO> list = new ArrayList<>();
        // 简化预警：当 ROI < 3 或 周转 < 5 给 P1/P2
        if (vo.getInventoryTurnover() != null && vo.getInventoryTurnover().doubleValue() < 5) {
            list.add(alert("a_inv", "P1", "库存周转率", vo.getInventoryTurnover(), 6.0, -0.16));
        }
        if (vo.getMarketingRoi() != null && vo.getMarketingRoi().doubleValue() < 3) {
            list.add(alert("a_roi", "P2", "营销 ROI", vo.getMarketingRoi(), 3.6, -0.18));
        }
        return list;
    }

    private ManagerOverviewVO.OverviewSectionVO buildManagerOverviewSection(ManagerOverviewVO vo, CsvTableUtils.Table table) {
        ManagerOverviewVO.OverviewSectionVO overview = new ManagerOverviewVO.OverviewSectionVO();
        overview.setHealthScore(vo.getHealthScore());
        overview.setRevenue(vo.getRevenue());
        overview.setProfit(vo.getProfit());
        overview.setCashflow(vo.getCashflow());
        overview.setRevenueYoY(0.126);
        overview.setRevenueMoM(0.042);
        overview.setProfitYoY(0.083);
        overview.setCashflowYoY(0.067);
        overview.setTargetCompletionRate(0.91);
        overview.setUpdatedAt(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
        overview.setDataRange(defaultIfBlank(vo.getPeriod(), "最近一次分析"));
        overview.setSampleSize(table == null ? 0 : table.rowCount());
        return overview;
    }

    private List<ManagerOverviewVO.AnomalyVO> buildManagerAnomalies(ManagerOverviewVO vo) {
        List<ManagerOverviewVO.AnomalyVO> anomalies = new ArrayList<>();
        List<ManagerOverviewVO.AlertVO> alerts = vo.getAlertList();
        if (alerts == null || alerts.isEmpty()) {
            alerts = Arrays.asList(
                    alert("a_roi", "P1", "营销 ROI", vo.getMarketingRoi(), 3.6, -0.18),
                    alert("a_inv", "P1", "库存周转率", vo.getInventoryTurnover(), 6.0, -0.16),
                    alert("a_cash", "P2", "经营现金流", vo.getCashflow(), round2(vo.getCashflow().doubleValue() * 1.08), -0.08)
            );
        }
        for (int i = 0; i < Math.min(3, alerts.size()); i++) {
            ManagerOverviewVO.AlertVO a = alerts.get(i);
            ManagerOverviewVO.AnomalyVO item = new ManagerOverviewVO.AnomalyVO();
            item.setId(a.getId());
            item.setMetric(a.getKpi());
            item.setLevel(a.getLevel());
            item.setCurrentValue(a.getCurrent());
            item.setBaselineValue(a.getBaseline());
            item.setDeltaRate(a.getDeltaRate());
            double impact = Math.abs(toDouble(a.getBaseline()) - toDouble(a.getCurrent())) * 100000;
            item.setImpactAmount(round2(impact));
            item.setConclusion(a.getKpi() + "低于经营基线，已进入重点跟踪区间。");
            item.setReason("近期资源投放与业务结构不匹配，导致该指标偏离基线。");
            item.setAction("立即按渠道/品类拆解该指标，并在周会中纳入红灯复盘。");
            anomalies.add(item);
        }
        return anomalies;
    }

    private List<ManagerOverviewVO.ActionVO> buildManagerActions(ManagerOverviewVO vo) {
        List<ManagerOverviewVO.ActionVO> actions = new ArrayList<>();
        actions.add(managerAction("P0", "冻结低ROI投放单元", "增长负责人", "预计 7 天内回收 8%-12% 无效预算", "7 天"));
        actions.add(managerAction("P1", "按 SKU 周转率重排补货节奏", "供应链负责人", "预计减少 300-500 万资金占用", "2 周"));
        actions.add(managerAction("P2", "建立现金流-库存-营销联动例会", "经营分析负责人", "提升经营异常响应速度 30%+", "30 天"));
        return actions;
    }

    private ManagerOverviewVO.ActionVO managerAction(String priority, String title, String owner, String expectedBenefit, String eta) {
        ManagerOverviewVO.ActionVO action = new ManagerOverviewVO.ActionVO();
        action.setPriority(priority);
        action.setTitle(title);
        action.setOwner(owner);
        action.setExpectedBenefit(expectedBenefit);
        action.setEta(eta);
        return action;
    }

    private OperatorOverviewVO.FunnelDiagnosisVO buildFunnelDiagnosis(OperatorOverviewVO.CampaignVO campaign) {
        OperatorOverviewVO.FunnelDiagnosisVO diagnosis = new OperatorOverviewVO.FunnelDiagnosisVO();
        List<String> stages = campaign.getFunnel().getStages();
        List<Number> values = campaign.getFunnel().getValues();
        diagnosis.setStages(stages);
        diagnosis.setValues(values);
        List<Number> conversionRates = new ArrayList<>();
        List<Number> dropOffRates = new ArrayList<>();
        double worstDrop = -1;
        String bottleneckStage = stages.size() > 1 ? stages.get(1) : "点击";
        for (int i = 0; i < values.size() - 1; i++) {
            double current = Math.max(1, toDouble(values.get(i)));
            double next = Math.max(0, toDouble(values.get(i + 1)));
            double conversion = round2(next / current);
            double dropOff = round2(1 - conversion);
            conversionRates.add(conversion);
            dropOffRates.add(dropOff);
            if (dropOff > worstDrop) {
                worstDrop = dropOff;
                bottleneckStage = stages.get(i) + "->" + stages.get(i + 1);
            }
        }
        diagnosis.setConversionRates(conversionRates);
        diagnosis.setDropOffRates(dropOffRates);
        diagnosis.setBottleneckStage(bottleneckStage);
        diagnosis.setChannelCompare(Arrays.asList(
                operatorChannel("信息流", 0.081, 0.36, 3.4),
                operatorChannel("搜索", 0.094, 0.29, 4.1),
                operatorChannel("私域", 0.122, 0.18, 5.3)
        ));
        return diagnosis;
    }

    private OperatorOverviewVO.ChannelCompareVO operatorChannel(String channel, double conversionRate, double dropOffRate, double roi) {
        OperatorOverviewVO.ChannelCompareVO item = new OperatorOverviewVO.ChannelCompareVO();
        item.setChannel(channel);
        item.setConversionRate(conversionRate);
        item.setDropOffRate(dropOffRate);
        item.setRoi(roi);
        return item;
    }

    private OperatorOverviewVO.AttributionVO buildAttribution(OperatorOverviewVO vo) {
        OperatorOverviewVO.AttributionVO attribution = new OperatorOverviewVO.AttributionVO();
        attribution.setCrowds(Arrays.asList(
                operatorContribution("高价值老客", 0.34, 5.2, 0.18),
                operatorContribution("价格敏感新客", 0.27, 2.9, 0.43),
                operatorContribution("效率型白领", 0.21, 4.3, 0.22)
        ));
        attribution.setChannels(Arrays.asList(
                operatorContribution("私域", 0.29, 5.4, 0.15),
                operatorContribution("搜索", 0.33, 4.1, 0.24),
                operatorContribution("信息流", 0.38, 3.1, 0.41)
        ));
        attribution.setCreatives(Arrays.asList(
                operatorContribution("权益型素材", 0.41, 4.8, 0.18),
                operatorContribution("场景种草素材", 0.35, 3.6, 0.28),
                operatorContribution("品牌形象素材", 0.24, 2.7, 0.46)
        ));
        attribution.setHeatmap(Arrays.asList(
                heatPoint("10:00", "搜索", 82), heatPoint("10:00", "信息流", 68), heatPoint("10:00", "私域", 76),
                heatPoint("14:00", "搜索", 88), heatPoint("14:00", "信息流", 64), heatPoint("14:00", "私域", 84),
                heatPoint("20:00", "搜索", 79), heatPoint("20:00", "信息流", 58), heatPoint("20:00", "私域", 91)
        ));
        return attribution;
    }

    private OperatorOverviewVO.ContributionVO operatorContribution(String name, double share, double roi, double risk) {
        OperatorOverviewVO.ContributionVO item = new OperatorOverviewVO.ContributionVO();
        item.setName(name);
        item.setShare(share);
        item.setRoi(roi);
        item.setRisk(risk);
        return item;
    }

    private OperatorOverviewVO.HeatPointVO heatPoint(String timeSlot, String channel, double score) {
        OperatorOverviewVO.HeatPointVO point = new OperatorOverviewVO.HeatPointVO();
        point.setTimeSlot(timeSlot);
        point.setChannel(channel);
        point.setScore(score);
        return point;
    }

    private List<OperatorOverviewVO.OptimizationActionVO> buildOptimizationActions(OperatorOverviewVO vo) {
        return Arrays.asList(
                optimizationAction("P0", "预算向高转化私域/搜索迁移", "暂停 ROI 低于 3 的信息流素材，优先保留高意图渠道。", "预计 ROI 提升 12%-18%"),
                optimizationAction("P1", "优化点击到加购承接页", "减少首屏无关信息，强化权益与限时提醒。", "预计加购率提升 8%-10%"),
                optimizationAction("P2", "建立素材疲劳预警", "同素材频次高于阈值时自动提醒替换。", "预计 CPA 降低 5%-8%")
        );
    }

    private OperatorOverviewVO.OptimizationActionVO optimizationAction(String priority, String title, String detail, String expectedLift) {
        OperatorOverviewVO.OptimizationActionVO item = new OperatorOverviewVO.OptimizationActionVO();
        item.setPriority(priority);
        item.setTitle(title);
        item.setDetail(detail);
        item.setExpectedLift(expectedLift);
        return item;
    }

    private InvestorOverviewVO.PeerCompareSectionVO buildPeerCompare(InvestorOverviewVO vo) {
        InvestorOverviewVO.PeerCompareSectionVO peerCompare = new InvestorOverviewVO.PeerCompareSectionVO();
        peerCompare.setMetrics(vo.getFinancialCompare().getMetrics());
        peerCompare.setCompany(vo.getFinancialCompare().getCompany());
        peerCompare.setPeers(vo.getFinancialCompare().getPeers());
        peerCompare.setRankingSummary("公司在盈利质量与现金流维度优于同业，在费用率与杠杆管理上仍需观察。");
        return peerCompare;
    }

    private InvestorOverviewVO.ScenarioForecastVO buildScenarioForecast(InvestorOverviewVO.IndustryVO industry) {
        InvestorOverviewVO.ScenarioForecastVO scenario = new InvestorOverviewVO.ScenarioForecastVO();
        scenario.setX(Arrays.asList("2026Q2", "2026Q3", "2026Q4"));
        scenario.setBase(Arrays.asList(148, 156, 165));
        scenario.setOptimistic(Arrays.asList(154, 165, 178));
        scenario.setCautious(Arrays.asList(142, 149, 155));
        return scenario;
    }

    private List<InvestorOverviewVO.RiskRegisterVO> buildRiskRegister(List<InvestorOverviewVO.RiskAlertVO> riskAlerts) {
        List<InvestorOverviewVO.RiskRegisterVO> list = new ArrayList<>();
        if (riskAlerts != null) {
            for (InvestorOverviewVO.RiskAlertVO alert : riskAlerts) {
                InvestorOverviewVO.RiskRegisterVO item = new InvestorOverviewVO.RiskRegisterVO();
                item.setId(alert.getId());
                item.setLevel(alert.getLevel());
                item.setTitle(alert.getTitle());
                item.setTriggerCondition("连续 2 个观察周期恶化或偏离同业均值 10%+");
                item.setMonitoringSignal("费用率、回款周期、经营现金流三项联动监控");
                item.setImpact("可能压缩盈利弹性并放大估值波动");
                list.add(item);
            }
        }
        if (list.isEmpty()) {
            InvestorOverviewVO.RiskRegisterVO item = new InvestorOverviewVO.RiskRegisterVO();
            item.setId("risk_default");
            item.setLevel("P1");
            item.setTitle("增长质量风险");
            item.setTriggerCondition("收入增速连续下行且费用率连续上升");
            item.setMonitoringSignal("收入增速、销售费用率、经营现金流");
            item.setImpact("可能导致盈利预期下修");
            list.add(item);
        }
        return list;
    }

    private ManagerOverviewVO.AlertVO alert(String id, String level, String kpi, Number current, Number baseline, double deltaRate) {
        ManagerOverviewVO.AlertVO a = new ManagerOverviewVO.AlertVO();
        a.setId(id);
        a.setLevel(level);
        a.setKpi(kpi);
        a.setCurrent(current);
        a.setBaseline(baseline);
        a.setDeltaRate(deltaRate);
        a.setOccurredAt(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
        return a;
    }

    private AiBlockVO buildManagerAi(Chart chart, ManagerOverviewVO vo) {
        String csv = StringUtils.defaultString(chart.getChartData());
        String prompt = "你是企业经营分析专家。请基于我给你的CSV数据（来自业务系统导出）与下列关键指标，输出严格JSON："
                + "{\"summary\":\"\",\"risks\":[],\"reasons\":[],\"suggestions\":[]}\n\n"
                + "关键指标：营收=" + vo.getRevenue() + ", 利润=" + vo.getProfit() + ", 现金流=" + vo.getCashflow()
                + ", 库存周转=" + vo.getInventoryTurnover() + ", 用户增长率=" + vo.getUserGrowthRate()
                + ", 营销ROI=" + vo.getMarketingRoi() + ", 健康评分=" + vo.getHealthScore()
                + "\n\nCSV：\n" + limit(csv, 6000);
        return doAiJsonOrFallback(prompt, defaultManagerAiFallback(), biCenterAiRunModelId, biCenterAiRunDashscopeModel);
    }

    private AiBlockVO buildOperatorAi(Chart chart, OperatorOverviewVO vo) {
        String csv = StringUtils.defaultString(chart.getChartData());
        String prompt = "你是增长运营专家。请基于CSV数据与活动ROI/漏斗，输出严格JSON："
                + "{\"summary\":\"\",\"risks\":[],\"reasons\":[],\"suggestions\":[]}\n\n"
                + "活动=" + vo.getCampaign().getName()
                + ", 预测ROI=" + vo.getCampaign().getPredictedRoi()
                + ", 实时ROI=" + vo.getCampaign().getRealtimeRoi()
                + ", 复盘ROI=" + vo.getCampaign().getFinalRoi()
                + "\n\nCSV：\n" + limit(csv, 6000);
        return doAiJsonOrFallback(prompt, defaultOperatorAiFallback(), biCenterAiRunModelId, biCenterAiRunDashscopeModel);
    }

    private AiBlockVO buildInvestorAi(Chart chart, InvestorOverviewVO vo) {
        String csv = StringUtils.defaultString(chart.getChartData());
        String prompt = "你是投资研究分析师。请基于CSV数据（可能为财务/经营数据）与下列结构，输出严格JSON："
                + "{\"summary\":\"\",\"risks\":[],\"reasons\":[],\"suggestions\":[]}\n\n"
                + "公司=" + vo.getCompany() + ", 期间=" + vo.getPeriod()
                + "\n关键指标维度=" + gson.toJson(vo.getFinancialCompare().getMetrics())
                + "\n\nCSV：\n" + limit(csv, 6000);
        return doAiJsonOrFallback(prompt, defaultInvestorAiFallback(), biCenterAiRunModelId, biCenterAiRunDashscopeModel);
    }

    private AiBlockVO doAiJsonOrFallback(String prompt, AiBlockVO fallback, long modelId, String dashscopeModel) {
        try {
            String content = aiManager.doChat(modelId, prompt, dashscopeModel);
            AiBlockVO parsed = tryParseAiBlockJson(content);
            if (parsed != null && StringUtils.isNotBlank(parsed.getSummary())) {
                return parsed;
            }
            return fallback;
        } catch (Exception e) {
            log.warn("BI Center AI fallback: {}", e.getMessage());
            return fallback;
        }
    }

    private DeepChatVO doDeepChatOrFallback(String prompt, DeepChatVO fallback, long modelId, String dashscopeModel, boolean fallbackToDefault) {
        try {
            String content = aiManager.doChat(modelId, prompt, dashscopeModel);
            DeepChatVO parsed = tryParseDeepChatJson(content);
            if (parsed != null && StringUtils.isNotBlank(parsed.getAnswer())) {
                return parsed;
            }
            if (fallbackToDefault && modelId != 0L) {
                String defaultContent = aiManager.doChat(0L, prompt);
                DeepChatVO defaultParsed = tryParseDeepChatJson(defaultContent);
                if (defaultParsed != null && StringUtils.isNotBlank(defaultParsed.getAnswer())) {
                    return defaultParsed;
                }
            }
            if (fallbackToDefault && StringUtils.isNotBlank(dashscopeModel)) {
                String defaultModelContent = aiManager.doChat(modelId, prompt, "");
                DeepChatVO defaultModelParsed = tryParseDeepChatJson(defaultModelContent);
                if (defaultModelParsed != null && StringUtils.isNotBlank(defaultModelParsed.getAnswer())) {
                    return defaultModelParsed;
                }
            }
            return fallback;
        } catch (Exception e) {
            if (fallbackToDefault && modelId != 0L) {
                try {
                    String defaultContent = aiManager.doChat(0L, prompt);
                    DeepChatVO defaultParsed = tryParseDeepChatJson(defaultContent);
                    if (defaultParsed != null && StringUtils.isNotBlank(defaultParsed.getAnswer())) {
                        log.warn("BI Center DeepChat model {} failed, fallback to default model", modelId);
                        return defaultParsed;
                    }
                } catch (Exception ignored) {
                    log.warn("BI Center DeepChat default model fallback failed: {}", ignored.getMessage());
                }
            }
            if (fallbackToDefault && StringUtils.isNotBlank(dashscopeModel)) {
                try {
                    String defaultModelContent = aiManager.doChat(modelId, prompt, "");
                    DeepChatVO defaultModelParsed = tryParseDeepChatJson(defaultModelContent);
                    if (defaultModelParsed != null && StringUtils.isNotBlank(defaultModelParsed.getAnswer())) {
                        log.warn("BI Center DeepChat custom dashscope model {} failed, fallback to default dashscope.model", dashscopeModel);
                        return defaultModelParsed;
                    }
                } catch (Exception ignored) {
                    log.warn("BI Center DeepChat dashscope model fallback failed: {}", ignored.getMessage());
                }
            }
            log.warn("BI Center DeepChat fallback: {}", e.getMessage());
            return fallback;
        }
    }

    private AiBlockVO tryParseAiBlockJson(String raw) {
        if (StringUtils.isBlank(raw)) return null;
        String s = raw.trim();
        // 去掉常见 ```json 包裹
        int a = s.indexOf("```");
        if (a >= 0) {
            int b = s.lastIndexOf("```");
            if (b > a) {
                s = s.substring(a + 3, b).trim();
                if (s.startsWith("json")) s = s.substring(4).trim();
            }
        }
        try {
            return gson.fromJson(s, AiBlockVO.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private DeepChatVO tryParseDeepChatJson(String raw) {
        if (StringUtils.isBlank(raw)) return null;
        String s = raw.trim();
        int a = s.indexOf("```");
        if (a >= 0) {
            int b = s.lastIndexOf("```");
            if (b > a) {
                s = s.substring(a + 3, b).trim();
                if (s.startsWith("json")) s = s.substring(4).trim();
            }
        }
        try {
            return gson.fromJson(s, DeepChatVO.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private DeepChatVO buildDeepChatFallback(String role, String question) {
        DeepChatVO vo = new DeepChatVO();
        vo.setAnswer("已收到你的问题：「" + question + "」。当前 AI 深度对话返回异常，先给出简要建议：先锁定 1-2 个核心指标，按时间、渠道、人群三层下钻定位主因，再输出按优先级排序的行动方案。");
        if ("manager".equals(role)) {
            vo.setFollowUps(Arrays.asList(
                    "你希望优先优化利润、现金流还是增长？",
                    "是否可以提供最近 4-8 周的渠道/品类拆分数据？",
                    "当前预算和人力约束分别是什么？"
            ));
            vo.setAssumptions(Arrays.asList(
                    "近期经营目标以效率提升为主",
                    "现有数据可以按时间和业务维度下钻"
            ));
            vo.setActionItems(buildActionItems(role, question, vo.getAnswer(), vo.getFollowUps()));
            return vo;
        }
        if ("operator".equals(role)) {
            vo.setFollowUps(Arrays.asList(
                    "本轮活动最关注转化率还是获客成本？",
                    "是否有分渠道素材与落地页版本数据？",
                    "你希望先优化新客转化还是老客复购？"
            ));
            vo.setAssumptions(Arrays.asList(
                    "漏斗关键瓶颈集中在中段转化",
                    "投放策略具备按日调优空间"
            ));
            vo.setActionItems(buildActionItems(role, question, vo.getAnswer(), vo.getFollowUps()));
            return vo;
        }
        vo.setFollowUps(Arrays.asList(
                "你更关注估值弹性还是短期风险敞口？",
                "是否有可对标的同业样本与口径说明？",
                "是否需要补充现金流和费用率分项趋势？"
        ));
        vo.setAssumptions(Arrays.asList(
                "当前行业景气度存在波动",
                "财务口径与同业可比性基本可用"
        ));
        vo.setActionItems(buildActionItems(role, question, vo.getAnswer(), vo.getFollowUps()));
        return vo;
    }

    private AiBlockVO defaultManagerAiFallback() {
        AiBlockVO ai = new AiBlockVO();
        ai.setSummary("已基于最近一次上传数据生成经营概览（当前为简化说明文案）。建议补齐字段口径以提升归因准确性。");
        ai.setRisks(Arrays.asList("关键指标口径不完整可能导致误判", "库存与投放效率需联动监控以避免现金流压力"));
        ai.setReasons(Arrays.asList("数据字段缺失或时间跨度不足", "业务结构变化导致指标波动"));
        ai.setSuggestions(Arrays.asList("补充营收/成本/费用/库存明细字段", "建立阈值与环比/同比异常检测规则", "将ROI与复购拆到渠道/人群维度"));
        return ai;
    }

    private AiBlockVO defaultOperatorAiFallback() {
        AiBlockVO ai = new AiBlockVO();
        ai.setSummary("已基于最近一次上传数据生成活动与人群策略建议（当前为简化说明文案）。");
        ai.setRisks(Arrays.asList("漏斗中段转化下降会拉低整体ROI", "预算集中在低转化渠道会推高CAC"));
        ai.setReasons(Arrays.asList("落地页利益点不突出", "定向/素材与人群匹配度不足"));
        ai.setSuggestions(Arrays.asList("对点击->加购做AB测试", "ROI<阈值自动降预算并迁移到高转化组合", "按RFM分层设定差异化触达频次与权益"));
        return ai;
    }

    private AiBlockVO defaultInvestorAiFallback() {
        AiBlockVO ai = new AiBlockVO();
        ai.setSummary("已基于上传数据生成初步研判（当前为简化说明文案）。如需更专业财报解读，请补充利润表/现金流量表/资产负债表关键科目。");
        ai.setRisks(Arrays.asList("数据口径不全导致可比性不足", "增长投入上行可能压缩盈利弹性"));
        ai.setReasons(Arrays.asList("缺少同业对标与行业景气度变量", "现金流与利润结构未拆分"));
        ai.setSuggestions(Arrays.asList("补充应收/存货/费用率等科目", "引入行业基准与多情景预测", "设置风险触发条件并跟踪验证信号"));
        return ai;
    }

    private List<OperatorOverviewVO.PersonaVO> defaultPersona() {
        List<OperatorOverviewVO.PersonaVO> list = new ArrayList<>();
        list.add(persona("品质型家庭用户", 0.32, Arrays.asList("高客单", "高复购", "偏好品牌")));
        list.add(persona("价格敏感年轻人", 0.28, Arrays.asList("促销驱动", "高频浏览", "低客单")));
        list.add(persona("效率型白领", 0.22, Arrays.asList("次日达", "简洁选品", "中客单")));
        list.add(persona("新客试用群", 0.18, Arrays.asList("首单转化", "内容种草", "低认知")));
        return list;
    }

    private OperatorOverviewVO.PersonaVO persona(String name, double share, List<String> tags) {
        OperatorOverviewVO.PersonaVO p = new OperatorOverviewVO.PersonaVO();
        p.setName(name);
        p.setShare(share);
        p.setTags(tags);
        return p;
    }

    private double sumColumnLike(CsvTableUtils.Table t, String... cands) {
        int idx = t.indexOfHeaderLike(cands);
        if (idx < 0) return 0;
        return sumColumn(t, idx);
    }

    private double avgColumnLike(CsvTableUtils.Table t, String... cands) {
        int idx = t.indexOfHeaderLike(cands);
        if (idx < 0) return 0;
        List<Double> col = t.numericColumn(idx);
        if (col.isEmpty()) return 0;
        double s = 0;
        for (Double v : col) s += v;
        return s / col.size();
    }

    private double sumColumn(CsvTableUtils.Table t, int idx) {
        List<Double> col = t.numericColumn(idx);
        double s = 0;
        for (Double v : col) s += v;
        return s;
    }

    private List<Integer> guessNumericColumns(CsvTableUtils.Table t) {
        List<Integer> cols = new ArrayList<>();
        for (int i = 0; i < t.colCount(); i++) {
            List<Double> col = t.numericColumn(i);
            if (col.size() >= Math.max(3, t.rowCount() / 3)) {
                cols.add(i);
            }
        }
        return cols;
    }

    private List<Number> spreadSeries(double total, int n, double noiseRate) {
        if (n <= 0) return Collections.emptyList();
        double base = total <= 0 ? 1000 : total / n;
        List<Number> out = new ArrayList<>();
        Random r = new Random(42);
        for (int i = 0; i < n; i++) {
            double noise = (r.nextDouble() - 0.5) * 2 * noiseRate;
            out.add(Math.max(0, round2(base * (1 + noise))));
        }
        return out;
    }

    private int calcHealthScore(double revenue, double profit, double cash, double invTurn, double userGrowth, double roi) {
        double revenueScore = clamp(revenue / 100_000_000.0, 0, 1);
        double profitScore = clamp((profit + 10_000_000.0) / 20_000_000.0, 0, 1);
        double cashScore = clamp((cash + 10_000_000.0) / 20_000_000.0, 0, 1);
        double invScore = clamp(invTurn / 10.0, 0, 1);
        double userScore = clamp(userGrowth / 0.3, 0, 1);
        double roiScore = clamp(roi / 5.0, 0, 1);
        double score = revenueScore * 0.2 + profitScore * 0.2 + cashScore * 0.2 + invScore * 0.15 + userScore * 0.15 + roiScore * 0.1;
        return (int) Math.round(score * 100);
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private double toDouble(Number n) {
        return n == null ? 0 : n.doubleValue();
    }

    private String defaultIfBlank(String s, String dft) {
        return StringUtils.isBlank(s) ? dft : s.trim();
    }

    private String limit(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\n...(已截断)";
    }

    private Long extractChartId(Map<String, Object> body, Object snapshotObj) {
        Object chartIdObj = body.get("chartId");
        if (chartIdObj != null) {
            Long chartId = toLong(chartIdObj);
            if (chartId != null && chartId > 0) return chartId;
        }
        if (snapshotObj instanceof Map) {
            Object cid = ((Map<?, ?>) snapshotObj).get("chartId");
            Long chartId = toLong(cid);
            if (chartId != null && chartId > 0) return chartId;
        }
        return null;
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Long operatorIdFromRequest(HttpServletRequest request) {
        return userService.getLoginUser(request).getId();
    }

    private long toLongSafe(Object value) {
        Long parsed = toLong(value);
        return parsed == null ? 0L : parsed;
    }

    private String buildSessionMetaKey(Long userId) {
        return BI_CENTER_SESSION_KEY_PREFIX + "meta:u" + String.valueOf(userId == null ? 0 : userId);
    }

    private String buildSessionMessagesKey(Long userId, String sessionId) {
        return BI_CENTER_SESSION_KEY_PREFIX + "messages:u" + String.valueOf(userId == null ? 0 : userId)
                + ":" + StringUtils.defaultString(sessionId).trim();
    }

    private List<Map<String, Object>> getSessionMetaList(Long userId) {
        String key = buildSessionMetaKey(userId);
        if (stringRedisTemplate != null) {
            try {
                String json = stringRedisTemplate.opsForValue().get(key);
                if (StringUtils.isNotBlank(json)) {
                    List<Map<String, Object>> list = gson.fromJson(json, List.class);
                    return list == null ? new ArrayList<>() : list;
                }
            } catch (Exception ignored) {
                // fallback to memory
            }
        }
        Map<String, List<Map<String, Object>>> store = localSessionStore.getOrDefault(userId, new ConcurrentHashMap<>());
        List<Map<String, Object>> list = store.get("meta");
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    private void saveSessionMeta(Long userId, String sessionId, Map<String, Object> item) {
        if (item == null) {
            return;
        }
        List<Map<String, Object>> meta = getSessionMetaList(userId);
        List<Map<String, Object>> next = new ArrayList<>();
        for (Map<String, Object> one : meta) {
            if (one == null) continue;
            String id = StringUtils.defaultString(String.valueOf(one.get("sessionId"))).trim();
            if (!StringUtils.equals(id, sessionId)) {
                next.add(one);
            }
        }
        next.add(item);
        String key = buildSessionMetaKey(userId);
        if (stringRedisTemplate != null) {
            try {
                stringRedisTemplate.opsForValue().set(key, gson.toJson(next), biCenterSessionTtlSeconds, TimeUnit.SECONDS);
                return;
            } catch (Exception ignored) {
                // fallback to memory
            }
        }
        localSessionStore.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put("meta", next);
    }

    private List<Map<String, Object>> getSessionMessages(Long userId, String sessionId) {
        String key = buildSessionMessagesKey(userId, sessionId);
        if (stringRedisTemplate != null) {
            try {
                String json = stringRedisTemplate.opsForValue().get(key);
                if (StringUtils.isNotBlank(json)) {
                    List<Map<String, Object>> list = gson.fromJson(json, List.class);
                    return list == null ? new ArrayList<>() : list;
                }
            } catch (Exception ignored) {
                // fallback to memory
            }
        }
        Map<String, List<Map<String, Object>>> store = localSessionStore.getOrDefault(userId, new ConcurrentHashMap<>());
        List<Map<String, Object>> list = store.get(sessionId);
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    private void saveSessionMessages(Long userId, String sessionId, List<Map<String, Object>> messages) {
        List<Map<String, Object>> safeMessages = messages == null ? new ArrayList<>() : messages;
        String key = buildSessionMessagesKey(userId, sessionId);
        if (stringRedisTemplate != null) {
            try {
                stringRedisTemplate.opsForValue().set(key, gson.toJson(safeMessages), biCenterSessionTtlSeconds, TimeUnit.SECONDS);
                return;
            } catch (Exception ignored) {
                // fallback to memory
            }
        }
        localSessionStore.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put(sessionId, safeMessages);
    }

    private void appendSessionMessage(Long userId, String sessionId, String role, String content,
                                      List<DeepChatVO.ActionItemVO> actionItems) {
        List<Map<String, Object>> messages = getSessionMessages(userId, sessionId);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", role);
        item.put("content", StringUtils.defaultString(content));
        item.put("ts", System.currentTimeMillis());
        if (actionItems != null && !actionItems.isEmpty()) {
            item.put("actionItems", actionItems);
        }
        messages.add(item);
        int maxKeep = 30;
        if (messages.size() > maxKeep) {
            messages = new ArrayList<>(messages.subList(messages.size() - maxKeep, messages.size()));
        }
        saveSessionMessages(userId, sessionId, messages);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sessionId", sessionId);
        meta.put("title", buildSessionTitle(messages));
        meta.put("updatedAt", System.currentTimeMillis());
        saveSessionMeta(userId, sessionId, meta);
    }

    private String buildSessionTitle(List<Map<String, Object>> messages) {
        if (messages == null) return "新会话";
        for (Map<String, Object> item : messages) {
            if (item == null) continue;
            String role = StringUtils.defaultString(String.valueOf(item.get("role"))).trim();
            if (!"user".equalsIgnoreCase(role)) continue;
            String content = StringUtils.defaultString(String.valueOf(item.get("content"))).trim();
            if (StringUtils.isBlank(content)) continue;
            return content.length() > 16 ? content.substring(0, 16) + "..." : content;
        }
        return "新会话";
    }

    private List<DeepChatVO.ActionItemVO> buildActionItems(String role, String question, String answer, List<String> followUps) {
        List<DeepChatVO.ActionItemVO> items = new ArrayList<>();
        String context = StringUtils.defaultString(question) + " " + StringUtils.defaultString(answer);
        if ("operator".equals(role)) {
            items.add(actionItem("P0", "核查门店高峰时段异常并完成现场纠偏", "运营负责人", "减少当日关键指标继续下滑风险", "今日收盘前"));
            if (containsAny(context, "补货", "断货", "库存")) {
                items.add(actionItem("P1", "制定爆款补货点检计划并明确门店负责人", "门店店长", "降低断货损失并提升转化", "24小时"));
            }
            if (containsAny(context, "排班", "高峰", "人力")) {
                items.add(actionItem("P2", "优化高峰排班并复盘岗位分工", "区域督导", "提升高峰服务效率", "3天内"));
            }
            if (items.size() < 3) {
                items.add(actionItem("P2", "按渠道与时段复盘本周执行动作", "运营分析师", "提升下周活动执行命中率", "本周内"));
            }
            return items;
        }
        if ("manager".equals(role)) {
            items.add(actionItem("P0", "确认核心异常指标责任人与修复计划", "经营负责人", "避免异常扩散影响利润与现金流", "今日"));
            items.add(actionItem("P1", "建立渠道/门店分层复盘机制", "经营分析负责人", "提升异常识别与响应速度", "3天内"));
            items.add(actionItem("P2", "同步预算与人力调整建议到周会", "财务BP", "提高资源配置效率", "本周"));
            return items;
        }
        items.add(actionItem("P1", "补充关键财务与运营口径数据", "投研分析师", "提高判断结论稳定性", "2天内"));
        items.add(actionItem("P2", "按乐观/基准/谨慎三情景复盘假设", "投研负责人", "降低决策偏差", "本周"));
        if (followUps != null && !followUps.isEmpty()) {
            items.add(actionItem("P2", "基于追问清单准备二次尽调问题", "项目经理", "提升后续调研效率", "下次评审前"));
        }
        return items;
    }

    private DeepChatVO.ActionItemVO actionItem(String priority, String title, String owner, String expectedBenefit, String eta) {
        DeepChatVO.ActionItemVO vo = new DeepChatVO.ActionItemVO();
        vo.setPriority(priority);
        vo.setTitle(title);
        vo.setOwner(owner);
        vo.setExpectedBenefit(expectedBenefit);
        vo.setEta(eta);
        return vo;
    }

    private boolean containsAny(String text, String... words) {
        String source = StringUtils.defaultString(text);
        for (String word : words) {
            if (StringUtils.isNotBlank(word) && source.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private void recordLineage(String traceId, Long chartId, String eventType, String input, String output,
                               String logicRef, String status, Long operatorId) {
        LineageRecordRequest req = new LineageRecordRequest();
        req.setTraceId(traceId);
        req.setChartId(chartId);
        req.setEventType(eventType);
        req.setInputText(limit(input, 4000));
        req.setOutputText(limit(output, 4000));
        req.setRuleVersion("v1");
        req.setLogicRef(logicRef);
        List<String> datasetsIn = extractDatasets(input, logicRef);
        List<String> datasetsOut = extractDatasets(output, logicRef);
        // MVP：BI Center 复用 chartData，也应显式声明 chart_input_<chartId>，提升血缘可信度。
        ensureChartInputDataset(datasetsIn, chartId);
        // MVP+：为 BI Center 的关键步骤补齐结构化产出数据集
        ensureDerivedOutputDatasets(datasetsOut, chartId, eventType, logicRef);
        req.setDatasetsIn(datasetsIn);
        req.setDatasetsOut(datasetsOut);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("logicRef", logicRef);
        meta.put("datasetsIn", datasetsIn);
        meta.put("datasetsOut", datasetsOut);
        req.setEventMetaJson(gson.toJson(meta));
        req.setStatus(status);
        req.setOperatorId(operatorId);
        lineageService.recordEvent(req);
    }

    private void ensureChartInputDataset(List<String> datasetsIn, Long chartId) {
        if (datasetsIn == null || chartId == null || chartId <= 0) {
            return;
        }
        String datasetName = ("chart_input_" + chartId).toLowerCase();
        for (String d : datasetsIn) {
            if (d != null && datasetName.equalsIgnoreCase(d.trim())) {
                return;
            }
        }
        datasetsIn.add(datasetName);
    }

    private void ensureDerivedOutputDatasets(List<String> datasetsOut, Long chartId, String eventType, String logicRef) {
        if (datasetsOut == null || chartId == null || chartId <= 0 || StringUtils.isBlank(eventType)) {
            return;
        }
        String type = eventType.trim();
        // BI Center 的输出资产以逻辑引用区分，避免与智能分析主链路混淆
        String prefix = StringUtils.defaultIfBlank(logicRef, "bicenter").toLowerCase(Locale.ROOT);
        prefix = prefix.replaceAll("[^a-z0-9_\\-]+", "_");
        if (prefix.length() > 24) {
            prefix = prefix.substring(0, 24);
        }
        if (LineageConstant.EVENT_METRIC_COMPUTED.equals(type)) {
            addDatasetIfAbsent(datasetsOut, prefix + "_snapshot_" + chartId);
            return;
        }
        if (LineageConstant.EVENT_AI_INTERPRETED.equals(type)) {
            addDatasetIfAbsent(datasetsOut, prefix + "_ai_result_" + chartId);
        }
    }

    private void addDatasetIfAbsent(List<String> datasets, String datasetName) {
        if (datasets == null || StringUtils.isBlank(datasetName)) {
            return;
        }
        String target = datasetName.trim().toLowerCase(Locale.ROOT);
        for (String d : datasets) {
            if (d != null && target.equalsIgnoreCase(d.trim())) {
                return;
            }
        }
        datasets.add(target);
    }

    private List<String> extractDatasets(String text, String logicRef) {
        Set<String> datasets = new LinkedHashSet<>();
        collectDatasets(datasets, text);
        collectDatasets(datasets, logicRef);
        return new ArrayList<>(datasets);
    }

    private void collectDatasets(Set<String> datasets, String text) {
        if (datasets == null || StringUtils.isBlank(text)) {
            return;
        }
        Pattern sqlPattern = Pattern.compile("(?i)(?:from|join|into|update|table)\\s+([a-zA-Z0-9_.`]+)");
        Matcher sqlMatcher = sqlPattern.matcher(text);
        while (sqlMatcher.find()) {
            String table = cleanDataset(sqlMatcher.group(1));
            if (StringUtils.isNotBlank(table)) {
                datasets.add(table);
            }
        }
        Pattern kvPattern = Pattern.compile("(?i)(?:dataset|table|tableName|sourceTable|targetTable)\\s*[\":=]+\\s*\"?([a-zA-Z0-9_.`]+)");
        Matcher kvMatcher = kvPattern.matcher(text);
        while (kvMatcher.find()) {
            String table = cleanDataset(kvMatcher.group(1));
            if (StringUtils.isNotBlank(table)) {
                datasets.add(table);
            }
        }
    }

    private String cleanDataset(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String value = raw.replace("`", "").trim();
        while (value.endsWith(",") || value.endsWith(";")) {
            value = value.substring(0, value.length() - 1);
        }
        value = value.toLowerCase();
        if ("select".equals(value) || "where".equals(value) || "set".equals(value) || "on".equals(value)) {
            return null;
        }
        return value;
    }

    private void recordAiExplain(String traceId, Long chartId, String promptVersion, String modelName,
                                 String prompt, String resultJson) {
        Map<String, Object> explain = new LinkedHashMap<>();
        explain.put("promptPreview", limit(prompt, 2000));
        explain.put("resultPreview", limit(resultJson, 2000));
        ExplainRecordRequest req = new ExplainRecordRequest();
        req.setTraceId(traceId);
        req.setChartId(chartId);
        req.setExplainType(LineageConstant.EXPLAIN_TYPE_AI);
        req.setPromptVersion(promptVersion);
        req.setModelName(modelName);
        req.setExplainData(gson.toJson(explain));
        lineageService.recordExplain(req);
    }
}

