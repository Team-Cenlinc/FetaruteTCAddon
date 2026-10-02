package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
  /** 确认终点：快慢车或多条线路共用的站台才放。 */
  CHECK(false),
  /** 注意间隙。 */
  GAP(false),
  /** 列车通过，请勿靠近站台边缘。 */
  PASSING(true);

  private static final int COURTESY_COUNT =
      (int) Arrays.stream(values()).filter(notice -> !notice.warning).count();

  private final boolean warning;

  PidsNotice(boolean warning) {
    this.warning = warning;
  }

  /** 宣传页有几种。 */
  public static int courtesyCount() {
    return COURTESY_COUNT;
  }

  /** 全部宣传页，按声明顺序。 */
  public static List<PidsNotice> courtesy() {
    return Arrays.stream(values()).filter(notice -> !notice.warning).toList();
  }

  /** 按语言文件里的键找宣传页（不分大小写）；安全提示页与未知的键为空。 */
  public static Optional<PidsNotice> courtesy(String key) {
    String normalized = key.trim().toLowerCase(Locale.ROOT);
    return courtesy().stream().filter(notice -> notice.key().equals(normalized)).findFirst();
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
