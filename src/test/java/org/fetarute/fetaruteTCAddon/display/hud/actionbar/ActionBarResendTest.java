package org.fetarute.fetaruteTCAddon.display.hud.actionbar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** ActionBar 文字没变就不重发，但隔一段时间补一次，免得淡出或被其他插件盖掉后一直不回来。 */
class ActionBarResendTest {

  private static final ActionBarTrainHudManager.Shown SHOWN =
      new ActionBarTrainHudManager.Shown("下一站 新笛矢·壑湖", 10_000L);

  @Test
  void unchangedTextIsNotResentRightAway() {
    assertFalse(ActionBarTrainHudManager.needsSend(SHOWN, "下一站 新笛矢·壑湖", 10_500L));
  }

  @Test
  void changedTextIsSentAtOnce() {
    assertTrue(ActionBarTrainHudManager.needsSend(SHOWN, "下一站 镇西", 10_500L));
  }

  @Test
  void unchangedTextIsRefreshedBeforeItFades() {
    assertTrue(ActionBarTrainHudManager.needsSend(SHOWN, "下一站 新笛矢·壑湖", 11_500L));
    assertTrue(ActionBarTrainHudManager.needsSend(null, "下一站 新笛矢·壑湖", 10_000L));
  }
}
