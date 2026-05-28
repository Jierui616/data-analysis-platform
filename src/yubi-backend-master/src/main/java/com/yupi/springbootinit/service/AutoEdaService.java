package com.yupi.springbootinit.service;

import com.yupi.springbootinit.model.dto.autoeda.ContinueExplorationRequest;
import com.yupi.springbootinit.model.dto.autoeda.StartExplorationRequest;
import com.yupi.springbootinit.model.vo.autoeda.AutoEdaChartOptionVO;
import com.yupi.springbootinit.model.vo.autoeda.AutoEdaSessionSummaryVO;
import com.yupi.springbootinit.model.vo.autoeda.ExplorationStateVO;
import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import com.yupi.springbootinit.model.vo.autoeda.InsightReportVO;

import java.util.List;

public interface AutoEdaService {

    /**
     * 列出当前用户可用于自主探索的图表（MySQL chart 表）
     */
    List<AutoEdaChartOptionVO> listExplorableCharts(Long userId);

    /**
     * 当前用户的探索历史（用于回看报告）
     */
    List<AutoEdaSessionSummaryVO> listMySessions(Long userId, int limit);

    /**
     * 启动一次探索会话。异步执行探索循环，返回 sessionId。
     */
    String startExploration(StartExplorationRequest request, Long userId);

    /**
     * 轮询探索状态（前端每 2-3 秒调用）
     */
    ExplorationStateVO getState(String sessionId);

    /**
     * 获取最终报告（探索完成后调用）
     */
    InsightReportVO getReport(String sessionId);

    /**
     * 用户追问：基于已有会话继续探索
     */
    ExplorationStateVO continueExploration(ContinueExplorationRequest request);

    /**
     * 获取单个假设的详细信息
     */
    HypothesisVO getHypothesisDetail(String sessionId, String hypothesisId);

    /**
     * 停止探索
     */
    boolean stopExploration(String sessionId);

    /**
     * 删除探索记录（含报告与关联日志，仅本人可操作）
     */
    boolean deleteSession(String sessionId, Long userId);
}
