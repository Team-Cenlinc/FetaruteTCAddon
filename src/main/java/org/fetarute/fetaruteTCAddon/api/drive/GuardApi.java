package org.fetarute.fetaruteTCAddon.api.drive;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/**
 * 车掌 API（1.14.0）：给玩家派车掌任务、查询任务与值乘状态、读取车掌记录。
 *
 * <p>车掌任务就是当一班表定车次的车掌：从接班站上岗（列车到站后坐进车尾驾驶室；始发站的终点站待命车先扣着等车掌），值乘到终点站或指定的交班站。 任务插件（例如 Typewriter）通过
 * {@link #assign} 给玩家派任务，通过 {@code org.fetarute.fetaruteTCAddon.api.event} 里的 {@code Guard*}
 * 事件跟进：领取、上岗、每站作业、发车信号、紧急停车、异常报告、每趟成绩、离岗与任务结束。玩家不领任务、直接 {@code /fta guard on}
 * 上岗时同样发出值乘与作业事件，只是没有任务。
 *
 * <p>派任务不受任务板时间窗限制，也不要求玩家在车站附近；其余规则与车掌任务板相同：车掌功能开启、玩家不在值乘也不在驾驶、每名玩家一个未结束的任务、每个车次一名车掌。
 * 车掌与驾驶员的名额分开算，同一车次可以各有一名。
 *
 * <p>线程：{@link #offersAt}、{@link #assign}、{@link #abandon} 只能在服务器主线程调用，否则抛出 {@link
 * IllegalStateException}；{@link #taskOf}、{@link #dutyOf}、{@link #guardOf} 可在任意线程调用，车掌事件发出时已是最新，
 * 其余时候非主线程读到的是最多半秒前的快照；{@link #records} 读数据库，返回的 future 在异步线程完成。所有返回值都是不可变快照。
 *
 * <p>车掌功能未启用（{@code drive.yml} 的 {@code guard.enabled} 关闭或模块未加载）时 {@link #enabled()} 为 {@code
 * false}，查询返回空，派任务返回 {@link AssignResult#DISABLED}。
 */
public interface GuardApi {

  /** 车掌功能是否可用。 */
  boolean enabled();

  /**
   * 某站接下来可以领取车掌的车次（与车掌任务板同一口径：去掉已取消、在本站终到、车掌已被领取、早已开走的）。
   *
   * @param stationCode 站码（不分大小写）
   * @param from 从这个时刻起
   * @param window 往后看多久
   * @param limit 最多返回几条
   */
  List<DriveApi.TaskOffer> offersAt(String stationCode, Instant from, Duration window, int limit);

  /** 玩家当前的车掌任务（未结束的，或最近结束、尚未被新任务替换的）。 */
  Optional<TaskView> taskOf(UUID playerId);

  /** 玩家当前的值乘；没有在当车掌时为空。 */
  Optional<DutyView> dutyOf(UUID playerId);

  /** 这列车上的车掌（按当前车名，不分大小写）；没有车掌时为空。 */
  Optional<UUID> guardOf(String trainName);

  /**
   * 给玩家派一个车掌任务（主线程）。成功时玩家会收到领取提示，和在车掌任务板领取一样。
   *
   * <p>派出前会触发可取消的 {@code GuardTaskClaimEvent}。
   */
  AssignResult assign(Player player, TaskRequest request);

  /**
   * 放弃玩家的车掌任务（主线程）：还没上岗的直接作废；值乘中的结束值乘。
   *
   * @param reason 原因（写进任务的结束原因，只对还没上岗的任务生效）
   * @return 玩家是否有未结束的任务
   */
  boolean abandon(UUID playerId, String reason);

  /**
   * 玩家最近的车掌记录（每趟一条，只含按时刻表运行、做过作业的），新的在前。
   *
   * @param limit 最多几条
   */
  CompletableFuture<List<DriveApi.TaskRecord>> records(UUID playerId, int limit);

  /** 车掌任务状态。 */
  enum TaskState {
    /** 已领取，等列车到站。 */
    CLAIMED,
    /** 值乘中。 */
    ON_DUTY,
    /** 值乘到终点站或交班站。 */
    COMPLETED,
    /** 车掌放弃或离开。 */
    ABANDONED,
    /** 列车没等到、已开走，或始发站没按时上岗。 */
    EXPIRED,
    /** 连续超时、漏乘或换端没坐进车尾。 */
    FAILED,
    /** 管理员撤下、列车不在了或功能关闭，不怪车掌。 */
    INTERRUPTED;

    /** 是否已结束。 */
    public boolean finished() {
      return this != CLAIMED && this != ON_DUTY;
    }
  }

  /** 派任务的结果。 */
  enum AssignResult {
    /** 已派出。 */
    ASSIGNED,
    /** 车掌功能未启用。 */
    DISABLED,
    /** 玩家正在值乘。 */
    ON_DUTY,
    /** 玩家正在驾驶。 */
    DRIVING,
    /** 玩家已有未结束的车掌任务。 */
    ALREADY_HAS_TASK,
    /** 这个车次的车掌已被别人领取。 */
    TAKEN,
    /** 找不到这个车次，或它在接班站已取消。 */
    UNAVAILABLE,
    /** 接班站或交班站不是这趟车停车的车站，或交班站不在接班站之后。 */
    INVALID_STATIONS,
    /** 列车已经开过接班站，或还没对上列车而接班站计划发车已过去 10 分钟以上（会立即作废）。 */
    DEPARTED,
    /** 被 {@code GuardTaskClaimEvent} 取消。 */
    CANCELLED
  }

  /** 异常情况报告的原因。 */
  enum Incident {
    /** 夹人夹物。 */
    CAUGHT,
    /** 上下车拥挤。 */
    CROWDED,
    /** 乘客求助。 */
    PASSENGER,
    /** 设备异常。 */
    EQUIPMENT
  }

  /**
   * 一个车掌任务的快照。
   *
   * @param taskId 任务 ID（每次领取或派出都不同）
   * @param playerId 车掌
   * @param timetableId 时刻表
   * @param tripCode 车次号
   * @param serviceDate 服务日
   * @param routeCode 交路代码
   * @param takeoverStation 接班站
   * @param handoverStation 交班站；值乘到终点站的任务为空
   * @param plannedDeparture 接班站计划发车
   * @param state 状态
   * @param trainName 担当的列车；还没对上时为空
   * @param source 来源：车掌任务板为 {@code "board"}；插件派出的为调用方给的来源标记
   * @param metadata 调用方给的附加数据，事件里原样带回
   * @param endReason 结束原因；未结束时为空串
   * @param points 这一趟的得分（0–100）；还没结算时为空
   * @param grade 评级（S/A/B/C/D）；还没结算时为空
   */
  record TaskView(
      UUID taskId,
      UUID playerId,
      UUID timetableId,
      String tripCode,
      LocalDate serviceDate,
      String routeCode,
      DriveApi.StationRef takeoverStation,
      Optional<DriveApi.StationRef> handoverStation,
      Instant plannedDeparture,
      TaskState state,
      Optional<String> trainName,
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
  }

  /**
   * 一次值乘的快照。
   *
   * @param playerId 车掌
   * @param trainName 列车（当前车名）
   * @param taskId 所接的任务；不是领任务上岗、或任务那一趟已结算时为空
   * @param station 正在停站的车站；不在停站时为空
   * @param completedStops 本次值乘做过作业的站数
   * @param timeoutStops 有超时的站数（站台代开、代关或代发发车信号）
   * @param emergencyHold 是否拉着紧急停车
   */
  record DutyView(
      UUID playerId,
      String trainName,
      Optional<UUID> taskId,
      Optional<String> station,
      int completedStops,
      int timeoutStops,
      boolean emergencyHold) {
    public DutyView {
      Objects.requireNonNull(playerId, "playerId");
      trainName = trainName == null ? "" : trainName;
      taskId = taskId == null ? Optional.empty() : taskId;
      station = station == null ? Optional.empty() : station;
    }
  }

  /**
   * 一站的作业。
   *
   * @param station 站名
   * @param forcedOpen 开门超时，由站台代开
   * @param forcedClose 关门超时，由站台代关
   * @param forcedSignal 发车铃超时，由站台代发发车信号
   * @param wrongDoor 开过不该开的一侧车门
   * @param closedEarly 停站时间未到就关门
   * @param closingWatch 关门监视是否合格；没采到样时为空
   * @param departureWatch 出站监视是否合格；没采到样时为空
   * @param incidents 本站异常报告次数
   */
  record StopWork(
      String station,
      boolean forcedOpen,
      boolean forcedClose,
      boolean forcedSignal,
      boolean wrongDoor,
      boolean closedEarly,
      Optional<Boolean> closingWatch,
      Optional<Boolean> departureWatch,
      int incidents) {
    public StopWork {
      station = station == null ? "" : station;
      closingWatch = closingWatch == null ? Optional.empty() : closingWatch;
      departureWatch = departureWatch == null ? Optional.empty() : departureWatch;
    }

    /** 有没有超时。 */
    public boolean timedOut() {
      return forcedOpen || forcedClose || forcedSignal;
    }
  }

  /**
   * 一趟的成绩。
   *
   * @param points 得分（0–100）
   * @param grade 评级（S/A/B/C/D）；连续超时、漏乘、换端没坐进车尾的最高 D
   * @param stops 各站作业
   * @param kilometres 值乘里程（公里）
   */
  record TripScore(int points, String grade, List<StopWork> stops, double kilometres) {
    public TripScore {
      grade = grade == null ? "" : grade;
      stops = stops == null ? List.of() : List.copyOf(stops);
    }
  }

  /**
   * 派车掌任务的请求。用 {@link #trip} 开始，按需链式设置。
   *
   * @param timetableId 时刻表
   * @param tripCode 车次号（不分大小写）
   * @param serviceDate 服务日
   * @param takeoverStation 接班站站码；为空时从这趟车第一个停车的车站接班
   * @param takeoverStopSequence 接班站的停靠序号（同一车次两次经过同一站时用它区分）；-1 时取该站码的第一次停靠
   * @param handoverStation 交班站站码；为空时值乘到终点站
   * @param source 来源标记（例如插件名），事件里原样带回
   * @param metadata 附加数据（例如任务 ID），事件里原样带回
   * @param notifyPlayer 是否给玩家发领取提示
   * @param rewards 是否按 {@code drive.yml} 的 rewards 段与车掌系数发车掌奖励；任务插件自己发奖、不想重复时可关掉
   */
  record TaskRequest(
      UUID timetableId,
      String tripCode,
      LocalDate serviceDate,
      Optional<String> takeoverStation,
      int takeoverStopSequence,
      Optional<String> handoverStation,
      String source,
      Map<String, String> metadata,
      boolean notifyPlayer,
      boolean rewards) {
    public TaskRequest {
      Objects.requireNonNull(timetableId, "timetableId");
      Objects.requireNonNull(tripCode, "tripCode");
      Objects.requireNonNull(serviceDate, "serviceDate");
      takeoverStation = takeoverStation == null ? Optional.empty() : takeoverStation;
      takeoverStopSequence = Math.max(-1, takeoverStopSequence);
      handoverStation = handoverStation == null ? Optional.empty() : handoverStation;
      source = source == null || source.isBlank() ? "api" : source;
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** 当这趟车次的车掌：从第一个停车的车站值乘到终点站、给玩家发提示、发车掌奖励。 */
    public static TaskRequest trip(UUID timetableId, String tripCode, LocalDate serviceDate) {
      return new TaskRequest(
          timetableId,
          tripCode,
          serviceDate,
          Optional.empty(),
          -1,
          Optional.empty(),
          "api",
          Map.of(),
          true,
          true);
    }

    /** 当一条可领取车次的车掌：从它的这一站、这一次停靠接班。 */
    public static TaskRequest of(DriveApi.TaskOffer offer, String stationCode) {
      Objects.requireNonNull(offer, "offer");
      return new TaskRequest(
          offer.timetableId(),
          offer.tripCode(),
          offer.serviceDate(),
          Optional.ofNullable(stationCode).filter(code -> !code.isBlank()),
          offer.stopSequence(),
          Optional.empty(),
          "api",
          Map.of(),
          true,
          true);
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
          source,
          metadata,
          notifyPlayer,
          rewards);
    }

    /** 值乘到这一站就交班（区间任务）：列车在这一站停妥即交班，车门交还站台。 */
    public TaskRequest handoverAt(String stationCode) {
      return new TaskRequest(
          timetableId,
          tripCode,
          serviceDate,
          takeoverStation,
          takeoverStopSequence,
          Optional.ofNullable(stationCode).filter(code -> !code.isBlank()),
          source,
          metadata,
          notifyPlayer,
          rewards);
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
          newSource,
          newMetadata,
          notifyPlayer,
          rewards);
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
          source,
          metadata,
          notify,
          rewards);
    }

    /** 是否发车掌奖励。 */
    public TaskRequest rewards(boolean pay) {
      return new TaskRequest(
          timetableId,
          tripCode,
          serviceDate,
          takeoverStation,
          takeoverStopSequence,
          handoverStation,
          source,
          metadata,
          notifyPlayer,
          pay);
    }
  }

  /** 车掌功能未加载时的占位实现。 */
  GuardApi UNAVAILABLE =
      new GuardApi() {
        @Override
        public boolean enabled() {
          return false;
        }

        @Override
        public List<DriveApi.TaskOffer> offersAt(
            String stationCode, Instant from, Duration window, int limit) {
          return List.of();
        }

        @Override
        public Optional<TaskView> taskOf(UUID playerId) {
          return Optional.empty();
        }

        @Override
        public Optional<DutyView> dutyOf(UUID playerId) {
          return Optional.empty();
        }

        @Override
        public Optional<UUID> guardOf(String trainName) {
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
        public CompletableFuture<List<DriveApi.TaskRecord>> records(UUID playerId, int limit) {
          return CompletableFuture.completedFuture(List.of());
        }
      };
}
