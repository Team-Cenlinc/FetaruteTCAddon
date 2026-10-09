package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.junit.jupiter.api.Test;

final class DispatchPriorityPolicyTest {

  @Test
  void runtimePriorityOrdersMainlineDepotExitAndReturn() {
    int mainline = DispatchPriorityPolicy.runtimePriority(0, RouteOperationType.OPERATION, false);
    int depotExit = DispatchPriorityPolicy.runtimePriority(0, RouteOperationType.OPERATION, true);
    int create = DispatchPriorityPolicy.runtimePriority(0, RouteOperationType.CREATE, true);
    int returning = DispatchPriorityPolicy.runtimePriority(0, RouteOperationType.RETURN, false);

    assertTrue(mainline > depotExit);
    assertEquals(depotExit, create);
    assertTrue(depotExit > returning);
  }

  @Test
  void depotSpawnPriorityDoesNotUseLegacyHardBoost() {
    assertEquals(
        DispatchPriorityPolicy.DEPOT_EXIT_OFFSET,
        DispatchPriorityPolicy.depotSpawnPriority(RouteOperationType.CREATE, 0));
    assertTrue(
        DispatchPriorityPolicy.depotSpawnPriority(RouteOperationType.OPERATION, 0)
            < DispatchPriorityPolicy.OPERATION_OFFSET);
    assertEquals(
        DispatchPriorityPolicy.RETURN_OFFSET,
        DispatchPriorityPolicy.depotSpawnPriority(RouteOperationType.RETURN, 0));
  }

  /** 叫车票与叫来的车不论交路类型都排在回库车之后。 */
  @Test
  void calledTrainsRankLowestWhateverTheRoute() {
    for (RouteOperationType type : RouteOperationType.values()) {
      assertEquals(
          DispatchPriorityPolicy.CALLED_OFFSET,
          DispatchPriorityPolicy.depotSpawnPriority(type, 0, true));
      assertEquals(
          DispatchPriorityPolicy.CALLED_OFFSET,
          DispatchPriorityPolicy.operationOffset(type, false, true));
      assertEquals(
          DispatchPriorityPolicy.depotSpawnPriority(type, 0),
          DispatchPriorityPolicy.depotSpawnPriority(type, 0, false),
          "不是叫车票时与原来一样");
    }
    assertTrue(DispatchPriorityPolicy.CALLED_OFFSET < DispatchPriorityPolicy.RETURN_OFFSET);
  }
}
