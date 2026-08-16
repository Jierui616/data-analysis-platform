package com.yupi.springbootinit.controller;

import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yupi.springbootinit.annotation.AuthCheck;
import com.yupi.springbootinit.bizmq.BiMessageProducer;
import com.yupi.springbootinit.common.BaseResponse;
import com.yupi.springbootinit.common.DeleteRequest;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.common.ResultUtils;
import com.yupi.springbootinit.constant.CommonConstant;
import com.yupi.springbootinit.constant.LineageConstant;
import com.yupi.springbootinit.constant.UserConstant;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.exception.ThrowUtils;
import com.yupi.springbootinit.manager.AiManager;
import com.yupi.springbootinit.manager.RedisLimiterManager;
import com.yupi.springbootinit.mapper.ChartActionMapper;
import com.yupi.springbootinit.model.dto.chart.*;
import com.yupi.springbootinit.model.entity.ChartAction;
import com.yupi.springbootinit.model.dto.lineage.ExplainRecordRequest;
import com.yupi.springbootinit.model.dto.lineage.LineageRecordRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.model.vo.BiResponse;
import com.yupi.springbootinit.config.bigdata.BigDataProperties;
import com.yupi.springbootinit.service.ChartGenerationService;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.service.LineageService;
import com.yupi.springbootinit.service.UserService;
import com.yupi.springbootinit.service.bigdata.BigDataEventPublisher;
import com.yupi.springbootinit.service.storage.ChartDataStorageService;
import com.yupi.springbootinit.utils.BiAiResultParser;
import com.yupi.springbootinit.utils.BiPromptUtils;
import com.yupi.springbootinit.utils.ExcelUtils;
import com.yupi.springbootinit.utils.LineageRecordMetaHelper;
import com.yupi.springbootinit.utils.SqlUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 帖子接口
 *
 */
@RestController
@RequestMapping("/chart")
@Slf4j
public class ChartController {

    @Resource
    private ChartService chartService;

    @Resource
    private ChartGenerationService chartGenerationService;

    @Resource
    private UserService userService;

    @Resource
    private AiManager aiManager;

    @Resource
    private RedisLimiterManager redisLimiterManager;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Resource
    private BiMessageProducer biMessageProducer;

    @Resource
    private LineageService lineageService;

    @Resource
    private ChartActionMapper chartActionMapper;

    @Resource
    private ChartDataStorageService chartDataStorageService;

    @Resource
    private BigDataEventPublisher bigDataEventPublisher;

    @Resource
    private BigDataProperties bigDataProperties;


    // region 增删改查

    /**
     * 创建
     *
     * @param chartAddRequest
     * @param request
     * @return
     */
    @PostMapping("/add")
    public BaseResponse<Long> addChart(@RequestBody ChartAddRequest chartAddRequest, HttpServletRequest request) {
        if (chartAddRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart chart = new Chart();
        BeanUtils.copyProperties(chartAddRequest, chart);
        User loginUser = userService.getLoginUser(request);
        chart.setUserId(loginUser.getId());
        boolean result = chartService.save(chart);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        long newChartId = chart.getId();
        return ResultUtils.success(newChartId);
    }

    /**
     * 删除
     *
     * @param deleteRequest
     * @param request
     * @return
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteChart(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = userService.getLoginUser(request);
        long id = deleteRequest.getId();
        // 判断是否存在
        Chart oldChart = chartService.getById(id);
        ThrowUtils.throwIf(oldChart == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可删除
        if (!oldChart.getUserId().equals(user.getId()) && !userService.isAdmin(request)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean b = chartService.removeById(id);
        return ResultUtils.success(b);
    }

    /**
     * 更新（仅管理员）
     *
     * @param chartUpdateRequest
     * @return
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateChart(@RequestBody ChartUpdateRequest chartUpdateRequest) {
        if (chartUpdateRequest == null || chartUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart chart = new Chart();
        BeanUtils.copyProperties(chartUpdateRequest, chart);
        long id = chartUpdateRequest.getId();
        // 判断是否存在
        Chart oldChart = chartService.getById(id);
        ThrowUtils.throwIf(oldChart == null, ErrorCode.NOT_FOUND_ERROR);
        boolean result = chartService.updateById(chart);
        return ResultUtils.success(result);
    }

    /**
     * 根据 id 获取
     *
     * @param id
     * @return
     */
    @GetMapping("/get")
    public BaseResponse<Chart> getChartById(long id, HttpServletRequest request) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart chart = getOwnedChartOrThrow(id, request);
        if (StringUtils.isBlank(chart.getResultMeta())) {
            chart.setResultMeta(buildResultMeta(chart));
        }
        return ResultUtils.success(chart);
    }

    /**
     * 获取当前用户可访问的图表。
     *
     * @param chartId
     * @param request
     * @return
     */
    private Chart getOwnedChartOrThrow(long chartId, HttpServletRequest request) {
        ThrowUtils.throwIf(chartId <= 0, ErrorCode.PARAMS_ERROR);
        Chart chart = chartService.getById(chartId);
        ThrowUtils.throwIf(chart == null, ErrorCode.NOT_FOUND_ERROR);
        User loginUser = userService.getLoginUser(request);
        boolean isOwner = chart.getUserId() != null && chart.getUserId().equals(loginUser.getId());
        boolean isAdmin = userService.isAdmin(loginUser);
        ThrowUtils.throwIf(!isOwner && !isAdmin, ErrorCode.NO_AUTH_ERROR);
        return chart;
    }

    /**
     * 分页获取列表（封装类）
     *
     * @param chartQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/list/page")
    public BaseResponse<Page<Chart>> listChartByPage(@RequestBody ChartQueryRequest chartQueryRequest,
            HttpServletRequest request) {
        if (chartQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        if (!userService.isAdmin(loginUser)) {
            chartQueryRequest.setUserId(loginUser.getId());
        }
        long current = chartQueryRequest.getCurrent();
        long size = chartQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        Page<Chart> chartPage = chartService.page(new Page<>(current, size),
                getQueryWrapper(chartQueryRequest));
        return ResultUtils.success(chartPage);
    }

    /**
     * 分页获取当前用户创建的资源列表
     *
     * @param chartQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/my/list/page")
    public BaseResponse<Page<Chart>> listMyChartByPage(@RequestBody ChartQueryRequest chartQueryRequest,
            HttpServletRequest request) {
        if (chartQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        chartQueryRequest.setUserId(loginUser.getId());
        long current = chartQueryRequest.getCurrent();
        long size = chartQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        Page<Chart> chartPage = chartService.page(new Page<>(current, size),
                getQueryWrapper(chartQueryRequest));
        return ResultUtils.success(chartPage);
    }

    /**
     * 获取图表行动项（管理者闭环）
     */
    @GetMapping("/actions")
    public BaseResponse<List<ChartAction>> listChartActions(@RequestParam("chartId") Long chartId, HttpServletRequest request) {
        if (chartId == null || chartId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "chartId 非法");
        }
        Chart chart = chartService.getById(chartId);
        ThrowUtils.throwIf(chart == null, ErrorCode.NOT_FOUND_ERROR, "图表不存在");
        User loginUser = userService.getLoginUser(request);
        if (!chart.getUserId().equals(loginUser.getId()) && !userService.isAdmin(request)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        QueryWrapper<ChartAction> qw = new QueryWrapper<>();
        qw.eq("chartId", chartId);
        qw.eq("isDelete", 0);
        qw.orderByAsc("createTime");
        List<ChartAction> actions = chartActionMapper.selectList(qw);
        if (actions == null || actions.isEmpty()) {
            actions = initDefaultActions(chart);
        }
        return ResultUtils.success(actions == null ? Collections.emptyList() : actions);
    }

    /**
     * 批量获取图表未完成行动项数量（用于列表角标）
     */
    @GetMapping("/actions/pending-counts")
    public BaseResponse<Map<Long, Integer>> listChartActionPendingCounts(@RequestParam("chartIds") String chartIds,
                                                                         HttpServletRequest request) {
        if (StringUtils.isBlank(chartIds)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "chartIds 不能为空");
        }
        List<Long> idList = new ArrayList<>();
        for (String part : chartIds.split(",")) {
            String text = StringUtils.trimToEmpty(part);
            if (StringUtils.isBlank(text)) {
                continue;
            }
            try {
                long chartId = Long.parseLong(text);
                if (chartId > 0) {
                    idList.add(chartId);
                }
            } catch (NumberFormatException ignored) {
                // ignore bad id
            }
        }
        ThrowUtils.throwIf(idList.isEmpty(), ErrorCode.PARAMS_ERROR, "chartIds 非法");
        User loginUser = userService.getLoginUser(request);
        boolean isAdmin = userService.isAdmin(request);
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (Long chartId : idList) {
            Chart chart = chartService.getById(chartId);
            if (chart == null) {
                continue;
            }
            if (!isAdmin && !chart.getUserId().equals(loginUser.getId())) {
                continue;
            }
            QueryWrapper<ChartAction> qw = new QueryWrapper<>();
            qw.eq("chartId", chartId);
            qw.eq("isDelete", 0);
            qw.ne("status", "completed");
            Long count = chartActionMapper.selectCount(qw);
            result.put(chartId, count == null ? 0 : count.intValue());
        }
        return ResultUtils.success(result);
    }

    /**
     * 更新图表行动项状态 / 负责人 / 截止时间
     */
    @PostMapping("/actions/update")
    public BaseResponse<Boolean> updateChartAction(@RequestBody ChartActionUpdateRequest updateRequest,
                                                   HttpServletRequest request) {
        if (updateRequest == null || updateRequest.getId() == null || updateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行动项 id 非法");
        }
        ChartAction oldAction = chartActionMapper.selectById(updateRequest.getId());
        ThrowUtils.throwIf(oldAction == null, ErrorCode.NOT_FOUND_ERROR, "行动项不存在");
        Chart chart = chartService.getById(oldAction.getChartId());
        ThrowUtils.throwIf(chart == null, ErrorCode.NOT_FOUND_ERROR, "关联图表不存在");
        User loginUser = userService.getLoginUser(request);
        if (!chart.getUserId().equals(loginUser.getId()) && !userService.isAdmin(request)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        String status = StringUtils.trimToEmpty(updateRequest.getStatus());
        if (StringUtils.isNotBlank(status)
                && !"pending".equals(status)
                && !"in_progress".equals(status)
                && !"completed".equals(status)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "status 仅支持 pending/in_progress/completed");
        }
        ChartAction update = new ChartAction();
        update.setId(oldAction.getId());
        if (StringUtils.isNotBlank(status)) {
            update.setStatus(status);
        }
        if (updateRequest.getOwner() != null) {
            update.setOwner(updateRequest.getOwner());
        }
        if (updateRequest.getDueDate() != null) {
            update.setDueDate(updateRequest.getDueDate());
        }
        boolean ok = chartActionMapper.updateById(update) > 0;
        return ResultUtils.success(ok);
    }

    // endregion

    /**
     * 编辑（用户）
     *
     * @param chartEditRequest
     * @param request
     * @return
     */
    @PostMapping("/edit")
    public BaseResponse<Boolean> editChart(@RequestBody ChartEditRequest chartEditRequest, HttpServletRequest request) {
        if (chartEditRequest == null || chartEditRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart chart = new Chart();
        BeanUtils.copyProperties(chartEditRequest, chart);
        User loginUser = userService.getLoginUser(request);
        long id = chartEditRequest.getId();
        // 判断是否存在
        Chart oldChart = chartService.getById(id);
        ThrowUtils.throwIf(oldChart == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可编辑
        if (!oldChart.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean result = chartService.updateById(chart);
        return ResultUtils.success(result);
    }

    /**
     * 智能分析（同步）
     *
     * @param multipartFile
     * @param genChartByAiRequest
     * @param request
     * @return
     */
    @PostMapping("/gen")
    public BaseResponse<BiResponse> genChartByAi(@RequestPart("file") MultipartFile multipartFile,
                                                 GenChartByAiRequest genChartByAiRequest, HttpServletRequest request) {
        String name = genChartByAiRequest.getName();
        String goal = genChartByAiRequest.getGoal();
        String chartType = genChartByAiRequest.getChartType();
        // 校验
        ThrowUtils.throwIf(StringUtils.isBlank(goal), ErrorCode.PARAMS_ERROR, "目标为空");
        ThrowUtils.throwIf(StringUtils.isNotBlank(name) && name.length() > 100, ErrorCode.PARAMS_ERROR, "名称过长");
        // 校验文件
        long size = multipartFile.getSize();
        String originalFilename = multipartFile.getOriginalFilename();
        // 校验文件大小
        final long ONE_MB = 1024 * 1024L;
        ThrowUtils.throwIf(size > ONE_MB, ErrorCode.PARAMS_ERROR, "文件超过 1M");
        // 校验文件后缀 aaa.png
        String suffix = FileUtil.getSuffix(originalFilename);
        final List<String> validFileSuffixList = Arrays.asList("xlsx", "csv");
        ThrowUtils.throwIf(!validFileSuffixList.contains(suffix), ErrorCode.PARAMS_ERROR, "文件后缀非法，请上传 .xlsx 或 .csv");

        User loginUser = userService.getLoginUser(request);
        // 限流判断，每个用户一个限流器
        redisLimiterManager.doRateLimit("genChartByAi_" + loginUser.getId());
        // 无需写 prompt，直接调用现有模型，https://www.yucongming.com，公众号搜【鱼聪明AI】
//        final String prompt = "你是一个数据分析师和前端开发专家，接下来我会按照以下固定格式给你提供内容：\n" +
//                "分析需求：\n" +
//                "{数据分析的需求或者目标}\n" +
//                "原始数据：\n" +
//                "{csv格式的原始数据，用,作为分隔符}\n" +
//                "请根据这两部分内容，按照以下指定格式生成内容（此外不要输出任何多余的开头、结尾、注释）\n" +
//                "【【【【【\n" +
//                "{前端 Echarts V5 的 option 配置对象js代码，合理地将数据进行可视化，不要生成任何多余的内容，比如注释}\n" +
//                "【【【【【\n" +
//                "{明确的数据分析结论、越详细越好，不要生成多余的注释}";
        long biModelId = CommonConstant.BI_MODEL_ID;
        // 分析需求：
        // 分析网站用户的增长情况
        // 原始数据：
        // 日期,用户数
        // 1号,10
        // 2号,20
        // 3号,30

        String csvData = ExcelUtils.multipartToCsvData(multipartFile);
        ThrowUtils.throwIf(StringUtils.isBlank(csvData), ErrorCode.PARAMS_ERROR, "表格数据为空，请检查文件是否为有效 UTF-8 CSV 或 xlsx");
        String userInput = BiPromptUtils.buildChartAiUserMessage(goal, chartType, csvData);
        String traceId = lineageService.newTraceId();
        recordLineage(traceId, null, LineageConstant.EVENT_SOURCE_INGESTED, originalFilename, csvData,
                "upload_csv_v1", "succeed", loginUser.getId());
        recordLineage(traceId, null, LineageConstant.EVENT_DATA_CLEANED, csvData, csvData,
                "excel_to_csv_v1", "succeed", loginUser.getId());

        String result = aiManager.doChat(biModelId, userInput);
        BiAiResultParser.Parsed parsed = BiAiResultParser.parse(result);
        String genChart = parsed.getGenChart();
        String genResult = parsed.getGenResult();
        // 插入到数据库
        Chart chart = new Chart();
        chart.setName(name);
        chart.setGoal(goal);
        chart.setChartData(csvData);
        chart.setChartType(chartType);
        chart.setGenChart(genChart);
        chart.setGenResult(genResult);
        chart.setResultMeta(buildResultMeta(goal, name, genResult));
        // 同步模式：直接标记成功，便于「我的图表」按状态渲染
        chart.setStatus("succeed");
        chart.setExecMessage("生成成功");
        chart.setUserId(loginUser.getId());
        boolean saveResult = chartService.save(chart);
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "图表保存失败");
        recordLineage(traceId, chart.getId(), LineageConstant.EVENT_CHART_RENDERED, userInput, genChart,
                "chart_option_v1", "succeed", loginUser.getId());
        recordLineage(traceId, chart.getId(), LineageConstant.EVENT_AI_INTERPRETED, userInput, genResult,
                "chart_ai_prompt_v1", "succeed", loginUser.getId());
        recordAiExplain(traceId, chart.getId(), "chart_ai_prompt_v1", "dashscope_or_yuapi", userInput, genResult);
        BiResponse biResponse = new BiResponse();
        biResponse.setGenChart(genChart);
        biResponse.setGenResult(genResult);
        biResponse.setChartId(chart.getId());
        return ResultUtils.success(biResponse);
    }

    /**
     * 一键复跑图表（同步）
     */
    @PostMapping("/rerun")
    public BaseResponse<BiResponse> rerunChart(@RequestBody ChartRerunRequest rerunRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(rerunRequest == null || rerunRequest.getChartId() == null || rerunRequest.getChartId() <= 0,
                ErrorCode.PARAMS_ERROR, "图表 id 非法");
        User loginUser = userService.getLoginUser(request);
        Chart chart = chartService.getById(rerunRequest.getChartId());
        ThrowUtils.throwIf(chart == null, ErrorCode.NOT_FOUND_ERROR, "图表不存在");
        if (!chart.getUserId().equals(loginUser.getId()) && !userService.isAdmin(request)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        ThrowUtils.throwIf(StringUtils.isBlank(chart.getGoal()), ErrorCode.PARAMS_ERROR, "分析目标为空，无法复跑");
        ThrowUtils.throwIf(StringUtils.isBlank(chart.getChartData()), ErrorCode.PARAMS_ERROR, "图表原始数据为空，无法复跑");

        String userInput = BiPromptUtils.buildChartAiUserMessage(chart.getGoal(), chart.getChartType(), chart.getChartData());
        String traceId = lineageService.newTraceId();
        Chart running = new Chart();
        running.setId(chart.getId());
        running.setStatus("running");
        running.setExecMessage("复跑中");
        chartService.updateById(running);
        recordLineage(traceId, chart.getId(), LineageConstant.EVENT_METRIC_COMPUTED, chart.getGoal(), chart.getChartType(),
                "rerun_prepare_v1", "succeed", loginUser.getId());
        try {
            String result = aiManager.doChat(CommonConstant.BI_MODEL_ID, userInput);
            BiAiResultParser.Parsed parsed = BiAiResultParser.parse(result);
            String genChart = parsed.getGenChart();
            String genResult = parsed.getGenResult();

            Chart updateChart = new Chart();
            updateChart.setId(chart.getId());
            updateChart.setGenChart(genChart);
            updateChart.setGenResult(genResult);
            updateChart.setResultMeta(buildResultMeta(chart.getGoal(), chart.getName(), genResult));
            updateChart.setStatus("succeed");
            updateChart.setExecMessage("复跑成功");
            boolean ok = chartService.updateById(updateChart);
            ThrowUtils.throwIf(!ok, ErrorCode.SYSTEM_ERROR, "更新复跑结果失败");

            recordLineage(traceId, chart.getId(), LineageConstant.EVENT_CHART_RENDERED, userInput, genChart,
                    "chart_option_v1", "succeed", loginUser.getId());
            recordLineage(traceId, chart.getId(), LineageConstant.EVENT_AI_INTERPRETED, userInput, genResult,
                    "chart_ai_prompt_v1", "succeed", loginUser.getId());
            recordAiExplain(traceId, chart.getId(), "chart_ai_prompt_v1", "dashscope_or_yuapi", userInput, genResult);

            BiResponse response = new BiResponse();
            response.setChartId(chart.getId());
            response.setGenChart(genChart);
            response.setGenResult(genResult);
            return ResultUtils.success(response);
        } catch (Exception e) {
            Chart failed = new Chart();
            failed.setId(chart.getId());
            failed.setStatus("failed");
            failed.setExecMessage("复跑失败：" + e.getMessage());
            chartService.updateById(failed);
            recordLineage(traceId, chart.getId(), LineageConstant.EVENT_AI_INTERPRETED, userInput, e.getMessage(),
                    "chart_ai_prompt_v1", "failed", loginUser.getId());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "复跑失败：" + e.getMessage());
        }
    }

    /**
     * 一键复跑图表（异步）
     */
    @PostMapping("/rerun/async")
    public BaseResponse<BiResponse> rerunChartAsync(@RequestBody ChartRerunRequest rerunRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(rerunRequest == null || rerunRequest.getChartId() == null || rerunRequest.getChartId() <= 0,
                ErrorCode.PARAMS_ERROR, "图表 id 非法");
        User loginUser = userService.getLoginUser(request);
        Chart chart = chartService.getById(rerunRequest.getChartId());
        ThrowUtils.throwIf(chart == null, ErrorCode.NOT_FOUND_ERROR, "图表不存在");
        if (!chart.getUserId().equals(loginUser.getId()) && !userService.isAdmin(request)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        ThrowUtils.throwIf(StringUtils.isBlank(chart.getGoal()), ErrorCode.PARAMS_ERROR, "分析目标为空，无法复跑");
        ThrowUtils.throwIf(StringUtils.isBlank(chart.getChartData()), ErrorCode.PARAMS_ERROR, "图表原始数据为空，无法复跑");

        String userInput = BiPromptUtils.buildChartAiUserMessage(chart.getGoal(), chart.getChartType(), chart.getChartData());
        String traceId = lineageService.newTraceId();
        Long operatorId = loginUser.getId();

        Chart running = new Chart();
        running.setId(chart.getId());
        running.setStatus("running");
        running.setExecMessage("复跑任务执行中");
        chartService.updateById(running);
        recordLineage(traceId, chart.getId(), LineageConstant.EVENT_METRIC_COMPUTED, chart.getGoal(), chart.getChartType(),
                "rerun_prepare_v1", "succeed", operatorId);

        CompletableFuture.runAsync(() -> {
            try {
                String result = aiManager.doChat(CommonConstant.BI_MODEL_ID, userInput);
                BiAiResultParser.Parsed parsed = BiAiResultParser.parse(result);
                String genChart = parsed.getGenChart();
                String genResult = parsed.getGenResult();

                // 先写入复跑结果内容，状态仍保持 running，避免前端在链路未落库完时过早读到 succeed。
                Chart updateChartData = new Chart();
                updateChartData.setId(chart.getId());
                updateChartData.setGenChart(genChart);
                updateChartData.setGenResult(genResult);
                updateChartData.setResultMeta(buildResultMeta(chart.getGoal(), chart.getName(), genResult));
                updateChartData.setExecMessage("复跑结果处理中");
                boolean ok = chartService.updateById(updateChartData);
                if (!ok) {
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "更新复跑结果失败");
                }

                recordLineage(traceId, chart.getId(), LineageConstant.EVENT_CHART_RENDERED, userInput, genChart,
                        "chart_option_v1", "succeed", operatorId);
                recordLineage(traceId, chart.getId(), LineageConstant.EVENT_AI_INTERPRETED, userInput, genResult,
                        "chart_ai_prompt_v1", "succeed", operatorId);
                recordAiExplain(traceId, chart.getId(), "chart_ai_prompt_v1", "dashscope_or_yuapi", userInput, genResult);

                // 链路与 explain 落库完成后，再标记成功。
                Chart updateChartStatus = new Chart();
                updateChartStatus.setId(chart.getId());
                updateChartStatus.setStatus("succeed");
                updateChartStatus.setExecMessage("复跑成功");
                chartService.updateById(updateChartStatus);
            } catch (Exception e) {
                Chart failed = new Chart();
                failed.setId(chart.getId());
                failed.setStatus("failed");
                failed.setExecMessage("复跑失败：" + e.getMessage());
                chartService.updateById(failed);
                recordLineage(traceId, chart.getId(), LineageConstant.EVENT_AI_INTERPRETED, userInput, e.getMessage(),
                        "chart_ai_prompt_v1", "failed", operatorId);
            }
        }, threadPoolExecutor);

        BiResponse response = new BiResponse();
        response.setChartId(chart.getId());
        return ResultUtils.success(response);
    }

    /**
     * 智能分析（异步）
     *
     * @param multipartFile
     * @param genChartByAiRequest
     * @param request
     * @return
     */
    @PostMapping("/gen/async")
    public BaseResponse<BiResponse> genChartByAiAsync(@RequestPart("file") MultipartFile multipartFile,
                                             GenChartByAiRequest genChartByAiRequest, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        redisLimiterManager.doRateLimit("genChartByAi_" + loginUser.getId());

        Chart chart = chartGenerationService.createPendingChart(multipartFile, genChartByAiRequest, loginUser);
        String name = chart.getName();
        String goal = chart.getGoal();
        String chartType = chart.getChartType();
        String csvData = chart.getChartData();
        String originalFilename = multipartFile.getOriginalFilename();
        final String userInput = BiPromptUtils.buildChartAiUserMessage(goal, chartType, csvData);
        final String traceId = lineageService.newTraceId();
        final long biModelId = CommonConstant.BI_MODEL_ID;
        recordLineage(traceId, null, LineageConstant.EVENT_SOURCE_INGESTED, originalFilename, csvData,
                "upload_csv_v1", "succeed", loginUser.getId());
        recordLineage(traceId, null, LineageConstant.EVENT_DATA_CLEANED, csvData, csvData,
                "excel_to_csv_v1", "succeed", loginUser.getId());
        applyChartDataExternalStorage(chart);
        recordLineage(traceId, chart.getId(), LineageConstant.EVENT_METRIC_COMPUTED, goal, chartType,
                "goal_parse_v1", "succeed", loginUser.getId());

        // todo 建议处理任务队列满了后，抛异常的情况
        CompletableFuture.runAsync(() -> {
            // 先修改图表任务状态为 “执行中”。等执行成功后，修改为 “已完成”、保存执行结果；执行失败后，状态修改为 “失败”，记录任务失败信息。
            Chart updateChart = new Chart();
            updateChart.setId(chart.getId());
            updateChart.setStatus("running");
            boolean b = chartService.updateById(updateChart);
            if (!b) {
                handleChartUpdateError(chart.getId(), "更新图表执行中状态失败");
                return;
            }
            // 调用 AI
            String result = aiManager.doChat(biModelId, userInput);
            BiAiResultParser.Parsed parsed;
            try {
                parsed = BiAiResultParser.parse(result);
            } catch (BusinessException e) {
                log.warn("chartId={} {}", chart.getId(), e.getMessage());
                handleChartUpdateError(chart.getId(), e.getMessage());
                return;
            }
            String genChart = parsed.getGenChart();
            String genResult = parsed.getGenResult();
            Chart updateChartResult = new Chart();
            updateChartResult.setId(chart.getId());
            updateChartResult.setGenChart(genChart);
            updateChartResult.setGenResult(genResult);
            updateChartResult.setResultMeta(buildResultMeta(goal, name, genResult));
            // todo 建议定义状态为枚举值
            updateChartResult.setStatus("succeed");
            boolean updateResult = chartService.updateById(updateChartResult);
            if (!updateResult) {
                handleChartUpdateError(chart.getId(), "更新图表成功状态失败");
                recordLineage(traceId, chart.getId(), LineageConstant.EVENT_CHART_RENDERED, userInput, "更新图表失败",
                        "chart_option_v1", "failed", loginUser.getId());
                return;
            }
            recordLineage(traceId, chart.getId(), LineageConstant.EVENT_CHART_RENDERED, userInput, genChart,
                    "chart_option_v1", "succeed", loginUser.getId());
            recordLineage(traceId, chart.getId(), LineageConstant.EVENT_AI_INTERPRETED, userInput, genResult,
                    "chart_ai_prompt_v1", "succeed", loginUser.getId());
            recordAiExplain(traceId, chart.getId(), "chart_ai_prompt_v1", "dashscope_or_yuapi", userInput, genResult);
            publishChartGenBigDataEvent(chart.getId(), traceId, "succeed", loginUser.getId());
        }, threadPoolExecutor);

        BiResponse biResponse = new BiResponse();
        biResponse.setChartId(chart.getId());
        return ResultUtils.success(biResponse);
    }

    /**
     * 智能分析（异步消息队列）
     *
     * @param multipartFile
     * @param genChartByAiRequest
     * @param request
     * @return
     */
    @PostMapping("/gen/async/mq")
    public BaseResponse<BiResponse> genChartByAiAsyncMq(@RequestPart("file") MultipartFile multipartFile,
                                                      GenChartByAiRequest genChartByAiRequest, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        redisLimiterManager.doRateLimit("genChartByAi_" + loginUser.getId());

        Chart chart = chartGenerationService.createPendingChart(multipartFile, genChartByAiRequest, loginUser);
        String goal = chart.getGoal();
        String chartType = chart.getChartType();
        String csvData = chart.getChartData();
        String originalFilename = multipartFile.getOriginalFilename();
        String traceId = lineageService.newTraceId();
        recordLineage(traceId, null, LineageConstant.EVENT_SOURCE_INGESTED, originalFilename, csvData,
                "upload_csv_v1", "succeed", loginUser.getId());
        recordLineage(traceId, null, LineageConstant.EVENT_DATA_CLEANED, csvData, csvData,
                "excel_to_csv_v1", "succeed", loginUser.getId());
        applyChartDataExternalStorage(chart);
        recordLineage(traceId, chart.getId(), LineageConstant.EVENT_METRIC_COMPUTED, goal, chartType,
                "goal_parse_v1", "succeed", loginUser.getId());
        long newChartId = chart.getId();
        biMessageProducer.sendMessage(newChartId + "|" + traceId);
        BiResponse biResponse = new BiResponse();
        biResponse.setChartId(newChartId);
        return ResultUtils.success(biResponse);
    }


    private void handleChartUpdateError(long chartId, String execMessage) {
        Chart updateChartResult = new Chart();
        updateChartResult.setId(chartId);
        updateChartResult.setStatus("failed");
        updateChartResult.setExecMessage(execMessage);
        boolean updateResult = chartService.updateById(updateChartResult);
        if (!updateResult) {
            log.error("更新图表失败状态失败" + chartId + "," + execMessage);
        }
    }


    /**
     * 获取查询包装类
     *
     * @param chartQueryRequest
     * @return
     */
    private QueryWrapper<Chart> getQueryWrapper(ChartQueryRequest chartQueryRequest) {
        QueryWrapper<Chart> queryWrapper = new QueryWrapper<>();
        if (chartQueryRequest == null) {
            return queryWrapper;
        }
        Long id = chartQueryRequest.getId();
        String name = chartQueryRequest.getName();
        String goal = chartQueryRequest.getGoal();
        String chartType = chartQueryRequest.getChartType();
        Long userId = chartQueryRequest.getUserId();
        String sortField = chartQueryRequest.getSortField();
        String sortOrder = chartQueryRequest.getSortOrder();

        queryWrapper.eq(id != null && id > 0, "id", id);
        queryWrapper.like(StringUtils.isNotBlank(name), "name", name);
        queryWrapper.eq(StringUtils.isNotBlank(goal), "goal", goal);
        queryWrapper.eq(StringUtils.isNotBlank(chartType), "chartType", chartType);
        queryWrapper.eq(ObjectUtils.isNotEmpty(userId), "userId", userId);
        queryWrapper.eq("isDelete", false);
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }

    private String buildResultMeta(Chart chart) {
        if (chart == null) {
            return "{}";
        }
        if (StringUtils.isNotBlank(chart.getResultMeta())) {
            return chart.getResultMeta();
        }
        return buildResultMeta(chart.getGoal(), chart.getName(), chart.getGenResult());
    }

    private String buildResultMeta(String goal, String chartName, String genResult) {
        String text = StringUtils.defaultString(genResult).trim();
        JsonObject root = new JsonObject();

        JsonObject summary = new JsonObject();
        String headline = extractHeadline(text);
        summary.addProperty("headline", StringUtils.defaultIfBlank(headline, "本次分析已生成，请关注关键指标波动与异常点。"));
        summary.addProperty("riskLevel", inferRiskLevel(text));
        JsonArray keyActions = new JsonArray();
        for (String action : extractKeyActions(text, goal)) {
            keyActions.add(action);
        }
        summary.add("keyActions", keyActions);
        root.add("summary", summary);

        JsonArray kpis = new JsonArray();
        kpis.add(buildKpi("营收", "--", "元"));
        kpis.add(buildKpi("毛利率", "--", "%"));
        kpis.add(buildKpi("客单价", "--", "元"));
        kpis.add(buildKpi("翻台率", "--", "次/日"));
        root.add("kpis", kpis);

        JsonArray issues = new JsonArray();
        for (String action : extractKeyActions(text, goal)) {
            JsonObject issue = new JsonObject();
            issue.addProperty("title", action);
            issue.addProperty("priority", "medium");
            issue.addProperty("impact", "待结合业务数据评估影响范围");
            issue.addProperty("ownerSuggestion", "门店运营负责人");
            issue.addProperty("action", action);
            issues.add(issue);
        }
        root.add("issues", issues);

        JsonArray insights = new JsonArray();
        JsonObject insight = new JsonObject();
        insight.addProperty("chartTitle", StringUtils.defaultIfBlank(chartName, "当前图表"));
        JsonArray points = new JsonArray();
        points.add(StringUtils.defaultIfBlank(headline, "请结合图表趋势关注关键波动点"));
        if (StringUtils.isNotBlank(goal)) {
            points.add("分析目标：" + goal);
        }
        insight.add("points", points);
        insights.add(insight);
        root.add("insights", insights);

        JsonArray actions = new JsonArray();
        int idx = 0;
        for (String actionText : extractKeyActions(text, goal)) {
            idx++;
            JsonObject action = new JsonObject();
            action.addProperty("id", "local-" + idx);
            action.addProperty("task", actionText);
            action.addProperty("owner", "待分配");
            action.addProperty("dueDate", "");
            action.addProperty("status", "pending");
            action.addProperty("priority", "medium");
            action.addProperty("expectedBenefit", "提升经营指标稳定性");
            actions.add(action);
        }
        root.add("actions", actions);
        return new Gson().toJson(root);
    }

    private JsonObject buildKpi(String name, String value, String unit) {
        JsonObject kpi = new JsonObject();
        kpi.addProperty("name", name);
        kpi.addProperty("value", value);
        kpi.addProperty("unit", unit);
        kpi.addProperty("trend", "flat");
        kpi.addProperty("delta", "--");
        return kpi;
    }

    private String extractHeadline(String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        String normalized = text.replace("\r", "").replace("\n", " ").trim();
        int splitIdx = normalized.indexOf("。");
        if (splitIdx > 0) {
            return normalized.substring(0, Math.min(splitIdx + 1, 80)).trim();
        }
        return normalized.substring(0, Math.min(normalized.length(), 80)).trim();
    }

    private String inferRiskLevel(String text) {
        String lower = StringUtils.defaultString(text).toLowerCase();
        if (lower.contains("失败") || lower.contains("异常") || lower.contains("下滑") || lower.contains("风险")) {
            return "high";
        }
        if (lower.contains("波动") || lower.contains("优化")) {
            return "medium";
        }
        return "low";
    }

    private List<String> extractKeyActions(String text, String goal) {
        List<String> actions = new ArrayList<>();
        if (StringUtils.isNotBlank(text)) {
            String[] parts = text.split("[\\n；;。]");
            for (String part : parts) {
                String line = StringUtils.trimToEmpty(part);
                if (line.length() < 8) {
                    continue;
                }
                if (line.matches("^(\\d+\\.|\\d+、).*")) {
                    line = line.replaceFirst("^(\\d+\\.|\\d+、)\\s*", "");
                }
                if (line.contains("建议") || line.contains("优化") || line.contains("提升") || line.contains("控制")) {
                    actions.add(line);
                }
                if (actions.size() >= 3) {
                    break;
                }
            }
        }
        if (actions.isEmpty() && StringUtils.isNotBlank(goal)) {
            actions.add("围绕分析目标执行专项优化：" + goal);
        }
        if (actions.isEmpty()) {
            actions.add("优先复核异常指标并确认数据口径");
            actions.add("制定本周执行动作并明确负责人");
            actions.add("一周后复盘执行效果并更新策略");
        }
        return actions;
    }

    private List<ChartAction> initDefaultActions(Chart chart) {
        if (chart == null) {
            return Collections.emptyList();
        }
        List<String> actionTexts = new ArrayList<>();
        String resultMeta = buildResultMeta(chart);
        try {
            JsonElement rootEl = JsonParser.parseString(resultMeta);
            if (rootEl != null && rootEl.isJsonObject()) {
                JsonObject root = rootEl.getAsJsonObject();
                JsonObject summary = root.has("summary") && root.get("summary").isJsonObject()
                        ? root.getAsJsonObject("summary")
                        : null;
                if (summary != null && summary.has("keyActions") && summary.get("keyActions").isJsonArray()) {
                    for (JsonElement el : summary.getAsJsonArray("keyActions")) {
                        if (el != null && el.isJsonPrimitive()) {
                            String t = StringUtils.trimToEmpty(el.getAsString());
                            if (StringUtils.isNotBlank(t)) {
                                actionTexts.add(t);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore parse fallback
        }
        if (actionTexts.isEmpty()) {
            actionTexts = extractKeyActions(chart.getGenResult(), chart.getGoal());
        }
        List<ChartAction> actions = new ArrayList<>();
        int idx = 0;
        for (String actionText : actionTexts) {
            idx++;
            ChartAction action = new ChartAction();
            action.setChartId(chart.getId());
            action.setTask(actionText);
            action.setOwner("待分配");
            action.setDueDate("");
            action.setStatus("pending");
            action.setPriority(idx == 1 ? "high" : "medium");
            action.setExpectedBenefit("提升关键经营指标稳定性");
            action.setCreateTime(new Date());
            action.setUpdateTime(new Date());
            chartActionMapper.insert(action);
            actions.add(action);
        }
        return actions;
    }

    private void recordLineage(String traceId, Long chartId, String eventType, String input, String output,
                               String logicRef, String status, Long operatorId) {
        LineageRecordRequest req = new LineageRecordRequest();
        req.setTraceId(traceId);
        req.setChartId(chartId);
        req.setEventType(eventType);
        req.setInputText(input);
        req.setOutputText(output);
        req.setRuleVersion("v1");
        req.setLogicRef(logicRef);
        List<String> datasetsIn = extractDatasets(input, logicRef);
        List<String> datasetsOut = extractDatasets(output, logicRef);
        LineageRecordMetaHelper.enrichDatasetsForChart(datasetsIn, datasetsOut, chartId, eventType);
        req.setDatasetsIn(datasetsIn);
        req.setDatasetsOut(datasetsOut);
        req.setEventMetaJson(LineageRecordMetaHelper.buildEventMetaJson(logicRef, req.getDatasetsIn(), req.getDatasetsOut()));
        req.setStatus(status);
        req.setOperatorId(operatorId);
        lineageService.recordEvent(req);
    }

    private List<String> extractDatasets(String text, String logicRef) {
        Set<String> datasets = new LinkedHashSet<>();
        collectDatasetsFromText(datasets, text);
        collectDatasetsFromText(datasets, logicRef);
        return new ArrayList<>(datasets);
    }

    private void collectDatasetsFromText(Set<String> datasets, String text) {
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

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.isBlank(value) ? defaultValue : value.trim();
    }

    private void recordAiExplain(String traceId, Long chartId, String promptVersion, String modelName,
                                 String prompt, String aiResult) {
        Map<String, Object> explain = new LinkedHashMap<>();
        explain.put("promptPreview", StringUtils.substring(prompt, 0, 2000));
        explain.put("resultPreview", StringUtils.substring(aiResult, 0, 2000));
        ExplainRecordRequest req = new ExplainRecordRequest();
        req.setTraceId(traceId);
        req.setChartId(chartId);
        req.setExplainType(LineageConstant.EXPLAIN_TYPE_AI);
        req.setPromptVersion(promptVersion);
        req.setModelName(modelName);
        req.setExplainData(new Gson().toJson(explain));
        lineageService.recordExplain(req);
    }

    private void applyChartDataExternalStorage(Chart chart) {
        if (chart == null || chart.getId() == null) {
            return;
        }
        String csv = chart.getChartData();
        if (StringUtils.isBlank(csv)) {
            return;
        }
        if (chartDataStorageService.tryExternalizeChartData(chart, csv)) {
            chartService.updateById(chart);
        }
    }

    private void publishChartGenBigDataEvent(Long chartId, String traceId, String status, Long operatorId) {
        if (!bigDataProperties.isEnabled()) {
            return;
        }
        bigDataEventPublisher.publishChartGenCompleted(chartId, traceId, status, operatorId);
    }
}
