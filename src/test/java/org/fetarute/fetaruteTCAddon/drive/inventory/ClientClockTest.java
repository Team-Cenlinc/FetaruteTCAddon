package org.fetarute.fetaruteTCAddon.drive.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.guard.BuzzerPress;
import org.junit.jupiter.api.Test;

/** 客户端时钟：按客户端发来的 tick 结束包计数，按键之间隔多久只看客户端自己的节奏。 */
class ClientClockTest {

  private final UUID player = UUID.randomUUID();

  @Test
  void countsClientTicks() {
    ClientClock clock = new ClientClock();
    clock.tickEnded(player);
    long start = clock.now(player);
    for (int i = 0; i < 3; i++) {
      clock.tickEnded(player);
    }
    assertEquals(start + 3 * ClientClock.MILLIS_PER_TICK, clock.now(player));
    assertEquals(clock.now(player), clock.now(player), "同一 tick 里读数相同");
  }

  /** 没收到过 tick 结束包时按真实时间；开始计数之后从当时的真实时间往上数，前后读数接得上。 */
  @Test
  void continuesFromRealTime() {
    ClientClock clock = new ClientClock();
    long before = clock.now(player);
    assertTrue(before > 0, "从 1 起，-1 留作没有");
    clock.tickEnded(player);
    long counted = clock.now(player);
    assertTrue(counted >= before + ClientClock.MILLIS_PER_TICK);
    clock.forget(player);
    assertTrue(clock.now(player) >= before, "下线之后回到真实时间");
  }

  /** 按住发车铃时网络卡了：重发包与 tick 结束包一起晚到。卡住期间时钟也停着，按住不会被当成松开；包一下子涌进来时各自的 tick 不变。 */
  @Test
  void lateBurstsKeepAHoldTogether() {
    ClientClock clock = new ClientClock();
    clock.tickEnded(player);
    BuzzerPress press = new BuzzerPress(16, 20);
    List<BuzzerPress.Kind> seen = new ArrayList<>();
    // 客户端第 0–8 tick：按住，每 4 tick 重发一次
    deliver(clock, press, 0, 8);
    // 网络卡了 0.6 秒：服务器每拍照常检查，收不到包
    for (int i = 0; i < 12; i++) {
      press.tick(clock.now(player)).ifPresent(seen::add);
    }
    assertTrue(press.ringing(clock.now(player)), "包没到，时钟也停着");
    // 卡住期间的包一下子到齐，之后松开
    deliver(clock, press, 9, 20);
    for (int i = 0; i < 40; i++) {
      clock.tickEnded(player);
      press.tick(clock.now(player)).ifPresent(seen::add);
    }
    assertEquals(List.of(BuzzerPress.Kind.LONG), seen);
  }

  /** 驾驶员按两下丢弃键，两下隔 0.4 秒：两个包挤在一起到也仍是呼叫，不会并成一下。 */
  @Test
  void bunchedTapsStayApart() {
    ClientClock clock = new ClientClock();
    clock.tickEnded(player);
    BuzzerPress press = new BuzzerPress(16, 20);
    // 两个包一起到：中间主线程没来得及检查
    press.press(clock.now(player));
    for (int i = 0; i < 8; i++) {
      clock.tickEnded(player);
    }
    press.press(clock.now(player));
    List<BuzzerPress.Kind> seen = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      clock.tickEnded(player);
      press.tick(clock.now(player)).ifPresent(seen::add);
    }
    assertEquals(List.of(BuzzerPress.Kind.CALL), seen);
  }

  /** 客户端第 {@code from}–{@code to} tick 发出的包按顺序送到：按住时逢 4 的倍数重发一次，每 tick 末尾一个 tick 结束包。 */
  private void deliver(ClientClock clock, BuzzerPress press, int from, int to) {
    for (int tick = from; tick <= to; tick++) {
      if (tick % 4 == 0) {
        press.press(clock.now(player));
      }
      clock.tickEnded(player);
    }
  }
}
