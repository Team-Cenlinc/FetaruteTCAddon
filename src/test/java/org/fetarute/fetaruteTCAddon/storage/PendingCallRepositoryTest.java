package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.call.PendingCallRecord;
import org.fetarute.fetaruteTCAddon.call.repository.PendingCallRepository;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 未派出叫车表与仓库：整条往返（含可空的预计到站与空的屏幕站台）、按编号覆盖、删除。 */
class PendingCallRepositoryTest {

  @TempDir Path dir;
  private TransitTestStorage storage;
  private PendingCallRepository calls;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    calls = storage.provider().pendingCalls();
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  /** SQLite 按毫秒存时间戳。 */
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MILLIS);
  }

  private static PendingCallRecord call(Set<String> platforms, OptionalInt eta) {
    return new PendingCallRecord(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new PidsStationKey("SURC", "PPK"),
        platforms,
        UUID.randomUUID() + "|LOCAL|SURC:NTA|2",
        UUID.randomUUID(),
        now(),
        eta);
  }

  @Test
  void roundTripsEveryField() {
    PendingCallRecord withEta = call(Set.of("2"), OptionalInt.of(185));
    PendingCallRecord bare = call(Set.of(), OptionalInt.empty());
    calls.save(withEta);
    calls.save(bare);

    assertEquals(Set.of(withEta, bare), Set.copyOf(calls.listAll()));
  }

  @Test
  void savingAgainReplacesAndDeleteRemoves() {
    PendingCallRecord original = call(Set.of("1"), OptionalInt.of(60));
    calls.save(original);
    PendingCallRecord replaced =
        new PendingCallRecord(
            original.id(),
            original.playerId(),
            original.station(),
            original.screenPlatforms(),
            original.directionKey(),
            original.lineId(),
            original.createdAt(),
            OptionalInt.of(240));
    calls.save(replaced);

    assertEquals(List.of(replaced), calls.listAll());
    calls.delete(original.id());
    assertTrue(calls.listAll().isEmpty());
  }
}
