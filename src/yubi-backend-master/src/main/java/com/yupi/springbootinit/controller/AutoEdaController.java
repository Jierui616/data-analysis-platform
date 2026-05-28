package com.yupi.springbootinit.controller;

import com.yupi.springbootinit.common.BaseResponse;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.common.ResultUtils;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.model.dto.autoeda.ContinueExplorationRequest;
import com.yupi.springbootinit.model.dto.autoeda.StartExplorationRequest;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.model.vo.autoeda.AutoEdaChartOptionVO;
import com.yupi.springbootinit.model.vo.autoeda.AutoEdaSessionSummaryVO;
import com.yupi.springbootinit.model.vo.autoeda.ExplorationStateVO;
import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import com.yupi.springbootinit.model.vo.autoeda.InsightReportVO;
import com.yupi.springbootinit.service.AutoEdaService;
import com.yupi.springbootinit.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/auto-eda")
@Slf4j
public class AutoEdaController {

    @Resource
    private AutoEdaService autoEdaService;

    @Resource
    private UserService userService;

    /**
     * 获取当前用户可探索的图表列表（从 MySQL chart 表查询）
     */
    @GetMapping("/charts")
    public BaseResponse<List<AutoEdaChartOptionVO>> listCharts(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(autoEdaService.listExplorableCharts(loginUser.getId()));
    }

    /**
     * 探索历史列表（回看入口）
     */
    @GetMapping("/sessions")
    public BaseResponse<List<AutoEdaSessionSummaryVO>> listSessions(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return ResultUtils.success(autoEdaService.listMySessions(loginUser.getId(), safeLimit));
    }

    /**
     * 启动探索会话
     */
    @PostMapping("/start")
    public BaseResponse<Map<String, Object>> start(@RequestBody StartExplorationRequest request,
                                                    HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        String sessionId = autoEdaService.startExploration(request, loginUser.getId());
        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("status", "RUNNING");
        return ResultUtils.success(result);
    }

    /**
     * 轮询探索状态（前端 2-3 秒轮询）
     */
    @GetMapping("/state/{sessionId}")
    public BaseResponse<ExplorationStateVO> state(@PathVariable String sessionId,
                                                   HttpServletRequest request) {
        userService.getLoginUser(request);
        return ResultUtils.success(autoEdaService.getState(sessionId));
    }

    /**
     * 获取最终报告
     */
    @GetMapping("/report/{sessionId}")
    public BaseResponse<InsightReportVO> report(@PathVariable String sessionId,
                                                 HttpServletRequest request) {
        userService.getLoginUser(request);
        return ResultUtils.success(autoEdaService.getReport(sessionId));
    }

    /**
     * 用户追问继续探索
     */
    @PostMapping("/continue")
    public BaseResponse<ExplorationStateVO> continueExploration(@RequestBody ContinueExplorationRequest request,
                                                                  HttpServletRequest httpRequest) {
        userService.getLoginUser(httpRequest);
        return ResultUtils.success(autoEdaService.continueExploration(request));
    }

    /**
     * 获取单个假设详情
     */
    @GetMapping("/hypothesis/{sessionId}/{hypothesisId}")
    public BaseResponse<HypothesisVO> hypothesisDetail(@PathVariable String sessionId,
                                                        @PathVariable String hypothesisId,
                                                        HttpServletRequest request) {
        userService.getLoginUser(request);
        return ResultUtils.success(autoEdaService.getHypothesisDetail(sessionId, hypothesisId));
    }

    /**
     * 停止探索
     */
    @PostMapping("/stop/{sessionId}")
    public BaseResponse<Boolean> stop(@PathVariable String sessionId,
                                       HttpServletRequest request) {
        userService.getLoginUser(request);
        return ResultUtils.success(autoEdaService.stopExploration(sessionId));
    }

    /**
     * 删除探索记录
     */
    @DeleteMapping("/sessions/{sessionId}")
    public BaseResponse<Boolean> deleteSession(@PathVariable String sessionId,
                                                HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(autoEdaService.deleteSession(sessionId, loginUser.getId()));
    }
}
