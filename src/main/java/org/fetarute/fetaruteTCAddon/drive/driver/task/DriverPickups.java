package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 始发站与车库接班：车次已有人领、列车要从终点站待命或从车库出车时，派车那一刻先不发车，等驾驶员上车坐进驾驶室并确认座位。
 *
 * <p>等到时限照常发车：终点站待命车按自动运行发车，车库出车的放开发车门控（车次仍可在第一个车站接班）。本类只记状态与判定， 不依赖服务器对象，便于单测；只在服务器主线程使用。
 */
public final class DriverPickups {

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

  /** 一次接车。 */
  public static final class Pickup {
    private final UUID playerId;
    private final TaskKey key;
    private final Kind kind;
    private final String trainName;
    private final String location;
    private final Instant deadline;
    private Stage stage = Stage.WAITING;

    Pickup(
        UUID playerId,
        TaskKey key,
        Kind kind,
        String trainName,
        String location,
        Instant deadline) {
      this.playerId = Objects.requireNonNull(playerId, "playerId");
      this.key = Objects.requireNonNull(key, "key");
      this.kind = Objects.requireNonNull(kind, "kind");
      this.trainName = Objects.requireNonNull(trainName, "trainName");
      this.location = location == null ? "" : location;
      this.deadline = Objects.requireNonNull(deadline, "deadline");
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

  /** 开始一次接车；同一名驾驶员之前的接车记录被替换。 */
  public Pickup start(
      UUID playerId, TaskKey key, Kind kind, String trainName, String location, Instant deadline) {
    Pickup pickup = new Pickup(playerId, key, kind, trainName, location, deadline);
    byPlayer.put(playerId, pickup);
    return pickup;
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
