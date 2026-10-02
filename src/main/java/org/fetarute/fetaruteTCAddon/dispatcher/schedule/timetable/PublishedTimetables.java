package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 同一线路同时只能有一张时刻表生效。
 *
 * <p>两张表管同一条线、服务时段又重叠时，每个班次都会在同一时刻各出一张票：车只属于其中一张表的交路，另一张票接不到车，挂满容差后作废并登记取消，
 * 站牌上就是一行越拖越晚的“晚点”。时区不同的两张表无法按时段比较，一律视为重叠。
 */
public final class PublishedTimetables {

  private PublishedTimetables() {}

  private static final int DAY_SECONDS = 24 * 60 * 60;

  /**
   * 两张表是否会同时管同一条线。
   *
   * <p>表每天都生效，计划窗口终点可以超过一天（跨零点的表）：一张表当天的尾段会落到另一张表次日的开头，所以按前一天、当天、 后一天三种相对位置各比一次。
   *
   * @param a 时刻表
   * @param b 时刻表
   */
  public static boolean overlaps(Timetable a, Timetable b) {
    Objects.requireNonNull(a, "a");
    Objects.requireNonNull(b, "b");
    if (a.id().equals(b.id()) || !a.lineId().equals(b.lineId())) {
      return false;
    }
    if (!a.zoneId().equals(b.zoneId())) {
      return true;
    }
    for (int shift = -DAY_SECONDS; shift <= DAY_SECONDS; shift += DAY_SECONDS) {
      if (a.serviceStartSecondOfDay() < b.serviceEndSecondOfDay() + shift
          && b.serviceStartSecondOfDay() + shift < a.serviceEndSecondOfDay()) {
        return true;
      }
    }
    return false;
  }

  /**
   * 从已发布的表里选出生效的：同一线路时段重叠时，最近发布（{@code updatedAt} 最晚）的生效，其余被它顶掉。
   *
   * @param published 已发布的时刻表
   * @return 生效的表与被顶掉的表
   */
  public static Selection select(List<Timetable> published) {
    List<Timetable> sorted = new ArrayList<>();
    for (Timetable timetable : published) {
      if (timetable != null) {
        sorted.add(timetable);
      }
    }
    sorted.sort(
        Comparator.comparing(Timetable::updatedAt)
            .reversed()
            .thenComparing(timetable -> timetable.id().toString()));
    List<Timetable> active = new ArrayList<>();
    List<Shadowed> shadowed = new ArrayList<>();
    for (Timetable candidate : sorted) {
      active.stream()
          .filter(kept -> overlaps(kept, candidate))
          .findFirst()
          .ifPresentOrElse(
              kept -> shadowed.add(new Shadowed(candidate, kept)), () -> active.add(candidate));
    }
    return new Selection(active, shadowed);
  }

  /**
   * 选择结果。
   *
   * @param active 生效的表
   * @param shadowed 被顶掉的表
   */
  public record Selection(List<Timetable> active, List<Shadowed> shadowed) {

    public Selection {
      active = List.copyOf(active);
      shadowed = List.copyOf(shadowed);
    }
  }

  /**
   * 被顶掉的表。
   *
   * @param timetable 不生效的表
   * @param by 顶掉它的表
   */
  public record Shadowed(Timetable timetable, Timetable by) {

    public Shadowed {
      Objects.requireNonNull(timetable, "timetable");
      Objects.requireNonNull(by, "by");
    }
  }
}
