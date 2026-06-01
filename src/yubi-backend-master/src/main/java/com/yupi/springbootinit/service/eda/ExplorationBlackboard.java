package com.yupi.springbootinit.service.eda;

import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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

        /** 已验证假设（CopyOnWrite 避免与 getState 并发迭代死锁） */
        private List<HypothesisVO> verifiedHypotheses = new CopyOnWriteArrayList<>();

        /** 待验证假设 */
        private List<HypothesisVO> pendingHypotheses = new CopyOnWriteArrayList<>();

        /** 当前活跃假设（本轮优先验证） */
        private List<HypothesisVO> activeHypotheses = new CopyOnWriteArrayList<>();

        /** 探索轨迹：查询ID列表 */
        private List<String> queryTrace = new CopyOnWriteArrayList<>();

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

        /** 假设连续无后验提升轮次 */
        private Map<String, Integer> hypothesisStaleRounds = new ConcurrentHashMap<>();

        /** 上一轮假设后验，用于判断 stale */
        private Map<String, Double> hypothesisLastPosterior = new ConcurrentHashMap<>();

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

        public void recordPosteriorProgress(String hypothesisId, double newPosterior) {
            if (hypothesisId == null) {
                return;
            }
            Double last = hypothesisLastPosterior.get(hypothesisId);
            if (last == null || newPosterior > last + 0.02) {
                hypothesisStaleRounds.put(hypothesisId, 0);
            } else {
                hypothesisStaleRounds.merge(hypothesisId, 1, Integer::sum);
            }
            hypothesisLastPosterior.put(hypothesisId, newPosterior);
        }

        public int getStaleRounds(String hypothesisId) {
            return hypothesisStaleRounds.getOrDefault(hypothesisId, 0);
        }
    }
}
