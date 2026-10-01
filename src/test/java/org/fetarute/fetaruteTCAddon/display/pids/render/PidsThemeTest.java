package org.fetarute.fetaruteTCAddon.display.pids.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 线路色牌的文字色：与官网 getRailwayLineTextColor 一致，亮度低于 0.25 用浅色字。 */
class PidsThemeTest {

  @Test
  void textColorFollowsLuminanceThreshold() {
    assertEquals(PidsTheme.INK, PidsTheme.textOn(0x70DEEE), "WS 浅色线路用深字");
    assertEquals(PidsTheme.INK, PidsTheme.textOn(0xF6A000), "DS");
    assertEquals(PidsTheme.PAPER, PidsTheme.textOn(0xD920D9), "MT 深色线路用浅字");
    assertEquals(PidsTheme.PAPER, PidsTheme.textOn(0x000000));
    assertEquals(PidsTheme.INK, PidsTheme.textOn(0xFFFFFF));
  }
}
