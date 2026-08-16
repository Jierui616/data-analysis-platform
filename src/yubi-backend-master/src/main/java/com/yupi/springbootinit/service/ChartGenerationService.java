package com.yupi.springbootinit.service;

import com.yupi.springbootinit.model.dto.chart.GenChartByAiRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.entity.User;
import org.springframework.web.multipart.MultipartFile;

public interface ChartGenerationService {

    Chart createPendingChart(MultipartFile multipartFile, GenChartByAiRequest request, User loginUser);
}
