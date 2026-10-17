package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 车掌的右键按在哪个按钮上：坐在车上时对着实体也算；站在站台上对着实体右键是点列车回座，不按按钮、不响铃。 */
class GuardUseRoutingTest {

  private static final Optional<GuardHotbar.Button> BUZZER = Optional.of(GuardHotbar.Button.BUZZER);
  private static final Optional<GuardHotbar.Button> DOOR =
      Optional.of(GuardHotbar.Button.DOOR_LEFT);

  @Test
  void seatedEntityClicksPressTheSelectedButton() {
    assertEquals(BUZZER, GuardSessionManager.buttonForUse(BUZZER, true, true));
    assertEquals(DOOR, GuardSessionManager.buttonForUse(DOOR, true, true));
  }

  @Test
  void platformEntityClicksOnlyReseat() {
    assertEquals(Optional.empty(), GuardSessionManager.buttonForUse(BUZZER, true, false));
    assertEquals(Optional.empty(), GuardSessionManager.buttonForUse(DOOR, true, false));
  }

  @Test
  void ordinaryClicksWorkSeatedOrOnThePlatform() {
    assertEquals(DOOR, GuardSessionManager.buttonForUse(DOOR, false, false));
    assertEquals(BUZZER, GuardSessionManager.buttonForUse(BUZZER, false, true));
    assertEquals(Optional.empty(), GuardSessionManager.buttonForUse(Optional.empty(), false, true));
  }

  /** 分行后等选择期间按的发车铃不作数：清掉之后不会再认出一长。 */
  @Test
  void resetDropsAHalfRecognisedPress() {
    BuzzerPress press = BuzzerPress.inMillis(16, 20);
    for (long t = 1_000; t <= 2_000; t += 200) {
      press.press(t);
    }
    press.reset();
    for (long t = 2_050; t <= 4_000; t += 50) {
      assertEquals(Optional.empty(), press.tick(t));
    }
  }
}
