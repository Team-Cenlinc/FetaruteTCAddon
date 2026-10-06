package org.fetarute.fetaruteTCAddon.drive.tutorial;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.fetarute.fetaruteTCAddon.drive.hud.DriverStationHint;

/**
 * 驾驶中的情境提示：第一次遇到某种情况时弹一次简短说明，每名玩家每种只出一次（是否出过记在玩家的持久数据里）。
 *
 * <p>与新手教程互相独立：没做教程的玩家也会在第一次遇到时看到。本类不依赖服务器对象。
 */
public enum DriveTip {
  /** 信号变严，要右键确认。 */
  SIGNAL_ACKNOWLEDGE(
      "signal-acknowledge", "signal-confirm", s -> manual(s) && s.signalAcknowledgePending()),
  /** 第一次进站对标。 */
  STOP_MARK(
      "stop-mark",
      s ->
          manual(s)
              && (s.stationHint() == DriverStationHint.Kind.APPROACH
                  || s.stationHint() == DriverStationHint.Kind.ON_MARK
                  || s.stationHint() == DriverStationHint.Kind.MOVE_UP
                  || s.stationHint() == DriverStationHint.Kind.OVERRUN)),
  /** 第一次停妥后需要开门。 */
  OPEN_DOORS(
      "open-doors",
      s -> manual(s) && s.stationHint() == DriverStationHint.Kind.OPEN_DOORS && s.doorsRequired()),
  /** 第一次停站时间到、需要关门。 */
  CLOSE_DOORS(
      "close-doors", s -> manual(s) && s.stationHint() == DriverStationHint.Kind.CLOSE_DOORS),
  /** 第一次看到发车信号。 */
  DEPARTURE("departure", s -> manual(s) && s.stationHint() == DriverStationHint.Kind.DEPART),
  /** 第一次 ATO 下要确认发车。 */
  ATO_CONFIRM("ato-confirm", s -> s.dispatch() && s.ato() && s.departurePending()),
  /** 第一次警惕装置报警（simulation 级，任何列车）。 */
  VIGILANCE("vigilance", s -> s.cab() && s.vigilanceWarning());

  private final String key;
  private final String storageKey;
  private final Predicate<TutorialSnapshot> due;

  DriveTip(String key, Predicate<TutorialSnapshot> due) {
    this(key, key, due);
  }

  DriveTip(String key, String storageKey, Predicate<TutorialSnapshot> due) {
    this.key = key;
    this.storageKey = storageKey;
    this.due = due;
  }

  /** 文案键的后缀（{@code drive.tutorial.tips.<键>}）。 */
  public String key() {
    return key;
  }

  /** 持久数据标记名的一部分：与文案键分开，文案键改名后已出过的提示不会再出一次。 */
  public String storageKey() {
    return storageKey;
  }

  /** 此刻的会话是否正处在这种情况。 */
  public boolean due(TutorialSnapshot snapshot) {
    return due.test(snapshot);
  }

  /**
   * 此刻该出的第一条提示。
   *
   * @param shown 已经出过的提示
   * @return 没有该出的提示时为空
   */
  public static Optional<DriveTip> firstDue(TutorialSnapshot snapshot, Collection<DriveTip> shown) {
    for (DriveTip tip : values()) {
      if (!shown.contains(tip) && tip.due(snapshot)) {
        return Optional.of(tip);
      }
    }
    return Optional.empty();
  }

  /** 全部提示。 */
  public static Set<DriveTip> all() {
    return EnumSet.allOf(DriveTip.class);
  }

  /** 人工驾驶调度列车：车站与信号的提示只对它有意义。 */
  private static boolean manual(TutorialSnapshot snapshot) {
    return snapshot.dispatch() && !snapshot.ato();
  }
}
