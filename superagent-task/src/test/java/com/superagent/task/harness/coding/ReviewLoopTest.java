package com.superagent.task.harness.coding;

import com.superagent.task.harness.HarnessHost.ReviewLimits;
import com.superagent.task.harness.HarnessState;
import com.superagent.task.harness.HarnessState.Finding;
import com.superagent.task.harness.HarnessState.ReviewRound;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** When the review loop gives up and asks the user, and how one round's issue is told to be the last one's. */
class ReviewLoopTest {

    private static final ReviewLimits LIMITS = new ReviewLimits(8, 30);

    @Test
    void eachStopRuleStopsOnItsOwn() {
        // The same issue through two fixes.
        List<ReviewRound> stuck = List.of(failed(1, "1", "2"), failed(2, "1"), failed(3, "1", "3"));
        assertThat(ReviewLoop.stop(stuck, LIMITS, BigDecimal.ZERO)).contains("修了 2 轮还在", "#1");
        assertThat(ReviewLoop.stop(stuck.subList(0, 2), LIMITS, BigDecimal.ZERO)).isNull();

        // The count not falling two rounds running: 3 → 3 → 4, every issue new.
        List<ReviewRound> flat = List.of(failed(1, "1", "2", "3"), failed(2, "4", "5", "6"), failed(3, "7", "8", "9", "10"));
        assertThat(ReviewLoop.stop(flat, LIMITS, BigDecimal.ZERO)).contains("3 → 3 → 4");
        List<ReviewRound> falling = List.of(failed(1, "1", "2", "3"), failed(2, "4", "5", "6"), failed(3, "7"));
        assertThat(ReviewLoop.stop(falling, LIMITS, BigDecimal.ZERO)).isNull();

        // Rounds and spend.
        assertThat(ReviewLoop.stop(List.of(failed(1, "1"), failed(2, "2")), new ReviewLimits(2, 30), BigDecimal.ZERO))
                .contains("上限（2 轮）");
        assertThat(ReviewLoop.stop(List.of(failed(1, "1")), LIMITS, new BigDecimal("30.5"))).contains("费用上限");
    }

    @Test
    void anIssueKeepsItsIdByNumberOrByPlaceAndWords() {
        HarnessState s = new HarnessState();
        s.nextFindingId = 5;
        List<Finding> previous = List.of(new Finding("high", "读取文件时没有关闭流", "src/A.java:10", "open", "3"));
        List<Finding> found = ReviewLoop.identify(s, List.of(
                new Finding("high", "读取文件后没有关闭输入流", "src/A.java:42", "open", null),
                new Finding("medium", "完全无关的另一个问题", "src/B.java:1", "open", null)), previous);
        assertThat(found).extracting(Finding::id).containsExactly("3", "5");

        s.waived.add("3");
        assertThat(ReviewLoop.identify(s, List.of(new Finding("high", "x", null, "open", "3")), previous))
                .singleElement().extracting(Finding::state).isEqualTo("waived");
    }

    private static ReviewRound failed(int round, String... ids) {
        List<Finding> findings = Arrays.stream(ids).map(id -> new Finding("high", "问题 " + id, null, "open", id)).toList();
        return new ReviewRound(round, "review", Instant.now(), "m", "FAIL", false, findings.size(), findings, List.of(),
                List.of(), false);
    }
}
