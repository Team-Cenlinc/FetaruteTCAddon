package org.fetarute.fetaruteTCAddon.drive.driver;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;

/**
 * 折返换端：发车方向与驾驶员所坐的一端相反时，驾驶员要自己下车走到另一端的驾驶室坐下，不再自动换座。
 *
 * <p>三个阶段：
 *
 * <ul>
 *   <li>{@link Stage#IDLE}：不需要换端；
 *   <li>{@link Stage#ANNOUNCED}：列车停在尽头式终点站（车门已开，或已转入待命）、派车还没放行，已知下一趟由此刻的车尾端驾驶，提前告诉驾驶员。
 *       这时没有时限，驾驶员离座不结束驾驶，车门可以开着；坐进车尾端驾驶室即完成，放行调头后那一端就是车头；
 *   <li>{@link Stage#ACTIVE}：派车已放行（发车方向已定、该调头的已调头），驾驶员不在车头端驾驶室：牵引封锁、列车保持停车，
 *       离座不结束驾驶；在换端时间预留内坐进车头端驾驶室即完成，超时交还自动运行。
 * </ul>
 *
 * <p>换端开始时（提前告知或放行时）定下完成后要不要重做制动试验：simulation 级、距计划发车不少于换端时间预留加制动试验所需时间才要求重做，
 * 否则视为已做；距计划发车不到换端时间预留时报准备时间不足。每次待命只提前告知一次：驾驶员坐进车尾端之后再离座就按离岗处理。
 *
 * <p>驾驶座没有标记的列车（见 {@link CabSeats}）按车厢位置认端，坐进那一端的客室座位也会被当成驾驶室：坐进要换到的那一端后，还要驾驶员确认座位才算完成（见 {@link
 * #awaitsSeatConfirm}）。
 *
 * <p>本类不依赖服务器对象，只在服务器主线程使用。
 */
public final class CabChange {

  /** 换端进行到哪一步。 */
  public enum Stage {
    IDLE,
    ANNOUNCED,
    ACTIVE
  }

  /** 一拍里发生的变化，驾驶侧据此提示驾驶员、交还或重设制动试验。 */
  public enum Event {
    NONE,
    /** 放行前提前告知要换到车尾端。 */
    ANNOUNCED,
    /** 放行后开始计时换端。 */
    STARTED,
    /** 已坐进要坐的驾驶室。 */
    COMPLETED,
    /** 换端时间预留用完，应交还自动运行。 */
    TIMED_OUT,
    /** 不再需要换端（列车动了、改为 ATO、预计方向变了）。 */
    CANCELLED
  }

  /**
   * 一拍的观测。
   *
   * @param applicable 人工驾驶调度列车、编组两节以上、已停稳；ATO 由自动运行折返发车，驾驶员不换端
   * @param preRelease 列车停在终点站待命、派车还没放行（放行那一拍才按发车方向调头）
   * @param predicted 放行前预计下一趟由哪一端驾驶
   * @param seat 驾驶员此刻所坐座位在哪一端；离座或坐在客室时为 {@link CabSeats.End#NONE}
   * @param released 调度已给出行车许可（不是停车信号）
   * @param now 此刻
   * @param reserveSeconds 换端时间预留（秒）
   * @param plannedDeparture 计划发车时刻；不明时为 {@code null}
   * @param simulation simulation 级（有制动试验）
   * @param brakeTestSeconds 制动试验所需时间（秒）
   * @param memberCount 编组节数（提示第几节用）
   */
  public record Input(
      boolean applicable,
      boolean preRelease,
      CabSeats.Departure predicted,
      CabSeats.End seat,
      boolean released,
      Instant now,
      long reserveSeconds,
      Instant plannedDeparture,
      boolean simulation,
      long brakeTestSeconds,
      int memberCount) {

    public Input {
      Objects.requireNonNull(now, "now");
      predicted = predicted == null ? CabSeats.Departure.EITHER : predicted;
      seat = seat == null ? CabSeats.End.NONE : seat;
      reserveSeconds = Math.max(0L, reserveSeconds);
      brakeTestSeconds = Math.max(0L, brakeTestSeconds);
    }
  }

  private Stage stage = Stage.IDLE;
  private CabSeats.End target = CabSeats.End.HEAD;
  private Instant deadline;
  private Instant lastNow;
  private long reserveSeconds;
  private OptionalLong remainingAtStart = OptionalLong.empty();
  private boolean brakeTestRequired;
  private boolean shortOfTime;
  private int memberCount;
  private boolean announcedThisLayover;
  private CabSeats.Departure cachedPrediction;
  private boolean cachedFromLayover;

  /**
   * 换端后要不要重做制动试验：simulation 级，且距计划发车的时间够走过去再做一次试验。计划发车不明时不要求（不为它拖晚发车）。
   *
   * @param remainingSeconds 换端开始时距计划发车的秒数（已晚点时为负）
   */
  public static boolean brakeTestRequired(
      boolean simulation,
      OptionalLong remainingSeconds,
      long reserveSeconds,
      long brakeTestSeconds) {
    return simulation
        && remainingSeconds.isPresent()
        && remainingSeconds.getAsLong() >= reserveSeconds + brakeTestSeconds;
  }

  /**
   * 是否按“放行前”处理：列车在终点站待命，或停在终点站、车门已开而派车还没放行。开门后就能去换端，不必等关门转入待命。
   *
   * @param turnbackPending 列车已转入终点站待命
   * @param released 调度已给出行车许可
   * @param atTerminalStop 列车停在终点站、车门已开（见 {@link DriverLink#atTerminalStop()}）
   */
  public static boolean preRelease(
      boolean turnbackPending, boolean released, boolean atTerminalStop) {
    return turnbackPending || (atTerminalStop && !released);
  }

  /**
   * 驾驶座没有标记的列车：驾驶员坐进了要换到的那一端（放行前是车尾端，放行后是车头端），但还没确认这个座位是驾驶室。
   *
   * @param marked 列车有标记的驾驶座（有标记时座位本身就能认定，不用确认）
   * @param changing 换端正在进行（已告知或计时中）
   * @param preRelease 放行前（见 {@link #preRelease}）
   * @param seat 座位在哪一端
   * @param confirmed 驾驶员已确认过此刻所坐的座位
   */
  public static boolean awaitsSeatConfirm(
      boolean marked, boolean changing, boolean preRelease, CabSeats.End seat, boolean confirmed) {
    if (marked || confirmed || !(changing || preRelease)) {
      return false;
    }
    return seat == (preRelease ? CabSeats.End.TAIL : CabSeats.End.HEAD);
  }

  /** 准备时间不足：距计划发车已不到换端时间预留，按时走过去也会晚点。 */
  public static boolean shortOfTime(OptionalLong remainingSeconds, long reserveSeconds) {
    return remainingSeconds.isPresent() && remainingSeconds.getAsLong() < reserveSeconds;
  }

  /**
   * 放行前预计的发车端：同一次待命里只算一次（要查线路图），放行后清掉。
   *
   * @param compute 计算预计发车端
   */
  public CabSeats.Departure prediction(boolean preRelease, Supplier<CabSeats.Departure> compute) {
    return prediction(preRelease, true, compute);
  }

  /**
   * 放行前预计的发车端：终点站开门后先按停站车站算一次，转入待命（有了待命登记、交路也已推进）时再算一次，其余时候沿用；放行后清掉。
   *
   * @param layover 列车已转入终点站待命
   * @param compute 计算预计发车端
   */
  public CabSeats.Departure prediction(
      boolean preRelease, boolean layover, Supplier<CabSeats.Departure> compute) {
    if (!preRelease) {
      cachedPrediction = null;
      return CabSeats.Departure.EITHER;
    }
    if (cachedPrediction == null || cachedFromLayover != layover) {
      CabSeats.Departure computed = compute.get();
      cachedPrediction = computed == null ? CabSeats.Departure.EITHER : computed;
      cachedFromLayover = layover;
    }
    return cachedPrediction;
  }

  /** 推进一拍。 */
  public Event tick(Input in) {
    Objects.requireNonNull(in, "in");
    lastNow = in.now();
    memberCount = in.memberCount();
    if (!in.preRelease()) {
      cachedPrediction = null;
      announcedThisLayover = false;
    }
    if (!in.applicable()) {
      return cancel();
    }
    if (in.preRelease() && stage != Stage.ACTIVE) {
      return tickBeforeRelease(in);
    }
    // 放行之后（或本来就不在终点站待命）：要坐的是此刻车头端的驾驶室。
    if (in.seat() == CabSeats.End.HEAD) {
      return complete();
    }
    boolean needed = stage != Stage.IDLE || (in.seat() == CabSeats.End.TAIL && in.released());
    if (!needed) {
      return Event.NONE;
    }
    if (stage != Stage.ACTIVE) {
      stage = Stage.ACTIVE;
      target = CabSeats.End.HEAD;
      deadline = in.now().plusSeconds(in.reserveSeconds());
      begin(in);
      return Event.STARTED;
    }
    if (!in.now().isBefore(deadline)) {
      stage = Stage.IDLE;
      return Event.TIMED_OUT;
    }
    return Event.NONE;
  }

  private Event tickBeforeRelease(Input in) {
    if (in.predicted() != CabSeats.Departure.TAIL) {
      return cancel();
    }
    if (in.seat() == CabSeats.End.TAIL) {
      // 已坐在下一趟的车头端：这次待命不再提前告知，之后离座按离岗处理。
      announcedThisLayover = true;
      return complete();
    }
    if (stage == Stage.IDLE && !announcedThisLayover) {
      stage = Stage.ANNOUNCED;
      target = CabSeats.End.TAIL;
      announcedThisLayover = true;
      begin(in);
      return Event.ANNOUNCED;
    }
    return Event.NONE;
  }

  private Event complete() {
    if (stage == Stage.IDLE) {
      return Event.NONE;
    }
    stage = Stage.IDLE;
    return Event.COMPLETED;
  }

  private Event cancel() {
    if (stage == Stage.IDLE) {
      return Event.NONE;
    }
    stage = Stage.IDLE;
    return Event.CANCELLED;
  }

  private void begin(Input in) {
    reserveSeconds = in.reserveSeconds();
    remainingAtStart =
        in.plannedDeparture() == null
            ? OptionalLong.empty()
            : OptionalLong.of(Duration.between(in.now(), in.plannedDeparture()).toSeconds());
    brakeTestRequired =
        brakeTestRequired(in.simulation(), remainingAtStart, reserveSeconds, in.brakeTestSeconds());
    shortOfTime = shortOfTime(remainingAtStart, reserveSeconds);
  }

  public Stage stage() {
    return stage;
  }

  /** 换端计时中：牵引封锁、列车保持停车。 */
  public boolean holding() {
    return stage == Stage.ACTIVE;
  }

  /** 换端途中离座不结束驾驶，也不把驾驶员送回原来的座位。 */
  public boolean allowsLeavingSeat() {
    return stage != Stage.IDLE;
  }

  /** 要坐进哪一端的驾驶室（按此刻的编组次序）。 */
  public CabSeats.End target() {
    return target;
  }

  /** 要坐的驾驶室在第几节（1 起）。 */
  public int targetCar() {
    return target == CabSeats.End.TAIL ? Math.max(1, memberCount) : 1;
  }

  /** 换端计时还剩几秒（向上取整，不为负）；不在计时时为 -1。 */
  public long secondsLeft() {
    if (stage != Stage.ACTIVE || deadline == null || lastNow == null) {
      return -1L;
    }
    long millis = deadline.toEpochMilli() - lastNow.toEpochMilli();
    return millis <= 0L ? 0L : (millis + 999L) / 1000L;
  }

  /** 本次换端的时间预留（秒）。 */
  public long reserveSeconds() {
    return reserveSeconds;
  }

  /** 换端开始时距计划发车的秒数；计划发车不明时为空。 */
  public OptionalLong remainingAtStart() {
    return remainingAtStart;
  }

  /** 完成后要求重做制动试验（simulation 级且时间够）；否则视为已做。 */
  public boolean brakeTestRequired() {
    return brakeTestRequired;
  }

  /** 换端开始时准备时间就已不足。 */
  public boolean shortOfTime() {
    return shortOfTime;
  }
}
