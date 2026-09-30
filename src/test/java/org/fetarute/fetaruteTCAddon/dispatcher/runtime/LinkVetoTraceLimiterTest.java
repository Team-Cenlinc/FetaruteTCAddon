package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * 被否决的联挂不会“完成”：叠在一起的两个编组每个物理 tick 都会再撞一次，事件以每秒 20 次的频率重复触发。
 * 限流器让同一对列车在窗口内只留一条证据，窗口过后带上被压掉的次数；持续叠放时窗口按倍数退避，直到上限。
 */
class LinkVetoTraceLimiterTest {

  private static final long WINDOW = 30_000L;
  private static final long MAX_WINDOW = 3_600_000L;

  private static LinkVetoTraceLimiter limiter(int maxKeys) {
    return new LinkVetoTraceLimiter(WINDOW, MAX_WINDOW, maxKeys);
  }

  @Test
  void firstSightOfAPairIsAdmittedWithNothingSuppressed() {
    assertEquals(OptionalInt.of(0), limiter(16).admit("A|B", 1_000L));
  }

  @Test
  void repeatsInsideTheWindowAreSuppressed() {
    LinkVetoTraceLimiter limiter = limiter(16);
    limiter.admit("A|B", 1_000L);

    assertTrue(limiter.admit("A|B", 1_050L).isEmpty());
    assertTrue(limiter.admit("A|B", 30_999L).isEmpty());
  }

  @Test
  void afterTheWindowThePairIsAdmittedAgainCarryingTheSuppressedCount() {
    LinkVetoTraceLimiter limiter = limiter(16);
    limiter.admit("A|B", 1_000L);
    limiter.admit("A|B", 1_050L);
    limiter.admit("A|B", 1_100L);

    assertEquals(OptionalInt.of(2), limiter.admit("A|B", 31_000L));
  }

  /** 持续叠放时窗口逐次放大（×4），避免几小时里每对每 30 秒一条 WARN。 */
  @Test
  void aPairThatKeepsCollidingBacksOffGeometricallyUpToTheCap() {
    LinkVetoTraceLimiter limiter = limiter(16);
    assertEquals(OptionalInt.of(0), limiter.admit("A|B", 0L)); // 窗口 30s
    assertEquals(OptionalInt.of(0), limiter.admit("A|B", 30_000L)); // 窗口 120s
    assertTrue(limiter.admit("A|B", 149_999L).isEmpty());
    assertEquals(OptionalInt.of(1), limiter.admit("A|B", 150_000L)); // 窗口 480s
    assertTrue(limiter.admit("A|B", 629_999L).isEmpty());
    assertEquals(OptionalInt.of(1), limiter.admit("A|B", 630_000L)); // 窗口 1920s

    assertTrue(limiter.admit("A|B", 2_549_999L).isEmpty());
    // 1920s 之后本该是 7680s，被封顶为 3600s，并且此后一直是 3600s。
    assertEquals(OptionalInt.of(1), limiter.admit("A|B", 2_550_000L));
    assertTrue(limiter.admit("A|B", 6_149_999L).isEmpty());
    assertEquals(OptionalInt.of(1), limiter.admit("A|B", 6_150_000L));
    assertTrue(limiter.admit("A|B", 9_749_999L).isEmpty());
    assertTrue(limiter.admit("A|B", 9_750_000L).isPresent());
  }

  @Test
  void differentPairsDoNotShareAWindow() {
    LinkVetoTraceLimiter limiter = limiter(16);
    limiter.admit("A|B", 1_000L);

    assertEquals(OptionalInt.of(0), limiter.admit("A|C", 1_010L));
  }

  @Test
  void memoryIsBoundedByForgettingTheLeastRecentlySeenPair() {
    LinkVetoTraceLimiter limiter = limiter(2);
    limiter.admit("A|B", 1_000L);
    limiter.admit("A|C", 1_001L);
    limiter.admit("A|D", 1_002L);

    // A|B 已被挤出，视作新的一对；A|D 仍在窗口内。
    assertEquals(OptionalInt.of(0), limiter.admit("A|B", 1_003L));
    assertTrue(limiter.admit("A|D", 1_004L).isEmpty());
  }

  /** 墙钟被回拨时不能把证据压制到时钟追平（回拨 1 小时就压制 1 小时）：视为新窗口重新放行。 */
  @Test
  void aClockThatJumpsBackwardsStartsAFreshWindowInsteadOfSilencingTheTrace() {
    LinkVetoTraceLimiter limiter = limiter(16);
    limiter.admit("A|B", 10_000_000L);
    assertTrue(limiter.admit("A|B", 10_000_100L).isEmpty());

    assertTrue(limiter.admit("A|B", 5_000_000L).isPresent());
    assertTrue(limiter.admit("A|B", 5_000_100L).isEmpty());
  }
}
