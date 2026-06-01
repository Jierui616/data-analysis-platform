package com.yupi.springbootinit.service.eda;

import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * InfoGain-EDA 核心逻辑单元测试（不依赖 Spring / ClickHouse）。
 */
public class InfoGainEdaUnitTest {

    @Test
    public void explorationMode_fromString_defaultsToFull() {
        assertEquals(ExplorationMode.FULL, ExplorationMode.from(null));
        assertEquals(ExplorationMode.RANDOM, ExplorationMode.from("random"));
        assertFalse(ExplorationMode.FIXED_STEP.useAdaptiveConvergence());
        assertTrue(ExplorationMode.FULL.useIgSelection());
        assertFalse(ExplorationMode.NO_IG.useIgSelection());
    }

    @Test
    public void infoGainCalculator_coveragePrefersUnexplored() {
        InfoGainCalculator calc = new InfoGainCalculator();
        Map<String, List<String>> dims = new LinkedHashMap<>();
        dims.put("metric", Arrays.asList("revenue"));
        dims.put("dimension", Arrays.asList("storeId"));
        Map<String, java.util.Set<String>> explored = new LinkedHashMap<>();
        explored.put("metric", new java.util.HashSet<>(Arrays.asList("revenue")));

        double igPartial = calc.calculateWithoutLineage(dims, explored, 0, 50_000);
        double igFresh = calc.calculateWithoutLineage(dims, new LinkedHashMap<>(), 0, 50_000);
        assertTrue(igFresh >= igPartial);
    }

    @Test
    public void hypothesisVerify_scoreConsistency_anomalyHighVariance() {
        HypothesisVerifyExecutor executor = new HypothesisVerifyExecutor();
        HypothesisVO h = new HypothesisVO();
        h.setType("anomaly");

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("avg_metric", 10.0));
        rows.add(row("avg_metric", 100.0));
        rows.add(row("avg_metric", 12.0));

        double score = invokeScoreConsistency(executor, h, rows);
        assertTrue(score > 0.5);
    }

    @Test
    public void blackboard_staleRoundsIncrement() {
        ExplorationBlackboard board = new ExplorationBlackboard();
        ExplorationBlackboard.SessionState state = board.getOrCreate("s1");
        state.recordPosteriorProgress("h1", 0.3);
        state.recordPosteriorProgress("h1", 0.31);
        state.recordPosteriorProgress("h1", 0.31);
        assertTrue(state.getStaleRounds("h1") >= 2);
    }

    private static Map<String, Object> row(String key, double val) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, val);
        return m;
    }

    private double invokeScoreConsistency(HypothesisVerifyExecutor executor, HypothesisVO h,
                                        List<Map<String, Object>> rows) {
        try {
            java.lang.reflect.Method m = HypothesisVerifyExecutor.class.getDeclaredMethod(
                    "scoreConsistency", HypothesisVO.class, List.class);
            m.setAccessible(true);
            return (double) m.invoke(executor, h, rows);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
