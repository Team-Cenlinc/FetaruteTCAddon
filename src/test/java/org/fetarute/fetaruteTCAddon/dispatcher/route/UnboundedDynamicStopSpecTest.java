package org.fetarute.fetaruteTCAddon.dispatcher.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/**
 * 未声明轨道范围的 DYNAMIC 规范必须表示“该站现有的全部股道”。
 *
 * <p>历史上两处同名解析对“无范围”给出了不同默认值：{@link DynamicStopMatcher} 取 1..10，而 {@code RuntimeDispatchService}
 * 内的同名解析取 1..1。后果是列车被固化到 2 道后，{@code matchesStop} 认可该目标， 候选枚举却只产出 1
 * 道，二者不相等导致候选循环静默走空——列车被永久硬停在站外，而诊断里只剩 {@code no-available-dynamic-target rejections=[]}，说不出任何原因。
 *
 * <p>这些用例固定“无范围 = unbounded”这一语义，避免任何一侧再退回人为上限或单一 1 道。
 */
class UnboundedDynamicStopSpecTest {

  @Test
  void specWithoutRangeIsUnbounded() {
    Optional<DynamicStopMatcher.DynamicSpec> spec =
        DynamicStopMatcher.parseDynamicSpec("DYNAMIC:SURC:S:PPK");

    assertTrue(spec.isPresent(), "无范围规范应当可解析");
    assertTrue(spec.get().unbounded(), "无范围必须标记为 unbounded，而不是套用人为上限");
  }

  @Test
  void specWithExplicitRangeIsNotUnbounded() {
    Optional<DynamicStopMatcher.DynamicSpec> spec =
        DynamicStopMatcher.parseDynamicSpec("DYNAMIC:SURC:S:PPK:[1:3]");

    assertTrue(spec.isPresent());
    assertFalse(spec.get().unbounded());
    assertEquals(1, spec.get().fromTrack());
    assertEquals(3, spec.get().toTrack());
  }

  @Test
  void unboundedSpecMatchesTracksBeyondAnyArbitraryCap() {
    DynamicStopMatcher.DynamicSpec spec =
        DynamicStopMatcher.parseDynamicSpec("DYNAMIC:SURC:S:PPK").orElseThrow();

    // 2 道是本次实服里真正被分配、却因两套默认值不一致而卡死的那一条。
    assertTrue(DynamicStopMatcher.matches(NodeId.of("SURC:S:PPK:2"), spec));
    // 编号超过旧的人为上限 10 也必须匹配：无范围表达的是“全部股道”。
    assertTrue(DynamicStopMatcher.matches(NodeId.of("SURC:S:PPK:12"), spec));
  }

  @Test
  void unboundedSpecStillRejectsOtherStationsAndThroats() {
    DynamicStopMatcher.DynamicSpec spec =
        DynamicStopMatcher.parseDynamicSpec("DYNAMIC:SURC:S:PPK").orElseThrow();

    assertFalse(DynamicStopMatcher.matches(NodeId.of("SURC:S:RVS:1"), spec), "不得跨站匹配");
    assertFalse(DynamicStopMatcher.matches(NodeId.of("SURC:D:PPK:1"), spec), "不得跨类型匹配");
    assertFalse(DynamicStopMatcher.matches(NodeId.of("SURC:S:PPK:1:001"), spec), "咽喉（5 段）不是股道");
  }
}
