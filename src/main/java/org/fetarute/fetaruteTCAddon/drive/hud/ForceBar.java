package org.fetarute.fetaruteTCAddon.drive.hud;

/**
 * 动作栏里的力度条：十格，牵引为绿、常用制动为橙、紧急制动整条为红。
 *
 * <p>本类只做换算，不依赖服务器对象。
 */
public final class ForceBar {

  /** 力度条的格数。 */
  public static final int CELLS = 10;

  /** 力度的种类。 */
  public enum Kind {
    NONE,
    TRACTION,
    BRAKE,
    EMERGENCY
  }

  /**
   * 力度条的样子。
   *
   * @param kind 种类
   * @param filled 点亮的格数，0–{@link #CELLS}
   */
  public record Bar(Kind kind, int filled) {}

  private ForceBar() {}

  /**
   * 由实际力度换算力度条。
   *
   * @param effort 实际力度：牵引为正、制动为负，满力为 1，紧急制动可超过 1
   */
  public static Bar of(double effort) {
    if (!Double.isFinite(effort)) {
      return new Bar(Kind.NONE, 0);
    }
    if (effort > 0.0) {
      return new Bar(Kind.TRACTION, cells(effort));
    }
    if (effort < 0.0) {
      if (-effort > 1.0 + 1.0e-6) {
        return new Bar(Kind.EMERGENCY, CELLS);
      }
      return new Bar(Kind.BRAKE, cells(-effort));
    }
    return new Bar(Kind.NONE, 0);
  }

  private static int cells(double magnitude) {
    long rounded = Math.round(Math.min(1.0, magnitude) * CELLS);
    return (int) Math.max(0, Math.min(CELLS, rounded));
  }
}
