package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.junit.jupiter.api.Test;

/**
 * 等待图的强连通分量换成 Tarjan、trace 改为按开关拼之后，规划结论必须逐项不变。
 *
 * <p>强连通分量用旧的「逐点双向可达」实现作对照，在随机图上比对结果与顺序（含大小写不同的同名车）。
 */
class SmartWaitForPlannerGraphTest {

  private static final Instant CAPTURED_AT = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void tarjanMatchesPairwiseReachabilityOnRandomGraphs() {
    SplittableRandom random = new SplittableRandom(20261020L);
    for (int round = 0; round < 600; round++) {
      Map<String, Set<String>> graph = randomGraph(random, 1 + random.nextInt(28));
      List<List<String>> expected = spelled(List.copyOf(referenceComponents(graph)));
      List<List<String>> actual = spelled(SmartWaitForPlanner.stronglyConnectedComponents(graph));
      assertEquals(expected, actual, "round " + round + " graph " + graph);
    }
  }

  @Test
  void tarjanHandlesLongChainsAndSingleRingWithoutRecursion() {
    Map<String, Set<String>> ring = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    int size = 5_000;
    for (int i = 0; i < size; i++) {
      ring.computeIfAbsent("T" + i, unused -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
          .add("T" + ((i + 1) % size));
    }
    List<Set<String>> components = SmartWaitForPlanner.stronglyConnectedComponents(ring);
    assertEquals(1, components.size());
    assertEquals(size, components.get(0).size());

    Map<String, Set<String>> chain = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (int i = 0; i < size; i++) {
      chain
          .computeIfAbsent("T" + i, unused -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
          .add("T" + (i + 1));
      chain.computeIfAbsent("T" + (i + 1), unused -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
    }
    assertTrue(SmartWaitForPlanner.stronglyConnectedComponents(chain).isEmpty());
  }

  @Test
  void emptyGraphResultMatchesPlanningAnEmptyInput() {
    SmartWaitForPlanner planner = new SmartWaitForPlanner();
    for (SmartDispatcherPlannerMode mode :
        List.of(
            SmartDispatcherPlannerMode.OBSERVE_ONLY,
            SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD)) {
      for (boolean traces : List.of(true, false)) {
        SmartWaitForPlanner.PlanResult planned =
            planner.plan(
                new SmartWaitForPlanner.PlannerInput(
                    CAPTURED_AT, settings(mode), List.of(), Map.of(), Set.of(), Set.of(), traces));
        assertEquals(planned, SmartWaitForPlanner.emptyGraphResult(traces), mode + " " + traces);
      }
    }
  }

  /** 关掉 trace 只省字符串：除 traceLines 外，随机等待图上的规划结论逐项相同。 */
  @Test
  void disablingTracesChangesNothingButTraceLines() {
    SmartWaitForPlanner planner = new SmartWaitForPlanner();
    SplittableRandom random = new SplittableRandom(7L);
    int selected = 0;
    for (int round = 0; round < 400; round++) {
      List<SmartWaitForPlanner.InputEdge> edges = randomEdges(random);
      Map<String, SmartWaitForPlanner.TrainState> states = statesFor(edges, random);
      SmartWaitForPlanner.PlannerSettings settings =
          settings(SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD);
      SmartWaitForPlanner.PlanResult traced =
          planner.plan(
              new SmartWaitForPlanner.PlannerInput(
                  CAPTURED_AT, settings, edges, states, Set.of(), Set.of(), true));
      SmartWaitForPlanner.PlanResult quiet =
          planner.plan(
              new SmartWaitForPlanner.PlannerInput(
                  CAPTURED_AT, settings, edges, states, Set.of(), Set.of(), false));
      assertFalse(traced.traceLines().isEmpty());
      assertTrue(quiet.traceLines().isEmpty());
      assertEquals(traced.graphHash(), quiet.graphHash());
      assertEquals(traced.selectedPlanHash(), quiet.selectedPlanHash());
      assertEquals(traced.throttleKey(), quiet.throttleKey());
      assertEquals(traced.candidates(), quiet.candidates());
      assertEquals(traced.selectedPlan(), quiet.selectedPlan());
      assertEquals(traced.directionAuditNeeded(), quiet.directionAuditNeeded());
      assertEquals(traced.hardDeadlockEvidenceStrong(), quiet.hardDeadlockEvidenceStrong());
      if (traced.selectedPlan().isPresent()) {
        selected++;
      }
    }
    assertTrue(selected > 20, "随机图里应有足够多选中方案的样本，实际 " + selected);
  }

  /** 入图筛选与规划时实际进图的边一致。 */
  @Test
  void admittedEdgesMatchTheEdgesThePlannerAdmits() {
    SmartWaitForPlanner planner = new SmartWaitForPlanner();
    SplittableRandom random = new SplittableRandom(11L);
    for (int round = 0; round < 200; round++) {
      List<SmartWaitForPlanner.InputEdge> edges = randomEdges(random);
      SmartWaitForPlanner.PlannerSettings settings =
          settings(SmartDispatcherPlannerMode.OBSERVE_ONLY);
      SmartWaitForPlanner.PlanResult result =
          planner.plan(
              new SmartWaitForPlanner.PlannerInput(
                  CAPTURED_AT, settings, edges, Map.of(), Set.of(), Set.of(), true));
      long admittedTraces =
          result.traceLines().stream()
              .filter(line -> line.startsWith("SMART_DISPATCH_INPUT_EDGE blockedTrain="))
              .count();
      assertEquals(admittedTraces, SmartWaitForPlanner.admittedEdges(edges, settings).size());
    }
  }

  private static SmartWaitForPlanner.PlannerSettings settings(SmartDispatcherPlannerMode mode) {
    return new SmartWaitForPlanner.PlannerSettings(
        true, mode, 4, 60, 10_000, true, false, false, true);
  }

  private static List<SmartWaitForPlanner.InputEdge> randomEdges(SplittableRandom random) {
    int trains = 2 + random.nextInt(7);
    int count = 1 + random.nextInt(trains * 2);
    List<SmartWaitForPlanner.InputEdge> edges = new ArrayList<>();
    CorridorDirection[] directions = CorridorDirection.values();
    String[] resources = {
      "CONFLICT:single:WS", "CONFLICT:single:EAST", "CONFLICT:switcher:705", "NODE:SURC:A:1"
    };
    for (int i = 0; i < count; i++) {
      String blocked = "T" + random.nextInt(trains);
      String blocker = "T" + random.nextInt(trains);
      boolean queue = random.nextInt(5) == 0;
      edges.add(
          new SmartWaitForPlanner.InputEdge(
              blocked,
              blocker,
              resources[random.nextInt(resources.length)],
              null,
              "HARD_OCCUPANCY",
              queue ? "QUEUE_POSITION" : "MOVEMENT_REQUIRED",
              queue ? "QUEUE_POSITION" : "MOVEMENT_REQUIRED",
              "test",
              directions[random.nextInt(directions.length)],
              java.util.Optional.empty(),
              random.nextInt(4) == 0 ? 20_000L : 10L,
              !queue,
              random.nextBoolean()));
    }
    return edges;
  }

  private static Map<String, SmartWaitForPlanner.TrainState> statesFor(
      List<SmartWaitForPlanner.InputEdge> edges, SplittableRandom random) {
    Map<String, SmartWaitForPlanner.TrainState> states = new HashMap<>();
    for (SmartWaitForPlanner.InputEdge edge : edges) {
      for (String train : List.of(edge.blockedTrain(), edge.blockerTrain())) {
        if (states.containsKey(train) || random.nextInt(6) == 0) {
          continue;
        }
        states.put(
            train,
            new SmartWaitForPlanner.TrainState(
                train,
                "COMP:OP:LINE:" + (random.nextBoolean() ? "route" : "other"),
                1,
                train + ":current",
                random.nextInt(5) == 0 ? "-" : train + ":next",
                train + ":current",
                random.nextBoolean() ? CorridorDirection.A_TO_B : CorridorDirection.B_TO_A,
                "RUNTIME_ROUTE_CONTEXT",
                "-",
                random.nextInt(120),
                random.nextBoolean(),
                false,
                false,
                false,
                false,
                false,
                "NONE"));
      }
    }
    return states;
  }

  private static Map<String, Set<String>> randomGraph(SplittableRandom random, int size) {
    Map<String, Set<String>> graph = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    double density = random.nextDouble() * 0.25;
    for (int i = 0; i < size; i++) {
      graph.computeIfAbsent(
          spelling(random, i), unused -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
    }
    for (int from = 0; from < size; from++) {
      for (int to = 0; to < size; to++) {
        if (random.nextDouble() < density) {
          // 与规划器的邻接表同形：两端都登记为键，写法可能与已有键大小写不同。
          String blocked = spelling(random, from);
          String blocker = spelling(random, to);
          graph
              .computeIfAbsent(blocked, unused -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
              .add(blocker);
          graph.computeIfAbsent(blocker, unused -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
        }
      }
    }
    return graph;
  }

  private static String spelling(SplittableRandom random, int index) {
    return (random.nextBoolean() ? "T" : "t") + "-" + index;
  }

  private static List<List<String>> spelled(List<Set<String>> components) {
    List<List<String>> result = new ArrayList<>();
    for (Set<String> component : components) {
      result.add(List.copyOf(component));
    }
    return result;
  }

  /** 换成 Tarjan 之前的实现，原样保留作对照。 */
  private static Set<Set<String>> referenceComponents(Map<String, Set<String>> graph) {
    Set<Set<String>> result = new LinkedHashSet<>();
    Set<String> nodes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    nodes.addAll(graph.keySet());
    graph.values().forEach(nodes::addAll);
    for (String node : nodes) {
      Set<String> reachable = reachable(graph, node);
      Set<String> component = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      for (String other : nodes) {
        if (reachable.contains(other) && reachable(graph, other).contains(node)) {
          component.add(other);
        }
      }
      if (component.size() > 1) {
        result.add(component);
      }
    }
    return result;
  }

  private static Set<String> reachable(Map<String, Set<String>> graph, String start) {
    Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    ArrayDeque<String> queue = new ArrayDeque<>();
    queue.add(start);
    while (!queue.isEmpty()) {
      String node = queue.removeFirst();
      if (!seen.add(node)) {
        continue;
      }
      for (String next : graph.getOrDefault(node, Set.of())) {
        if (!seen.contains(next)) {
          queue.addLast(next);
        }
      }
    }
    return seen;
  }
}
