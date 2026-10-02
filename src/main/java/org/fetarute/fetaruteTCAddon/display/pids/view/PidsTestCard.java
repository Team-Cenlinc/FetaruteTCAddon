package org.fetarute.fetaruteTCAddon.display.pids.view;

import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsView.Names;

/**
 * 测试卡：白底说明页。屏幕刚装上、尚未确认绑定时显示，用来核对尺寸、地图顺序与识别出的车站；无法正常显示（未注册、尺寸不符）时也用它说明原因。
 *
 * <p>纯数据，{@code equals} 即是否需要重绘。
 *
 * @param title 标题
 * @param lines 说明行
 * @param hint 底部提示
 * @param tileRows 实际地图行数
 * @param tileCols 实际地图列数
 */
public record PidsTestCard(
    Names title, List<String> lines, String hint, int tileRows, int tileCols) {

  public PidsTestCard {
    Objects.requireNonNull(title, "title");
    lines = List.copyOf(lines);
    Objects.requireNonNull(hint, "hint");
    if (tileRows < 1 || tileCols < 1) {
      throw new IllegalArgumentException("测试卡尺寸无效: " + tileRows + "×" + tileCols);
    }
  }
}
