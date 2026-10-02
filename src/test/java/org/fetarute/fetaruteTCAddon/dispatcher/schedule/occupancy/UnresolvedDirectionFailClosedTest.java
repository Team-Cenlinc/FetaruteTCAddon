package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;
import org.junit.jupiter.api.Test;

/**
 * “方向已被明确判定为不可确定”必须 fail-closed 的回归。
 *
 * <p>语义解析器判定歧义时，冲突 key 不会出现在 {@code corridorDirections} 里。在本次修复之前，这与“该冲突不在本次计划内” 完全无法区分：{@code
 * blockedKeys} 只活在 {@code OccupancyRequestBuilder} 内部，从不随请求下发。下游因此继续沿回退链 推断方向，把已被否决的结论重新变成看似确定的方向。
 *
 * <p>这些用例固定两件事：被显式标记的 key 不得由任何回退复活；<b>未</b>被标记的路径行为保持不变。
 */
class UnresolvedDirectionFailClosedTest {

  /** 目标冲突：section 形态，轴为 {@code OP:S:ALFA:1~OP:S:BRAVO:1}。 */
  private static final String CONFLICT_KEY = "single:section:bridge:OP:S:ALFA:1~OP:S:BRAVO:1";

  /**
   * 与 {@link #CONFLICT_KEY} 同轴的锚定变体。
   *
   * <p>生产日志里两族键正是这样成对出现的（{@code single:section:bridge:*} 401 次、 {@code single:<INTERVAL锚>:*} 576
   * 次），{@code equivalentSectionToken} 会把二者视为同一走廊。
   */
  private static final String EQUIVALENT_KEY = "single:OP:CGL:WYB:1:005:OP:S:ALFA:1~OP:S:BRAVO:1";

  private SimpleOccupancyManager manager() {
    return new SimpleOccupancyManager(
        (routeId, resource) -> Duration.ZERO,
        SignalAspectPolicy.defaultPolicy(),
        new SignalEventBus());
  }

  private OccupancyRequest request(
      String trainName,
      Map<String, CorridorDirection> declaredDirections,
      Set<String> unresolvedDirectionKeys) {
    OccupancyResource conflict = OccupancyResource.forConflict(CONFLICT_KEY);
    return new OccupancyRequest(
        trainName,
        Optional.empty(),
        Instant.now(),
        List.of(conflict),
        declaredDirections,
        Map.of(),
        0,
        AuthorizationPurpose.RUNTIME_MOVE,
        Map.of(),
        Map.of(conflict, ResourceIntent.MOVEMENT_REQUIRED),
        Optional.empty(),
        unresolvedDirectionKeys);
  }

  /** 让冲突区内先有一辆外车，方向相关的准入判定才会真正参与。 */
  private void placeExternalOccupant(SimpleOccupancyManager manager, CorridorDirection direction) {
    assertTrue(
        manager.acquire(request("occupant", Map.of(CONFLICT_KEY, direction), Set.of())).allowed(),
        "外车应当能先取得该单线冲突");
  }

  @Test
  void equivalentSectionTokenCannotResurrectAnExplicitlyUnresolvedDirection() {
    SimpleOccupancyManager manager = manager();
    placeExternalOccupant(manager, CorridorDirection.A_TO_B);

    // 请求没有给出目标 key 的方向，只给了同轴等价 key 的方向。
    // 修复前：等价 token 回退补出 A_TO_B，与外车同向 -> 放行。
    // 修复后：该 key 已被明确判定不可确定，任何回退都不得再给出方向 -> fail-closed。
    OccupancyDecision decision =
        manager.canEnter(
            request(
                "reverser",
                Map.of(EQUIVALENT_KEY, CorridorDirection.A_TO_B),
                Set.of(CONFLICT_KEY)));

    // 断言的是<b>判定原因</b>而不只是 allowed：只有方向门会产出这个 reason，
    // 用 allowed 断言会被同一现场里的普通 blocker 掩盖，无法隔离本次改动的效果。
    assertEquals(
        "single-conflict-direction-unknown",
        decision.reason(),
        "被显式判定为不可确定的方向不得由等价 section token 复活");
    assertFalse(decision.allowed());
  }

  @Test
  void equivalentSectionTokenStillResolvesWhenTheKeyIsNotMarked() {
    SimpleOccupancyManager manager = manager();
    placeExternalOccupant(manager, CorridorDirection.A_TO_B);

    // 未被标记时不得触发方向门——该现场可能因别的 blocker 被拒，但原因绝不能是方向未知，
    // 否则说明这次收紧波及了正常的同轴解析。
    OccupancyDecision decision =
        manager.canEnter(
            request("follower", Map.of(EQUIVALENT_KEY, CorridorDirection.A_TO_B), Set.of()));

    assertNotEquals(
        "single-conflict-direction-unknown", decision.reason(), "未被标记的 key 不应触发方向 fail-closed");
  }

  @Test
  void selfOwnedUnknownDirectionWithoutExternalPresenceStillProceeds() {
    SimpleOccupancyManager manager = manager();
    assertTrue(
        manager
            .acquire(request("solo", Map.of(CONFLICT_KEY, CorridorDirection.A_TO_B), Set.of()))
            .allowed());

    // 冲突区内没有别的列车时，自持 claim 不应挡住本车——这是既有的有意放行。
    // 本次收紧不得把它变严，否则单车在歧义枢纽会被硬卡住（WSD 那一类现象）。
    OccupancyDecision decision = manager.canEnter(request("solo", Map.of(), Set.of(CONFLICT_KEY)));

    assertTrue(decision.allowed(), "无外车时的自持未知方向放行必须保持不变：" + decision.reason());
  }

  @Test
  void explicitDirectionStillWinsWhenPresent() {
    SimpleOccupancyManager manager = manager();

    OccupancyDecision decision =
        manager.canEnter(
            request("mover", Map.of(CONFLICT_KEY, CorridorDirection.A_TO_B), Set.of()));

    assertTrue(decision.allowed(), "已解析出方向的请求不受影响：" + decision.reason());
  }

  @Test
  void unresolvedKeysDefaultToEmptyForExistingCallSites() {
    OccupancyResource conflict = OccupancyResource.forConflict(CONFLICT_KEY);
    OccupancyRequest legacy =
        new OccupancyRequest(
            "legacy",
            Optional.empty(),
            Instant.now(),
            List.of(conflict),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(conflict, ResourceIntent.MOVEMENT_REQUIRED));

    assertTrue(legacy.unresolvedDirectionKeys().isEmpty());
    assertFalse(legacy.directionExplicitlyUnresolved(CONFLICT_KEY));
  }
}
