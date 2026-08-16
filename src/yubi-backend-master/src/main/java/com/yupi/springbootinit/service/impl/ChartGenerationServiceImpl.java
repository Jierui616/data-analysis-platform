package com.yupi.springbootinit.service.impl;

import cn.hutool.core.io.FileUtil;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.ThrowUtils;
import com.yupi.springbootinit.model.dto.chart.GenChartByAiRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.service.ChartGenerationService;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.utils.ExcelUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;

@Service
public class ChartGenerationServiceImpl implements ChartGenerationService {

    private static final long ONE_MB = 1024 * 1024L;
    private static final List<String> VALID_FILE_SUFFIX_LIST = Arrays.asList("xlsx", "xls", "csv");

    @Resource
    private ChartService chartService;

    @Override
    public Chart createPendingChart(MultipartFile multipartFile, GenChartByAiRequest request, User loginUser) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "请求参数为空");
        ThrowUtils.throwIf(multipartFile == null, ErrorCode.PARAMS_ERROR, "文件为空");
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);

        String name = request.getName();
        String goal = request.getGoal();
        String chartType = request.getChartType();

        ThrowUtils.throwIf(StringUtils.isBlank(goal), ErrorCode.PARAMS_ERROR, "目标为空");
        ThrowUtils.throwIf(StringUtils.isNotBlank(name) && name.length() > 100, ErrorCode.PARAMS_ERROR, "名称过长");
        ThrowUtils.throwIf(multipartFile.getSize() > ONE_MB, ErrorCode.PARAMS_ERROR, "文件超过 1M");

        String suffix = StringUtils.lowerCase(FileUtil.getSuffix(multipartFile.getOriginalFilename()));
        ThrowUtils.throwIf(!VALID_FILE_SUFFIX_LIST.contains(suffix), ErrorCode.PARAMS_ERROR, "文件后缀非法，请上传 .xlsx、.xls 或 .csv");

        String csvData = ExcelUtils.multipartToCsvData(multipartFile);
        ThrowUtils.throwIf(StringUtils.isBlank(csvData), ErrorCode.PARAMS_ERROR, "表格数据为空，请检查文件是否为有效 UTF-8 CSV 或 Excel");

        Chart chart = new Chart();
        chart.setName(name);
        chart.setGoal(goal);
        chart.setChartData(csvData);
        chart.setChartType(chartType);
        chart.setStatus("wait");
        chart.setUserId(loginUser.getId());
        boolean saveResult = chartService.save(chart);
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "图表保存失败");
        return chart;
    }
}
