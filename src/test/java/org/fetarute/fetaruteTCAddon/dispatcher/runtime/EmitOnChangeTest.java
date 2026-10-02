package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「只在变化时输出」的去重判据。
 *
 * <p>它存在的理由是体量：{@code SIGNAL_ASPECT_STAGING} 按 tick 产生（raw 约 5000 行/分钟）， 此前全被诊断预算当作 {@code
 * OTHER_DIAGNOSTIC} 丢弃——实服 2026-09-17 一轮丢 572957 行， 于是「绿灯为什么突然变红」无法归因。按变化去重后才能既进必留名单又不撑爆日志。
 *
 * <p>两个方向都要钉死：**变化必须输出**（漏掉就等于没加这条 trace）， **不变必须静默**（否则必留身份会把日志和诊断预算一起吃掉）。
 */
class EmitOnChangeTest {

  @Test
  @DisplayName("首次出现与签名变化时必须输出")
  void firstSightAndChangesEmit() {
    Map<String, String> last = new HashMap<>();
    assertTrue(OccupancyClaimEvidence.shouldEmitOnChange(last, "T1", "result=PROCEED"));
    assertTrue(OccupancyClaimEvidence.shouldEmitOnChange(last, "T1", "result=STOP"));
    assertTrue(OccupancyClaimEvidence.shouldEmitOnChange(last, "T1", "result=PROCEED"));
  }

  @Test
  @DisplayName("签名不变时必须静默")
  void unchangedSignatureIsSilent() {
    Map<String, String> last = new HashMap<>();
    assertTrue(OccupancyClaimEvidence.shouldEmitOnChange(last, "T1", "result=PROCEED"));
    assertFalse(OccupancyClaimEvidence.shouldEmitOnChange(last, "T1", "result=PROCEED"));
    assertFalse(OccupancyClaimEvidence.shouldEmitOnChange(last, "T1", "result=PROCEED"));
  }

  /** 去重必须按列车分开，否则两辆车会互相把对方的变化吃掉。 */
  @Test
  @DisplayName("不同列车之间互不影响")
  void dedupIsPerKey() {
    Map<String, String> last = new HashMap<>();
    assertTrue(OccupancyClaimEvidence.shouldEmitOnChange(last, "T1", "result=STOP"));
    assertTrue(OccupancyClaimEvidence.shouldEmitOnChange(last, "T2", "result=STOP"));
    assertFalse(OccupancyClaimEvidence.shouldEmitOnChange(last, "T1", "result=STOP"));
    assertFalse(OccupancyClaimEvidence.shouldEmitOnChange(last, "T2", "result=STOP"));
  }

  /** 拿不到去重状态时宁可输出：漏掉一次灯位变化比多打一行代价大得多。 */
  @Test
  @DisplayName("状态缺失时应放行输出而非静默")
  void missingStateFailsOpenTowardsEmitting() {
    assertTrue(OccupancyClaimEvidence.shouldEmitOnChange(null, "T1", "x"));
    assertTrue(OccupancyClaimEvidence.shouldEmitOnChange(new HashMap<>(), null, "x"));
    assertTrue(OccupancyClaimEvidence.shouldEmitOnChange(new HashMap<>(), "  ", "x"));
    assertTrue(OccupancyClaimEvidence.shouldEmitOnChange(new HashMap<>(), "T1", null));
  }
}
