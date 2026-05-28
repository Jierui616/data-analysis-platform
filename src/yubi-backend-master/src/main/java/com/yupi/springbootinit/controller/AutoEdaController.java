package com.yupi.springbootinit.controller;

import com.yupi.springbootinit.common.BaseResponse;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.common.ResultUtils;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.model.dto.autoeda.ContinueExplorationRequest;
import com.yupi.springbootinit.model.dto.autoeda.StartExplorationRequest;
import com.yupi.springbootinit.model.entity.User;
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
}
