package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;

/**
 * 始发站与车库接班：车次已有人领、列车要从终点站待命或从车库出车时，派车那一刻先不发车，等驾驶员上车坐进驾驶室并确认座位。
 *
 * <p>等到时限照常发车：终点站待命车按自动运行发车，车库出车的放开发车门控（车次仍可在第一个车站接班）。本类只记状态与判定， 不依赖服务器对象，便于单测；只在服务器主线程使用。
 */
public final class DriverPickups {

  /** 终点站待命车多久重新判定一次发车端。 */
  static final Duration DEPARTURE_RECHECK = Duration.ofSeconds(5);

  /** 从哪里接车。 */
  public enum Kind {
    /** 终点站待命车：派去跑这一班之前留着等驾驶员。 */
    TERMINAL,
    /** 车库出车：出车后挂着发车门控停在车库股道上等驾驶员。 */
    DEPOT
  }

  /** 接车进行到哪一步。 */
  public enum Stage {
    /** 列车停着等驾驶员。 */
    WAITING,
    /** 驾驶员已上车接班，等派车。 */
    BOARDED,
    /** 过了时限，照常发车。 */
    EXPIRED
  }

  /** 终点站待命车的派车判定。 */
  public enum Verdict {
    /** 照常派车。 */
    DISPATCH,
    /** 留着这列车，先不派。 */
    HOLD,
    /** 这一班有人领、还没开始接车：由调用方核对能否接车，能则开始接车并留车。 */
    START
  }

  /** 车库出车的扣车判定。 */
  public enum DepotVerdict {
    /** 开始接车（扣车并通知驾驶员）。 */
    START,
    /** 同一列车已经在等这名驾驶员：照旧扣着，不再通知。 */
    KEEP,
    /** 不扣：这一班已经接过车（驾驶员已上车或已过时限），或不是车库接车。 */
    SKIP
  }

  /** 一次接车。 */
  public static final class Pickup {
    private final UUID playerId;
    private final TaskKey key;
    private final Kind kind;
    private final String trainName;
    private final String location;
    private final Instant deadline;
    private CabSeats.Departure departure;
    private Instant nextDepartureCheck;
    private Stage stage = Stage.WAITING;

    Pickup(
        UUID playerId,
        TaskKey key,
        Kind kind,
        String trainName,
        String location,
        Instant deadline,
        CabSeats.Departure departure) {
      this.playerId = Objects.requireNonNull(playerId, "playerId");
      this.key = Objects.requireNonNull(key, "key");
      this.kind = Objects.requireNonNull(kind, "kind");
      this.trainName = Objects.requireNonNull(trainName, "trainName");
      this.location = location == null ? "" : location;
      this.deadline = Objects.requireNonNull(deadline, "deadline");
      this.departure = departure == null ? defaultDeparture(kind) : departure;
    }

    public UUID playerId() {
      return playerId;
    }

    public TaskKey key() {
      return key;
    }

    public Kind kind() {
      return kind;
    }

    /** 留给驾驶员的列车（派车改名之前的车名）。 */
    public String trainName() {
      return trainName;
    }

    /** 列车停在哪里（站名或车库节点），用于提示。 */
    public String location() {
      return location;
    }

    public Instant deadline() {
      return deadline;
    }

    /** 下一趟由此刻编组的哪一端驾驶：驾驶员要坐进的驾驶室。 */
    public CabSeats.Departure departure() {
      return departure;
    }

    /**
     * 到了该重新判定发车端的时候：每 {@link #DEPARTURE_RECHECK} 一次（要查线路图、可能要寻路，不必每次维护都算）。
     *
     * @param now 此刻
     */
    public boolean departureCheckDue(Instant now) {
      if (nextDepartureCheck != null && now.isBefore(nextDepartureCheck)) {
        return false;
      }
      nextDepartureCheck = now.plus(DEPARTURE_RECHECK);
      return true;
    }

    /**
     * 重新判定发车端（列车待命初期还可能在居中对位，往回挪时 TrainCarts 会把整列调头）。
     *
     * @return 是否有变化
     */
    public boolean updateDeparture(CabSeats.Departure latest) {
      CabSeats.Departure next = latest == null ? defaultDeparture(kind) : latest;
      if (next == departure) {
        return false;
      }
      departure = next;
      return true;
    }

    public Stage stage() {
      return stage;
    }

    /** 是否已过时限。 */
    public boolean expiredAt(Instant now) {
      return !now.isBefore(deadline);
    }

    /** 离时限还有几秒（向上取整，不为负）。 */
    public long secondsLeft(Instant now) {
      long millis = deadline.toEpochMilli() - now.toEpochMilli();
      return millis <= 0L ? 0L : (millis + 999L) / 1000L;
    }

    /** 是否是这列车（车名不分大小写）。 */
    public boolean isTrain(String name) {
      return name != null && trainName.equalsIgnoreCase(name);
    }
  }

  private final Map<UUID, Pickup> byPlayer = new HashMap<>();

  /**
   * 接车时限：从 {@code from} 起等 {@code waitSeconds}；终点站接车时票据还挂在发车队列里，过了 assign-tolerance 票会作废、
   * 这一班开天窗，所以不晚于 “计划发车 + 容差 − 余量”。
   *
   * @param plannedDeparture 车次的计划发车时刻；为空时不按容差收紧
   * @param tolerance 票据的 assign-tolerance；为空时不按容差收紧
   */
  public static Instant deadline(
      Instant from,
      long waitSeconds,
      Instant plannedDeparture,
      Duration tolerance,
      long marginSeconds) {
    Instant deadline = from.plusSeconds(Math.max(0L, waitSeconds));
    if (plannedDeparture == null || tolerance == null) {
      return deadline;
    }
    Instant latest = plannedDeparture.plus(tolerance).minusSeconds(Math.max(0L, marginSeconds));
    if (!latest.isBefore(deadline)) {
      return deadline;
    }
    return latest.isBefore(from) ? from : latest;
  }

  /** 开始一次接车，发车端按接车方式取默认（见 {@link #defaultDeparture}）；同一名驾驶员之前的接车记录被替换。 */
  public Pickup start(
      UUID playerId, TaskKey key, Kind kind, String trainName, String location, Instant deadline) {
    return start(playerId, key, kind, trainName, location, deadline, defaultDeparture(kind));
  }

  /**
   * 开始一次接车；同一名驾驶员之前的接车记录被替换。
   *
   * @param departure 下一趟由此刻编组的哪一端驾驶（尽头式终点站为车尾端）
   */
  public Pickup start(
      UUID playerId,
      TaskKey key,
      Kind kind,
      String trainName,
      String location,
      Instant deadline,
      CabSeats.Departure departure) {
    Pickup pickup = new Pickup(playerId, key, kind, trainName, location, deadline, departure);
    byPlayer.put(playerId, pickup);
    return pickup;
  }

  /** 发车端不明时的默认：车库出车已朝发车方向，坐车头；终点站待命车要到派车才知道方向，两端都可以。 */
  public static CabSeats.Departure defaultDeparture(Kind kind) {
    return kind == Kind.DEPOT ? CabSeats.Departure.HEAD : CabSeats.Departure.EITHER;
  }

  public Optional<Pickup> ofPlayer(UUID playerId) {
    return Optional.ofNullable(playerId == null ? null : byPlayer.get(playerId));
  }

  /** 留着这列车的接车（还在等或驾驶员已上车）；过了时限的不算。 */
  public Optional<Pickup> ofTrain(String trainName) {
    if (trainName == null) {
      return Optional.empty();
    }
    for (Pickup pickup : byPlayer.values()) {
      if (pickup.stage != Stage.EXPIRED && pickup.isTrain(trainName)) {
        return Optional.of(pickup);
      }
    }
    return Optional.empty();
  }

  /** 这列车是否正停着等驾驶员上车。 */
  public boolean awaiting(String trainName) {
    return ofTrain(trainName).filter(pickup -> pickup.stage == Stage.WAITING).isPresent();
  }

  /** 驾驶员上了这列车。 */
  public Optional<Pickup> board(UUID playerId, String trainName) {
    Pickup pickup = byPlayer.get(playerId);
    if (pickup == null || pickup.stage != Stage.WAITING || !pickup.isTrain(trainName)) {
      return Optional.empty();
    }
    pickup.stage = Stage.BOARDED;
    return Optional.of(pickup);
  }

  /** 过了时限还在等的接车，标为过时并返回。 */
  public List<Pickup> expire(Instant now) {
    List<Pickup> expired = new ArrayList<>();
    for (Pickup pickup : byPlayer.values()) {
      if (pickup.stage == Stage.WAITING && pickup.expiredAt(now)) {
        pickup.stage = Stage.EXPIRED;
        expired.add(pickup);
      }
    }
    return expired;
  }

  public void remove(UUID playerId) {
    if (playerId != null) {
      byPlayer.remove(playerId);
    }
  }

  public List<Pickup> all() {
    return new ArrayList<>(byPlayer.values());
  }

  public boolean isEmpty() {
    return byPlayer.isEmpty();
  }

  /**
   * 车库刚为这一班出车的 {@code trainName} 要不要扣着等驾驶员。
   *
   * <p>同一班又出了一次车（上次出车在提交前回滚了）时换成新的那一列：调用方先放开旧车的门控，再开始接车。
   */
  public DepotVerdict depot(UUID playerId, TaskKey key, String trainName) {
    Pickup existing = playerId == null ? null : byPlayer.get(playerId);
    if (existing == null || !existing.key.equals(key)) {
      return DepotVerdict.START;
    }
    if (existing.kind != Kind.DEPOT || existing.stage != Stage.WAITING) {
      return DepotVerdict.SKIP;
    }
    return existing.isTrain(trainName) ? DepotVerdict.KEEP : DepotVerdict.START;
  }

  /**
   * 终点站待命车能不能派去跑这一班。
   *
   * <p>别人留着的车不派给别的班次（到时限为止）；有人领的班次，还没开始接车时由调用方决定是否开始；正在接车时等驾驶员上了留给他的那列车才派， 换成别的车或驾驶员没上车都等到时限。
   *
   * @param tripKey 这一班
   * @param claimant 领了这一班的驾驶员；没人领时为 {@code null}
   * @param trainName 候选的待命车
   * @param driverAboard 领班的驾驶员此刻正在驾驶这列车
   */
  public Verdict layover(
      TaskKey tripKey, UUID claimant, String trainName, boolean driverAboard, Instant now) {
    Optional<Pickup> reserved = ofTrain(trainName);
    if (reserved.isPresent()
        && (claimant == null || !reserved.get().playerId.equals(claimant))
        && !reserved.get().expiredAt(now)) {
      return Verdict.HOLD;
    }
    if (claimant == null) {
      return Verdict.DISPATCH;
    }
    Pickup mine = byPlayer.get(claimant);
    if (mine == null || !mine.key.equals(tripKey)) {
      return driverAboard ? Verdict.DISPATCH : Verdict.START;
    }
    return switch (mine.stage) {
      case EXPIRED -> Verdict.DISPATCH;
      case BOARDED, WAITING -> {
        if (driverAboard && mine.isTrain(trainName)) {
          yield Verdict.DISPATCH;
        }
        yield mine.expiredAt(now) ? Verdict.DISPATCH : Verdict.HOLD;
      }
    };
  }
}
