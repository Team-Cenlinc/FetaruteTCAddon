package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.Locale;

/**
 * 站台屏的宣传页与安全提示页。
 *
 * <p>宣传页（乘车礼仪）与主页轮播；安全提示页在通过列车临近时锁定显示。文案取自语言文件 {@code pids.board.notice.<键>.*}。
 */
public enum PidsNotice {
  /** 先下后上。 */
  ORDER(false),
  /** 排队候车。 */
  QUEUE(false),
  /** 勿挡车门。 */
  DOORS(false),
  /** 列车通过，请勿靠近站台边缘。 */
  PASSING(true);

  private final boolean warning;

  PidsNotice(boolean warning) {
    this.warning = warning;
  }

  /** 安全提示（图标块用警示色）；否则为宣传页（图标块用信息色）。 */
  public boolean warning() {
    return warning;
  }

  /** 语言文件里的键。 */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }
}
