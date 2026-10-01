package org.fetarute.fetaruteTCAddon.display.pids.map;

import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 一块屏幕此刻该显示的内容。
 *
 * @param key 内容标识；与上次相同时不重绘（视图、测试卡都是纯数据记录，{@code equals} 即内容相同）
 * @param image 按需渲染整帧
 */
public record PidsContent(Object key, Supplier<BufferedImage> image) {

  public PidsContent {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(image, "image");
  }
}
