package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 相位第三层：为每个方向选一个 δ，使共用资源上的周期冲突最少。
 *
 * <p>前两层只看端点（往返对锚定、共用起点交错），沿线哪里交会看不见。这一组用例钉的是： 能减少"让不掉的冲突"时才付出端点等待、δ 有上限、同一输入永远同一结果、并列时零偏移胜出。
 */
class ResourcePhasePlannerTest {

  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String C = "OP:S:C:1";
  private static final int SEPARATION = 30;
  private static final int MAX_WAIT = 300;

  /** 一个单元至多几个完整候选：δ=0、四个粗档、三级细档各 ±。 */
  private static final int MAX_CANDIDATES_PER_UNIT = 11;

  /** 单股道直链 A–B–C：每段 100 blocks、10 bps，站间 10 秒。 */
  private static TimetableConflictChecker.GraphIndex index() {
    return TimetableConflictChecker.GraphIndex.of(
        TimetableTestFixtures.chain(
            List.of(A, B, C), new int[] {100, 100}, new double[] {10.0, 10.0}));
  }

  /** CREATE 按需求出现，周期模板看不到它；完整资源评分必须能改变运营相位来避开这趟出库。 */
  @Test
  void completedScoringIncludesDemandCreateMovements() {
    Fixture fixture = opposingPair(300);
    String direction = fixture.groups().get(0).directions().get(0).key();
    UUID route = fixture.groups().get(0).directions().get(0).routeIds().get(0);
    PhasePlanner.Phases initial =
        new PhasePlanner.Phases(Map.of(direction, 0), Map.of(), List.of());
    var graph = index();
    java.util.function.Function<PhasePlanner.Phases, ResourcePhasePlanner.CompletionScore>
        completion =
            candidate -> {
              var report =
                  TimetableConflictChecker.check(
                      graph,
                      fixture.profiles(),
                      List.of(
                          new TimetableConflictChecker.Movement("DEMAND-CREATE", route, 0),
                          new TimetableConflictChecker.Movement(
                              "OPERATION", route, candidate.effectivePhaseOf(direction))),
                      List.of(),
                      SEPARATION);
              return new ResourcePhasePlanner.CompletionScore(
                  report.conflicts().size(), 0, 0, report.conflicts().size(), 0);
            };

    var chosen =
        ResourcePhasePlanner.refineCompleted(
            initial,
            fixture.groups(),
            fixture.intervals(),
            300,
            java.util.Set.of(route),
            completion);

    assertTrue(completion.apply(initial).unabsorbable() > 0);
    assertEquals(0, completion.apply(chosen).unabsorbable());
    assertTrue(chosen.effectivePhaseOf(direction) > 0);
    assertEquals(
        chosen.deltaByDirection().get(direction),
        chosen.deltaByDirection().get(fixture.groups().get(0).directions().get(0).reverseKey()),
        "原地折返对必须保持整体平移，不能拆开接续");
  }

  /** 更少冲突但需要额外取消的候选不能胜出；评分调用不超过各单元候选数之和。 */
  @Test
  void completedSearchDoesNotTradeExtraCancellationsForFewerConflicts() {
    Fixture fixture = opposingPair(300);
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    var chosen =
        ResourcePhasePlanner.refineCompleted(
            fixture.phases(),
            fixture.groups(),
            fixture.intervals(),
            300,
            java.util.Set.of(),
            candidate -> {
              calls.incrementAndGet();
              boolean original =
                  candidate.deltaByDirection().equals(fixture.phases().deltaByDirection());
              return new ResourcePhasePlanner.CompletionScore(
                  original ? 10 : 0, original ? 0 : 1, 0, original ? 10 : 0, 0);
            });

    assertEquals(fixture.phases(), chosen);
    assertTrue(calls.get() <= 1 + 2 * MAX_CANDIDATES_PER_UNIT, () -> calls.get() + " 次");
  }

  /**
   * 单元内各候选并行评分：挑出的相位、写下的说明与评分次数都与串行相同，并列仍取先评的那个。
   *
   * <p>十六个互不相干的方向，评分是相位的确定函数、大量并列。
   */
  @Test
  void parallelCompletedScoringMatchesSerial() {
    List<ServiceGroupClassifier.Group> groups = new java.util.ArrayList<>();
    Map<String, Integer> intervals = new java.util.TreeMap<>();
    for (int i = 0; i < 16; i++) {
      String name = String.format(java.util.Locale.ROOT, "g%02d", i);
      groups.add(
          new ServiceGroupClassifier.Group(
              name,
              List.of(
                  new ServiceGroupClassifier.Direction(
                      "S" + i,
                      "T" + i,
                      List.of(),
                      List.of(TimetableTestFixtures.routeId("R" + i)))),
              List.of()));
      intervals.put(name, 300 + 60 * i);
    }
    PhasePlanner.Phases initial = new PhasePlanner.Phases(Map.of(), Map.of(), List.of());
    List<java.util.function.Function<PhasePlanner.Phases, ResourcePhasePlanner.CompletionScore>>
        scorers =
            List.of(
                phases -> score(phases, 7, 3),
                phases -> score(phases, 5, 2),
                phases -> score(phases, 11, 1),
                phases -> new ResourcePhasePlanner.CompletionScore(1, 0, 0, 0, 0));
    for (int threads : new int[] {2, 3, 4}) {
      try (BuildPool pool = BuildPool.open(threads)) {
        for (int i = 0; i < scorers.size(); i++) {
          var scorer = scorers.get(i);
          java.util.concurrent.atomic.AtomicInteger serialCalls =
              new java.util.concurrent.atomic.AtomicInteger();
          java.util.concurrent.atomic.AtomicInteger parallelCalls =
              new java.util.concurrent.atomic.AtomicInteger();
          PhasePlanner.Phases serial =
              ResourcePhasePlanner.refineCompleted(
                  initial,
                  groups,
                  intervals,
                  300,
                  java.util.Set.of(),
                  phases -> {
                    serialCalls.incrementAndGet();
                    return scorer.apply(phases);
                  });
          PhasePlanner.Phases parallel =
              ResourcePhasePlanner.refineCompleted(
                  initial,
                  groups,
                  intervals,
                  300,
                  java.util.Set.of(),
                  phases -> {
                    parallelCalls.incrementAndGet();
                    return scorer.apply(phases);
                  },
                  pool);
          assertEquals(serial, parallel, "评分 " + i + "，" + threads + " 线程");
          assertEquals(serialCalls.get(), parallelCalls.get());
          assertTrue(serialCalls.get() > groups.size(), "每个单元至少试过");
        }
      }
    }
  }

  /**
   * 每个单元的候选都试到。
   *
   * <p>八个互不相干的方向，间隔 300 s、起点 δ 都是 100：细档步长是间隔的 1/20、1/10、3/20 向下取整到 10 s（10、30、40）； 每个单元
   * δ=0、两个远端粗档（220、280）、细档 ±10/±30/±40 与两个近处粗档（160、40）共 11 个候选，都要试到。
   */
  @Test
  void everyUnitGetsItsOwnCandidates() {
    Units units = units(8, 300, 100);
    Map<String, java.util.Set<Integer>> tried = new java.util.TreeMap<>();

    ResourcePhasePlanner.refineCompleted(
        units.initial(),
        units.groups(),
        units.intervals(),
        300,
        java.util.Set.of(),
        phases -> {
          record(units.initial(), phases, tried);
          return new ResourcePhasePlanner.CompletionScore(1, 0, 0, 0, 0);
        });

    for (String key : units.keys()) {
      assertEquals(
          java.util.Set.of(0, 220, 280, 90, 110, 160, 70, 130, 40, 60, 140),
          tried.get(key),
          key + " 的候选：0、远端粗档、细档与近处粗档");
    }
  }

  /** 细档只取落在 [0, 上限] 内的：起点 δ 为 0 时没有负的细档，反向方向不超过闲置上限。 */
  @Test
  void fineCandidatesStayWithinTheLimit() {
    Units units = units(1, 300, 0);
    Map<String, java.util.Set<Integer>> tried = new java.util.TreeMap<>();

    ResourcePhasePlanner.refineCompleted(
        units.initial(),
        units.groups(),
        units.intervals(),
        300,
        java.util.Set.of(),
        phases -> {
          record(units.initial(), phases, tried);
          return new ResourcePhasePlanner.CompletionScore(1, 0, 0, 0, 0);
        });

    assertEquals(java.util.Set.of(10, 30, 40, 60, 120, 180, 240), tried.get(units.keys().get(0)));
  }

  /** 正向单元平移一个整间隔与不平移相同：起点 290、间隔 300 时细档 +10 落在 300 上，不试。 */
  @Test
  void aForwardUnitNeverTriesAWholeInterval() {
    Units units = units(1, 300, 290);
    Map<String, java.util.Set<Integer>> tried = new java.util.TreeMap<>();

    ResourcePhasePlanner.refineCompleted(
        units.initial(),
        units.groups(),
        units.intervals(),
        300,
        java.util.Set.of(),
        phases -> {
          record(units.initial(), phases, tried);
          return new ResourcePhasePlanner.CompletionScore(1, 0, 0, 0, 0);
        });

    assertTrue(
        tried.get(units.keys().get(0)).stream().allMatch(delta -> delta < 300), tried.toString());
  }

  /** 细档随间隔等比伸缩：间隔的 1/20、1/10、3/20，向下取整到扫描步长、不小于它，重合的只留一个。 */
  @Test
  void fineStepsScaleWithTheInterval() {
    assertArrayEquals(new int[] {10, 20}, ResourcePhasePlanner.fineSteps(150));
    assertArrayEquals(new int[] {10, 20, 40}, ResourcePhasePlanner.fineSteps(270));
    assertArrayEquals(new int[] {30, 60, 90}, ResourcePhasePlanner.fineSteps(600));
    assertArrayEquals(new int[] {10}, ResourcePhasePlanner.fineSteps(60));
  }

  /** 单元按它在不可吸收残余里出现的次数排，多的先定；平局按原来的组名序。 */
  @Test
  void unitsWithMoreUnabsorbableResidualsGoFirst() {
    Units units = units(4, 300, 100);
    List<String> keys = units.keys();
    List<String> order = new java.util.ArrayList<>();

    ResourcePhasePlanner.refineCompleted(
        units.initial(),
        units.groups(),
        units.intervals(),
        300,
        java.util.Set.of(),
        phases -> {
          for (String key : keys) {
            if (!phases.deltaByDirection().get(key).equals(100) && !order.contains(key)) {
              order.add(key);
            }
          }
          return new ResourcePhasePlanner.CompletionScore(
              4, 0, 0, 0, 0, Map.of(keys.get(2), 7, keys.get(3), 2, keys.get(1), 2));
        });

    assertEquals(List.of(keys.get(2), keys.get(1), keys.get(3), keys.get(0)), order);
  }

  /** 单元多（十二个）时不设全局预算：每个单元都试完自己的 11 个候选，远端与近处的粗档都在里面，排在后面的单元不会被前面的吃光份额。 */
  @Test
  void manyUnitsEachTryAllTheirCandidates() {
    Units units = units(12, 300, 100);
    Map<String, java.util.Set<Integer>> tried = new java.util.TreeMap<>();
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();

    ResourcePhasePlanner.refineCompleted(
        units.initial(),
        units.groups(),
        units.intervals(),
        300,
        java.util.Set.of(),
        phases -> {
          calls.incrementAndGet();
          record(units.initial(), phases, tried);
          return new ResourcePhasePlanner.CompletionScore(
              3, 0, 0, 0, 0, Map.of(units.keys().get(3), 2));
        });

    assertEquals(1 + 12 * MAX_CANDIDATES_PER_UNIT, calls.get());
    for (String key : units.keys()) {
      assertEquals(MAX_CANDIDATES_PER_UNIT, tried.get(key).size(), key);
      assertTrue(tried.get(key).containsAll(java.util.Set.of(220, 280, 160, 40)), key);
    }
  }

  /** 记下这次评分里哪些方向的 δ 偏离了起点。 */
  private static void record(
      PhasePlanner.Phases initial,
      PhasePlanner.Phases candidate,
      Map<String, java.util.Set<Integer>> tried) {
    candidate
        .deltaByDirection()
        .forEach(
            (key, value) -> {
              if (!value.equals(initial.deltaByDirection().get(key))) {
                tried.computeIfAbsent(key, k -> new java.util.TreeSet<>()).add(value);
              }
            });
  }

  private record Units(
      List<ServiceGroupClassifier.Group> groups,
      Map<String, Integer> intervals,
      PhasePlanner.Phases initial,
      List<String> keys) {}

  /** {@code count} 个互不相干的单方向组，间隔相同，起点 δ 都是 {@code start}。 */
  private static Units units(int count, int interval, int start) {
    List<ServiceGroupClassifier.Group> groups = new java.util.ArrayList<>();
    Map<String, Integer> intervals = new java.util.TreeMap<>();
    Map<String, Integer> delta = new java.util.TreeMap<>();
    List<String> keys = new java.util.ArrayList<>();
    for (int i = 0; i < count; i++) {
      String name = String.format(java.util.Locale.ROOT, "g%02d", i);
      ServiceGroupClassifier.Direction direction =
          new ServiceGroupClassifier.Direction(
              "S" + i, "T" + i, List.of(), List.of(TimetableTestFixtures.routeId("R" + i)));
      groups.add(new ServiceGroupClassifier.Group(name, List.of(direction), List.of()));
      intervals.put(name, interval);
      delta.put(direction.key(), start);
      keys.add(direction.key());
    }
    PhasePlanner.Phases initial =
        new PhasePlanner.Phases(Map.of(), Map.of(), delta, List.of(), List.of(), List.of());
    return new Units(groups, intervals, initial, List.copyOf(keys));
  }

  /** 由各方向 δ 算出的评分，取模制造大量并列。 */
  private static ResourcePhasePlanner.CompletionScore score(
      PhasePlanner.Phases phases, int modulus, int buckets) {
    long sum = 0;
    for (Map.Entry<String, Integer> entry :
        new java.util.TreeMap<>(phases.deltaByDirection()).entrySet()) {
      sum += (long) entry.getKey().hashCode() % 97 + entry.getValue() / 10;
    }
    int value = (int) Math.floorMod(sum, modulus);
    return new ResourcePhasePlanner.CompletionScore(value / buckets, 0, value % buckets, 0, 0);
  }

  /** 没有可动的东西时原样返回：只有一个方向就没有"相对相位"可谈。 */
  @Test
  void aSingleFlowIsLeftAlone() {
    PhasePlanner.Phases before = new PhasePlanner.Phases(Map.of("A→C", 0), Map.of(), List.of());

    PhasePlanner.Phases after =
        ResourcePhasePlanner.refine(
            before,
            List.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            index(),
            SEPARATION,
            MAX_WAIT,
            300,
            java.util.Set.of());

    assertEquals(before, after);
  }

  /** 端点多等的 δ 不会超过闲置上限——再久运行时就把车收回库了，表上写了也不作数。 */
  @Test
  void deltaIsBoundedByMaxIdle() {
    Fixture fixture = opposingPair(600);

    PhasePlanner.Phases after =
        ResourcePhasePlanner.refine(
            fixture.phases(),
            fixture.groups(),
            fixture.intervals(),
            fixture.templates(),
            fixture.profiles(),
            index(),
            SEPARATION,
            MAX_WAIT,
            120,
            java.util.Set.of());

    for (Integer delta : after.deltaByDirection().values()) {
      assertTrue(delta <= 600, "正向 δ 不超过间隔");
    }
    assertTrue(
        after.deltaByDirection().getOrDefault(fixture.reverseKey(), 0) <= 120,
        "反向 δ 是端点多等，必须不超过 --max-idle");
  }

  /** 闲置上限很大（回收关着时是一整天）时，反向 δ 也只扫一个间隔：再往后与减去一个间隔的周期相同。 */
  @Test
  void reverseDeltaIsAlsoBoundedByInterval() {
    Fixture fixture = opposingPair(600);

    PhasePlanner.Phases after =
        ResourcePhasePlanner.refine(
            fixture.phases(),
            fixture.groups(),
            fixture.intervals(),
            fixture.templates(),
            fixture.profiles(),
            index(),
            SEPARATION,
            MAX_WAIT,
            86_400,
            java.util.Set.of());

    assertTrue(after.deltaByDirection().getOrDefault(fixture.reverseKey(), 0) <= 600);
  }

  /** 同一份输入跑两次，δ 与说明逐字段相同——第三层不能引入任何不确定性。 */
  @Test
  void refineIsDeterministic() {
    Fixture fixture = opposingPair(600);

    PhasePlanner.Phases first =
        ResourcePhasePlanner.refine(
            fixture.phases(),
            fixture.groups(),
            fixture.intervals(),
            fixture.templates(),
            fixture.profiles(),
            index(),
            SEPARATION,
            MAX_WAIT,
            300,
            java.util.Set.of());
    PhasePlanner.Phases second =
        ResourcePhasePlanner.refine(
            fixture.phases(),
            fixture.groups(),
            fixture.intervals(),
            fixture.templates(),
            fixture.profiles(),
            index(),
            SEPARATION,
            MAX_WAIT,
            300,
            java.util.Set.of());

    assertEquals(first.deltaByDirection(), second.deltaByDirection());
    assertEquals(first.resourceNotes(), second.resourceNotes());
  }

  /** 本来就不撞的一对：δ 全取 0，并在说明里讲清楚"评估过但没有更好的"，而不是默不作声。 */
  @Test
  void zeroDeltaWinsTies() {
    // 间隔 3600 秒、全程 20 秒：两个方向怎么摆都不可能撞。
    Fixture fixture = opposingPair(3600);

    PhasePlanner.Phases after =
        ResourcePhasePlanner.refine(
            fixture.phases(),
            fixture.groups(),
            fixture.intervals(),
            fixture.templates(),
            fixture.profiles(),
            index(),
            SEPARATION,
            MAX_WAIT,
            300,
            java.util.Set.of());

    assertTrue(
        after.deltaByDirection().values().stream().allMatch(delta -> delta == 0), "不撞就不该付出任何端点等待");
    assertEquals(1, after.resourceNotes().size());
    assertTrue(after.resourceNotes().get(0).contains("零偏移"), after.resourceNotes().get(0));
  }

  /** 按车接续把几个方向钉在一起，第三层只能让它们一起平移：单独给其中一个 δ 会把接续拆开， 实测拆开之后派车器改接另一辆车，交路形态整个翻掉。 */
  @Test
  void connectedDirectionsMoveTogether() {
    Fixture fixture = opposingPair(600);
    List<String> keys = List.copyOf(fixture.phases().phaseByDirection().keySet());
    PhasePlanner.Connection connection =
        new PhasePlanner.Connection(
            "OP:S:A", keys.get(1), keys.get(0), "", List.of(), List.of(), 0, Integer.MAX_VALUE);
    PhasePlanner.Phases connected =
        new PhasePlanner.Phases(
            fixture.phases().phaseByDirection(),
            fixture.phases().offsetByGroup(),
            Map.of(),
            fixture.phases().notes(),
            List.of(),
            List.of(connection));

    PhasePlanner.Phases after =
        ResourcePhasePlanner.refine(
            connected,
            fixture.groups(),
            fixture.intervals(),
            fixture.templates(),
            fixture.profiles(),
            index(),
            SEPARATION,
            MAX_WAIT,
            300,
            java.util.Set.of());

    assertEquals(
        after.deltaByDirection().get(keys.get(0)),
        after.deltaByDirection().get(keys.get(1)),
        "接续链上的方向 δ 必须相同");
    assertEquals(List.of(connection), after.connections(), "接续关系原样交给派车器");
  }

  /**
   * 锚在正线折返点的往返对只能整对平移：正线上的停留 = 折返 + 反向 δ − 正向 δ，单独动任何一边都会把等待加回正线上。
   *
   * <p>间隔 80 秒的单线对开：不标正线折返时正向取 10、反向取 160——反向车在折返点多等 150 秒（前提成立，这个用例才有意义）。
   */
  @Test
  void aMainlineTurnbackPairMovesOnlyAsAPair() {
    Fixture fixture = opposingPair(80);
    List<String> keys = List.copyOf(fixture.phases().phaseByDirection().keySet());

    PhasePlanner.Phases free = refine(fixture, java.util.Set.of());
    PhasePlanner.Phases pinned =
        refine(fixture, java.util.Set.of(TimetableTestFixtures.routeId("FWD")));

    assertTrue(
        !free.deltaByDirection().get(keys.get(0)).equals(free.deltaByDirection().get(keys.get(1))),
        free.deltaByDirection().toString());
    assertEquals(
        pinned.deltaByDirection().get(keys.get(0)),
        pinned.deltaByDirection().get(keys.get(1)),
        pinned.deltaByDirection().toString());
  }

  private static PhasePlanner.Phases refine(Fixture fixture, java.util.Set<UUID> mainline) {
    return ResourcePhasePlanner.refine(
        fixture.phases(),
        fixture.groups(),
        fixture.intervals(),
        fixture.templates(),
        fixture.profiles(),
        index(),
        SEPARATION,
        MAX_WAIT,
        300,
        mainline);
  }

  /**
   * 所有流共用一个评估视野（{@code CYCLES × 最长间隔}）：间隔 300 与 600 混排时，300 的流也要铺满 600 的流 3 个周期那么久。 每流各铺 3
   * 个自己的周期，短间隔的流只覆盖 900 秒，长间隔的流后半段撞谁都看不见，第三层会把有冲突的偏移判成"零冲突"。 实测 prod 库加入
   * MT-3（600）与干线（300）混排后，旧评估选出的偏移在最终表上有 138 处让不掉的冲突，统一视野后 0 处。
   */
  @Test
  void allFlowsShareOneEvaluationHorizon() {
    int horizon = ResourcePhasePlanner.CYCLES * 600;

    assertEquals(6, ResourcePhasePlanner.cyclesFor(300, horizon), "短间隔的流铺得更多");
    assertEquals(ResourcePhasePlanner.CYCLES, ResourcePhasePlanner.cyclesFor(600, horizon));
    assertEquals(7, ResourcePhasePlanner.cyclesFor(270, horizon), "不整除时向上取整：视野只会多不会少");
    assertEquals(
        ResourcePhasePlanner.CYCLES,
        ResourcePhasePlanner.cyclesFor(300, ResourcePhasePlanner.CYCLES * 300),
        "间隔相同时与原来每流 CYCLES 个周期一致");
  }

  /** 间隔悬殊时周期数封顶：60 秒对 3600 秒不能逼出上百个周期拖慢冲突检查。 */
  @Test
  void cyclesAreCappedWhenIntervalsAreFarApart() {
    int horizon = ResourcePhasePlanner.CYCLES * 3600;

    assertEquals(ResourcePhasePlanner.MAX_CYCLES, ResourcePhasePlanner.cyclesFor(60, horizon));
    assertEquals(ResourcePhasePlanner.CYCLES, ResourcePhasePlanner.cyclesFor(3600, horizon));
  }

  /** 往返对余数只进报告：算得出来，但不参与任何决策。 */
  @Test
  void residuesAreReportedNotActedOn() {
    Fixture fixture = opposingPair(600);
    UUID forward = TimetableTestFixtures.routeId("FWD");

    List<PhasePlanner.Residue> residues =
        PhasePlanner.residues(
            fixture.groups(),
            fixture.intervals(),
            Map.of(forward, 20, TimetableTestFixtures.routeId("REV"), 20),
            TurnaroundTable.fixed(40),
            java.util.Set.of());

    assertEquals(1, residues.size());
    PhasePlanner.Residue residue = residues.get(0);
    assertEquals(20, residue.runSeconds());
    assertEquals(40, residue.turnaroundSeconds());
    assertEquals(60, residue.residue(), "(20 + 40) mod 600");
  }

  // ------------------------------------------------------------------ 夹具

  private record Fixture(
      List<ServiceGroupClassifier.Group> groups,
      Map<String, Integer> intervals,
      Map<String, PeriodicTemplate> templates,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      PhasePlanner.Phases phases,
      String reverseKey) {}

  /** 单股道上对开的一对：FWD 从 A 到 C、REV 从 C 到 A，共用同一条链。 */
  private static Fixture opposingPair(int interval) {
    UUID forward = TimetableTestFixtures.routeId("FWD");
    UUID reverse = TimetableTestFixtures.routeId("REV");
    List<TimetableBuilder.RouteInput> routes =
        List.of(
            operation(forward, "FWD", List.of(A, B, C)),
            operation(reverse, "REV", List.of(C, B, A)));
    ServiceGroupClassifier.Classification classification = ServiceGroupClassifier.classify(routes);
    ServiceGroupClassifier.Group group = classification.groups().get(0);

    Map<UUID, Integer> runByRoute = Map.of(forward, 20, reverse, 20);
    Map<String, PeriodicTemplate> templates = new java.util.LinkedHashMap<>();
    for (ServiceGroupClassifier.Direction direction : group.directions()) {
      templates.put(
          direction.key(),
          PeriodicTemplate.of(
              direction,
              interval,
              runByRoute,
              VehicleDutyPlanner.Legs.none(),
              TurnaroundTable.fixed(40),
              Map.of(),
              false));
    }
    Map<UUID, TimetableConflictChecker.RouteProfile> profiles =
        Map.of(
            forward, profileOf(forward, "FWD", List.of(A, B, C)),
            reverse, profileOf(reverse, "REV", List.of(C, B, A)));

    PhasePlanner.Phases phases =
        PhasePlanner.plan(
            List.of(group),
            Map.of(group.name(), interval),
            runByRoute,
            TurnaroundTable.fixed(40),
            7200,
            Map.of());
    String reverseKey =
        phases.phaseByDirection().entrySet().stream()
            .filter(entry -> entry.getValue() != 0)
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(group.directions().get(0).key());
    return new Fixture(
        List.of(group), Map.of(group.name(), interval), templates, profiles, phases, reverseKey);
  }

  private static TimetableBuilder.RouteInput operation(UUID id, String code, List<String> nodes) {
    return new TimetableBuilder.RouteInput(
        id,
        code,
        1,
        TimetableTestFixtures.route(code, nodes),
        TimetableTestFixtures.stops(id, nodes.size(), 0),
        java.util.Optional.empty());
  }

  /** 逐段 10 秒的投影：够冲突检查把它铺到边与站台上。 */
  private static TimetableConflictChecker.RouteProfile profileOf(
      UUID id, String code, List<String> nodes) {
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(
                TimetableTestFixtures.chain(
                    List.of(A, B, C), new int[] {100, 100}, new double[] {10.0, 10.0}),
                TimetableTestFixtures.perEdgeSpeedModel(),
                TimetableTestFixtures.route(code, nodes),
                TimetableTestFixtures.stops(id, nodes.size(), 0),
                java.time.Duration.ZERO);
    return new TimetableConflictChecker.RouteProfile(
        id,
        code,
        timing.stops(),
        timing.segments(),
        TimetableConflictChecker.platformsOf(
            timing.stops(),
            TimetableTestFixtures.stops(id, nodes.size(), 0),
            nodes.stream().map(org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId::of).toList(),
            Map.of()));
  }
}
