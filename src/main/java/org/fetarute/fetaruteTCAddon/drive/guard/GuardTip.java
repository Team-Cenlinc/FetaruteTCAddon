package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Predicate;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop.Phase;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverDoorSide;

/**
 * 值乘中的情境提示：第一次遇到某一步时，在聊天框与屏幕下方各说明一次要做什么，每名玩家每种只出一次（是否出过记在玩家的持久数据里）。
 *
 * <p>与驾驶员的驾驶提示同一套做法；侧边栏每一步都照常显示，这里只在第一次时讲清楚。按值乘的这一刻判定，本类不依赖服务器对象。
 */
public enum GuardTip {
  /** 第一次上岗：提示都在侧边栏、快捷栏怎么用、哪里看手册。 */
  ON_DUTY("on-duty", s -> true),
  /** 第一次停妥要开门。 */
  OPEN_DOORS(
      "open-doors",
      s -> phase(s, Phase.OPEN_DOORS) && s.stop().get().required() != DriverDoorSide.NONE),
  /** 第一次停站时间到要关门：下到站台注视车门。 */
  CLOSE_DOORS("close-doors", s -> phase(s, Phase.CLOSE_DOORS) && !s.stop().get().closing()),
  /** 第一次关好门后要回座。 */
  RETURN_SEAT("return-seat", s -> waiting(s) && !s.seated()),
  /** 第一次在座位上等出站信号开放。 */
  WAIT_EXIT(
      "wait-exit",
      s -> waiting(s) && s.seated() && !s.stop().get().confirmed() && !s.stop().get().exitOpen()),
  /** 第一次出站信号开放、要确认。 */
  CONFIRM(
      "confirm",
      s -> waiting(s) && s.seated() && !s.stop().get().confirmed() && s.stop().get().exitOpen()),
  /** 第一次确认后要按发车铃。 */
  BUZZER("buzzer", s -> waiting(s) && s.seated() && s.stop().get().confirmed()),
  /** 第一次发出发车信号：起步后的出站监视。 */
  DEPARTURE_WATCH(
      "departure-watch",
      s -> s.stop().map(stop -> stop.released() || stop.phase() == Phase.DEPART).orElse(false)),
  /** 第一次终点站换端。 */
  CAB_CHANGE("cab-change", s -> s.cabChange().isPresent());

  private final String key;
  private final Predicate<GuardDisplay.Snapshot> due;

  GuardTip(String key, Predicate<GuardDisplay.Snapshot> due) {
    this.key = key;
    this.due = due;
  }

  /** 文案键的后缀（{@code drive.guard.tips.<键>}），也是持久数据标记名的一部分。 */
  public String key() {
    return key;
  }

  /** 此刻的值乘是否正处在这一步。 */
  public boolean due(GuardDisplay.Snapshot snapshot) {
    return due.test(snapshot);
  }

  /**
   * 此刻该出的第一条提示。
   *
   * @param shown 已经出过的提示
   * @return 没有该出的提示时为空
   */
  public static Optional<GuardTip> firstDue(
      GuardDisplay.Snapshot snapshot, Collection<GuardTip> shown) {
    for (GuardTip tip : values()) {
      if (!shown.contains(tip) && tip.due(snapshot)) {
        return Optional.of(tip);
      }
    }
    return Optional.empty();
  }

  private static boolean phase(GuardDisplay.Snapshot s, Phase phase) {
    return s.stop().map(stop -> stop.phase() == phase).orElse(false);
  }

  /** 门已关好、还没发出发车信号。 */
  private static boolean waiting(GuardDisplay.Snapshot s) {
    return s.stop()
        .map(stop -> stop.phase() == Phase.WAIT_DEPARTURE && !stop.released())
        .orElse(false);
  }
}
