package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.fetarute.fetaruteTCAddon.drive.session.SeatExitGuard.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("按 Shift 离座的防误操作")
class SeatExitGuardTest {

  private final SeatExitGuard guard = new SeatExitGuard();

  @Test
  @DisplayName("行驶中一律拦下：按住不放只提示一次，松开再按再提示；折返换端途中照常放行")
  void blocksWhileMoving() {
    assertEquals(Decision.MOVING, guard.decide(false, true, false, 100, 100));
    assertEquals(Decision.QUIET, guard.decide(false, true, false, 100, 101), "按住不放");
    assertEquals(Decision.QUIET, guard.decide(false, true, true, 100, 102));
    assertEquals(Decision.MOVING, guard.decide(false, true, true, 110, 110), "重新按下");
    assertEquals(Decision.ALLOW, guard.decide(true, true, true, 120, 120), "换端途中允许离座");
  }

  @Test
  @DisplayName("停稳且没有任务：按一次就放行")
  void allowsAtOnceWithoutTask() {
    assertEquals(Decision.ALLOW, guard.decide(false, false, false, 100, 100));
  }

  @Test
  @DisplayName("停稳且有任务：第一次提示，两秒内再按一次才放行；按住不放不算再按")
  void asksForSecondPressWithTask() {
    assertEquals(Decision.CONFIRM, guard.decide(false, false, true, 100, 100));
    assertEquals(Decision.QUIET, guard.decide(false, false, true, 100, 101), "按住不放");
    assertEquals(Decision.QUIET, guard.decide(false, false, true, 100, 102));
    assertEquals(Decision.ALLOW, guard.decide(false, false, true, 120, 120), "松开再按");
    assertEquals(Decision.ALLOW, guard.decide(false, false, true, 120, 120), "同一 tick 重复来的请求");
  }

  @Test
  @DisplayName("超过两秒再按算重新开始；中途开车会取消上一次提示")
  void confirmationExpires() {
    assertEquals(Decision.CONFIRM, guard.decide(false, false, true, 100, 100));
    assertEquals(Decision.CONFIRM, guard.decide(false, false, true, 200, 200), "超时");
    assertEquals(Decision.MOVING, guard.decide(false, true, true, 210, 210));
    assertEquals(Decision.CONFIRM, guard.decide(false, false, true, 220, 220), "开过车要重新确认");
  }

  @Test
  @DisplayName("收不到潜行键事件时，按离座请求之间的间隔区分两次按键")
  void fallsBackToRequestGapWithoutSneakEvents() {
    assertEquals(Decision.CONFIRM, guard.decide(false, false, true, 0, 100));
    assertEquals(Decision.QUIET, guard.decide(false, false, true, 0, 101));
    assertEquals(Decision.ALLOW, guard.decide(false, false, true, 0, 110));
  }
}
