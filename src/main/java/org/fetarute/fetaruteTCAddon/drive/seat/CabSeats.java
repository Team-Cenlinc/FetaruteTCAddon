package org.fetarute.fetaruteTCAddon.drive.seat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 驾驶室座位的认定：哪些座位算驾驶室、在列车的哪一端。接管、接车上车与折返换端都用这一个判定。
 *
 * <p>座位附件可以在 TrainCarts 的附件编辑器里命名；名字在驾驶座名单里的座位是被标记的驾驶座。一列车只要有被标记的座位，
 * 就只有端车（第一节、最后一节）上被标记的座位算驾驶室，端车两头都有驾驶座时（单节车重连）只认外侧那个；完全没有标记时按车厢位置近似： 前半列车算车头端，最后一节算车尾端。
 *
 * <p>车头、车尾都按此刻的编组次序：TrainCarts 调头后车厢序号翻转，原来的车尾端就成了车头端。本类不依赖服务器对象。
 */
public final class CabSeats {

  /** 座位在列车的哪一端。 */
  public enum End {
    /** 车头端的驾驶室。 */
    HEAD,
    /** 车尾端的驾驶室。 */
    TAIL,
    /** 不是驾驶室（客室座位，或标记过的列车上未标记的座位）。 */
    NONE
  }

  /** 下一趟发车时车头在此刻编组的哪一端。 */
  public enum Departure {
    /** 此刻的车头端。 */
    HEAD,
    /** 此刻的车尾端：尽头式终点站折返，发车时车尾变成车头。 */
    TAIL,
    /** 还不知道：两端驾驶室都可以坐，发车方向与所坐端相反时再换端。 */
    EITHER
  }

  private final int memberCount;
  private final List<Set<Integer>> marked;
  private final boolean anyMarked;

  private CabSeats(int memberCount, List<Set<Integer>> marked) {
    this.memberCount = Math.max(0, memberCount);
    this.marked = marked;
    boolean any = false;
    for (Set<Integer> seats : marked) {
      if (!seats.isEmpty()) {
        any = true;
        break;
      }
    }
    this.anyMarked = any;
  }

  /** 没有任何标记的列车：按车厢位置认定。 */
  public static CabSeats unmarked(int memberCount) {
    return new CabSeats(memberCount, List.of());
  }

  /**
   * 按各节车厢被标记的座位建立。
   *
   * @param markedSeatsByMember 从车头起每节车厢里被标记为驾驶座的座位序号（座位序号见 {@link SeatBinding#seatIndex()}）
   */
  public static CabSeats of(List<? extends Collection<Integer>> markedSeatsByMember) {
    List<Set<Integer>> copy = new ArrayList<>(markedSeatsByMember.size());
    for (Collection<Integer> seats : markedSeatsByMember) {
      copy.add(seats == null ? Set.of() : Set.copyOf(seats));
    }
    return new CabSeats(copy.size(), List.copyOf(copy));
  }

  public int memberCount() {
    return memberCount;
  }

  /** 这列车有没有被标记的驾驶座。 */
  public boolean marked() {
    return anyMarked;
  }

  /** 座位在哪一端。 */
  public End endOf(SeatBinding seat) {
    return seat == null ? End.NONE : endOf(seat.memberIndex(), seat.seatIndex());
  }

  /**
   * 座位在哪一端。
   *
   * <p>单节车的驾驶室总算车头端：两端在同一节车里分不出前后，调头也不改变车厢序号。
   *
   * @param memberIndex 车厢序号（从车头起，0 起）
   * @param seatIndex 座位在该节车厢里的序号（0 起）
   */
  public End endOf(int memberIndex, int seatIndex) {
    if (memberIndex < 0 || memberIndex >= memberCount || seatIndex < 0) {
      return End.NONE;
    }
    if (anyMarked) {
      if (!marked.get(memberIndex).contains(seatIndex)) {
        return End.NONE;
      }
      if (memberIndex == 0) {
        return End.HEAD;
      }
      return memberIndex == memberCount - 1 ? End.TAIL : End.NONE;
    }
    if (memberIndex * 2 <= memberCount - 1) {
      return End.HEAD;
    }
    return memberIndex == memberCount - 1 ? End.TAIL : End.NONE;
  }

  /** 某一端的端车在此刻编组里是第几节（1 起，提示用）。 */
  public int carNumber(End end) {
    return end == End.TAIL ? Math.max(1, memberCount) : 1;
  }

  /**
   * 座位所在的一端能不能担当下一趟的驾驶室。
   *
   * @param end 座位在哪一端
   * @param expected 下一趟发车时车头在哪一端
   */
  public static boolean accepts(End end, Departure expected) {
    if (end == null || end == End.NONE) {
      return false;
    }
    return switch (expected == null ? Departure.HEAD : expected) {
      case HEAD -> end == End.HEAD;
      case TAIL -> end == End.TAIL;
      case EITHER -> true;
    };
  }

  /** 两端离出口的距离至少差这么多（格）才认定哪一端朝出口。 */
  private static final double EXIT_MARGIN_BLOCKS = 1.0;

  /**
   * 列车停在终点站待命时，下一趟由哪一端驾驶。
   *
   * <p>待命站在线路图里只连着一条区间（尽头式站台）时，列车只能沿这条区间开出去，离区间另一端节点（出口）近的那一端就是下一趟的车头。 一般是到站时的车尾端；居中对位时往回挪过的车已被
   * TrainCarts 调了头，这时就是此刻的车头端，所以按位置判而不按到站方向判。 站台两头都通（贯通式、环线、站后折返线）时方向要到派车寻路才知道。单节车分不出两端，总按车头端。
   *
   * @param edgeCount 待命站在线路图里连着几条区间；查不到时为负数
   * @param headToExit 车头离出口的距离（格）；量不出时为 {@code NaN}
   * @param tailToExit 车尾离出口的距离（格）；量不出时为 {@code NaN}
   * @param memberCount 编组节数
   */
  public static Departure terminalDeparture(
      int edgeCount, double headToExit, double tailToExit, int memberCount) {
    if (memberCount < 2) {
      return Departure.HEAD;
    }
    if (edgeCount != 1 || !Double.isFinite(headToExit) || !Double.isFinite(tailToExit)) {
      return Departure.EITHER;
    }
    if (tailToExit + EXIT_MARGIN_BLOCKS < headToExit) {
      return Departure.TAIL;
    }
    if (headToExit + EXIT_MARGIN_BLOCKS < tailToExit) {
      return Departure.HEAD;
    }
    return Departure.EITHER;
  }

  /**
   * 座位附件的名字里有没有驾驶座名单上的名字（不区分大小写，两端空白不计）。
   *
   * @param seatNames 座位附件的名字（TrainCarts 附件配置的 {@code names}）
   * @param cabNames 驾驶座名单，已按 {@link #normalize} 处理
   */
  public static boolean nameMatches(Collection<String> seatNames, Collection<String> cabNames) {
    if (seatNames == null || seatNames.isEmpty() || cabNames == null || cabNames.isEmpty()) {
      return false;
    }
    for (String name : seatNames) {
      if (name != null && cabNames.contains(normalize(name))) {
        return true;
      }
    }
    return false;
  }

  /**
   * 把座位标为驾驶座后的名字：已经有名单上的名字就原样返回，否则在末尾加上名单的第一个名字。
   *
   * @param seatNames 座位附件现有的名字
   * @param cabNames 驾驶座名单，已按 {@link #normalize} 处理；不能为空
   */
  public static List<String> withCabName(List<String> seatNames, List<String> cabNames) {
    List<String> names = seatNames == null ? new ArrayList<>() : new ArrayList<>(seatNames);
    if (!nameMatches(names, cabNames)) {
      names.add(cabNames.get(0));
    }
    return List.copyOf(names);
  }

  /** 端车上两个驾驶座到相邻车厢的距离至少差这么多（格）才分得出内外。 */
  private static final double OUTER_MARGIN_BLOCKS = 1.0;

  /**
   * 端车上不止一个驾驶座时只认外侧那个：离相邻车厢最远的座位。单节车重连后，端车靠内一端的驾驶室不能用来驾驶。
   *
   * @param seats 端车上被标记的座位序号
   * @param distanceToNeighbour 各座位到相邻车厢的距离（格）；量不出时为 NaN
   * @return 算作驾驶室的座位；分不出内外（量不出、距离差不到 {@value #OUTER_MARGIN_BLOCKS} 格）时原样返回
   */
  public static List<Integer> outerCabSeats(
      List<Integer> seats, java.util.function.IntToDoubleFunction distanceToNeighbour) {
    if (seats.size() < 2) {
      return List.copyOf(seats);
    }
    int farthest = -1;
    double best = Double.NEGATIVE_INFINITY;
    double second = Double.NEGATIVE_INFINITY;
    for (int seat : seats) {
      double distance = distanceToNeighbour.applyAsDouble(seat);
      if (!Double.isFinite(distance)) {
        return List.copyOf(seats);
      }
      if (distance > best) {
        second = best;
        best = distance;
        farthest = seat;
      } else if (distance > second) {
        second = distance;
      }
    }
    return best - second < OUTER_MARGIN_BLOCKS ? List.copyOf(seats) : List.of(farthest);
  }

  /** 一节车厢最多几个驾驶座：车厢两端各一个驾驶室（单节车重连后每节车两端都有驾驶室）。 */
  public static final int MAX_CAB_SEATS_PER_CAR = 2;

  /**
   * 在一节车厢里把某个座位标为驾驶座。同一节里原有的驾驶座不动；已有 {@value #MAX_CAB_SEATS_PER_CAR} 个时不标。
   *
   * @param seatNames 这节车厢各座位此刻的名字，按座位序号（见 {@link SeatBinding#seatIndex()}）
   * @param seat 要标的座位序号
   * @param cabNames 驾驶座名单，已按 {@link #normalize} 处理；不能为空
   */
  public static CarMarking markInCar(
      List<List<String>> seatNames, int seat, List<String> cabNames) {
    List<Integer> others = new ArrayList<>();
    for (int index = 0; index < seatNames.size(); index++) {
      if (index != seat && nameMatches(seatNames.get(index), cabNames)) {
        others.add(index);
      }
    }
    List<String> current =
        seat >= 0 && seat < seatNames.size() && seatNames.get(seat) != null
            ? seatNames.get(seat)
            : List.of();
    boolean full = !nameMatches(current, cabNames) && others.size() >= MAX_CAB_SEATS_PER_CAR;
    return new CarMarking(
        full ? List.copyOf(current) : withCabName(current, cabNames), others, full);
  }

  /**
   * 一节车厢标记驾驶座的结果。
   *
   * @param names 要标的座位标记后的名字（{@code full} 时不变）
   * @param otherCabSeats 同一节里其余的驾驶座（座位序号）
   * @param full 这节车厢已有 {@value #MAX_CAB_SEATS_PER_CAR} 个驾驶座，没有标
   */
  public record CarMarking(List<String> names, List<Integer> otherCabSeats, boolean full) {
    public CarMarking {
      names = List.copyOf(names);
      otherCabSeats = List.copyOf(otherCabSeats);
    }
  }

  /**
   * 取消驾驶座标记后的名字：去掉名单上的名字，其余名字（动画、效果等用的）保留。
   *
   * @param seatNames 座位附件现有的名字
   * @param cabNames 驾驶座名单，已按 {@link #normalize} 处理
   */
  public static List<String> withoutCabNames(List<String> seatNames, Collection<String> cabNames) {
    List<String> names = new ArrayList<>();
    if (seatNames != null) {
      for (String name : seatNames) {
        if (name != null && !cabNames.contains(normalize(name))) {
          names.add(name);
        }
      }
    }
    return List.copyOf(names);
  }

  /** 名字比较用的形式：去掉两端空白、转小写。 */
  public static String normalize(String name) {
    return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
  }
}
