package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

/**
 * 快车错峰接进编表器：每次 build 实测快车被卡、候选编表器按组平移相位。搜索策略本身由 {@link RapidStaggerTest} 用假的编表钉住， 在真路网上的效果靠离线重编复核。
 *
 * <p>慢车与快车同从 A 出发、沿同一串单股道走到 D，之后各进 E 的一道、二道；快车中间站通过。慢车每 400 秒、快车每 800 秒一班。
 */
class TimetableBuilderRapidStaggerTest {

  private static final Instant BUILT_AT = Instant.parse("2026-03-01T00:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final String DEP = "OP:D:DEP:1";
  private static final String DEP2 = "OP:D:DEQ:1";
  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String C = "OP:S:C:1";
  private static final String D = "OP:S:D:1";
  private static final String E = "OP:S:E:1";
  private static final String E2 = "OP:S:E:2";

  private final RailGraph graph =
      TimetableTestFixtures.graph(
          new LinkedHashMap<>(
              Map.of(
                  DEP, NodeType.DEPOT,
                  DEP2, NodeType.DEPOT,
                  A, NodeType.STATION,
                  B, NodeType.STATION,
                  C, NodeType.STATION,
                  D, NodeType.STATION,
                  E, NodeType.STATION,
                  E2, NodeType.STATION)),
          List.of(
              new TimetableTestFixtures.Edge(DEP, A, 50, 10.0),
              new TimetableTestFixtures.Edge(A, B, 100, 10.0),
              new TimetableTestFixtures.Edge(B, C, 100, 10.0),
              new TimetableTestFixtures.Edge(C, D, 100, 10.0),
              new TimetableTestFixtures.Edge(D, E, 100, 10.0),
              new TimetableTestFixtures.Edge(D, E2, 100, 10.0),
              new TimetableTestFixtures.Edge(E, DEP2, 50, 10.0),
              new TimetableTestFixtures.Edge(E2, DEP2, 50, 10.0)));
  private final UUID local = TimetableTestFixtures.routeId("LOC");
  private final UUID rapid = TimetableTestFixtures.routeId("RAP");

  /** 候选编表器按组平移相位：快车组的表定时刻整体晚 100 秒，慢车组不动（实际发车可能再被让车推一点，比的是名义时刻）。 */
  @Test
  void aCandidateBuilderShiftsTheWholeGroup() {
    TimetableBuildResult plain = build(new TimetableBuilder(), 0, false);
    TimetableBuildResult shifted =
        build(
            new TimetableBuilder(new TimetableTimingCalculator(), Map.of("rapid", 100), true),
            0,
            false);

    assertEquals(nominalOf(plain, local), nominalOf(shifted, local));
    List<Integer> before = nominalOf(plain, rapid);
    List<Integer> after = nominalOf(shifted, rapid);
    assertTrue(!before.isEmpty() && before.size() == after.size(), before + " / " + after);
    for (int i = 0; i < before.size(); i++) {
      assertEquals(before.get(i) + 100, after.get(i));
    }
  }

  /**
   * 实测接在成品表上：把一班快车挪到慢车之后 30 秒，它要晚 270 秒才追不上，被拖住 240 秒（超过裕量的部分）。
   *
   * <p>这个小路网上让车修复本来就会把快车在起点推后、自己躲开，编出来的表量不到被卡；所以手动挪一班来测。
   */
  @Test
  void theMeasureReadsTheFinishedTable() {
    Timetable table = build(new TimetableBuilder(), 90, false).timetable().orElseThrow();
    int firstLocal = departuresOf(table, local).get(0);
    List<TimetableTrip> trips = new java.util.ArrayList<>();
    boolean moved = false;
    for (TimetableTrip trip : table.trips()) {
      if (!moved && trip.routeId().equals(rapid)) {
        moved = true;
        trips.add(
            new TimetableTrip(
                trip.id(),
                trip.timetableId(),
                trip.routeId(),
                trip.sequence(),
                trip.tripCode(),
                firstLocal + 30,
                trip.dutyId()));
      } else {
        trips.add(trip);
      }
    }

    Timetable shifted = table.withTripsAndDuties(trips, table.duties());
    Map<UUID, TimetableConflictChecker.RouteProfile> profiles =
        Map.of(
            local, profileOf(local, "LOC", List.of(A, B, C, D, E), 90),
            rapid, profileOf(rapid, "RAP", List.of(A, E2), 0));
    TimetableConflictChecker.GraphIndex index = TimetableConflictChecker.GraphIndex.of(graph);

    String rapidTrip =
        shifted.trips().stream()
            .filter(trip -> trip.routeId().equals(rapid))
            .findFirst()
            .orElseThrow()
            .tripCode();
    String localTrip =
        shifted.trips().stream()
            .filter(trip -> trip.routeId().equals(local))
            .findFirst()
            .orElseThrow()
            .tripCode();
    List<ResourceRepair.Yield> yields =
        List.of(
            new ResourceRepair.Yield(
                TimetableConflictChecker.Kind.TRACK,
                "e",
                localTrip,
                Optional.empty(),
                rapidTrip,
                25,
                0),
            new ResourceRepair.Yield(
                TimetableConflictChecker.Kind.TRACK,
                "e",
                rapidTrip,
                Optional.empty(),
                localTrip,
                40,
                0));
    RapidStagger.Measure measure =
        RapidStagger.measure(
            shifted,
            profiles,
            index,
            TimetableBuildOptions.DEFAULT_SEPARATION_SECONDS,
            TimetableBuildOptions.Following.NONE,
            BlockingTimes.Trajectories.NONE,
            5 * 3600,
            java.util.Set.of(rapid),
            yields);
    RapidStagger.Measure notFast =
        RapidStagger.measure(
            shifted,
            profiles,
            index,
            TimetableBuildOptions.DEFAULT_SEPARATION_SECONDS,
            TimetableBuildOptions.Following.NONE,
            BlockingTimes.Trajectories.NONE,
            5 * 3600,
            java.util.Set.of(),
            yields);

    assertEquals(1, measure.trips(), measure::toString);
    assertEquals(240L, measure.seconds(), measure::toString);
    assertEquals(25L, measure.held(), "快车自己在表里的等待算进快车损失；普通车的等待不算");
    assertEquals(0L, notFast.seconds(), "只算快车交路：出入库走行之类不停站的运行跟着慢车不算快车被卡");
    assertEquals(0L, notFast.held());
  }

  /** 两组一样快就没有快车被卡，开了错峰也不搜。 */
  @Test
  void nothingCaughtNeedsNoSearch() {
    TimetableBuildResult result = build(new TimetableBuilder(), 0, true);

    assertTrue(result.success(), () -> result.warnings().toString());
    assertTrue(result.phaseNotes().contains("快车被卡（成品表实测）：无"), result.phaseNotes()::toString);
    assertTrue(result.phaseNotes().stream().noneMatch(note -> note.startsWith("快车错峰：")));
  }

  /** 错峰候选与发起的编表器共用进度：候选的完整构建一起数、取消了也停，但阶段由错峰搜索报，候选自己不改。 */
  @Test
  void candidateBuildsShareTheProgress() {
    TimetableBuildProgress progress = new TimetableBuildProgress();
    build(
        new TimetableBuilder(
            new TimetableTimingCalculator(), Map.of("rapid", 100), false, progress),
        0,
        false);

    TimetableBuildProgress.Snapshot snapshot = progress.snapshot();
    assertTrue(snapshot.fullBuilds() > 0, snapshot::toString);
    assertEquals(TimetableBuildProgress.Stage.QUEUED, snapshot.stage());

    progress.cancel();
    assertThrows(
        TimetableBuildProgress.Cancelled.class,
        () ->
            build(
                new TimetableBuilder(
                    new TimetableTimingCalculator(), Map.of("rapid", 100), false, progress),
                0,
                false));
  }

  /**
   * 并行编表与串行逐字段相同：班次、交路、相位与错峰说明、警告都一样。
   *
   * <p>两组同在 A、E 之间开行，相位各选各的：慢车停站不同，快车在表里给不给慢车让车、错峰搜不搜、搜到哪一步停都不同。
   * 哪些停站会搜、搜到哪一步停是窄窗口，随时分算法微调就会挪动，所以不挑固定的停站，而是扫一段停站（{@link #scan}）逐个比， 并要求扫到的值里至少 {@value
   * #MIN_SEARCHED} 个确实进了错峰搜索，且早停（扫到错开的窗口就停）与扫完整个周期两种情形都出现。 每次编表里相位第三层的完整候选也并行评。
   */
  @Test
  void aParallelBuildEqualsTheSerialOne() {
    List<Scanned> scanned = scan();
    List<Scanned> searched = scanned.stream().filter(Scanned::searched).toList();
    assertTrue(
        searched.size() >= MIN_SEARCHED,
        () -> "停站扫描里编了错峰候选的不足 " + MIN_SEARCHED + " 个，夹具或扫描范围要调：" + scanned);
    assertTrue(searched.stream().anyMatch(Scanned::earlyStop), () -> "没有早停的停站：" + scanned);
    assertTrue(searched.stream().anyMatch(Scanned::fullSweep), () -> "没有扫完整个周期的停站：" + scanned);
    for (Scanned one : scanned) {
      for (int threads : new int[] {3, 4}) {
        TimetableBuildResult parallel =
            build(
                new TimetableBuilder(TimetableBuildProgress.untracked(), threads),
                one.dwell(),
                true);
        String label = "停站 " + one.dwell() + "，" + threads + " 线程";
        TimetableBuildResult serial = one.serial();
        assertEquals(serial.timetable(), parallel.timetable(), label);
        assertEquals(
            withoutTiming(serial.phaseNotes()), withoutTiming(parallel.phaseNotes()), label);
        assertEquals(serial.warnings(), parallel.warnings(), label);
        assertEquals(serial.resourcePhaseNotes(), parallel.resourcePhaseNotes(), label);
        assertEquals(
            withoutTiming(List.of(serial.toString())),
            withoutTiming(List.of(parallel.toString())),
            label);
      }
    }
  }

  /**
   * 并行编表时取消：进了错峰搜索就取消，各候选在下一次完整构建前停下，取消从 build 抛出（不当作失败），完整构建次数远少于编完。
   *
   * <p>取消在进入错峰阶段的那一刻同步发出（进度换阶段时会读一次时钟，测试的时钟在这时取消），不靠线程赛跑。停站取扫描里扫完整个周期的一个（{@link #scan}）。
   */
  @Test
  void aParallelBuildStopsWhenCancelled() {
    int dwell = pick(Scanned::fullSweep, "扫完整个周期");
    TimetableBuildProgress whole = new TimetableBuildProgress();
    build(new TimetableBuilder(whole, 4), dwell, true);
    CancelOnStagger clock = new CancelOnStagger();
    TimetableBuildProgress progress = new TimetableBuildProgress(clock);
    clock.progress = progress;

    assertThrows(
        TimetableBuildProgress.Cancelled.class,
        () -> build(new TimetableBuilder(progress, 4), dwell, true));

    assertTrue(
        progress.snapshot().fullBuilds() < whole.snapshot().fullBuilds(),
        () -> progress.snapshot() + " / " + whole.snapshot());
  }

  /**
   * 慢车停站的扫描范围：两头都是错峰不搜的值，中间有搜到早停与扫完整个周期的值。停站一变，第三层选的相位、快车被不被卡就跟着变， 真正编了错峰候选的只是其中零星几个值，所以扫一段而不是挑几个。
   */
  private static final int SCAN_FROM = 100;

  private static final int SCAN_TO = 200;
  private static final int SCAN_STEP = 5;

  /** 扫描里至少几个停站要真的编了错峰候选。 */
  private static final int MIN_SEARCHED = 3;

  private static final int LOCAL_INTERVAL = 400;
  private static final int RAPID_INTERVAL = 800;

  /**
   * 一个慢车停站的串行编表。
   *
   * @param candidates 错峰搜索按顺序编过的候选的说明（平移、加停、加长折返）；没编过候选时为空——原表快车没被卡， 或原表已放宽、又没有可加长的原地折返端
   * @param shifts 其中的平移候选，如 {@code {rapid=10}}
   */
  private record Scanned(
      int dwell, TimetableBuildResult serial, List<String> candidates, List<String> shifts) {

    /** 真的编了错峰候选。 */
    boolean searched() {
      return !candidates.isEmpty();
    }

    /** 平移扫到了周期里最后一档：整个周期扫完。 */
    boolean fullSweep() {
      return shifts.contains(lastShift());
    }

    /** 试了平移，但没扫到周期里最后一档就停了（扫到错开的窗口就停）。 */
    boolean earlyStop() {
      return !shifts.isEmpty() && !fullSweep();
    }

    private static String lastShift() {
      List<Integer> offsets =
          RapidStagger.shifts(RapidStagger.period(RAPID_INTERVAL, List.of(LOCAL_INTERVAL)));
      return "{rapid=" + offsets.get(offsets.size() - 1) + "}";
    }

    @Override
    public String toString() {
      String kind = !searched() ? "不搜" : fullSweep() ? "扫完周期" : earlyStop() ? "早停" : "只试加停或加长折返";
      return dwell + "（" + kind + "，" + candidates.size() + " 个候选）";
    }
  }

  private static final Object SCAN_LOCK = new Object();

  private static List<Scanned> scanned;

  /**
   * 逐个停站串行编一遍（带错峰），记下编过哪些错峰候选。几个用例共用，只扫一次。
   *
   * <p>编过的候选从进度的阶段说明里读：错峰的时间上限每编一批读一次进度时钟（串行时一批一个候选），测试的时钟在每次被读时记下当时的说明，
   * 编完再读一次补上最后一个。时钟不走，上限永远不到点，与不限时的搜索逐个候选相同。
   */
  private List<Scanned> scan() {
    synchronized (SCAN_LOCK) {
      if (scanned == null) {
        scanned = scanDwells();
      }
      return scanned;
    }
  }

  private List<Scanned> scanDwells() {
    List<Scanned> out = new java.util.ArrayList<>();
    for (int dwell = SCAN_FROM; dwell <= SCAN_TO; dwell += SCAN_STEP) {
      DetailRecorder clock = new DetailRecorder();
      TimetableBuildProgress progress = new TimetableBuildProgress(clock);
      clock.watch(progress);
      TimetableBuildResult serial = build(new TimetableBuilder(progress, 1), dwell, true, 1);
      clock.instant();
      List<String> candidates = new java.util.ArrayList<>();
      List<String> shifts = new java.util.ArrayList<>();
      for (String detail : clock.details()) {
        if (CANDIDATE_DETAIL.matcher(detail).find()) {
          candidates.add(detail);
          java.util.regex.Matcher shift = SHIFT_DETAIL.matcher(detail);
          if (shift.find()) {
            shifts.add(shift.group(1));
          }
        }
      }
      boolean noted = serial.phaseNotes().stream().anyMatch(note -> note.startsWith("快车错峰："));
      assertTrue(noted || candidates.isEmpty(), () -> "编了错峰候选却没有错峰说明：" + serial.phaseNotes());
      // 记下的候选与说明里"试了 N 个位置"对得上，才说明每个候选都记到了。
      for (String note : serial.phaseNotes()) {
        java.util.regex.Matcher tried = TRIED_NOTE.matcher(note);
        if (note.startsWith("快车错峰：") && tried.find()) {
          assertEquals(Integer.parseInt(tried.group(1)), candidates.size(), note);
        }
      }
      out.add(new Scanned(dwell, serial, List.copyOf(candidates), List.copyOf(shifts)));
    }
    return List.copyOf(out);
  }

  /** 错峰候选的阶段说明：第 N 个候选：…… */
  private static final java.util.regex.Pattern CANDIDATE_DETAIL =
      java.util.regex.Pattern.compile("^第 \\d+ 个候选：");

  /** 错峰说明里编过几个候选。 */
  private static final java.util.regex.Pattern TRIED_NOTE =
      java.util.regex.Pattern.compile("试了 (\\d+) 个位置");

  /** 错峰平移候选的阶段说明：第 N 个候选：平移 {组=秒}。 */
  private static final java.util.regex.Pattern SHIFT_DETAIL =
      java.util.regex.Pattern.compile("个候选：平移 (\\{[^}]*\\})");

  /** 扫描里第一个符合条件的停站；没有时失败并列出扫描结果。 */
  private int pick(java.util.function.Predicate<Scanned> wanted, String what) {
    List<Scanned> all = scan();
    return all.stream()
        .filter(Scanned::searched)
        .filter(wanted)
        .findFirst()
        .orElseThrow(() -> new AssertionError("停站扫描里没有" + what + "的值：" + all))
        .dwell();
  }

  /** 每次被读时记下进度当时的阶段说明（与上一条不同才记）；时钟本身不走。读进度也会读时钟，用标记防止重入。 */
  private static final class DetailRecorder extends java.time.Clock {
    private final List<String> details = new java.util.ArrayList<>();
    private boolean inside;
    private TimetableBuildProgress progress;

    @Override
    public synchronized Instant instant() {
      if (progress != null && !inside) {
        inside = true;
        try {
          String detail = progress.snapshot().detail();
          if (!detail.isEmpty()
              && (details.isEmpty() || !details.get(details.size() - 1).equals(detail))) {
            details.add(detail);
          }
        } finally {
          inside = false;
        }
      }
      return BUILT_AT;
    }

    synchronized void watch(TimetableBuildProgress progress) {
      this.progress = progress;
    }

    synchronized List<String> details() {
      return List.copyOf(details);
    }

    @Override
    public ZoneId getZone() {
      return ZONE;
    }

    @Override
    public java.time.Clock withZone(ZoneId zone) {
      return this;
    }
  }

  /** 进度换到错峰阶段时读时钟，这时取消；读进度本身也会读时钟，用标记防止重入。 */
  private static final class CancelOnStagger extends java.time.Clock {
    private final java.time.Instant now = java.time.Instant.parse("2026-03-02T08:00:00Z");
    private final ThreadLocal<Boolean> inside = ThreadLocal.withInitial(() -> false);
    private volatile TimetableBuildProgress progress;

    @Override
    public java.time.Instant instant() {
      TimetableBuildProgress watched = progress;
      if (watched != null && !inside.get()) {
        inside.set(true);
        try {
          if (watched.snapshot().stage() == TimetableBuildProgress.Stage.STAGGER) {
            watched.cancel();
          }
        } finally {
          inside.set(false);
        }
      }
      return now;
    }

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public java.time.Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }

  /**
   * 错峰时间上限按进度的时钟算：时钟每读一次走一分钟、上限一分钟，第一批之前就到点，一个候选也不编，保持原表并写明超时；上限 0 不限时照常搜。 停站取扫描里确实进了错峰搜索的一个（{@link
   * #scan}）。
   */
  @Test
  void theStaggerTimeLimitUsesTheProgressClock() {
    int dwell = pick(one -> true, "进了错峰搜索");
    TimetableBuildResult limited =
        build(
            new TimetableBuilder(new TimetableBuildProgress(new SteppingClock()), 1),
            dwell,
            true,
            1);
    TimetableBuildResult unlimited =
        build(
            new TimetableBuilder(new TimetableBuildProgress(new SteppingClock()), 1),
            dwell,
            true,
            0);

    String note =
        limited.phaseNotes().stream()
            .filter(line -> line.startsWith("快车错峰："))
            .findFirst()
            .orElseThrow(() -> new AssertionError(limited.phaseNotes().toString()));
    assertTrue(note.startsWith("快车错峰：错峰超时（约 1 分钟上限到点，编完当时那一批就停，只在已编过的候选里挑），试了 0 个位置"), note);
    assertTrue(note.contains("保持原表"), note);
    assertTrue(
        unlimited.phaseNotes().stream()
            .anyMatch(line -> line.startsWith("快车错峰：") && !line.contains("错峰超时")),
        unlimited.phaseNotes()::toString);
  }

  /** 每读一次走一分钟的时钟。 */
  private static final class SteppingClock extends java.time.Clock {
    private Instant now = BUILT_AT;

    @Override
    public synchronized Instant instant() {
      now = now.plus(Duration.ofMinutes(1));
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZONE;
    }

    @Override
    public java.time.Clock withZone(ZoneId zone) {
      return this;
    }
  }

  /** 错峰说明里的用时随机器快慢变，比较时去掉。 */
  private static List<String> withoutTiming(List<String> notes) {
    return notes.stream().map(note -> note.replaceAll("用时 \\d+s", "用时 ?s")).toList();
  }

  private TimetableBuildResult build(TimetableBuilder builder, int localDwell, boolean stagger) {
    return build(builder, localDwell, stagger, 0);
  }

  private TimetableBuildResult build(
      TimetableBuilder builder, int localDwell, boolean stagger, int staggerMinutes) {
    List<TimetableBuilder.RouteInput> routes =
        List.of(
            operation(local, "LOC", List.of(A, B, C, D, E), localDwell, "local"),
            operation(rapid, "RAP", List.of(A, E2), 0, "rapid"),
            leg("CRT", RouteOperationType.CREATE, List.of(DEP, A), DEP),
            leg("RET", RouteOperationType.RETURN, List.of(E, DEP2), DEP2),
            leg("RES", RouteOperationType.RETURN, List.of(E2, DEP2), DEP2));
    return builder.build(
        new TimetableBuilder.BuildInput(
            UUID.nameUUIDFromBytes("RS".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            UUID.nameUUIDFromBytes("C".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            UUID.nameUUIDFromBytes("O".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            UUID.nameUUIDFromBytes("L".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "TT1",
            "快车错峰",
            routes,
            graph,
            TimetableTestFixtures.perEdgeSpeedModel(),
            Optional.empty()),
        new TimetableBuildOptions(
                5 * 3600,
                7 * 3600,
                Duration.ofSeconds(LOCAL_INTERVAL),
                Duration.ofSeconds(20),
                new VehicleDutyPlanner.Limits(100, 7200, 60),
                "",
                ZONE,
                Duration.ofSeconds(TimetableBuildOptions.DEFAULT_SEPARATION_SECONDS),
                false,
                Map.of("local", LOCAL_INTERVAL, "rapid", RAPID_INTERVAL))
            .withRapidStagger(stagger)
            .withStaggerMinutes(staggerMinutes),
        BUILT_AT);
  }

  private static TimetableBuilder.RouteInput operation(
      UUID id, String code, List<String> nodes, int dwell, String group) {
    return new TimetableBuilder.RouteInput(
        id,
        code,
        RouteOperationType.OPERATION,
        1,
        TimetableTestFixtures.route(code, nodes),
        TimetableTestFixtures.stops(id, nodes.size(), dwell),
        Optional.empty(),
        Optional.empty(),
        false,
        Optional.of(group));
  }

  /** 名义时刻：被端点串行或让车推过的班次取它的名义时隙，其余就是实际发车。 */
  private static List<Integer> nominalOf(TimetableBuildResult result, UUID route) {
    Timetable timetable = result.timetable().orElseThrow();
    Map<String, Integer> nominal = new java.util.HashMap<>();
    for (TimetableBuildResult.TripShift shift : result.shifts()) {
      nominal.put(shift.tripCode(), shift.nominalSecondOfDay());
    }
    return timetable.trips().stream()
        .filter(trip -> trip.routeId().equals(route))
        .map(trip -> nominal.getOrDefault(trip.tripCode(), trip.departureSecondOfDay()))
        .sorted()
        .toList();
  }

  private TimetableConflictChecker.RouteProfile profileOf(
      UUID id, String code, List<String> waypoints, int dwell) {
    org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition route =
        TimetableTestFixtures.route(code, waypoints);
    List<org.fetarute.fetaruteTCAddon.company.model.RouteStop> stops =
        TimetableTestFixtures.stops(id, waypoints.size(), dwell);
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(graph, TimetableTestFixtures.perEdgeSpeedModel(), route, stops, Duration.ZERO);
    assertTrue(timing.ok(), () -> code + ": " + timing.failure());
    return new TimetableConflictChecker.RouteProfile(
        id,
        code,
        timing.stops(),
        timing.segments(),
        TimetableConflictChecker.platformsOf(timing.stops(), stops, route.waypoints()));
  }

  private static List<Integer> departuresOf(Timetable timetable, UUID route) {
    return timetable.trips().stream()
        .filter(trip -> trip.routeId().equals(route))
        .map(TimetableTrip::departureSecondOfDay)
        .sorted()
        .toList();
  }

  private static TimetableBuilder.RouteInput leg(
      String code, RouteOperationType type, List<String> nodes, String depot) {
    UUID id = TimetableTestFixtures.routeId(code);
    return new TimetableBuilder.RouteInput(
        id,
        code,
        type,
        0,
        TimetableTestFixtures.route(code, nodes),
        type == RouteOperationType.CREATE
            ? TimetableTestFixtures.createStops(id, nodes.size(), depot)
            : TimetableTestFixtures.returnStops(id, nodes.size(), depot),
        Optional.empty());
  }
}
