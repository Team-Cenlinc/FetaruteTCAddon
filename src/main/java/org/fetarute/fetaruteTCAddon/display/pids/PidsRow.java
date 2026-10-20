package org.fetarute.fetaruteTCAddon.display.pids;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 站台屏的一行。
 *
 * <p>只描述事实，不含显示文字：状态词、颜色、倒计时由渲染侧按当前时间与外观决定。
 *
 * @param status 状态
 * @param lineName 列车到本站时所属线路的代码
 * @param routeId 交路 ID（{@code 运营商:线路:交路}）
 * @param destination 主目的地（站码；回库车越过运营终点后为“回库”）
 * @param destinationId 主目的地 ID（{@code 运营商:站码}），用于查站名
 * @param platform 站台号；无法解析或站台待定时为 {@code -}
 * @param expectedAt 预计到达或通过本站的时刻；已在站为快照时刻；取消行为计划到达（与运行中列车的到站时刻同一口径）
 * @param delaySeconds 相对计划的偏差（正数为晚点）；不按表运行或已取消时为空
 * @param stopSequence 本站停靠序号（交路节点的 0 起下标）；未知时为 -1
 * @param passing 本站通过不停
 * @param terminating 本站是运营终点
 * @param outOfService 列车在回库途中
 * @param trainName 运行中列车的列车名；票据、预测与取消行为空
 * @param platformPending 站台待定：本站是动态站台停靠，列车还没有选台
 * @param platformCandidates 站台待定时可能停靠的站台，按站台号升序；站台已定或候选未知时为空
 * @param cars 运行中列车各节车的座位与在座乘客，车头在前；没有载客数据时为空
 * @param previousPlatform 站台变更前的站台：这辆车在本站的站台定下来时与计划（或暂定）不同、或定下后又改了；没有变更时为空
 * @param lastTrain 当天从本站开往这个终点的最后一班（按已发布时刻表）
 * @param division 列车在本站或前方某站分行（运行中的列车按现场编组，还没出车的一班按表）；不分行时为空
 * @param joining 列车在前方的联挂站挂上主干、乘客留在车上继续前往主目的地；不联挂、本站就是联挂站时为空
 */
public record PidsRow(
    Status status,
    String lineName,
    String routeId,
    String destination,
    Optional<String> destinationId,
    String platform,
    Instant expectedAt,
    OptionalLong delaySeconds,
    int stopSequence,
    boolean passing,
    boolean terminating,
    boolean outOfService,
    Optional<String> trainName,
    boolean platformPending,
    List<String> platformCandidates,
    List<Car> cars,
    Optional<String> previousPlatform,
    boolean lastTrain,
    Optional<Division> division,
    Optional<Joining> joining) {

  public PidsRow {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(lineName, "lineName");
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(destination, "destination");
    destinationId = destinationId == null ? Optional.empty() : destinationId;
    Objects.requireNonNull(platform, "platform");
    Objects.requireNonNull(expectedAt, "expectedAt");
    delaySeconds = delaySeconds == null ? OptionalLong.empty() : delaySeconds;
    trainName = trainName == null ? Optional.empty() : trainName;
    platformCandidates = platformCandidates == null ? List.of() : List.copyOf(platformCandidates);
    cars = cars == null ? List.of() : List.copyOf(cars);
    previousPlatform = previousPlatform == null ? Optional.empty() : previousPlatform;
    division = division == null ? Optional.empty() : division;
    joining = joining == null ? Optional.empty() : joining;
  }

  /** 不联挂的行。 */
  public PidsRow(
      Status status,
      String lineName,
      String routeId,
      String destination,
      Optional<String> destinationId,
      String platform,
      Instant expectedAt,
      OptionalLong delaySeconds,
      int stopSequence,
      boolean passing,
      boolean terminating,
      boolean outOfService,
      Optional<String> trainName,
      boolean platformPending,
      List<String> platformCandidates,
      List<Car> cars,
      Optional<String> previousPlatform,
      boolean lastTrain,
      Optional<Division> division) {
    this(
        status,
        lineName,
        routeId,
        destination,
        destinationId,
        platform,
        expectedAt,
        delaySeconds,
        stopSequence,
        passing,
        terminating,
        outOfService,
        trainName,
        platformPending,
        platformCandidates,
        cars,
        previousPlatform,
        lastTrain,
        division,
        Optional.empty());
  }

  /** 不分行的行。 */
  public PidsRow(
      Status status,
      String lineName,
      String routeId,
      String destination,
      Optional<String> destinationId,
      String platform,
      Instant expectedAt,
      OptionalLong delaySeconds,
      int stopSequence,
      boolean passing,
      boolean terminating,
      boolean outOfService,
      Optional<String> trainName,
      boolean platformPending,
      List<String> platformCandidates,
      List<Car> cars,
      Optional<String> previousPlatform,
      boolean lastTrain) {
    this(
        status,
        lineName,
        routeId,
        destination,
        destinationId,
        platform,
        expectedAt,
        delaySeconds,
        stopSequence,
        passing,
        terminating,
        outOfService,
        trainName,
        platformPending,
        platformCandidates,
        cars,
        previousPlatform,
        lastTrain,
        Optional.empty());
  }

  /** 不是末班车的行。 */
  public PidsRow(
      Status status,
      String lineName,
      String routeId,
      String destination,
      Optional<String> destinationId,
      String platform,
      Instant expectedAt,
      OptionalLong delaySeconds,
      int stopSequence,
      boolean passing,
      boolean terminating,
      boolean outOfService,
      Optional<String> trainName,
      boolean platformPending,
      List<String> platformCandidates,
      List<Car> cars,
      Optional<String> previousPlatform) {
    this(
        status,
        lineName,
        routeId,
        destination,
        destinationId,
        platform,
        expectedAt,
        delaySeconds,
        stopSequence,
        passing,
        terminating,
        outOfService,
        trainName,
        platformPending,
        platformCandidates,
        cars,
        previousPlatform,
        false);
  }

  /** 没有站台变更的行。 */
  public PidsRow(
      Status status,
      String lineName,
      String routeId,
      String destination,
      Optional<String> destinationId,
      String platform,
      Instant expectedAt,
      OptionalLong delaySeconds,
      int stopSequence,
      boolean passing,
      boolean terminating,
      boolean outOfService,
      Optional<String> trainName,
      boolean platformPending,
      List<String> platformCandidates,
      List<Car> cars) {
    this(
        status,
        lineName,
        routeId,
        destination,
        destinationId,
        platform,
        expectedAt,
        delaySeconds,
        stopSequence,
        passing,
        terminating,
        outOfService,
        trainName,
        platformPending,
        platformCandidates,
        cars,
        Optional.empty());
  }

  /** 没有载客数据的行。 */
  public PidsRow(
      Status status,
      String lineName,
      String routeId,
      String destination,
      Optional<String> destinationId,
      String platform,
      Instant expectedAt,
      OptionalLong delaySeconds,
      int stopSequence,
      boolean passing,
      boolean terminating,
      boolean outOfService,
      Optional<String> trainName,
      boolean platformPending,
      List<String> platformCandidates) {
    this(
        status,
        lineName,
        routeId,
        destination,
        destinationId,
        platform,
        expectedAt,
        delaySeconds,
        stopSequence,
        passing,
        terminating,
        outOfService,
        trainName,
        platformPending,
        platformCandidates,
        List.of(),
        Optional.empty());
  }

  /** 站台已定的行。 */
  public PidsRow(
      Status status,
      String lineName,
      String routeId,
      String destination,
      Optional<String> destinationId,
      String platform,
      Instant expectedAt,
      OptionalLong delaySeconds,
      int stopSequence,
      boolean passing,
      boolean terminating,
      boolean outOfService,
      Optional<String> trainName) {
    this(
        status,
        lineName,
        routeId,
        destination,
        destinationId,
        platform,
        expectedAt,
        delaySeconds,
        stopSequence,
        passing,
        terminating,
        outOfService,
        trainName,
        false,
        List.of());
  }

  /**
   * 一节车的座位与在座乘客。
   *
   * @param seats 座位数
   * @param occupied 在座乘客数
   */
  public record Car(int seats, int occupied) {}

  /**
   * 列车分行：在分行站拆成两列，各开往不同的地方。
   *
   * @param stopSequence 分行站的停靠序号；等于本行的停靠序号时就在本站分行
   * @param stationId 分行站（{@code 运营商:站码}）
   * @param parts 两段，车头在前
   * @param divided 已经在本站拆开：这一行是解下、接着载客的一段（{@code detached} 为 true 的那段），另一段还停在站台上
   */
  public record Division(int stopSequence, String stationId, List<Part> parts, boolean divided) {

    public Division {
      Objects.requireNonNull(stationId, "stationId");
      parts = List.copyOf(parts);
    }

    /** 还没拆开的分行。 */
    public Division(int stopSequence, String stationId, List<Part> parts) {
      this(stopSequence, stationId, parts, false);
    }

    /**
     * 一段车厢。
     *
     * @param cars 节数
     * @param detached 在分行站解下另走；否则留下继续本交路
     * @param destinationId 分行后开往的车站（{@code 运营商:站码}）；分行后不载客时为空
     * @param routeId 分行后这一段跑的交路；不载客、不知道时为空
     * @param fromSequence 分行站在 {@code routeId} 里的停靠序号；没有时为 -1
     */
    public record Part(
        int cars,
        boolean detached,
        Optional<String> destinationId,
        Optional<String> routeId,
        int fromSequence) {

      public Part {
        destinationId = destinationId == null ? Optional.empty() : destinationId;
        routeId = routeId == null ? Optional.empty() : routeId;
        fromSequence = routeId.isEmpty() ? -1 : fromSequence;
      }

      /** 不知道分行后跑哪条交路。 */
      public Part(int cars, boolean detached, Optional<String> destinationId) {
        this(cars, detached, destinationId, Optional.empty(), -1);
      }
    }

    /** 解下另走的那一段；没有时为空。 */
    public Optional<Part> detachedPart() {
      return parts.stream().filter(Part::detached).findFirst();
    }

    /** 解下的那一段在车头一侧。 */
    public boolean detachedAtFront() {
      return !parts.isEmpty() && parts.get(0).detached();
    }
  }

  /**
   * 联挂站：回程支线的车在前方的联挂站挂上主干，乘客留在车上继续前往主目的地；或运行中的主干在本站挂上回程支线车。
   *
   * @param stopSequence 联挂站的停靠序号
   * @param stationId 联挂站（{@code 运营商:站码}）；解析不出时为空字符串
   * @param ownCars 这一行的列车几节；读不到时为 0
   * @param joiningCars 挂上来（或要挂上去）的另一列车几节；读不到时为 0
   * @param joiningStanding 另一列车已经停在联挂站
   * @param continuesOn 回程支线联挂以后接着跑的主干交路；本站联挂（主干）与不知道时为空
   * @param continuesFrom 联挂站在 {@code continuesOn} 里的停靠序号；没有时为 -1
   */
  public record Joining(
      int stopSequence,
      String stationId,
      int ownCars,
      int joiningCars,
      boolean joiningStanding,
      Optional<String> continuesOn,
      int continuesFrom) {

    public Joining {
      Objects.requireNonNull(stationId, "stationId");
      continuesOn = continuesOn == null ? Optional.empty() : continuesOn;
      continuesFrom = continuesOn.isEmpty() ? -1 : continuesFrom;
    }

    /** 节数与接着跑的主干未知。 */
    public Joining(int stopSequence, String stationId) {
      this(stopSequence, stationId, 0, 0, false, Optional.empty(), -1);
    }

    /** 不知道接着跑的主干。 */
    public Joining(
        int stopSequence, String stationId, int ownCars, int joiningCars, boolean joiningStanding) {
      this(stopSequence, stationId, ownCars, joiningCars, joiningStanding, Optional.empty(), -1);
    }

    /** 两列车各几节都知道（联挂页据此画车厢）。 */
    public boolean carsKnown() {
      return ownCars > 0 && joiningCars > 0;
    }
  }

  /** 列车在本站之后的某站联挂（本站就是联挂站时它在本站终到，不算）。 */
  public Optional<Joining> joiningAhead() {
    return joining.filter(found -> stopSequence < 0 || found.stopSequence() > stopSequence);
  }

  /** 主干在本站挂上回程支线车。 */
  public Optional<Joining> joiningHere() {
    return joining.filter(found -> stopSequence >= 0 && found.stopSequence() == stopSequence);
  }

  /** 列车在本站或前方分行（只认拆成两段、还没拆开的）。 */
  public Optional<Division> dividing() {
    return division
        .filter(found -> !found.divided())
        .filter(found -> found.parts().size() == 2 && found.stopSequence() >= 0)
        .filter(found -> stopSequence < 0 || found.stopSequence() >= stopSequence);
  }

  /** 这一行是刚在本站拆开、解下接着载客的一段，另一段还停在站台上。 */
  public Optional<Division> dividedHere() {
    return division.filter(
        found ->
            found.divided()
                && found.parts().size() == 2
                && found.parts().stream().anyMatch(Division.Part::detached));
  }

  /** 列车就在本站分行。 */
  public boolean dividesHere() {
    return dividing().filter(found -> found.stopSequence() == stopSequence).isPresent();
  }

  /** 列车会停在这些站台之一（站台已定时看站台号，待定时看候选），或原定停在这些站台之一、后来改了站台。 */
  public boolean mayUse(Collection<String> platforms) {
    return platforms.contains(platform)
        || (platformPending && platformCandidates.stream().anyMatch(platforms::contains))
        || previousPlatform.filter(platforms::contains).isPresent();
  }

  /** 原定停在这些站台之一、现在改去别的站台。 */
  public boolean movedAwayFrom(Collection<String> platforms) {
    return !platforms.isEmpty()
        && !platforms.contains(platform)
        && previousPlatform.filter(platforms::contains).isPresent();
  }

  /** 行状态，按离本站由远到近排列，取消单列。 */
  public enum Status {
    /** 计划：尚未出票的班次。 */
    PLANNED,
    /** 已出票、尚未发车。 */
    PENDING,
    /** 运行中。 */
    EN_ROUTE,
    /** 即将进站或通过。 */
    ARRIVING,
    /** 停在本站。 */
    BOARDING,
    /** 时刻表已取消，本站不再停。 */
    CANCELLED
  }
}
