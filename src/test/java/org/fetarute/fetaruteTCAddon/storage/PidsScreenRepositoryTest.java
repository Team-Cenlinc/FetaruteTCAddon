package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsFacing;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;
import org.fetarute.fetaruteTCAddon.display.pids.screen.repository.PidsScreenRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.MySqlDialect;
import org.fetarute.fetaruteTCAddon.storage.schema.StorageSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 站台屏表与仓库：整条往返、更新、位置唯一、删除。 */
class PidsScreenRepositoryTest {

  private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-00000000000a");

  @TempDir Path dir;
  private TransitTestStorage storage;
  private PidsScreenRepository screens;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    screens = storage.provider().pidsScreens();
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  /** SQLite 按毫秒存时间戳。 */
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MILLIS);
  }

  private static PidsScreen screen(int x) {
    Instant now = now();
    return new PidsScreen(
        UUID.randomUUID(),
        WORLD,
        new PidsScreen.Position(x, 70, -12),
        PidsFacing.SOUTH,
        1,
        3,
        "platform-1x3",
        Optional.empty(),
        Set.of(),
        Set.of(),
        PidsScreen.Appearance.AUTO,
        PidsScreen.Mode.TEST_CARD,
        now,
        now);
  }

  @Test
  void roundTripsEveryField() {
    PidsScreen pending = screens.save(screen(5));
    PidsScreen bound =
        pending
            .withBinding(
                Optional.of(new PidsStationKey("SURC", "HHU")),
                Set.of("4", "1"),
                Set.of("MT"),
                now())
            .withAppearance(PidsScreen.Appearance.DARK, now())
            .withMode(PidsScreen.Mode.LIVE, now());

    screens.save(bound);

    assertEquals(Optional.of(bound), screens.findById(pending.id()));
    assertEquals(List.of(bound), screens.listAll());
    assertEquals(List.of("1", "4"), List.copyOf(bound.platforms()), "站台按字典序");
  }

  @Test
  void unboundScreensKeepAnEmptyStation() {
    PidsScreen pending = screens.save(screen(5));

    PidsScreen loaded = screens.findById(pending.id()).orElseThrow();
    assertTrue(loaded.station().isEmpty());
    assertTrue(loaded.platforms().isEmpty());
  }

  @Test
  void operatorOnlyScreensRoundTrip() {
    PidsScreen pending = screens.save(screen(5));
    PidsScreen bound =
        screens.save(pending.withOperator("surc", Set.of("MT"), pending.updatedAt()));

    PidsScreen loaded = screens.findById(pending.id()).orElseThrow();
    assertEquals(bound, loaded);
    assertTrue(loaded.station().isEmpty());
    assertEquals(Optional.of("SURC"), loaded.operatorCode());
    assertEquals(Set.of("MT"), loaded.lines());
  }

  @Test
  void twoScreensCannotShareAnAnchor() {
    screens.save(screen(5));

    assertThrows(StorageException.class, () -> screens.save(screen(5)));
    assertEquals(1, screens.listAll().size());
  }

  @Test
  void deleteRemovesTheRow() {
    PidsScreen kept = screens.save(screen(1));
    PidsScreen removed = screens.save(screen(9));

    screens.delete(removed.id());
    screens.delete(null);

    assertEquals(List.of(kept), screens.listAll());
  }

  @Test
  void schemaDeclaresTheAnchorUniqueInlineForBothBackends() {
    StorageSchema schema = new StorageSchema("fta_");
    for (List<String> ddl :
        List.of(schema.sqliteStatements(), schema.statements(new MySqlDialect()))) {
      String table =
          ddl.stream()
              .filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS fta_pids_screens ("))
              .findFirst()
              .orElseThrow();
      assertTrue(table.contains("UNIQUE (world_id, x, y, z, facing)"), table);
    }
  }
}
