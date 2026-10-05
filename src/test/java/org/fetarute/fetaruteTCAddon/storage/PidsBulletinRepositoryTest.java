package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletin;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.repository.PidsBulletinRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 站台屏公告表与仓库：整条往返（含可空列与多行正文）、更新、删除。 */
class PidsBulletinRepositoryTest {

  @TempDir Path dir;
  private TransitTestStorage storage;
  private PidsBulletinRepository bulletins;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    bulletins = storage.provider().pidsBulletins();
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  /** SQLite 按毫秒存时间戳。 */
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MILLIS);
  }

  private static PidsBulletin bulletin(Optional<Instant> starts, Optional<Instant> ends) {
    Instant now = now();
    return new PidsBulletin(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "SURC",
        Set.of("PPK", "HHU"),
        Set.of("MT"),
        PidsBulletin.Level.IMPORTANT,
        new PidsBulletin.Text("MT 线部分停运", "Part of Line MT suspended"),
        new PidsBulletin.Text("第一行\n第二行", "Line one."),
        starts,
        ends,
        Optional.of(UUID.randomUUID()),
        now,
        now);
  }

  @Test
  void roundTripsEveryField() {
    PidsBulletin timed =
        bulletin(Optional.of(now().plusSeconds(60)), Optional.of(now().plusSeconds(3600)));
    bulletins.save(timed);

    assertEquals(List.of(timed), bulletins.listAll());
  }

  @Test
  void emptyOptionalColumnsStayEmpty() {
    PidsBulletin open =
        new PidsBulletin(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "SURC",
            Set.of(),
            Set.of(),
            PidsBulletin.Level.NORMAL,
            new PidsBulletin.Text("标题", ""),
            new PidsBulletin.Text("", ""),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            now(),
            now());
    bulletins.save(open);

    assertEquals(List.of(open), bulletins.listAll());
  }

  @Test
  void savingAgainUpdatesAndDeleteRemoves() {
    PidsBulletin original = bulletins.save(bulletin(Optional.empty(), Optional.empty()));
    PidsBulletin edited =
        original.edited(
            Set.of("PPK"),
            Set.of(),
            PidsBulletin.Level.NORMAL,
            new PidsBulletin.Text("改过的标题", ""),
            original.body(),
            Optional.empty(),
            Optional.empty(),
            original.updatedAt().plusSeconds(10));
    bulletins.save(edited);

    assertEquals(List.of(edited), bulletins.listAll());
    bulletins.delete(edited.id());
    assertTrue(bulletins.listAll().isEmpty());
  }
}
