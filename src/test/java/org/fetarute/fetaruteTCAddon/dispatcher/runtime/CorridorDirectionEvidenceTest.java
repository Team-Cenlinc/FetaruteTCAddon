package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 割环用的走廊方向归约——这条判据长期返回 UNKNOWN，等待图因此从未割过一次环。
 *
 * <p>实服 2026-09-17 第二十六轮：环被检测到 1035 次（3 对 WS 车占 1002 次）， {@code SMART_DISPATCH_CYCLE_CANDIDATE}
 * <b>0 次</b>，全部 {@code INSUFFICIENT_DIRECTION_EVIDENCE}。 最长一辆在 CHT 折返站卡了 1019 秒、占死那个单站台尽头线。
 *
 * <p>本类钉两件相反的事，缺一不可：**能给方向时必须给**（否则割环还是不动）， **不该给时绝不能给**（否则换向后的车会取回旧方向、绕过对向屏障——那是撞车，不是卡车）。
 */
class CorridorDirectionEvidenceTest {

  private static final String K1 = "single:section:CHT~PHI";
  private static final String K2 = "single:section:PHI~LYM";

  /** 证据一致时必须给出方向——这一条是"割环能不能动手"的下限。 */
  @Test
  @DisplayName("方向一致时应归约出该方向")
  void agreedDirectionIsReturned() {
    assertEquals(
        CorridorDirection.A_TO_B,
        OccupancyClaimEvidence.consistentCorridorDirection(
            map(K1, CorridorDirection.A_TO_B, K2, CorridorDirection.A_TO_B), Set.of()));
  }

  /**
   * 安全红线：key 落在 unresolvedDirectionKeys 里就绝不能给方向。
   *
   * <p>{@code OccupancyRequest} 的文档写明——该集合表示"证据本身矛盾或不足"， 任何回退推断都不得再给方向，否则换向后的列车会沿回退链取回旧方向
   * claim、绕过对向屏障。 本轮卡得最久的车正在 CHT 折返，换向点就是这条红线要防的场景。
   */
  @Test
  @DisplayName("命中 unresolvedDirectionKeys 时必须退回 UNKNOWN")
  void unresolvedKeyForcesUnknown() {
    assertEquals(
        CorridorDirection.UNKNOWN,
        OccupancyClaimEvidence.consistentCorridorDirection(
            map(K1, CorridorDirection.A_TO_B, K2, CorridorDirection.A_TO_B), Set.of(K2)));
  }

  /** 只要有**任何一个** key 不可确定就整体作废，不能只跳过那一个继续归约。 */
  @Test
  @DisplayName("单个 unresolved key 即令整体作废")
  void oneUnresolvedKeyPoisonsTheWholeReduction() {
    assertEquals(
        CorridorDirection.UNKNOWN,
        OccupancyClaimEvidence.consistentCorridorDirection(
            map(K1, CorridorDirection.B_TO_A), Set.of(K1)));
  }

  /** 同一辆车在不同单线区方向相反：证据自相矛盾，fail-closed。 */
  @Test
  @DisplayName("方向互相矛盾时应退回 UNKNOWN")
  void conflictingDirectionsFailClosed() {
    assertEquals(
        CorridorDirection.UNKNOWN,
        OccupancyClaimEvidence.consistentCorridorDirection(
            map(K1, CorridorDirection.A_TO_B, K2, CorridorDirection.B_TO_A), Set.of()));
  }

  /** 缺键与"已判定不可确定"不是一回事：UNKNOWN 值跳过即可，不该毒化整体。 */
  @Test
  @DisplayName("表内的 UNKNOWN 值应被跳过而非作废")
  void unknownValuesAreSkippedNotPoisonous() {
    assertEquals(
        CorridorDirection.B_TO_A,
        OccupancyClaimEvidence.consistentCorridorDirection(
            map(K1, CorridorDirection.UNKNOWN, K2, CorridorDirection.B_TO_A), Set.of()));
  }

  /** 无证据就是无方向。 */
  @Test
  @DisplayName("空表或空入参应为 UNKNOWN")
  void emptyEvidenceIsUnknown() {
    assertEquals(
        CorridorDirection.UNKNOWN,
        OccupancyClaimEvidence.consistentCorridorDirection(Map.of(), Set.of()));
    assertEquals(
        CorridorDirection.UNKNOWN,
        OccupancyClaimEvidence.consistentCorridorDirection(null, Set.of()));
    assertEquals(
        CorridorDirection.UNKNOWN,
        OccupancyClaimEvidence.consistentCorridorDirection(
            map(K1, CorridorDirection.UNKNOWN), Set.of()));
  }

  private static Map<String, CorridorDirection> map(Object... kv) {
    Map<String, CorridorDirection> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], (CorridorDirection) kv[i + 1]);
    }
    return m;
  }
}
