package org.fetarute.fetaruteTCAddon.display.pids;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 线路运行状况屏按运营商轮播：轮哪几家、第几轮放哪一家的第几页。
 *
 * <p>只轮同一公司的运营商：清单里已不存在、或已改属别的公司的跳过；一家也不剩时只显示屏幕所属的运营商。 每家分自己的页（线路多时几页），依次放完一家再到下一家；
 * 按屏幕的线路过滤一条线路也没有的那家跳过（都没有时放第一家，写过滤没有匹配的线路）。
 */
public final class PidsStatusRotation {

  private PidsStatusRotation() {}

  /**
   * 轮哪几家。
   *
   * @param own 屏幕所属的运营商
   * @param rotation 屏幕的轮播清单（按加入的先后）
   * @param companyOf 运营商所属的公司
   * @param exists 运营商是否还在
   * @return 依次轮播的运营商；清单为空或一家也不算数时只有 {@code own}
   */
  public static List<String> operators(
      String own,
      List<String> rotation,
      Function<String, Optional<UUID>> companyOf,
      Predicate<String> exists) {
    Objects.requireNonNull(own, "own");
    Optional<UUID> company = companyOf.apply(own);
    if (rotation.isEmpty() || company.isEmpty()) {
      return List.of(own);
    }
    List<String> out = new ArrayList<>();
    for (String operator : rotation) {
      if (exists.test(operator) && company.equals(companyOf.apply(operator))) {
        out.add(operator);
      }
    }
    return out.isEmpty() ? List.of(own) : List.copyOf(out);
  }

  /**
   * 第 {@code round} 轮放哪一家的第几页。
   *
   * @param operator 那一家在轮播清单里的下标
   * @param page 那一家的第几页（0 起）
   * @param shown 有线路可放的几家在轮播清单里的下标，按轮播顺序（页头的轮播标签用）
   */
  public record Slot(int operator, int page, List<Integer> shown) {

    public Slot {
      shown = List.copyOf(shown);
    }

    /** 那一家在有线路可放的几家里排第几。 */
    public int position() {
      return shown.indexOf(operator);
    }
  }

  /**
   * @param pages 各家按线路过滤后分几页；没有线路可放时为 0
   * @param round 按时钟数的轮次
   */
  public static Slot slot(List<Integer> pages, long round) {
    List<Integer> shown = new ArrayList<>();
    int total = 0;
    for (int i = 0; i < pages.size(); i++) {
      if (pages.get(i) > 0) {
        shown.add(i);
        total += pages.get(i);
      }
    }
    if (total == 0) {
      return new Slot(0, 0, List.of(0));
    }
    long left = Math.floorMod(round, total);
    for (int position = 0; position < shown.size(); position++) {
      int operator = shown.get(position);
      int count = pages.get(operator);
      if (left < count) {
        return new Slot(operator, (int) left, shown);
      }
      left -= count;
    }
    throw new IllegalStateException("轮播页数算错: " + pages + " round=" + round);
  }
}
