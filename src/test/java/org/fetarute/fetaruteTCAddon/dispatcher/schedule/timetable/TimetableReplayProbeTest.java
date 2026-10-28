package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunCurveModel;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.LineSpawnMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnGroup;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * 编表回放探针：对一份真实库副本离线跑编表，量用时、完整构建次数、取消、残余与各方向班距。只作验证，不作调参依据。
 *
 * <p>没设 {@code FTA_REPLAY_DIR} 时跳过，不影响 {@code check}。目录里放 {@code config.yml} 与 {@code
 * fetarute.sqlite} 的副本（不要直接指向运行中服务器的库）。其余环境变量：
 *
 * <ul>
 *   <li>{@code FTA_REPLAY_COMPANY} / {@code FTA_REPLAY_OPERATOR} / {@code FTA_REPLAY_LINES}（逗号分隔）/
 *       {@code FTA_REPLAY_CODE}：编哪几条线，必填
 *   <li>{@code FTA_REPLAY_GROUP_HEADWAY}：{@code 组=秒,组=秒}，与 {@code --group-headway} 同语义；不给按库里各组
 *       baseline
 *   <li>{@code FTA_REPLAY_START} / {@code FTA_REPLAY_END}（{@code HH:mm}）、{@code
 *       FTA_REPLAY_SEPARATION}、{@code FTA_REPLAY_STRICT}、{@code FTA_REPLAY_MAX_DUTY_MINUTES}、{@code
 *       FTA_REPLAY_MAX_TRIPS}、{@code FTA_REPLAY_MAX_CANCEL}
 *   <li>{@code FTA_REPLAY_TRAIN_LENGTH}：离线读不到车库牌子与存档车，车长统一按这个格数（不给则车长未知）
 *   <li>{@code FTA_REPLAY_OUT}：结果追加写到这个文件（缺省为目录下 {@code replay.log}）
 * </ul>
 */
class TimetableReplayProbeTest {

  @Test
  void replay() throws Exception {
    String dirValue = System.getenv("FTA_REPLAY_DIR");
    Assumptions.assumeTrue(dirValue != null && !dirValue.isBlank(), "FTA_REPLAY_DIR 未设置");
    Path dir = Path.of(dirValue).toAbsolutePath();
    Path out = Path.of(env("FTA_REPLAY_OUT", dir.resolve("replay.log").toString()));
    ConfigManager.ConfigView config = loadConfig(dir);
    StorageProvider provider = openStorage(config, dir.resolve("fetarute.sqlite"));

    Company company = provider.companies().findByCode(required("FTA_REPLAY_COMPANY")).orElseThrow();
    Operator operator =
        provider
            .operators()
            .findByCompanyAndCode(company.id(), required("FTA_REPLAY_OPERATOR"))
            .orElseThrow();
    String code = required("FTA_REPLAY_CODE");
    List<String> lineCodes =
        Arrays.stream(required("FTA_REPLAY_LINES").split(",")).map(String::trim).toList();
    boolean joint = lineCodes.size() > 1;

    RouteDefinitionCache cache = new RouteDefinitionCache(message -> {});
    cache.reload(provider);
    long trainLength = Long.parseLong(env("FTA_REPLAY_TRAIN_LENGTH", "0"));

    List<Member> members = new ArrayList<>();
    for (String lineCode : lineCodes) {
      Line line = provider.lines().findByOperatorAndCode(operator.id(), lineCode).orElseThrow();
      members.add(collect(provider, cache, operator, line, trainLength));
    }
    UUID world = worldOf(dir.resolve("fetarute.sqlite"), members);
    RailGraph graph =
        RailGraphService.buildGraphFromRecords(
            provider.railNodes().listByWorld(world),
            provider.railEdges().listByWorld(world),
            provider.railInterlockingSnapshots().findByWorld(world));
    Map<EdgeId, RailEdgeOverrideRecord> overrides = new HashMap<>();
    for (RailEdgeOverrideRecord record : provider.railEdgeOverrides().listByWorld(world)) {
      overrides.put(record.edgeId(), record);
    }
    RunCurveModel model =
        new RunCurveModel(
            RunCurveModel.Settings.forTimetable(config), TimetableEdgeSpeeds.resolver(overrides));

    Map<String, Integer> explicit = parseGroupHeadways(env("FTA_REPLAY_GROUP_HEADWAY", ""));
    Map<String, Integer> groupIntervals = new TreeMap<>();
    int headway = 0;
    List<SpawnGroup> spawnGroups = new ArrayList<>();
    for (Member member : members) {
      List<SpawnGroup> groups = LineSpawnMetadata.parseGroups(member.line().metadata());
      spawnGroups.addAll(groups);
      List<String> names =
          ServiceGroupClassifier.classify(member.inputs()).groups().stream()
              .map(ServiceGroupClassifier.Group::name)
              .toList();
      Map<String, Integer> forLine = new TreeMap<>();
      explicit.forEach(
          (key, seconds) -> {
            int slash = key.indexOf('/');
            if (slash < 0) {
              forLine.put(key, seconds);
            } else if (key.substring(0, slash).equalsIgnoreCase(member.line().code())) {
              forLine.put(key.substring(slash + 1), seconds);
            }
          });
      TimetableHeadwayDefaults.resolveGroups(
              Optional.empty(), forLine, member.line().spawnFreqBaselineSec(), groups, names)
          .forEach(
              (group, choice) ->
                  groupIntervals.put(
                      TimetableSetBuilder.groupKey(member.line().code(), Optional.of(group), joint),
                      choice.seconds()));
      if (headway == 0) {
        headway =
            TimetableHeadwayDefaults.resolve(
                    Optional.empty(), member.line().spawnFreqBaselineSec(), groups)
                .seconds();
      }
    }

    int maxTrips =
        Integer.parseInt(
            env(
                "FTA_REPLAY_MAX_TRIPS",
                String.valueOf(
                    spawnGroups.stream()
                        .map(SpawnGroup::maxOperationTrips)
                        .flatMap(Optional::stream)
                        .max(Integer::compare)
                        .orElse(VehicleDutyPlanner.Limits.DEFAULT_MAX_TRIPS))));
    ConfigManager.TimetableSettings timetable = config.timetableSettings();
    int tolerance = timetable.assignToleranceSeconds();
    Map<UUID, Long> lengths = new HashMap<>();
    if (trainLength > 0) {
      members.forEach(
          member -> member.inputs().forEach(input -> lengths.put(input.routeId(), trainLength)));
    }
    // FTA_REPLAY_PLAN：运行计划书原文的文件路径（与书里写法相同），按编表命令同一口径套用。
    ServiceSchedule schedule = ServiceSchedule.NONE;
    String planPath = System.getenv("FTA_REPLAY_PLAN");
    if (planPath != null && !planPath.isBlank()) {
      org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.TimetablePlanBook.Parsed
          parsed =
              org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.TimetablePlanBook
                  .parse(Files.readString(Path.of(planPath), StandardCharsets.UTF_8));
      if (!parsed.issues().isEmpty()) {
        throw new IllegalStateException("计划书有错：" + parsed.issues());
      }
      Map<String, List<String>> groupsByLine = new LinkedHashMap<>();
      for (Member member : members) {
        groupsByLine.put(
            member.line().code(),
            ServiceGroupClassifier.classify(member.inputs()).groups().stream()
                .map(ServiceGroupClassifier.Group::name)
                .toList());
      }
      schedule = ServiceSchedule.of(parsed.pattern(), groupsByLine, joint, Set.of());
    }
    TimetableBuildOptions unplanned =
        new TimetableBuildOptions(
            clock(env("FTA_REPLAY_START", "")).orElse(TimetableBuildOptions.DEFAULT_SERVICE_START),
            clock(env("FTA_REPLAY_END", "")).orElse(TimetableBuildOptions.DEFAULT_SERVICE_END),
            Duration.ofSeconds(headway),
            Duration.ofSeconds(Integer.parseInt(env("FTA_REPLAY_DWELL", "20"))),
            new VehicleDutyPlanner.Limits(
                maxTrips,
                Integer.parseInt(env("FTA_REPLAY_MAX_DUTY_MINUTES", "240")) * 60,
                TurnaroundTable.none(),
                maxIdleOf(config)),
            "",
            ZoneId.systemDefault(),
            Duration.ofSeconds(
                Integer.parseInt(
                    env(
                        "FTA_REPLAY_SEPARATION",
                        String.valueOf(TimetableBuildOptions.DEFAULT_SEPARATION_SECONDS)))),
            Boolean.parseBoolean(env("FTA_REPLAY_STRICT", "false")),
            groupIntervals,
            new TimetableBuildOptions.Repair(
                Duration.ofSeconds(tolerance), Duration.ofSeconds(tolerance)),
            followingRules(config).withTrainLengths(lengths),
            timetable.recoveryMinDwellSeconds(),
            Integer.parseInt(
                env(
                    "FTA_REPLAY_MAX_CANCEL",
                    String.valueOf(TimetableBuildOptions.DEFAULT_MAX_CANCEL_TRIPS))),
            (int)
                Math.min(
                    timetable.holdMaxSeconds(),
                    org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopCoordinator
                        .HOLD_CEILING.toSeconds()));
    TimetableBuildOptions options = unplanned.withSchedule(schedule);

    List<TimetableSetBuilder.Member> setMembers = new ArrayList<>();
    for (Member member : members) {
      setMembers.add(
          new TimetableSetBuilder.Member(
              new TimetableBuilder.BuildInput(
                  UUID.nameUUIDFromBytes(
                      (member.line().id() + "/" + code).getBytes(StandardCharsets.UTF_8)),
                  company.id(),
                  operator.id(),
                  member.line().id(),
                  code,
                  code,
                  member.inputs(),
                  graph,
                  model,
                  Optional.empty()),
              member.line().code(),
              member.line().code(),
              member.own()));
    }

    TimetableBuildProgress progress = new TimetableBuildProgress();
    long started = System.nanoTime();
    TimetableSetBuilder.SetResult result =
        new TimetableSetBuilder(progress)
            .build(new TimetableSetBuilder.SetInput(setMembers, List.of()), options, Instant.now());
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
    report(out, String.join(",", lineCodes) + "/" + code, options, elapsedMillis, progress, result);
  }

  // ---------------------------------------------------------------- 输入

  private record Member(Line line, List<TimetableBuilder.RouteInput> inputs, Set<UUID> own) {}

  /** 与编表命令收集 route 同一口径：本线全部 route + 本运营商其它线的走行线路，跳过停用发车的。 */
  private static Member collect(
      StorageProvider provider,
      RouteDefinitionCache cache,
      Operator operator,
      Line line,
      long trainLength) {
    Map<UUID, Route> routes = new LinkedHashMap<>();
    provider.routes().listByLine(line.id()).forEach(route -> routes.put(route.id(), route));
    for (Line other : provider.lines().listByOperator(operator.id())) {
      if (!other.id().equals(line.id())) {
        provider.routes().listByLine(other.id()).stream()
            .filter(route -> route.operationType() != RouteOperationType.OPERATION)
            .forEach(route -> routes.putIfAbsent(route.id(), route));
      }
    }
    Set<UUID> own = new java.util.HashSet<>();
    List<TimetableBuilder.RouteInput> inputs = new ArrayList<>();
    for (Route route : routes.values()) {
      if (!LineSpawnMetadata.readBoolean(route.metadata(), "spawn_enabled").orElse(true)) {
        continue;
      }
      Optional<RouteDefinition> definition = cache.findById(route.id());
      if (definition.isEmpty()) {
        continue;
      }
      if (route.lineId().equals(line.id())) {
        own.add(route.id());
      }
      List<RouteStop> stops = cache.listStops(definition.get().id());
      inputs.add(
          new TimetableBuilder.RouteInput(
              route.id(),
              route.code(),
              route.operationType(),
              weightOf(route),
              definition.get(),
              stops,
              Optional.empty(),
              Optional.empty(),
              false,
              Optional.ofNullable(
                      route.metadata() == null ? null : route.metadata().get("spawn_group"))
                  .map(String::valueOf)
                  .map(String::trim)
                  .filter(group -> !group.isBlank()),
              org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.RouteDispatchMetadata.enabled(
                  route)));
    }
    return new Member(line, List.copyOf(inputs), Set.copyOf(own));
  }

  private static int weightOf(Route route) {
    Object raw = route.metadata() == null ? null : route.metadata().get("spawn_weight");
    if (raw instanceof Number number) {
      return Math.max(0, number.intValue());
    }
    if (raw instanceof String text) {
      try {
        return Math.max(0, Integer.parseInt(text.trim()));
      } catch (NumberFormatException ignored) {
        return 1;
      }
    }
    return 1;
  }

  /** 找包含这批 route 全部路径点的世界：离线没有图服务，直接按库里各世界的节点集合比对。 */
  private static UUID worldOf(Path db, List<Member> members) throws Exception {
    Map<UUID, Set<String>> nodesByWorld = new HashMap<>();
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT world_id, node_id FROM fta_rail_nodes")) {
      while (rows.next()) {
        nodesByWorld
            .computeIfAbsent(UUID.fromString(rows.getString(1)), id -> new java.util.HashSet<>())
            .add(rows.getString(2));
      }
    }
    List<String> needed =
        members.stream()
            .flatMap(member -> member.inputs().stream())
            .flatMap(input -> input.definition().waypoints().stream())
            .map(NodeId::value)
            .distinct()
            .toList();
    return nodesByWorld.entrySet().stream()
        .max(
            java.util.Comparator.comparingLong(
                entry -> needed.stream().filter(entry.getValue()::contains).count()))
        .map(Map.Entry::getKey)
        .orElseThrow();
  }

  private static ConfigManager.ConfigView loadConfig(Path dir) throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.load(dir.resolve("config.yml").toFile());
    return ConfigManager.parse(yaml, Logger.getLogger("timetable-replay"));
  }

  private static StorageProvider openStorage(ConfigManager.ConfigView config, Path db) {
    ConfigManager.StorageSettings storage =
        new ConfigManager.StorageSettings(
            ConfigManager.StorageBackend.SQLITE,
            new ConfigManager.SqliteSettings(db.toString()),
            Optional.empty(),
            config.storageSettings().poolSettings());
    ConfigManager.ConfigView view =
        new ConfigManager.ConfigView(
            config.configVersion(),
            false,
            config.locale(),
            storage,
            config.graphSettings(),
            config.autoStationSettings(),
            config.runtimeSettings(),
            config.spawnSettings(),
            config.trainConfigSettings(),
            config.reclaimSettings(),
            config.smartDispatcherSettings(),
            config.healthSettings(),
            config.timetableSettings(),
            config.callSettings(),
            config.couplerSettings(),
            config.webApiSettings());
    StorageManager manager =
        new StorageManager(null, new LoggerManager(Logger.getLogger("timetable-replay")));
    manager.apply(view);
    return manager.provider().orElseThrow();
  }

  /** 与 {@code FtaTimetableCommand} 的"不限闲置"同一个数。 */
  private static final int NO_IDLE_LIMIT_SECONDS = 86_400;

  /** 与 {@code FtaTimetableCommand#resolveMaxIdle} 同一口径（不给 --max-idle 时）。 */
  private static int maxIdleOf(ConfigManager.ConfigView config) {
    String explicit = System.getenv("FTA_REPLAY_MAX_IDLE");
    if (explicit != null && !explicit.isBlank()) {
      return Integer.parseInt(explicit.trim());
    }
    ConfigManager.ReclaimSettings reclaim = config.reclaimSettings();
    if (!reclaim.enabled()) {
      return NO_IDLE_LIMIT_SECONDS;
    }
    long configured = reclaim.maxIdleSeconds();
    return configured > 0 && configured <= NO_IDLE_LIMIT_SECONDS
        ? (int) configured
        : VehicleDutyPlanner.Limits.DEFAULT_MAX_IDLE_SECONDS;
  }

  /** 与 {@code FtaTimetableCommand#followingRules} 同一口径。 */
  private static TimetableBuildOptions.Following followingRules(ConfigManager.ConfigView config) {
    ConfigManager.RuntimeSettings runtime = config.runtimeSettings();
    double margin =
        Math.max(
            runtime.followingMinClearBlocks(),
            runtime.followingStopMarginBlocks() + runtime.movementAuthorityCautionMarginBlocks());
    ConfigManager.TrainConfigSettings trains = config.trainConfigSettings();
    return new TimetableBuildOptions.Following(
        trains.forType(trains.defaultTrainType()).decelBps2(),
        margin,
        runtime.rearGuardEdges(),
        runtime.dispatchTickIntervalTicks() / 20.0D,
        Map.of());
  }

  // ---------------------------------------------------------------- 报告

  private static void report(
      Path out,
      String scope,
      TimetableBuildOptions options,
      long elapsedMillis,
      TimetableBuildProgress progress,
      TimetableSetBuilder.SetResult set) {
    TimetableBuildResult result = set.joint();
    TimetableBuildProgress.Snapshot snapshot = progress.snapshot();
    List<String> lines = new ArrayList<>();
    lines.add("==== " + scope + " @ " + Instant.now());
    lines.add(
        String.format(
            Locale.ROOT,
            "参数: separation=%ds strict=%s groups=%s",
            options.separation().toSeconds(),
            options.strictConflicts(),
            options.groupIntervals()));
    lines.add(
        String.format(
            Locale.ROOT,
            "用时 %.1f s，完整构建 %d 次（%s）",
            elapsedMillis / 1000.0,
            snapshot.fullBuilds(),
            snapshot.describeTrials()));
    lines.add(
        "结果: "
            + (result.success() ? "成功" : "失败")
            + "，组间隔 "
            + result.groupIntervals().stream()
                .map(g -> g.group() + " " + g.targetSeconds() + "→" + g.effectiveSeconds())
                .toList());
    Map<String, Integer> dropped = new TreeMap<>();
    result.droppedTrips().forEach(trip -> dropped.merge(trip.reason().name(), 1, Integer::sum));
    long yieldSeconds = result.yields().stream().mapToLong(TripYield::waitSeconds).sum();
    lines.add(
        String.format(
            Locale.ROOT,
            "交路 %d、高峰 %d 列、取消 %d %s、让车 %d 处共 %d s、可吸收残余 %d、不可吸收 %d、快车被卡 %d s / %d 班、表里快车等待 %d s",
            result.dutyCount(),
            result.peakConcurrentVehicles(),
            result.droppedTrips().size(),
            dropped,
            result.yields().size(),
            yieldSeconds,
            result.absorbable().size(),
            result.unabsorbable().size(),
            result.rapidCatchUp().seconds(),
            result.rapidCatchUp().trips(),
            result.rapidCatchUp().held()));
    // 可吸收残余按车对类别：出入库走行之间、走行与运营车、运营车之间。
    Map<String, Integer> residualKinds = new TreeMap<>();
    for (ConflictAbsorption.Residual residual : result.absorbable()) {
      TimetableConflictChecker.Conflict conflict = residual.conflict();
      int legs =
          (conflict.first().matches(".*-(CREATE|RETURN)$") ? 1 : 0)
              + (conflict.second().matches(".*-(CREATE|RETURN)$") ? 1 : 0);
      String kind = legs == 2 ? "走行×走行" : legs == 1 ? "走行×运营" : "运营×运营";
      residualKinds.merge(kind + " " + conflict.kind(), 1, Integer::sum);
    }
    lines.add("  可吸收残余分类 " + residualKinds);
    // 同一对车相遇一次只算一处（一对车在一串相邻资源上、占用格与闭塞格各报一条），等待取最长；按车对类别合计。
    Map<String, Integer> encounters = new TreeMap<>();
    Map<String, Long> waits = new TreeMap<>();
    Map<String, Integer> longest = new HashMap<>();
    for (ConflictAbsorption.Residual residual : result.absorbable()) {
      TimetableConflictChecker.Conflict conflict = residual.conflict();
      String pair = conflict.first() + "|" + conflict.second();
      longest.merge(pair, residual.waitSeconds(), Math::max);
    }
    longest.forEach(
        (pair, wait) -> {
          String[] codes = pair.split("\\|", 2);
          int legs =
              (codes[0].matches(".*-(CREATE|RETURN)$") ? 1 : 0)
                  + (codes[1].matches(".*-(CREATE|RETURN)$") ? 1 : 0);
          String kind = legs == 2 ? "走行×走行" : legs == 1 ? "走行×运营" : "运营×运营";
          encounters.merge(kind, 1, Integer::sum);
          waits.merge(kind, (long) wait, Long::sum);
        });
    lines.add("  可吸收残余按车对 " + encounters + " 最长等待合计 " + waits);
    // 走行与运营相遇：按走行种类、资源（闭塞格并回原资源）、是不是闭塞格，看走行卡在哪。
    Map<String, long[]> legHits = new TreeMap<>();
    for (ConflictAbsorption.Residual residual : result.absorbable()) {
      TimetableConflictChecker.Conflict conflict = residual.conflict();
      boolean firstLeg = conflict.first().matches(".*-(CREATE|RETURN)$");
      boolean secondLeg = conflict.second().matches(".*-(CREATE|RETURN)$");
      if (firstLeg == secondLeg) {
        continue;
      }
      String leg = (firstLeg ? conflict.first() : conflict.second()).replaceAll(".*-", "");
      String resource = conflict.resource();
      boolean blocking = TimetableConflictChecker.isBlocking(resource);
      String base = resource.replace(TimetableConflictChecker.BLOCKING_SUFFIX, "");
      // 区间按两端所在的区段归并（edge:OP:A:B:track:seq → A:B），道岔、站台保留原名。
      String[] parts = base.replace("edge:", "").split("~")[0].split(":");
      String place =
          base.startsWith("edge:") && parts.length >= 3 ? "区间 " + parts[1] + ":" + parts[2] : base;
      String key =
          String.format(
              Locale.ROOT,
              "%s %s %s %s",
              leg,
              firstLeg ? "走行在前" : "走行在后",
              blocking ? "闭塞" : "占用",
              place);
      long[] acc = legHits.computeIfAbsent(key, k -> new long[3]);
      acc[0]++;
      acc[1] = Math.max(acc[1], residual.waitSeconds());
    }
    legHits.entrySet().stream()
        .sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]))
        .limit(12)
        .forEach(
            entry ->
                lines.add(
                    String.format(
                        Locale.ROOT,
                        "  走行残余 %-60s %4d 条 最长 %4d s",
                        entry.getKey(),
                        entry.getValue()[0],
                        entry.getValue()[1])));
    // FTA_REPLAY_TRACE=<交路前缀>,<HH:MM>,<HH:MM>：把这段时间里这些班次所在交路逐班列出来，看车是怎么接的。
    String trace = System.getenv("FTA_REPLAY_TRACE");
    if (trace != null && result.timetable().isPresent()) {
      String[] parts = trace.split(",");
      int from = LocalTime.parse(parts[1]).toSecondOfDay();
      int to = LocalTime.parse(parts[2]).toSecondOfDay();
      Timetable table = result.timetable().get();
      Map<UUID, TimetableTrip> trips = new HashMap<>();
      table.trips().forEach(trip -> trips.put(trip.id(), trip));
      for (VehicleDuty duty : table.duties()) {
        List<TimetableTrip> chain = duty.tripIds().stream().map(trips::get).toList();
        boolean hit =
            chain.stream()
                .anyMatch(
                    trip ->
                        trip.tripCode().startsWith(parts[0])
                            && trip.departureSecondOfDay() >= from
                            && trip.departureSecondOfDay() <= to);
        if (!hit) {
          continue;
        }
        StringBuilder line = new StringBuilder("  交路 " + duty.dutyCode());
        line.append(duty.createRouteId().isPresent() ? " 出库" : " 首站");
        for (TimetableTrip trip : chain) {
          line.append(" ")
              .append(trip.tripCode())
              .append("@")
              .append(LocalTime.ofSecondOfDay(trip.departureSecondOfDay()));
        }
        line.append(duty.returnRouteId().isPresent() ? " 回库" : " 收尾")
            .append("@")
            .append(LocalTime.ofSecondOfDay(Math.floorMod(duty.returnSecondOfDay(), 86_400)))
            .append(" ")
            .append(duty.closeReason());
        lines.add(line.toString());
      }
      result.droppedTrips().stream()
          .filter(gone -> gone.toString().contains(parts[0]))
          .forEach(gone -> lines.add("  取消 " + gone));
    }
    if (System.getenv("FTA_REPLAY_SAMPLES") != null) {
      result.absorbable().stream()
          .limit(Integer.parseInt(System.getenv("FTA_REPLAY_SAMPLES")))
          .forEach(residual -> lines.add("  残余例 " + residual));
    }
    // 偏离名义时隙的班次（端点串行、咽喉、让车、车未就绪）：按原因与 route 前缀计，看哪一步把相位推乱。
    Map<String, long[]> shifts = new TreeMap<>();
    for (TimetableBuildResult.TripShift shift : result.shifts()) {
      String route = shift.tripCode().replaceAll("-\\d+$", "");
      long[] acc = shifts.computeIfAbsent(shift.reason() + " " + route, key -> new long[2]);
      acc[0]++;
      acc[1] += Math.abs(shift.actualSecondOfDay() - shift.nominalSecondOfDay());
    }
    shifts.forEach(
        (key, acc) ->
            lines.add(String.format(Locale.ROOT, "  偏离 %-40s %4d 班 共 %6d s", key, acc[0], acc[1])));
    Map<String, long[]> yieldPairs = new TreeMap<>();
    for (TripYield yield : result.yields()) {
      String key =
          yield.second().replaceAll("-\\d+$", "") + " 让 " + yield.first().replaceAll("-\\d+$", "");
      long[] acc = yieldPairs.computeIfAbsent(key, k -> new long[2]);
      acc[0]++;
      acc[1] += yield.waitSeconds();
    }
    yieldPairs.entrySet().stream()
        .sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]))
        .limit(12)
        .forEach(
            entry ->
                lines.add(
                    String.format(
                        Locale.ROOT,
                        "  让车 %-48s %4d 处 共 %6d s",
                        entry.getKey(),
                        entry.getValue()[0],
                        entry.getValue()[1])));
    for (Map.Entry<UUID, Timetable> entry : set.tables().entrySet()) {
      lines.addAll(headways(entry.getValue()));
    }
    result.phaseNotes().forEach(note -> lines.add("  相位: " + note));
    result.resourcePhaseNotes().forEach(note -> lines.add("  资源相位: " + note));
    result.warnings().forEach(note -> lines.add("  提示: " + note));
    String text = String.join("\n", lines) + "\n";
    System.out.print(text);
    try {
      Files.writeString(
          out, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException ex) {
      System.out.println("写不进 " + out + ": " + ex.getMessage());
    }
  }

  /** 各运营 route 的班距分布：乘客看到的规整度。 */
  private static List<String> headways(Timetable table) {
    Map<UUID, String> codes = new HashMap<>();
    table.routePlans().forEach(plan -> codes.put(plan.routeId(), plan.routeCode()));
    Map<String, List<Integer>> departures = new TreeMap<>();
    for (TimetableTrip trip : table.trips()) {
      if (table.isLegTrip(trip)) {
        continue;
      }
      departures
          .computeIfAbsent(
              codes.getOrDefault(trip.routeId(), trip.routeId().toString()),
              key -> new ArrayList<>())
          .add(trip.departureSecondOfDay());
    }
    List<String> out = new ArrayList<>();
    departures.forEach(
        (route, times) -> {
          times.sort(Integer::compare);
          if (times.size() < 6) {
            out.add(String.format(Locale.ROOT, "  班距 %-24s n=%d", route, times.size()));
            return;
          }
          int[] gaps = new int[times.size() - 1];
          for (int i = 1; i < times.size(); i++) {
            gaps[i - 1] = times.get(i) - times.get(i - 1);
          }
          int[] sorted = gaps.clone();
          Arrays.sort(sorted);
          double mean = Arrays.stream(gaps).average().orElse(0.0D);
          double sd =
              Math.sqrt(
                  Arrays.stream(gaps)
                      .mapToDouble(g -> (g - mean) * (g - mean))
                      .average()
                      .orElse(0.0D));
          out.add(
              String.format(
                  Locale.ROOT,
                  "  班距 %-24s n=%4d min=%4d p10=%4d med=%4d p90=%4d max=%5d cv=%5.1f%%",
                  route,
                  times.size(),
                  sorted[0],
                  sorted[sorted.length / 10],
                  sorted[sorted.length / 2],
                  sorted[sorted.length * 9 / 10],
                  sorted[sorted.length - 1],
                  mean == 0.0D ? 0.0D : 100.0D * sd / mean));
        });
    return out;
  }

  // ---------------------------------------------------------------- 参数

  private static String env(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static String required(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank()) {
      throw new TestAbortedException(key + " 未设置");
    }
    return value.trim();
  }

  private static Optional<Integer> clock(String text) {
    if (text.isBlank()) {
      return Optional.empty();
    }
    String[] parts = text.split(":");
    return Optional.of(Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60);
  }

  private static Map<String, Integer> parseGroupHeadways(String text) {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (String part : text.split(",")) {
      int eq = part.indexOf('=');
      if (eq > 0) {
        out.put(part.substring(0, eq).trim(), Integer.parseInt(part.substring(eq + 1).trim()));
      }
    }
    return out;
  }
}
