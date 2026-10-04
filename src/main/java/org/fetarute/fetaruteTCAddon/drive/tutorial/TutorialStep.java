package org.fetarute.fetaruteTCAddon.drive.tutorial;

import java.util.function.Predicate;
import org.fetarute.fetaruteTCAddon.drive.SimulationLevel;
import org.fetarute.fetaruteTCAddon.drive.dynamics.ReverserPosition;

/**
 * 新手教程的一步：适用于哪种会话、怎样算做到了。按声明顺序进行。
 *
 * <p>三类步骤：
 *
 * <ul>
 *   <li>前提类（启动列车、压缩机、制动试验、停放制动）：开始教程时已经满足的不教，直接略过；
 *   <li>操作类（换向、起步、惰行、制动停车、开关车门）：判定玩家真的做到了才进入下一步；
 *   <li>说明类（紧急制动、ATO）：只讲不练，读完点「继续」或等一会儿自动继续。
 * </ul>
 *
 * <p>结束驾驶一步不靠判定：驾驶会话结束时教程随之完成。
 */
public enum TutorialStep {
  /** standard 级：按驾驶台的启动按钮。 */
  START_BUTTON(
      "start-button",
      false,
      true,
      false,
      s -> !s.ato() && s.setupMode() == SimulationLevel.SetupMode.ONE_CLICK,
      TutorialSnapshot::setupReady),
  /** simulation 级：按 HUD 的「下一步」逐项接通。 */
  SETUP_SWITCHES(
      "setup-switches",
      false,
      true,
      false,
      s -> !s.ato() && s.setupMode() == SimulationLevel.SetupMode.MANUAL,
      TutorialSnapshot::setupReady),
  /** simulation 级机车：打开压缩机。 */
  COMPRESSOR(
      "compressor",
      false,
      true,
      false,
      s -> !s.ato() && s.cab() && s.manualCompressor(),
      TutorialSnapshot::compressorOn),
  /** simulation 级：制动试验。 */
  BRAKE_TEST(
      "brake-test",
      false,
      true,
      false,
      s -> !s.ato() && s.cab(),
      TutorialSnapshot::brakeTestPassed),
  /** simulation 级：缓解停放制动。 */
  RELEASE_PARKING(
      "release-parking", false, true, false, s -> !s.ato() && s.cab(), s -> !s.parkingApplied()),
  /** 非调度列车：确认换向手柄在前进位（调度列车的方向由交路决定）。 */
  REVERSER(
      "reverser",
      false,
      false,
      false,
      s -> !s.ato() && !s.dispatch(),
      s -> s.reverser() == ReverserPosition.FORWARD),
  /** ATO：自动运行操纵列车，驾驶员确认发车，拉到 EB 转人工驾驶。 */
  ATO_OVERVIEW("ato-overview", true, false, false, TutorialSnapshot::ato, s -> false),
  /** 拉到 P1–P3 起步。 */
  TRACTION(
      "traction",
      false,
      false,
      true,
      s -> !s.ato(),
      s -> s.handle().isTraction() && s.speedBps() >= TutorialEngine.TRACTION_SPEED_BPS),
  /** 回到 N 惰行。 */
  COAST("coast", false, false, false, s -> !s.ato(), s -> false),
  /** 用 B1–B4 减速并停稳。 */
  SERVICE_BRAKE("service-brake", false, false, true, s -> !s.ato(), s -> false),
  /** 了解紧急制动（只讲不练）。 */
  EMERGENCY_BRAKE("emergency-brake", true, false, false, s -> !s.ato(), s -> false),
  /** 非调度列车：停稳后按 F 打开驾驶台开门（调度列车只能在车站停妥后开门，由情境提示讲）。 */
  OPEN_DOORS(
      "open-doors",
      false,
      false,
      false,
      s -> !s.ato() && !s.dispatch(),
      TutorialSnapshot::doorsOpen),
  /** 非调度列车：关门。 */
  CLOSE_DOORS(
      "close-doors",
      false,
      false,
      false,
      s -> !s.ato() && !s.dispatch(),
      s -> !s.doorsOpen() && !s.doorsClosing()),
  /** 结束驾驶：会话结束时教程完成。 */
  FINISH("finish", false, false, true, s -> true, s -> false);

  private final String key;
  private final boolean info;
  private final boolean prerequisite;
  private final boolean dispatchVariant;
  private final Predicate<TutorialSnapshot> relevant;
  private final Predicate<TutorialSnapshot> satisfied;

  TutorialStep(
      String key,
      boolean info,
      boolean prerequisite,
      boolean dispatchVariant,
      Predicate<TutorialSnapshot> relevant,
      Predicate<TutorialSnapshot> satisfied) {
    this.key = key;
    this.info = info;
    this.prerequisite = prerequisite;
    this.dispatchVariant = dispatchVariant;
    this.relevant = relevant;
    this.satisfied = satisfied;
  }

  /**
   * 这一步的文案键（{@code drive.tutorial.steps.<键>}）：调度列车与非调度列车说法不同的步骤带 {@code -dispatch} 后缀。
   *
   * @param snapshot 开始这一步时的会话
   */
  public String key(TutorialSnapshot snapshot) {
    return dispatchVariant && snapshot.dispatch() ? key + "-dispatch" : key;
  }

  /** 说明类步骤：只讲不练，读完继续。 */
  public boolean info() {
    return info;
  }

  /** 这一步适用于此刻的会话（仿真等级、是否调度列车、是否 ATO）。 */
  public boolean relevant(TutorialSnapshot snapshot) {
    return relevant.test(snapshot);
  }

  /** 前提类步骤：轮到它时已经满足就略过，不教。 */
  boolean skipsWhenSatisfied(TutorialSnapshot snapshot) {
    return prerequisite && satisfied.test(snapshot);
  }

  /** 是否做到了；惰行与制动停车要看这一步期间的经过。 */
  boolean done(TutorialSnapshot snapshot, TutorialEngine.Progress progress) {
    return switch (this) {
      case COAST -> progress.coasted();
      case SERVICE_BRAKE -> progress.usedServiceBrake() && snapshot.stopped();
      case FINISH -> false;
      default -> satisfied.test(snapshot);
    };
  }
}
