package org.fetarute.fetaruteTCAddon.api.drive;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/**
 * 驾驶任务 API（1.10.0）：给玩家派驾驶任务、查询任务与驾驶状态、读取成绩记录。
 *
 * <p>任务就是开一趟表定车次：从接班站接班，开到终点站或指定的交班站。任务插件（例如 Typewriter）通过 {@link #assign} 给玩家派任务， 通过 {@code
 * org.fetarute.fetaruteTCAddon.api.event} 里的 {@code Driver*}、{@code DriveSession*} 事件跟进：领取、开始、
 * 每站成绩、结束。
 *
 * <p>派任务不受任务板时间窗限制，也不要求玩家在车站附近；其余规则与任务板相同：驾驶功能开启、不在拥堵保护中、每名玩家一个未结束的任务、每个车次一名驾驶员。
 *
 * <p>线程：{@link #offersAt}、{@link #assign}、{@link #abandon} 只能在服务器主线程调用，否则抛出 {@link
 * IllegalStateException}；{@link #taskOf}、{@link #sessionOf}
 * 可在任意线程调用，驾驶事件发出时已是最新，其余时候非主线程读到的是最多半秒前的快照；{@link #records} 与 {@link #stats} 读数据库，返回的 future
 * 在异步线程完成。所有返回值都是不可变快照。
 *
 * <p>驾驶功能未启用（{@code drive.yml} 关闭或模块未加载）时 {@link #enabled()} 为 {@code false}，查询返回空，派任务返回 {@link
 * AssignResult#DISABLED}。
 */
public interface DriveApi {

  /** 驾驶调度列车（任务模式）是否可用。 */
  boolean enabled();

  /**
   * 某站接下来可以领取的车次（与任务板同一口径：去掉已取消、在本站终到、已被领取、早已开走的）。
   *
   * @param stationCode 站码（不分大小写）
   * @param from 从这个时刻起
   * @param window 往后看多久
   * @param limit 最多返回几条
   */
  List<TaskOffer> offersAt(String stationCode, Instant from, Duration window, int limit);

  /** 玩家当前的任务（未结束的，或最近结束、尚未被新任务替换的）。 */
  Optional<TaskView> taskOf(UUID playerId);

  /** 玩家当前的驾驶会话；没有在驾驶时为空。 */
  Optional<SessionView> sessionOf(UUID playerId);

  /**
   * 给玩家派一个驾驶任务（主线程）。成功时玩家会收到领取提示，和在任务板领取一样。
   *
   * <p>派出前会触发可取消的 {@code DriverTaskClaimEvent}。
   */
  AssignResult assign(Player player, TaskRequest request);

  /**
   * 放弃玩家的任务（主线程）：还没开始驾驶的直接作废；驾驶中的先停车再交还自动运行。
   *
   * @param reason 原因（写进任务的结束原因）
   * @return 玩家是否有未结束的任务
   */
  boolean abandon(UUID playerId, String reason);

  /**
   * 玩家最近的驾驶记录（只含开过车的任务），新的在前。
   *
   * @param limit 最多几条
   */
  CompletableFuture<List<TaskRecord>> records(UUID playerId, int limit);

  /** 玩家的累计成绩。 */
  CompletableFuture<TaskStats> stats(UUID playerId);

  /** 驾驶方式。 */
  enum Mode {
    /** 人工驾驶：驾驶员操纵手柄、对标、开关门。 */
    MANUAL,
    /** ATO：自动运行操纵与停车，驾驶员确认发车。 */
    ATO
  }

  /** 任务状态。 */
  enum TaskState {
    /** 已领取，等列车到站。 */
    CLAIMED,
    /** 驾驶中。 */
    DRIVING,
    /** 开到终点站或交班站。 */
    COMPLETED,
    /** 驾驶员放弃或离开。 */
    ABANDONED,
    /** 列车没等到或已开走。 */
    EXPIRED,
    /** 卡住太久或超过任务时限，被收回。 */
    FAILED,
    /** 调度、管理员或拥堵保护收回，不怪驾驶员。 */
    INTERRUPTED;

    /** 是否已结束。 */
    public boolean finished() {
      return this != CLAIMED && this != DRIVING;
    }
  }

  /** 派任务的结果。 */
  enum AssignResult {
    /** 已派出。 */
    ASSIGNED,
    /** 驾驶功能未启用。 */
    DISABLED,
    /** 拥堵保护中，暂停接班（常量名沿用早先的说法）。 */
    BREAKER_OPEN,
    /** 玩家已有未结束的任务。 */
    ALREADY_HAS_TASK,
    /** 这个车次已被别人领取。 */
    TAKEN,
    /** 找不到这个车次，或它在接班站已取消。 */
    UNAVAILABLE,
    /** 接班站或交班站不是这趟车停车的车站，或交班站不在接班站之后。 */
    INVALID_STATIONS,
    /** 列车已经开过接班站，或还没对上列车而接班站计划发车已过去 10 分钟以上（会立即作废）。 */
    DEPARTED,
    /** 被 {@code DriverTaskClaimEvent} 取消。 */
    CANCELLED
  }

  /** 每站的停车结果（1.12.0）。 */
  enum StopOutcome {
    /** 停准。 */
    ACCURATE,
    /** 停车合格：在可开门范围内。 */
    ACCEPTED,
    /** 欠标超限：停短（超出可开门范围、未前移到位）。 */
    SHORT,
    /** 过标超限：停过头（越过可开门范围，未到越站阈值）。 */
    OVERRUN,
    /** 越站：越过停车点太多，本站没停。 */
    SKIPPED
  }

  /**
   * 每站的停车结果。
   *
   * @deprecated 1.12.0 起改名为 {@link StopOutcome}，常量相同
   */
  @Deprecated(since = "1.12.0")
  enum StopWindow {
    /** 停准。 */
    ACCURATE,
    /** 停车合格。 */
    ACCEPTED,
    /** 欠标超限。 */
    SHORT,
    /** 过标超限。 */
    OVERRUN,
    /** 越站。 */
    SKIPPED
  }

  /**
   * 任务的车站。
   *
   * @param stationCode 站码
   * @param name 站名（查不到时为站码）
   * @param stopSequence 在这趟车次里的停靠序号（0 起）
   */
  record StationRef(String stationCode, String name, int stopSequence) {
    public StationRef {
      stationCode = stationCode == null ? "" : stationCode;
      name = name == null || name.isBlank() ? stationCode : name;
    }
  }

  /**
   * 一个任务的快照。
   *
   * @param taskId 任务 ID（每次领取或派出都不同）
   * @param playerId 驾驶员
   * @param timetableId 时刻表
   * @param tripCode 车次号
   * @param serviceDate 服务日
   * @param routeCode 交路代码
   * @param takeoverStation 接班站
   * @param handoverStation 交班站；开到终点站的任务为空
   * @param plannedDeparture 接班站计划发车
   * @param mode 驾驶方式
   * @param state 状态
   * @param trainName 担当的列车；还没对上时为空
   * @param depotPickup 是否从车库接车
   * @param source 来源：任务板为 {@code "board"}；没领任务直接接管调度列车、按列车所跑车次当场记成的为 {@code
   *     "takeover"}；终点站结算后接着开同一列车的下一趟为 {@code "continuation"}；插件派出的为调用方给的来源标记
   * @param metadata 调用方给的附加数据，事件里原样带回。驾驶证路考与路考练习的任务（来源 {@code "exam"}、{@code "training"}）带 {@code
   *     license}：所考等级的 ID，默认为 {@code learner}、{@code driver}（早先的版本为 {@code free}、{@code dispatch}，
   *     判断是否路考请按来源，不要按等级 ID 写死）
   * @param endReason 结束原因；未结束时为空串
   * @param points 得分（0–100）；没有开过车或尚未评分时为空
   * @param grade 评级（S/A/B/C/D）；没有评分时为空
   */
  record TaskView(
      UUID taskId,
      UUID playerId,
      UUID timetableId,
      String tripCode,
      LocalDate serviceDate,
      String routeCode,
      StationRef takeoverStation,
      Optional<StationRef> handoverStation,
      Instant plannedDeparture,
      Mode mode,
      TaskState state,
      Optional<String> trainName,
      boolean depotPickup,
      String source,
      Map<String, String> metadata,
      String endReason,
      OptionalInt points,
      Optional<String> grade) {
    public TaskView {
      Objects.requireNonNull(taskId, "taskId");
      Objects.requireNonNull(playerId, "playerId");
      handoverStation = handoverStation == null ? Optional.empty() : handoverStation;
      trainName = trainName == null ? Optional.empty() : trainName;
      source = source == null ? "" : source;
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
      endReason = endReason == null ? "" : endReason;
      points = points == null ? OptionalInt.empty() : points;
      grade = grade == null ? Optional.empty() : grade;
    }

    /**
     * 接班站。
     *
     * @deprecated 1.12.0 起改名为 {@link #takeoverStation()}
     */
    @Deprecated(since = "1.12.0")
    public StationRef board() {
      return takeoverStation;
    }

    /**
     * 交班站。
     *
     * @deprecated 1.12.0 起改名为 {@link #handoverStation()}
     */
    @Deprecated(since = "1.12.0")
    public Optional<StationRef> alight() {
      return handoverStation;
    }
  }

  /**
   * 一个驾驶会话的快照。
   *
   * @param playerId 驾驶员
   * @param trainName 列车
   * @param dispatched 是否为调度列车（驾驶任务或运营人员接管）；否则为自由驾驶
   * @param mode 驾驶方式
   * @param speedKmh 车速（km/h，按 1 格 = 1 米）
   * @param nextStation 下一站名；不明时为空
   * @param taskId 所属任务；没有任务时为空
   */
  record SessionView(
      UUID playerId,
      String trainName,
      boolean dispatched,
      Mode mode,
      double speedKmh,
      Optional<String> nextStation,
      Optional<UUID> taskId) {
    public SessionView {
      nextStation = nextStation == null ? Optional.empty() : nextStation;
      taskId = taskId == null ? Optional.empty() : taskId;
    }
  }

  /**
   * 一条可领取的车次。
   *
   * @param timetableId 时刻表
   * @param tripCode 车次号
   * @param serviceDate 服务日
   * @param routeCode 交路代码
   * @param stopSequence 本站停靠序号
   * @param plannedDeparture 本站计划发车
   * @param trainName 担当的列车；还没对上时为空
   * @param dwelling 列车是否正在本站停站
   * @param depotOrigin 列车从哪个车库出车（可改为从车库接车）；由终点站待命车接班时为空
   */
  record TaskOffer(
      UUID timetableId,
      String tripCode,
      LocalDate serviceDate,
      String routeCode,
      int stopSequence,
      Instant plannedDeparture,
      Optional<String> trainName,
      boolean dwelling,
      Optional<String> depotOrigin) {
    public TaskOffer {
      trainName = trainName == null ? Optional.empty() : trainName;
      depotOrigin = depotOrigin == null ? Optional.empty() : depotOrigin;
    }
  }

  /**
   * 派任务的请求。用 {@link #trip} 开始，按需链式设置。
   *
   * @param timetableId 时刻表
   * @param tripCode 车次号（不分大小写）
   * @param serviceDate 服务日
   * @param takeoverStation 接班站站码；为空时从这趟车第一个停车的车站接班
   * @param takeoverStopSequence 接班站的停靠序号（同一车次两次经过同一站时用它区分）；-1 时取该站码的第一次停靠
   * @param handoverStation 交班站站码；为空时开到终点站
   * @param mode 驾驶方式
   * @param depotPickup 列车从车库出车时是否从车库接车
   * @param source 来源标记（例如插件名），事件里原样带回
   * @param metadata 附加数据（例如任务 ID），事件里原样带回
   * @param notifyPlayer 是否给玩家发领取提示
   */
  record TaskRequest(
      UUID timetableId,
      String tripCode,
      LocalDate serviceDate,
      Optional<String> takeoverStation,
      int takeoverStopSequence,
      Optional<String> handoverStation,
      Mode mode,
      boolean depotPickup,
      String source,
      Map<String, String> metadata,
      boolean notifyPlayer) {
    public TaskRequest {
      Objects.requireNonNull(timetableId, "timetableId");
      Objects.requireNonNull(tripCode, "tripCode");
      Objects.requireNonNull(serviceDate, "serviceDate");
      takeoverStation = takeoverStation == null ? Optional.empty() : takeoverStation;
      takeoverStopSequence = Math.max(-1, takeoverStopSequence);
      handoverStation = handoverStation == null ? Optional.empty() : handoverStation;
      mode = mode == null ? Mode.MANUAL : mode;
      source = source == null || source.isBlank() ? "api" : source;
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** 开这趟车次：从第一个停车的车站开到终点站、人工驾驶、给玩家发提示。 */
    public static TaskRequest trip(UUID timetableId, String tripCode, LocalDate serviceDate) {
      return new TaskRequest(
          timetableId,
          tripCode,
          serviceDate,
          Optional.empty(),
          -1,
          Optional.empty(),
          Mode.MANUAL,
          false,
          "api",
          Map.of(),
          true);
    }

    /** 开一条可领取的车次：从它的这一站、这一次停靠接班。 */
    public static TaskRequest of(TaskOffer offer, String stationCode) {
      Objects.requireNonNull(offer, "offer");
      TaskRequest request =
          trip(offer.timetableId(), offer.tripCode(), offer.serviceDate()).takeoverAt(stationCode);
      return new TaskRequest(
          request.timetableId,
          request.tripCode,
          request.serviceDate,
          request.takeoverStation,
          offer.stopSequence(),
          request.handoverStation,
          request.mode,
          request.depotPickup,
          request.source,
          request.metadata,
          request.notifyPlayer);
    }

    /** 从这一站接班（该站码的第一次停靠）。 */
    public TaskRequest takeoverAt(String stationCode) {
      return new TaskRequest(
          timetableId,
          tripCode,
          serviceDate,
          Optional.ofNullable(stationCode).filter(code -> !code.isBlank()),
          -1,
          handoverStation,
          mode,
          depotPickup,
          source,
          metadata,
          notifyPlayer);
    }

    /** 开到这一站就结束（区间任务），交还自动运行。 */
    public TaskRequest handoverAt(String stationCode) {
      return new TaskRequest(
          timetableId,
          tripCode,
          serviceDate,
          takeoverStation,
          takeoverStopSequence,
          Optional.ofNullable(stationCode).filter(code -> !code.isBlank()),
          mode,
          depotPickup,
          source,
          metadata,
          notifyPlayer);
    }

    /** 驾驶方式。 */
    public TaskRequest mode(Mode newMode) {
      return new TaskRequest(
          timetableId,
          tripCode,
          serviceDate,
          takeoverStation,
          takeoverStopSequence,
          handoverStation,
          newMode,
          depotPickup,
          source,
          metadata,
          notifyPlayer);
    }

    /** 列车从车库出车时从车库接车。 */
    public TaskRequest depotPickup(boolean pickup) {
      return new TaskRequest(
          timetableId,
          tripCode,
          serviceDate,
          takeoverStation,
          takeoverStopSequence,
          handoverStation,
          mode,
          pickup,
          source,
          metadata,
          notifyPlayer);
    }

    /** 来源标记与附加数据，事件里原样带回。 */
    public TaskRequest tagged(String newSource, Map<String, String> newMetadata) {
      return new TaskRequest(
          timetableId,
          tripCode,
          serviceDate,
          takeoverStation,
          takeoverStopSequence,
          handoverStation,
          mode,
          depotPickup,
          newSource,
          newMetadata,
          notifyPlayer);
    }

    /** 是否给玩家发领取提示。 */
    public TaskRequest notifyPlayer(boolean notify) {
      return new TaskRequest(
          timetableId,
          tripCode,
          serviceDate,
          takeoverStation,
          takeoverStopSequence,
          handoverStation,
          mode,
          depotPickup,
          source,
          metadata,
          notify);
    }

    /**
     * 接班站站码。
     *
     * @deprecated 1.12.0 起改名为 {@link #takeoverStation()}
     */
    @Deprecated(since = "1.12.0")
    public Optional<String> boardStation() {
      return takeoverStation;
    }

    /**
     * 接班站的停靠序号。
     *
     * @deprecated 1.12.0 起改名为 {@link #takeoverStopSequence()}
     */
    @Deprecated(since = "1.12.0")
    public int boardStopSequence() {
      return takeoverStopSequence;
    }

    /**
     * 交班站站码。
     *
     * @deprecated 1.12.0 起改名为 {@link #handoverStation()}
     */
    @Deprecated(since = "1.12.0")
    public Optional<String> alightStation() {
      return handoverStation;
    }

    /**
     * 从这一站接班。
     *
     * @deprecated 1.12.0 起改名为 {@link #takeoverAt(String)}
     */
    @Deprecated(since = "1.12.0")
    public TaskRequest boardAt(String stationCode) {
      return takeoverAt(stationCode);
    }

    /**
     * 开到这一站就结束。
     *
     * @deprecated 1.12.0 起改名为 {@link #handoverAt(String)}
     */
    @Deprecated(since = "1.12.0")
    public TaskRequest alightAt(String stationCode) {
      return handoverAt(stationCode);
    }
  }

  /**
   * 一站的成绩。
   *
   * @param station 站名
   * @param offsetBlocks 停车误差（格，越过为正）；越站时为越过的距离
   * @param outcome 停车结果
   * @param wrongDoor 是否开过非站台侧的门
   * @param doorsTakenOver 驾驶员迟迟不开门，由站台代为开关门
   */
  record StopResult(
      String station,
      double offsetBlocks,
      StopOutcome outcome,
      boolean wrongDoor,
      boolean doorsTakenOver) {

    /**
     * 停车结果。
     *
     * @deprecated 1.12.0 起改为 {@link #outcome()}
     */
    @Deprecated(since = "1.12.0")
    public StopWindow window() {
      return StopWindow.valueOf(outcome.name());
    }
  }

  /**
   * 任务结束时的成绩。
   *
   * @param points 得分（0–100）
   * @param grade 评级（S/A/B/C/D）
   * @param stops 各站成绩
   * @param serviceInterventions 防护常用制动介入次数
   * @param emergencyInterventions 紧急制动介入次数
   * @param forcedStops 强制停车次数
   * @param signalMisses 漏确认信号次数
   * @param delayGainedSeconds 驾驶期间晚点增加的秒数（提前为负）；查不到时为空
   */
  record TaskScore(
      int points,
      String grade,
      List<StopResult> stops,
      int serviceInterventions,
      int emergencyInterventions,
      int forcedStops,
      int signalMisses,
      OptionalLong delayGainedSeconds) {
    public TaskScore {
      grade = grade == null ? "" : grade;
      stops = stops == null ? List.of() : List.copyOf(stops);
      delayGainedSeconds = delayGainedSeconds == null ? OptionalLong.empty() : delayGainedSeconds;
    }
  }

  /**
   * 一条驾驶记录。
   *
   * @param timetableId 时刻表
   * @param tripCode 车次号
   * @param serviceDate 服务日
   * @param routeCode 交路代码
   * @param trainName 列车
   * @param mode 驾驶方式
   * @param state 终态
   * @param points 得分
   * @param grade 评级
   * @param startedAt 开始驾驶
   * @param finishedAt 结束
   */
  record TaskRecord(
      UUID timetableId,
      String tripCode,
      LocalDate serviceDate,
      String routeCode,
      String trainName,
      String mode,
      String state,
      int points,
      String grade,
      Instant startedAt,
      Instant finishedAt) {}

  /**
   * 累计成绩。
   *
   * @param tasks 开过车的任务数
   * @param completed 其中开完的任务数
   * @param totalPoints 开完的任务的总得分（与排行榜同一口径）
   * @param bestGrade 最好的评级；没有记录时为空
   */
  record TaskStats(int tasks, int completed, long totalPoints, Optional<String> bestGrade) {
    public TaskStats {
      bestGrade = bestGrade == null ? Optional.empty() : bestGrade;
    }
  }

  /** 驾驶功能未加载时的占位实现。 */
  DriveApi UNAVAILABLE =
      new DriveApi() {
        @Override
        public boolean enabled() {
          return false;
        }

        @Override
        public List<TaskOffer> offersAt(
            String stationCode, Instant from, Duration window, int limit) {
          return List.of();
        }

        @Override
        public Optional<TaskView> taskOf(UUID playerId) {
          return Optional.empty();
        }

        @Override
        public Optional<SessionView> sessionOf(UUID playerId) {
          return Optional.empty();
        }

        @Override
        public AssignResult assign(Player player, TaskRequest request) {
          return AssignResult.DISABLED;
        }

        @Override
        public boolean abandon(UUID playerId, String reason) {
          return false;
        }

        @Override
        public CompletableFuture<List<TaskRecord>> records(UUID playerId, int limit) {
          return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<TaskStats> stats(UUID playerId) {
          return CompletableFuture.completedFuture(new TaskStats(0, 0, 0L, Optional.empty()));
        }
      };
}
