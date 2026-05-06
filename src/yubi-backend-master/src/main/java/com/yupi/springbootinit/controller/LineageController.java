package com.yupi.springbootinit.controller;

import com.yupi.springbootinit.common.BaseResponse;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.common.ResultUtils;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.model.vo.lineage.LineageFixTaskVO;
import com.yupi.springbootinit.model.vo.lineage.LineageGraphVO;
import com.yupi.springbootinit.model.vo.lineage.LineageImpactVO;
import com.yupi.springbootinit.model.vo.lineage.LineageTraceVO;
import com.yupi.springbootinit.service.LineageService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

@RestController
@RequestMapping("/lineage")
public class LineageController {

    @Resource
    private LineageService lineageService;

    @GetMapping("/trace/{traceId}")
    public BaseResponse<LineageTraceVO> getTrace(@PathVariable String traceId) {
        if (StringUtils.isBlank(traceId)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "traceId 不能为空");
        }
        LineageTraceVO vo = lineageService.getTrace(traceId);
        if (vo == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "未找到该链路");
        }
        return ResultUtils.success(vo);
    }

    @GetMapping("/chart/{chartId}/latest")
    public BaseResponse<LineageTraceVO> getLatestTraceByChartId(@PathVariable Long chartId) {
        if (chartId == null || chartId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "chartId 非法");
        }
        LineageTraceVO vo = lineageService.getLatestTraceByChartId(chartId);
        if (vo == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "该图表暂无血缘记录");
        }
        return ResultUtils.success(vo);
    }

    @GetMapping("/graph/chart/{chartId}")
    public BaseResponse<LineageGraphVO> getAssetGraphByChartId(@PathVariable Long chartId,
                                                                @RequestParam(value = "depth", required = false, defaultValue = "2") Integer depth) {
        if (chartId == null || chartId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "chartId 非法");
        }
        LineageGraphVO vo = lineageService.getAssetGraphByChartId(chartId, depth);
        if (vo == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "该图表暂无资产血缘图");
        }
        return ResultUtils.success(vo);
    }

    @GetMapping("/impact/chart/{chartId}")
    public BaseResponse<LineageImpactVO> getImpactByChartId(@PathVariable Long chartId) {
        if (chartId == null || chartId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "chartId 非法");
        }
        LineageImpactVO vo = lineageService.getImpactByChartId(chartId);
        return ResultUtils.success(vo);
    }

    @GetMapping("/fix-tasks/chart/{chartId}")
    public BaseResponse<List<LineageFixTaskVO>> getFixTasksByChartId(@PathVariable Long chartId) {
        if (chartId == null || chartId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "chartId 非法");
        }
        return ResultUtils.success(lineageService.getFixTasksByChartId(chartId));
    }

    @GetMapping("/fix-tasks/{taskId}/status")
    public BaseResponse<Boolean> updateFixTaskStatus(@PathVariable Long taskId,
                                                     @RequestParam("status") String status) {
        if (taskId == null || taskId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "taskId 非法");
        }
        if (!"pending".equals(status) && !"resolved".equals(status)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "status 仅支持 pending/resolved");
        }
        return ResultUtils.success(lineageService.updateFixTaskStatus(taskId, status));
    }
}
