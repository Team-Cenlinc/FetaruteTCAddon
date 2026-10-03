package org.fetarute.fetaruteTCAddon.drive.cab;

/**
 * 发车前的制动试验：施加制动直到制动缸升压，再缓解直到制动缸排空。
 *
 * <p>每次上车（激活驾驶室）都要重做，换端也一样；试验过程中列车动了就作废重来。本类不依赖任何服务器对象。
 */
public final class BrakeTest {

  /** 试验进度。 */
  public enum Stage {
    /** 还没做。 */
    NOT_DONE,
    /** 等待施加制动，制动缸升到试验压力。 */
    APPLY,
    /** 等待缓解，制动缸降到缓解压力以下。 */
    RELEASE,
    /** 已通过。 */
    PASSED
  }

  private Stage stage = Stage.NOT_DONE;

  public Stage stage() {
    return stage;
  }

  public boolean passed() {
    return stage == Stage.PASSED;
  }

  public boolean inProgress() {
    return stage == Stage.APPLY || stage == Stage.RELEASE;
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
    if (stage != Stage.NOT_DONE) {
      return false;
    }
    stage = Stage.APPLY;
    return true;
  }

  /**
   * 按制动缸压力推进试验。
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
}
