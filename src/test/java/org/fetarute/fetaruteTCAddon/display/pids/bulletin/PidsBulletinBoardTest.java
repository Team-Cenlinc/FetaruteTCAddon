package org.fetarute.fetaruteTCAddon.display.pids.bulletin;

import static org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletinFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.junit.jupiter.api.Test;

/** 公告表：按屏幕取生效中的公告、轮播顺序、编号前缀。 */
class PidsBulletinBoardTest {

  private static final PidsStationKey PPK = new PidsStationKey("SURC", "PPK");

  /** 重要的排在前面；未开始、已结束与范围外的不列；只在有公告限定了线路时才去查屏幕线路，且只查一次。 */
  @Test
  void activeListsImportantFirstAndLooksUpLinesLazily() {
    PidsBulletinBoard board = new PidsBulletinBoard();
    PidsBulletin exit = PidsBulletinFixtures.exitClosed();
    PidsBulletin suspended = PidsBulletinFixtures.lineSuspended();
    PidsBulletin ended =
        PidsBulletinFixtures.bulletin(
            Set.of(),
            Set.of("MT"),
            PidsBulletin.Level.NORMAL,
            new PidsBulletin.Text("已结束", ""),
            new PidsBulletin.Text("", ""),
            Optional.empty(),
            Optional.of(NOW.minusSeconds(1)));
    board.replaceAll(List.of(exit, suspended, ended));
    AtomicInteger lookups = new AtomicInteger();

    List<PidsBulletin> active =
        board.active(
            PPK,
            () -> {
              lookups.incrementAndGet();
              return Set.of("MT");
            },
            NOW);

    assertEquals(List.of(suspended, exit), active);
    assertEquals(1, lookups.get());

    board.remove(suspended.id());
    lookups.set(0);
    assertEquals(List.of(exit), board.active(PPK, () -> Set.of("MT"), NOW));
    board.active(
        PPK,
        () -> {
          lookups.incrementAndGet();
          return Set.of();
        },
        NOW);
    assertEquals(0, lookups.get(), "没有公告限定线路时不查");
  }

  @Test
  void findsByIdPrefix() {
    PidsBulletinBoard board = new PidsBulletinBoard();
    PidsBulletin exit = PidsBulletinFixtures.exitClosed();
    board.put(exit);
    String id = exit.id().toString();

    assertEquals(List.of(exit), board.matchIdOrPrefix(id.substring(0, 8).toUpperCase(Locale.ROOT)));
    assertEquals(List.of(exit), board.matchIdOrPrefix(id));
    assertTrue(board.matchIdOrPrefix(id.substring(0, 3)).isEmpty(), "前缀至少 4 位");
  }
}
