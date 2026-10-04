package org.fetarute.fetaruteTCAddon.drive.cab;

import java.util.Locale;

/**
 * 发车前的制动试验。
 *
 * <ul>
 *   <li>动车组：施加制动直到制动缸升压，再缓解直到制动缸排空；
 *   <li>机车（制动管）：充风到定压 → 减压到试验减压量 → 保压一段时间，期间制动缸下降不超过允许值 → 缓解到制动缸排空、制动管充回定压。
 *       保压中途缓解回到减压一步；保压时制动缸漏得太多即不通过，要重新开始。
 * </ul>
 *
 * <p>每次上车（激活驾驶室）都要重做，换端也一样；试验过程中列车动了就作废重来。本类不依赖任何服务器对象。
 */
public final class BrakeTest {

  /** 试验进度。 */
  public enum Stage {
    /** 还没做。 */
    NOT_DONE,
    /** 机车：等待制动管充风到定压。 */
    CHARGE,
    /** 等待施加制动：动车组为制动缸升到试验压力，机车为制动管减压到试验减压量。 */
    APPLY,
    /** 机车：保压，制动缸不应明显下降。 */
    HOLD,
    /** 等待缓解，制动缸降到缓解压力以下（机车还要制动管充回定压）。 */
    RELEASE,
    /** 已通过。 */
    PASSED,
    /** 上一次试验没通过（保压时漏泄超标），要重新开始。 */
    FAILED
  }

  /** 制动管压力距定压不超过它（kPa）就算充满。 */
  static final double CHARGED_TOLERANCE_KPA = 10.0;

  /** 保压时制动管比保压期间的最低值回升超过它（kPa），视为驾驶员中途缓解。 */
  private static final double RELEASE_DETECT_KPA = 5.0;

  private final boolean brakePipe;
  private Stage stage = Stage.NOT_DONE;
  private double heldSeconds;
  private double holdPeakCylinderKpa;
  private double holdMinPipeKpa;

  /** 动车组的制动试验。 */
  public BrakeTest() {
    this(false);
  }

  private BrakeTest(boolean brakePipe) {
    this.brakePipe = brakePipe;
  }

  /** 机车（制动管）的制动试验。 */
  public static BrakeTest forBrakePipe() {
    return new BrakeTest(true);
  }

  /** 是否为机车（制动管）的试验。 */
  public boolean usesBrakePipe() {
    return brakePipe;
  }

  public Stage stage() {
    return stage;
  }

  /** 进度在语言键里的写法；机车的施加一步写作 {@code reduce}。 */
  public String stageKey() {
    if (brakePipe && stage == Stage.APPLY) {
      return "reduce";
    }
    return stage.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  public boolean passed() {
    return stage == Stage.PASSED;
  }

  public boolean inProgress() {
    return stage == Stage.CHARGE
        || stage == Stage.APPLY
        || stage == Stage.HOLD
        || stage == Stage.RELEASE;
  }

  /** 直接视为已通过（热车交接：列车一直在运行，试验早已做过）。 */
  public void markPassed() {
    stage = Stage.PASSED;
  }

  /**
   * 开始试验。
   *
   * @return 是否开始了；已通过或正在进行时为 {@code false}
   */
  public boolean start() {
    if (stage != Stage.NOT_DONE && stage != Stage.FAILED) {
      return false;
    }
    stage = brakePipe ? Stage.CHARGE : Stage.APPLY;
    return true;
  }

  /** 保压还剩的秒数（向上取整）；不在保压时为 0。 */
  public long holdRemainingSeconds(BrakePipeConfig config) {
    if (stage != Stage.HOLD) {
      return 0L;
    }
    return (long) Math.ceil(Math.max(0.0, config.testHoldSeconds() - heldSeconds));
  }

  /**
   * 动车组：按制动缸压力推进试验。
   *
   * @return 进度是否有变化
   */
  public boolean tick(double brakeCylinderKpa, boolean stopped, CabConfig config) {
    if (!inProgress()) {
      return false;
    }
    if (!stopped) {
      stage = Stage.NOT_DONE;
      return true;
    }
    if (stage == Stage.APPLY && brakeCylinderKpa >= config.brakeTestApplyKpa()) {
      stage = Stage.RELEASE;
      return true;
    }
    if (stage == Stage.RELEASE && brakeCylinderKpa <= config.brakeTestReleaseKpa()) {
      stage = Stage.PASSED;
      return true;
    }
    return false;
  }

  /**
   * 机车：按制动管与制动缸压力推进试验。
   *
   * @param pipeKpa 制动管压力
   * @param brakeCylinderKpa 制动缸压力
   * @param commandedReductionKpa 手柄此刻要求的减压量（用来区分驾驶员缓解与漏泄）
   * @param stopped 列车是否已停稳
   * @param seconds 时间步长（秒）
   * @return 进度是否有变化
   */
  public boolean tickBrakePipe(
      double pipeKpa,
      double brakeCylinderKpa,
      double commandedReductionKpa,
      boolean stopped,
      double seconds,
      CabConfig config) {
    if (!inProgress()) {
      return false;
    }
    if (!stopped) {
      stage = Stage.NOT_DONE;
      return true;
    }
    BrakePipeConfig pipe = config.brakePipe();
    double reduction = Math.max(0.0, pipe.nominalKpa() - pipeKpa);
    switch (stage) {
      case CHARGE -> {
        if (pipeKpa >= pipe.nominalKpa() - CHARGED_TOLERANCE_KPA) {
          stage = Stage.APPLY;
          return true;
        }
      }
      case APPLY -> {
        if (reduction >= pipe.testReductionKpa()) {
          stage = Stage.HOLD;
          heldSeconds = 0.0;
          holdPeakCylinderKpa = brakeCylinderKpa;
          holdMinPipeKpa = pipeKpa;
          return true;
        }
      }
      case HOLD -> {
        return tickHold(pipeKpa, brakeCylinderKpa, commandedReductionKpa, seconds, pipe);
      }
      case RELEASE -> {
        if (brakeCylinderKpa <= config.brakeTestReleaseKpa()
            && pipeKpa >= pipe.nominalKpa() - CHARGED_TOLERANCE_KPA) {
          stage = Stage.PASSED;
          return true;
        }
      }
      default -> {}
    }
    return false;
  }

  private boolean tickHold(
      double pipeKpa,
      double brakeCylinderKpa,
      double commandedReductionKpa,
      double seconds,
      BrakePipeConfig pipe) {
    holdMinPipeKpa = Math.min(holdMinPipeKpa, pipeKpa);
    if (commandedReductionKpa < pipe.testReductionKpa()
        || pipeKpa > holdMinPipeKpa + RELEASE_DETECT_KPA) {
      // 驾驶员中途缓解：回到减压一步重新保压。
      stage = Stage.APPLY;
      return true;
    }
    holdPeakCylinderKpa = Math.max(holdPeakCylinderKpa, brakeCylinderKpa);
    if (holdPeakCylinderKpa - brakeCylinderKpa > pipe.testMaxLeakKpa()) {
      stage = Stage.FAILED;
      return true;
    }
    heldSeconds += Math.max(0.0, seconds);
    if (heldSeconds >= pipe.testHoldSeconds()) {
      stage = Stage.RELEASE;
      return true;
    }
    return false;
  }
}
