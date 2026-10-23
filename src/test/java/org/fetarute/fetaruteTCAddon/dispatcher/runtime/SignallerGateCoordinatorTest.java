package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignallerGate;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.junit.jupiter.api.Test;

/** 闸协作者本身：没接闸或闸空闲时什么都不做；只交硬授权资源；闸出错按放行；停车保持只借行车计划不带资源；被拦时只撤区内排队项。 */
class SignallerGateCoordinatorTest {

  private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");
  private static final NodeId A = NodeId.of("SURC:S:AAA:1");
  private static final NodeId B = NodeId.of("SURC:S:BBB:1");
  private static final OccupancyResource SWITCHER = OccupancyResource.forConflict("switcher:x");
  private static final OccupancyResource EDGE = OccupancyResource.forEdge(EdgeId.undirected(A, B));
  private static final OccupancyResource REAR = OccupancyResource.forNode(A);

  /** 记下每次调用的闸。 */
  private static final class RecordingGate implements SignallerGate {
    final List<List<OccupancyResource>> admitted = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    Optional<Hold> answer = Optional.empty();
    boolean active = true;
    boolean moved = true;

    @Override
    public boolean active() {
      return active;
    }

    @Override
    public Optional<Hold> admit(
        String trainName, Collection<OccupancyResource> requested, Instant now) {
      admitted.add(List.copyOf(requested));
      return answer;
    }

    @Override
    public boolean waiting(String trainName) {
      calls.add("waiting:" + trainName);
      return true;
    }

    @Override
    public boolean renamed(String from, String to) {
      calls.add("renamed:" + from + "->" + to);
      return moved;
    }

    @Override
    public void forget(String trainName) {
      calls.add("forget:" + trainName);
    }
  }

  private static OccupancyRequest request() {
    return new OccupancyRequest(
        "train-A",
        Optional.empty(),
        NOW,
        List.of(REAR, EDGE, SWITCHER),
        Map.of(),
        Map.of(),
        0,
        AuthorizationPurpose.RUNTIME_MOVE,
        Map.of(),
        Map.of(
            REAR, ResourceIntent.PROTECTIVE_RETAIN,
            EDGE, ResourceIntent.MOVEMENT_REQUIRED,
            SWITCHER, ResourceIntent.MOVEMENT_REQUIRED));
  }

  @Test
  void withoutAGateNothingIsAskedAndNothingWakes() {
    List<String> woken = new ArrayList<>();
    SignallerGateCoordinator gates = new SignallerGateCoordinator(message -> {}, null);
    gates.setWakeRequester(woken::add);

    assertFalse(gates.active());
    assertEquals(Optional.empty(), gates.admit("train-A", request(), NOW));
    assertFalse(gates.waiting("train-A"));
    gates.renamed("train-A", "train-B");
    gates.forget("train-A");
    assertTrue(woken.isEmpty(), "没接闸：改名也不唤醒");
  }

  @Test
  void onlyHardAuthorityResourcesAreAskedInRequestOrder() {
    SignallerGateCoordinator gates = new SignallerGateCoordinator(message -> {}, null);
    RecordingGate gate = new RecordingGate();
    gates.setGate(gate);

    assertEquals(Optional.empty(), gates.admit("train-A", request(), NOW));
    assertEquals(List.of(List.of(EDGE, SWITCHER)), gate.admitted, "尾部保护不是这次要申请的进路");

    gate.answer = Optional.of(new SignallerGate.Hold("SURC:PPK", NOW.minusSeconds(7)));
    SignallerGate.Hold hold = gates.admit("train-A", request(), NOW).orElseThrow();
    assertEquals("box=SURC:PPK since=2026-10-20T07:59:53Z", hold.detail(NOW));
  }

  @Test
  void aFailingGateLetsTheKernelDecide() {
    List<String> logs = new ArrayList<>();
    SignallerGateCoordinator gates = new SignallerGateCoordinator(logs::add, null);
    gates.setGate(
        new SignallerGate() {
          @Override
          public Optional<Hold> admit(
              String trainName, Collection<OccupancyResource> requested, Instant now) {
            throw new IllegalStateException("boom");
          }

          @Override
          public boolean waiting(String trainName) {
            throw new IllegalStateException("boom");
          }
        });

    assertEquals(Optional.empty(), gates.admit("train-A", request(), NOW));
    assertFalse(gates.waiting("train-A"));
    assertTrue(logs.stream().anyMatch(line -> line.startsWith("SIGNALLER_GATE_FAILED")));
  }

  @Test
  void renameMigratesThenWakesTheNewName() {
    List<String> woken = new ArrayList<>();
    SignallerGateCoordinator gates = new SignallerGateCoordinator(message -> {}, null);
    gates.setWakeRequester(woken::add);
    RecordingGate gate = new RecordingGate();
    gates.setGate(gate);

    gates.renamed("old", "new");
    gates.forget("gone");

    assertEquals(List.of("renamed:old->new", "forget:gone"), gate.calls);
    assertEquals(List.of("new"), woken);

    gates.setWakeRequester(
        name -> {
          throw new IllegalStateException("not-configured");
        });
    gates.wake("new");
    gates.setWakeRequester(null);
    gates.wake("new");
    gates.setGate(null);
    assertFalse(gates.active());
  }

  /** 闸空闲（此刻没有人工控制区）：与没接闸逐项一致——不整理申请、不问闸、不认等待；许可与扣车照样随车迁移、销毁时照样清掉。 */
  @Test
  void anIdleGateBehavesExactlyLikeNoGate() {
    List<String> woken = new ArrayList<>();
    SignallerGateCoordinator gates = new SignallerGateCoordinator(message -> {}, null);
    gates.setWakeRequester(woken::add);
    RecordingGate gate = new RecordingGate();
    gate.active = false;
    gate.answer = Optional.of(new SignallerGate.Hold("SURC:PPK", NOW));
    gates.setGate(gate);

    assertFalse(gates.active());
    assertEquals(Optional.empty(), gates.admit("train-A", request(), NOW));
    assertTrue(gate.admitted.isEmpty(), "闸空闲：连申请列表都不整理，直接放行");
    assertFalse(gates.waiting("train-A"));
    assertTrue(gate.calls.isEmpty(), "闸空闲：不问等待");

    gates.renamed("old", "new");
    gates.forget("gone");
    assertEquals(List.of("renamed:old->new", "forget:gone"), gate.calls);
    assertEquals(List.of("new"), woken);
  }

  @Test
  void renameWakesTheNewNameOnlyWhenSomethingMoved() {
    List<String> woken = new ArrayList<>();
    SignallerGateCoordinator gates = new SignallerGateCoordinator(message -> {}, null);
    gates.setWakeRequester(woken::add);
    RecordingGate gate = new RecordingGate();
    gate.moved = false;
    gates.setGate(gate);

    gates.renamed("old", "new");

    assertEquals(List.of("renamed:old->new"), gate.calls);
    assertTrue(woken.isEmpty(), "没有许可、等待或扣车迁过去：不唤醒");
  }

  /** 被拦时撤掉本控制区资源上的排队项，区外的排队不动；没接上资源归属时什么都不撤。 */
  @Test
  void withdrawsOnlyQueueEntriesInsideTheBlockingBox() {
    OccupancyResource inBox = OccupancyResource.forConflict("switcher:SWITCHER:in");
    OccupancyResource outside = OccupancyResource.forConflict("switcher:SWITCHER:out");
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> java.time.Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.now();
    manager.touchQueues(
        new OccupancyRequest(
            "train-A", Optional.empty(), now, List.of(inBox, outside), Map.of(), 0));
    assertEquals(List.of(inBox, outside), queuedFor(manager, "train-A"), "夹具：两处都排上了");
    List<String> logs = new ArrayList<>();
    SignallerGateCoordinator gates = new SignallerGateCoordinator(logs::add, manager);
    SignallerGate.Hold hold = new SignallerGate.Hold("SURC:SPB", now);

    assertEquals(0, gates.withdrawQueuedEntries("train-A", hold), "没接资源归属：不撤");
    assertEquals(List.of(inBox, outside), queuedFor(manager, "train-A"));

    gates.setZoneMembership((box, resource) -> box.equals("SURC:SPB") && resource.equals(inBox));
    assertEquals(1, gates.withdrawQueuedEntries("TRAIN-A", hold));
    assertEquals(List.of(outside), queuedFor(manager, "train-A"), "区外的排队不动");
    assertEquals(0, gates.withdrawQueuedEntries("train-A", hold), "撤过就没有了");
    assertTrue(logs.stream().anyMatch(line -> line.startsWith("SIGNALLER_QUEUE_WITHDRAWN")));
  }

  private static List<OccupancyResource> queuedFor(SimpleOccupancyManager manager, String train) {
    return manager.snapshotQueues().stream()
        .filter(
            snapshot ->
                snapshot.entries().stream().anyMatch(entry -> entry.trainName().equals(train)))
        .map(snapshot -> snapshot.resource())
        .sorted(java.util.Comparator.comparing(OccupancyResource::key))
        .toList();
  }

  @Test
  void blockedDecisionHasNoBlockers() {
    OccupancyDecision decision = SignallerGateCoordinator.blockedDecision(NOW);

    assertFalse(decision.allowed());
    assertEquals(SignalAspect.STOP, decision.signal());
    assertTrue(decision.blockers().isEmpty(), "没有阻挡者：不进等待图与互卡检测");
    assertFalse(decision.conflictRelease());
    assertEquals(SignallerGateCoordinator.DECISION_REASON, decision.reason());
  }

  @Test
  void stopHoldPlanKeepsTheDirectedPlanButNoResources() {
    DirectedTraversalContext context =
        new DirectedTraversalContext(
            "train-A",
            Optional.empty(),
            3,
            Optional.of(A),
            Optional.empty(),
            Optional.of(A),
            Optional.of(B),
            List.of(A, B),
            List.of(new DirectedTraversalContext.DirectedEdge(EdgeId.undirected(A, B), A, B)),
            Map.of(),
            Map.of(),
            "TEST",
            1L,
            1L,
            "req",
            Optional.empty());
    OccupancyRequest request = request().withDirectedContext(Optional.of(context));

    OccupancyRequest plan = SignallerGateCoordinator.withoutQueuePosition(request);

    assertTrue(plan.resourceList().isEmpty(), "不带资源：停车保持不会替它在前方冲突区排队");
    assertEquals(
        request.movementPlanSnapshot().orElseThrow().expandedPathNodes(),
        plan.movementPlanSnapshot().orElseThrow().expandedPathNodes(),
        "方向与路径证据照旧随有向上下文保留");
    assertEquals(request.priority(), plan.priority());
  }
}
