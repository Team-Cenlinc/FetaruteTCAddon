package org.fetarute.fetaruteTCAddon.api.eta;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * ETA API：提供列车到达时间与站牌列表的只读访问。
 *
 * <p>该 API 封装内部 ETA 服务，面向外部插件输出稳定、不可变的结果对象。
 *
 * <h2>使用示例</h2>
 *
 * <pre>{@code
 * EtaApi eta = api.eta();
 *
 * // 查询列车下一站 ETA
 * eta.getForTrain("train-1", EtaApi.Target.nextStop())
 *     .ifPresent(result -> System.out.println(result.etaMinutes()));
 *
 * // 查询站牌列表
 * eta.getBoard("OP", "AAA", null, Duration.ofMinutes(10))
 *     .rows().forEach(row -> System.out.println(row.destination()));
 * }</pre>
 */
public interface EtaApi {

  /**
   * 查询列车 ETA。
   *
   * @param trainName 列车名（trainId）
   * @param target 目标（下一站/指定站点/指定节点）
   * @return ETA 结果（不可用时会返回 statusText 为 N/A 的结果）
   */
  EtaResult getForTrain(String trainName, Target target);

  /**
   * 查询未发车票据 ETA。
   *
   * @param ticketId 票据 ID
   * @return ETA 结果（不可用时会返回 statusText 为 N/A 的结果）
   */
  EtaResult getForTicket(String ticketId);

  /**
   * 查询站牌列表（推荐形式：operator + stationCode）。
   *
   * @param operator 运营商代码
   * @param stationCode 站点代码
   * @param lineId 线路代码（可选）
   * @param horizon 时间窗口（可选，默认 10 分钟）
   * @return 站牌列表
   */
  BoardResult getBoard(String operator, String stationCode, String lineId, Duration horizon);

  /**
   * 查询站牌列表（兼容形式：stationId 可为 StationCode 或 Operator:StationCode）。
   *
   * @param stationId 站点标识
   * @param lineId 线路代码（可选）
   * @param horizon 时间窗口（可选，默认 10 分钟）
   * @return 站牌列表
   */
  BoardResult getBoard(String stationId, String lineId, Duration horizon);

  // ─────────────────────────────────────────────────────────────────────────────
  // 数据模型
  // ─────────────────────────────────────────────────────────────────────────────

  /** ETA 可信度。 */
  enum Confidence {
    HIGH,
    MED,
    LOW
  }

  /** ETA 诊断标签。 */
  enum Reason {
    NO_VEHICLE,
    NO_ROUTE,
    NO_TARGET,
    NO_PATH,
    THROAT,
    SINGLELINE,
    PLATFORM,
    DEPOT_GATE,
    /** 可预知的等待（按表等点、票据尚未到点）。1.4.0 起占用/信号造成的等待改报 {@link #HOLD}。 */
    WAIT,
    /** 列车正被运行时扣停，ETA 已按扣停时长顺延（1.4.0）。 */
    HOLD,
    /** 班次已过计划发车时刻仍未发出，ETA 已顺延（1.4.0）。 */
    OVERDUE
  }

  /** ETA 目标。 */
  sealed interface Target permits Target.NextStop, Target.Station, Target.PlatformNode {

    /** 下一站。 */
    record NextStop() implements Target {}

    /** 指定站点。 */
    record Station(String stationId) implements Target {
      public Station {
        if (stationId == null || stationId.isBlank()) {
          throw new IllegalArgumentException("stationId 不能为空");
        }
      }
    }

    /** 指定节点。 */
    record PlatformNode(String nodeId) implements Target {
      public PlatformNode {
        if (nodeId == null || nodeId.isBlank()) {
          throw new IllegalArgumentException("nodeId 不能为空");
        }
      }
    }

    static Target nextStop() {
      return new NextStop();
    }
  }

  /**
   * ETA 结果。
   *
   * @param arriving 是否即将到达
   * @param statusText 状态文本
   * @param etaEpochMillis 预计到达时间戳
   * @param etaMinutes 预计到达分钟数（四舍五入）
   * @param travelSec 行驶时间（秒）
   * @param dwellSec 停站时间（秒）
   * @param waitSec 等待时间（秒）
   * @param reasons 原因列表
   * @param confidence 可信度
   */
  record EtaResult(
      boolean arriving,
      String statusText,
      long etaEpochMillis,
      int etaMinutes,
      int travelSec,
      int dwellSec,
      int waitSec,
      List<Reason> reasons,
      Confidence confidence) {

    /** 预计到达时间。 */
    public Optional<Instant> eta() {
      return etaEpochMillis <= 0L
          ? Optional.empty()
          : Optional.of(Instant.ofEpochMilli(etaEpochMillis));
    }
  }

  /** 站牌列表结果。 */
  record BoardResult(List<BoardRow> rows) {
    public BoardResult {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  /** 站牌行所处的阶段（1.9.0），按离本站由远到近排列。 */
  enum BoardPhase {
    /** 未出票的预测班次（按发车计划或时刻表推算）。 */
    FORECAST,
    /** 已出票、尚未发车。 */
    PENDING,
    /** 运行中，尚未临近本站。 */
    EN_ROUTE,
    /** 即将到达或通过本站。 */
    ARRIVING,
    /** 已停在本站，尚未获准发车。 */
    AT_STATION
  }

  /**
   * 站牌行。
   *
   * <p>1.9.0 起增补结构化字段（时刻、阶段、停靠属性、晚点），显示方不必再解析 {@code statusText}。
   *
   * @param lineName 列车到本站时所属线路的代码（直通运转换线后为新线路）
   * @param routeId 交路 ID（{@code 运营商:线路:交路}）
   * @param destination 主目的地显示名（运营终点；回库车越过运营终点后为“回库”）
   * @param destinationId 主目的地 ID
   * @param endRoute 线路终点（EOR）显示名
   * @param endRouteId 线路终点 ID
   * @param endOperation 运营终点（EOP）显示名
   * @param endOperationId 运营终点 ID
   * @param platform 站台号；无法解析时为 {@code -}。1.9.0 起站台待定（见 {@code platformPending}）时也为 {@code -}， 此前给的是
   *     DYNAMIC 范围里的第一条股道，列车未必去那里
   * @param statusText 状态文本（英文短语）
   * @param reasons 诊断标签
   * @param etaEpochMillis 预计到达或通过本站的时间戳；已在站时为查询时刻；1.8.0 构造器创建的行为 0
   * @param phase 所处阶段（1.9.0）
   * @param stopSequence 本站停靠序号，交路节点的 0 起下标，与 RouteApi、TimetableApi 同一口径；未知时为 -1（1.9.0）
   * @param passing 本站通过不停（1.9.0）
   * @param terminating 本站是运营终点，乘客在此下车（1.9.0）；在运营终点分行、解下的一段接着载客时不算终到，主目的地写那一段开往的车站
   * @param outOfService 本站已越过运营终点，列车在回库途中（1.9.0）
   * @param trainName 运行中列车的列车名；票据与预测为空（1.9.0）
   * @param delaySeconds 按表运行时相对计划的偏差，正数为晚点：运行中为到达本站，已在站为发车，未发车为起点发车；不按表运行时为空（1.9.0）
   * @param platformPending 站台待定：本站是动态站台（DYNAMIC）停靠，列车还没有选台，也没有计划站台（1.9.0）。
   *     运行中列车通常在到达本站前一个节点（多为站咽喉）时选台
   * @param platformCandidates 站台待定时可能停靠的站台号，按站台号升序；站台已定或候选未知时为空（1.9.0）
   * @param platformPlanned 站台号是计划站台（1.9.0）：时刻表排定的股道，或没有时刻表计划时列车在下一个停车站上先定的暂定股道。
   *     列车还没有选台，进站前选台时这条股道被占会改停别的站台，届时发 {@code TrainPlatformAssignedEvent}（原因 {@code
   *     CHANGED_FROM_PLAN}）。有计划站台时不算待定
   * @param cars 运行中列车各节车的座位与在座乘客，车头在前（1.9.0）；票据、预测与读不到车辆模型时为空
   * @param division 列车在本站或前方某站分行（1.14.0）：交路写了解编，按表运行时表上这一班要拆、编组切得开。运行中的列车按现场编组切；
   *     还没出车的一班（票据、预测行）按表上那一班的出车编组切，只在按表运行时给
   * @param joining 列车联挂（1.14.0）。一是前方的联挂站：交路是写了联挂的主干的回程支线，列车在那里挂上主干、乘客留在车上继续前往主目的地，这时 {@code
   *     destination} 是联挂后主干的运营终点，{@code endOperation} 仍是本交路自己的（联挂站）；回程支线车到了联挂站按终到，不再带联挂。
   *     二是本站联挂：运行中的主干在本站挂上回程支线车（{@link Joining#stopSequence} 等于本行的 {@code stopSequence}）。不联挂时为空
   */
  record BoardRow(
      String lineName,
      String routeId,
      String destination,
      Optional<String> destinationId,
      String endRoute,
      Optional<String> endRouteId,
      String endOperation,
      Optional<String> endOperationId,
      String platform,
      String statusText,
      List<Reason> reasons,
      long etaEpochMillis,
      BoardPhase phase,
      int stopSequence,
      boolean passing,
      boolean terminating,
      boolean outOfService,
      Optional<String> trainName,
      OptionalLong delaySeconds,
      boolean platformPending,
      List<String> platformCandidates,
      boolean platformPlanned,
      List<CarLoad> cars,
      Optional<Division> division,
      Optional<Joining> joining) {

    public BoardRow {
      destinationId = destinationId == null ? Optional.empty() : destinationId;
      endRouteId = endRouteId == null ? Optional.empty() : endRouteId;
      endOperationId = endOperationId == null ? Optional.empty() : endOperationId;
      reasons = reasons == null ? List.of() : List.copyOf(reasons);
      phase = phase == null ? BoardPhase.EN_ROUTE : phase;
      trainName = trainName == null ? Optional.empty() : trainName;
      delaySeconds = delaySeconds == null ? OptionalLong.empty() : delaySeconds;
      platformCandidates = platformCandidates == null ? List.of() : List.copyOf(platformCandidates);
      cars = cars == null ? List.of() : List.copyOf(cars);
      division = division == null ? Optional.empty() : division;
      joining = joining == null ? Optional.empty() : joining;
    }

    /** 1.9.0 至 1.13.0 的全参构造器（源码与二进制兼容）：不分行、不联挂。 */
    public BoardRow(
        String lineName,
        String routeId,
        String destination,
        Optional<String> destinationId,
        String endRoute,
        Optional<String> endRouteId,
        String endOperation,
        Optional<String> endOperationId,
        String platform,
        String statusText,
        List<Reason> reasons,
        long etaEpochMillis,
        BoardPhase phase,
        int stopSequence,
        boolean passing,
        boolean terminating,
        boolean outOfService,
        Optional<String> trainName,
        OptionalLong delaySeconds,
        boolean platformPending,
        List<String> platformCandidates,
        boolean platformPlanned,
        List<CarLoad> cars) {
      this(
          lineName,
          routeId,
          destination,
          destinationId,
          endRoute,
          endRouteId,
          endOperation,
          endOperationId,
          platform,
          statusText,
          reasons,
          etaEpochMillis,
          phase,
          stopSequence,
          passing,
          terminating,
          outOfService,
          trainName,
          delaySeconds,
          platformPending,
          platformCandidates,
          platformPlanned,
          cars,
          Optional.empty(),
          Optional.empty());
    }

    /** 全车空位数；没有载客数据时为空。 */
    public java.util.OptionalInt vacantSeats() {
      return cars.isEmpty()
          ? java.util.OptionalInt.empty()
          : java.util.OptionalInt.of(cars.stream().mapToInt(CarLoad::vacant).sum());
    }

    /**
     * 1.8.0 及以前的构造器（源码与二进制兼容）：没有结构化字段，阶段按运行中、序号为 -1，{@code outOfService} 按主目的地 ID 是否为 {@code
     * OUT_OF_SERVICE} 推断。
     */
    public BoardRow(
        String lineName,
        String routeId,
        String destination,
        Optional<String> destinationId,
        String endRoute,
        Optional<String> endRouteId,
        String endOperation,
        Optional<String> endOperationId,
        String platform,
        String statusText,
        List<Reason> reasons) {
      this(
          lineName,
          routeId,
          destination,
          destinationId,
          endRoute,
          endRouteId,
          endOperation,
          endOperationId,
          platform,
          statusText,
          reasons,
          0L,
          BoardPhase.EN_ROUTE,
          -1,
          false,
          false,
          destinationId != null && destinationId.filter("OUT_OF_SERVICE"::equals).isPresent(),
          Optional.empty(),
          OptionalLong.empty(),
          false,
          List.of(),
          false,
          List.of(),
          Optional.empty(),
          Optional.empty());
    }

    /** 预计到达或通过本站的时间（1.9.0）；1.8.0 构造器创建的行为空。 */
    public Optional<Instant> eta() {
      return etaEpochMillis <= 0L
          ? Optional.empty()
          : Optional.of(Instant.ofEpochMilli(etaEpochMillis));
    }
  }

  /**
   * 列车分行（1.14.0）：在分行站拆成两列，各开往不同的地方。乘客按要去的地方坐对车厢。
   *
   * @param stopSequence 分行站的停靠序号（与站牌行的 {@code stopSequence} 同一口径；相等时就在本站分行）
   * @param stationId 分行站 ID（{@code 运营商:站码}）
   * @param station 分行站显示名
   * @param parts 两段，车头在前
   * @param divided 已经在本站拆开：这一行是解下、接着载客的一段，另一段还停在站台上（{@code parts} 里 {@code detached} 为 true
   *     的就是这一行的列车）。这时 {@code stopSequence} 是这一行的停靠序号
   */
  record Division(
      int stopSequence,
      String stationId,
      String station,
      List<DivisionPart> parts,
      boolean divided) {

    public Division {
      stationId = stationId == null ? "" : stationId;
      station = station == null ? "" : station;
      parts = parts == null ? List.of() : List.copyOf(parts);
    }

    /** 还没拆开的分行。 */
    public Division(int stopSequence, String stationId, String station, List<DivisionPart> parts) {
      this(stopSequence, stationId, station, parts, false);
    }

    /** 列车在 {@code stopSequence} 这一站（含）之前不分行。 */
    public boolean ahead(int stopSequence) {
      return this.stopSequence >= stopSequence;
    }
  }

  /**
   * 联挂站（1.14.0）：回程支线的车在这里挂上主干，乘客不必下车。
   *
   * @param stopSequence 联挂站的停靠序号（与站牌行的 {@code stopSequence} 同一口径）：回程支线是本交路的运营终点；等于本行的停靠序号时就在本站联挂
   * @param stationId 联挂站 ID（{@code 运营商:站码}）；解析不出时为空字符串
   * @param station 联挂站显示名
   * @param ownCars 这一行的列车几节；还没出车、读不到时为 0
   * @param joiningCars 挂上来（或要挂上去）的另一列车几节；还没出车、读不到时为 0
   * @param joiningStanding 另一列车已经停在联挂站
   * @param continuesOnRouteId 回程支线联挂以后接着跑的主干交路（{@code 运营商:线路:交路}），停站表据此接着列联挂之后的站；本站联挂（主干）与不知道时为空
   * @param continuesFromSequence 联挂站在 {@code continuesOnRouteId} 里的停靠序号；没有时为 -1
   */
  record Joining(
      int stopSequence,
      String stationId,
      String station,
      int ownCars,
      int joiningCars,
      boolean joiningStanding,
      Optional<String> continuesOnRouteId,
      int continuesFromSequence) {

    public Joining {
      stationId = stationId == null ? "" : stationId;
      station = station == null ? "" : station;
      ownCars = Math.max(0, ownCars);
      joiningCars = Math.max(0, joiningCars);
      continuesOnRouteId = continuesOnRouteId == null ? Optional.empty() : continuesOnRouteId;
      continuesFromSequence = continuesOnRouteId.isEmpty() ? -1 : continuesFromSequence;
    }

    /** 节数与接着跑的主干未知。 */
    public Joining(int stopSequence, String stationId, String station) {
      this(stopSequence, stationId, station, 0, 0, false, Optional.empty(), -1);
    }

    /** 列车在 {@code stopSequence} 这一站联挂。 */
    public boolean at(int stopSequence) {
      return this.stopSequence == stopSequence;
    }
  }

  /**
   * 分行后的一段（1.14.0）。
   *
   * @param cars 节数
   * @param detached 在分行站解下另走；否则留下继续本交路
   * @param destinationId 分行后开往的车站 ID（{@code 运营商:站码}）；分行后不载客（就地待命、回库）时为空
   * @param destination 开往的车站显示名；不载客时为空字符串
   * @param stops 分行后这一段还停几个车站（到它的终点为止）；不载客时为 0。站牌的主目的地取停站多的一段，一样多时取留下的一段
   * @param routeId 分行后这一段跑的交路（{@code 运营商:线路:交路}），停站表据此列分行之后的站；不载客、不知道时为空
   * @param fromSequence 分行站在 {@code routeId} 里的停靠序号；没有时为 -1
   */
  record DivisionPart(
      int cars,
      boolean detached,
      Optional<String> destinationId,
      String destination,
      int stops,
      Optional<String> routeId,
      int fromSequence) {

    public DivisionPart {
      cars = Math.max(1, cars);
      destinationId = destinationId == null ? Optional.empty() : destinationId;
      destination = destination == null ? "" : destination;
      stops = destinationId.isEmpty() ? 0 : Math.max(0, stops);
      routeId = routeId == null || destinationId.isEmpty() ? Optional.empty() : routeId;
      fromSequence = routeId.isEmpty() ? -1 : fromSequence;
    }

    /** 不知道分行后跑哪条交路。 */
    public DivisionPart(
        int cars, boolean detached, Optional<String> destinationId, String destination, int stops) {
      this(cars, detached, destinationId, destination, stops, Optional.empty(), -1);
    }

    /** 不知道分行后停几站（记 0）、跑哪条交路。 */
    public DivisionPart(
        int cars, boolean detached, Optional<String> destinationId, String destination) {
      this(cars, detached, destinationId, destination, 0);
    }

    /** 分行后继续载客。 */
    public boolean inService() {
      return destinationId.isPresent();
    }
  }

  /**
   * 一节车的座位与在座乘客（1.9.0）。座位数取车辆模型里的座位个数，在座取坐在这节车上的玩家。
   *
   * @param seats 座位数
   * @param occupied 在座乘客数，不超过座位数
   */
  record CarLoad(int seats, int occupied) {

    public CarLoad {
      seats = Math.max(0, seats);
      occupied = Math.max(0, Math.min(occupied, seats));
    }

    /** 空位数。 */
    public int vacant() {
      return seats - occupied;
    }
  }

  /**
   * ETA 诊断信息（面向调试/展示）。
   *
   * @param trainName 列车名
   * @param worldId 世界 UUID
   * @param routeId 路线 ID
   * @param routeIndex 列车最近到达的交路节点下标（0 起，与停靠序号同一口径）
   * @param currentNode 当前节点
   * @param lastPassedNode 上一节点
   */
  record RuntimeSnapshot(
      String trainName,
      UUID worldId,
      String routeId,
      int routeIndex,
      Optional<String> currentNode,
      Optional<String> lastPassedNode) {}

  /**
   * 查询运行时快照（用于调试/诊断）。
   *
   * @param trainName 列车名
   * @return 运行时快照
   */
  Optional<RuntimeSnapshot> getRuntimeSnapshot(String trainName);

  /**
   * 获取当前采样到的列车名集合（用于补全）。
   *
   * @return 列车名集合（不可变）
   */
  Collection<String> listSnapshotTrainNames();
}
