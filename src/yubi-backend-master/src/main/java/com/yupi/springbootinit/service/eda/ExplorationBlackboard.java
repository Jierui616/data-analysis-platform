package com.yupi.springbootinit.service.eda;

import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 探索共享状态黑板。
 * 维护每个探索会话的 S_explored、H_verified、H_pending、H_active 和轨迹 T。
 */
@Component
public class ExplorationBlackboard {

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public SessionState getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId, k -> new SessionState());
    }

    public SessionState get(String sessionId) {
        return sessions.get(sessionId);
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    @Data
    public static class SessionState {
        /** 已探索维度区域：dimensionType -> set of explored values */
        private Map<String, Set<String>> exploredRegions = new ConcurrentHashMap<>();

        /** 已验证假设 */
        private List<HypothesisVO> verifiedHypotheses = Collections.synchronizedList(new ArrayList<>());

        /** 待验证假设 */
        private List<HypothesisVO> pendingHypotheses = Collections.synchronizedList(new ArrayList<>());

        /** 当前活跃假设（本轮优先验证） */
        private List<HypothesisVO> activeHypotheses = Collections.synchronizedList(new ArrayList<>());

        /** 探索轨迹：查询ID列表 */
        private List<String> queryTrace = Collections.synchronizedList(new ArrayList<>());

        /** 最近 IG 最大值 */
        private double lastMaxIg = 1.0;

        /** 收敛计数器：连续 IG < epsilon 的轮次 */
        private int convergenceCounter = 0;

        /** 查询执行计数器 */
        private int queryCount = 0;

        /** 状态 */
        private String status = "RUNNING";

        /** 最近一次错误信息（FAILED 时展示） */
        private String lastError;

        public void markExplored(String dimensionType, String dimensionValue) {
            exploredRegions.computeIfAbsent(dimensionType, k -> ConcurrentHashMap.newKeySet())
                    .add(dimensionValue);
        }

        public boolean isExplored(String dimensionType, String dimensionValue) {
            Set<String> set = exploredRegions.get(dimensionType);
            return set != null && set.contains(dimensionValue);
        }

        public int getExploredRegionCount() {
            return exploredRegions.values().stream().mapToInt(Set::size).sum();
        }

        public void addVerified(HypothesisVO h) {
            h.setStatus("VERIFIED");
            verifiedHypotheses.add(h);
            pendingHypotheses.removeIf(p -> p.getHypothesisId().equals(h.getHypothesisId()));
            activeHypotheses.removeIf(a -> a.getHypothesisId().equals(h.getHypothesisId()));
        }

        public void addPending(HypothesisVO h) {
            h.setStatus("PENDING");
            pendingHypotheses.add(h);
        }

        public void addTrace(String queryId) {
            queryTrace.add(queryId);
            queryCount++;
        }

        public boolean checkConvergence(double epsilon, int requiredConsecutiveRounds) {
            if (lastMaxIg < epsilon) {
                convergenceCounter++;
            } else {
                convergenceCounter = 0;
            }
            return convergenceCounter >= requiredConsecutiveRounds;
        }

        public void resetConvergenceCounter() {
            convergenceCounter = 0;
        }
    }
}
