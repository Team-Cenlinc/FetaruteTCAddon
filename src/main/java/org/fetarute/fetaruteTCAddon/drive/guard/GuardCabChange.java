package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.Objects;
import org.fetarute.fetaruteTCAddon.drive.driver.CabChange;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;

/**
 * 车掌换端：车掌坐在发车端的另一头。终点站折返时发车端换到另一头，车掌也要换到另一头的驾驶室。
 *
 * <ul>
 *   <li>{@link Stage#ANNOUNCED}：派车还没放行，已知下一趟由车掌此刻这一端发车：提前告诉车掌换到另一头，不计时；
 *   <li>{@link Stage#ACTIVE}：派车已放行（该调头的已调头），车掌预留的座位不在车尾端：换端时间预留内坐进车尾端驾驶室即完成， 超时或列车开动了由车掌那边直接送进去。
 * </ul>
 *
 * <p>换端开始时预留的座位就让出来（驾驶员可能要坐），坐进要换到的那一端后预留改到新座位。单节车两头在同一节车里，不换端。
 *
 * <p>本类不依赖服务器对象，只在服务器主线程使用。
 */
public final class GuardCabChange {

  /** 换端进行到哪一步。 */
  public enum Stage {
    IDLE,
    ANNOUNCED,
    ACTIVE
  }

  /** 一拍里发生的变化。 */
  public enum Event {
    NONE,
    /** 放行前提前告知要换到另一头。 */
    ANNOUNCED,
    /** 放行后开始计时换端。 */
    STARTED,
    /** 已坐进要换到的那一端：调用方把预留改到所坐的座位。 */
    COMPLETED,
    /** 时限到了或列车开动了还没坐进去：调用方直接送过去，送不了就结束值乘。 */
    TIMED_OUT,
    /** 不再需要换端（发车方向变了、列车开走了、编组只剩一节）。 */
    CANCELLED
  }

  /**
   * 车掌换端看的发车端。
   *
   * @param departure 下一趟由此刻编组的哪一端发车；方向未定时为 {@link CabSeats.Departure#EITHER}
   * @param predicted 发车端是预计的：派车还没放行（终点站待命、终点站结算后等下一趟）
   */
  public record Outlook(CabSeats.Departure departure, boolean predicted) {

    /** 平常行车：车头端发车，不是预计。 */
    public static final Outlook RUNNING = new Outlook(CabSeats.Departure.HEAD, false);

    public Outlook {
      Objects.requireNonNull(departure, "departure");
    }
  }

  /**
   * 一拍的观测。
   *
   * @param applicable 两节及以上的编组
   * @param stopped 列车停着
   * @param outlook 发车端
   * @param reservedEnd 车掌预留的座位在哪一端
   * @param seatedEnd 车掌此刻所坐的座位在哪一端；不在这列车的驾驶室里时为 {@link CabSeats.End#NONE}
   * @param nowTick 当前 tick
   * @param reserveTicks 换端时间预留（tick）
   */
  public record Input(
      boolean applicable,
      boolean stopped,
      Outlook outlook,
      CabSeats.End reservedEnd,
      CabSeats.End seatedEnd,
      long nowTick,
      long reserveTicks) {

    public Input {
      Objects.requireNonNull(outlook, "outlook");
      reservedEnd = reservedEnd == null ? CabSeats.End.NONE : reservedEnd;
      seatedEnd = seatedEnd == null ? CabSeats.End.NONE : seatedEnd;
    }
  }

  private Stage stage = Stage.IDLE;
  private CabSeats.End target = CabSeats.End.NONE;
  private long deadlineTick = Long.MAX_VALUE;

  /** 发车端的另一头：车掌那一端；方向未定时为 {@link CabSeats.End#NONE}。 */
  public static CabSeats.End guardEnd(CabSeats.Departure departure) {
    return switch (departure) {
      case HEAD -> CabSeats.End.TAIL;
      case TAIL -> CabSeats.End.HEAD;
      case EITHER -> CabSeats.End.NONE;
    };
  }

  /** 另一头：驾驶员换到哪一端，车掌就换到它的另一头。 */
  public static CabSeats.End opposite(CabSeats.End end) {
    return switch (end) {
      case HEAD -> CabSeats.End.TAIL;
      case TAIL -> CabSeats.End.HEAD;
      case NONE -> CabSeats.End.NONE;
    };
  }

  /**
   * 预留座位所在的车厢在哪一端：按车厢在编组里的位置（第一节为车头端、最后一节为车尾端），只用于已认定为驾驶室的预留座位。
   *
   * @param memberIndex 车厢序号（从车头起，0 起）
   * @param memberCount 编组节数
   */
  public static CabSeats.End endOfCar(int memberIndex, int memberCount) {
    if (memberCount < 2 || memberIndex < 0 || memberIndex >= memberCount) {
      return CabSeats.End.NONE;
    }
    if (memberIndex == memberCount - 1) {
      return CabSeats.End.TAIL;
    }
    return memberIndex * 2 <= memberCount - 1 ? CabSeats.End.HEAD : CabSeats.End.NONE;
  }

  /**
   * 有驾驶员时车掌看的发车端：驾驶员在换端时按他要换到的那一端（方向未定时未定；放行前告知时算预计），不换端时按驾驶员所坐的车厢 （最后一节为车尾端，其余按车头端），终点站派车还没放行时算预计。
   *
   * @param stage 驾驶员的换端进行到哪一步
   * @param eitherEnd 驾驶员换端时发车方向未定
   * @param target 驾驶员要换到的那一端
   * @param turnbackPending 驾驶员停在终点站等派车放行
   * @param driverMemberIndex 驾驶员所坐的车厢序号
   * @param memberCount 编组节数
   */
  public static Outlook fromDriver(
      CabChange.Stage stage,
      boolean eitherEnd,
      CabSeats.End target,
      boolean turnbackPending,
      int driverMemberIndex,
      int memberCount) {
    if (stage != CabChange.Stage.IDLE) {
      CabSeats.Departure departure =
          eitherEnd
              ? CabSeats.Departure.EITHER
              : target == CabSeats.End.TAIL ? CabSeats.Departure.TAIL : CabSeats.Departure.HEAD;
      return new Outlook(departure, stage == CabChange.Stage.ANNOUNCED);
    }
    CabSeats.Departure departure =
        endOfCar(driverMemberIndex, memberCount) == CabSeats.End.TAIL
            ? CabSeats.Departure.TAIL
            : CabSeats.Departure.HEAD;
    return new Outlook(departure, turnbackPending);
  }

  /** 推进一拍。 */
  public Event tick(Input in) {
    Objects.requireNonNull(in, "in");
    CabSeats.End wanted = in.applicable() ? guardEnd(in.outlook().departure()) : CabSeats.End.NONE;
    boolean staleForecast = in.outlook().predicted() && !in.stopped();
    if (wanted == CabSeats.End.NONE || staleForecast) {
      return stage == Stage.IDLE ? Event.NONE : cancel();
    }
    if (in.reservedEnd() == wanted) {
      // 预留已在要换到的那一端（直接送过去时由调用方改了预留）：已经换好。
      return stage == Stage.IDLE ? Event.NONE : complete();
    }
    target = wanted;
    if (in.seatedEnd() == wanted) {
      return complete();
    }
    if (in.outlook().predicted()) {
      if (stage == Stage.IDLE) {
        stage = Stage.ANNOUNCED;
        return Event.ANNOUNCED;
      }
      return Event.NONE;
    }
    if (stage != Stage.ACTIVE) {
      stage = Stage.ACTIVE;
      deadlineTick = in.nowTick() + Math.max(0L, in.reserveTicks());
      return Event.STARTED;
    }
    if (!in.stopped() || in.nowTick() >= deadlineTick) {
      return Event.TIMED_OUT;
    }
    return Event.NONE;
  }

  /** 已直接送进要换到的那一端（换端超时、一起传送、车掌自己点了传送入座）。 */
  public void finish() {
    stage = Stage.IDLE;
    deadlineTick = Long.MAX_VALUE;
  }

  /** 值乘结束或不再需要换端：回到不换端。 */
  public Event cancel() {
    stage = Stage.IDLE;
    target = CabSeats.End.NONE;
    deadlineTick = Long.MAX_VALUE;
    return Event.CANCELLED;
  }

  private Event complete() {
    stage = Stage.IDLE;
    deadlineTick = Long.MAX_VALUE;
    return Event.COMPLETED;
  }

  public Stage stage() {
    return stage;
  }

  /** 正在换端：预留的座位已让出来。 */
  public boolean changing() {
    return stage != Stage.IDLE;
  }

  /** 要换到哪一端；没在换端时为最近一次的目标或 {@link CabSeats.End#NONE}。 */
  public CabSeats.End target() {
    return target;
  }

  /**
   * 要换到的那一端在此刻编组里是第几节（1 起，提示用）。
   *
   * @param memberCount 编组节数
   */
  public static int targetCar(CabSeats.End target, int memberCount) {
    return target == CabSeats.End.TAIL ? Math.max(1, memberCount) : 1;
  }

  /**
   * 计时换端还剩多少 tick。
   *
   * @return 没在计时（不换端或放行前告知）时为 -1
   */
  public long remainingTicks(long nowTick) {
    return stage == Stage.ACTIVE ? Math.max(0L, deadlineTick - nowTick) : -1L;
  }
}
