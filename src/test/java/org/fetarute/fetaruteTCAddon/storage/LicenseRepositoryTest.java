package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.license.LicenseRecord;
import org.fetarute.fetaruteTCAddon.drive.license.LicenseRepository;
import org.fetarute.fetaruteTCAddon.drive.license.TrainingRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 驾驶证表：发证往返、重复发证只更新、吊销；练习次数往返。 */
class LicenseRepositoryTest {

  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

  @TempDir Path dir;
  private TransitTestStorage storage;
  private LicenseRepository licenses;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    licenses = storage.provider().licenses();
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  @Test
  void grantListRevoke() {
    UUID alex = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    licenses.grant(new LicenseRecord(alex, "Alex", "free", NOW.minusSeconds(60), "exam"));
    licenses.grant(new LicenseRecord(alex, "Alex", "dispatch", NOW, "exam"));
    licenses.grant(new LicenseRecord(other, "Bo", "free", NOW, "Admin"));

    List<LicenseRecord> held = licenses.listByPlayer(alex);
    assertEquals(List.of("free", "dispatch"), held.stream().map(LicenseRecord::classId).toList());
    assertEquals(NOW.minusSeconds(60), held.get(0).grantedAt());

    licenses.grant(new LicenseRecord(alex, "Alex2", "free", NOW.plusSeconds(5), "Admin"));
    held = licenses.listByPlayer(alex);
    assertEquals(2, held.size(), "重复发证只更新，不重复记");
    LicenseRecord free =
        held.stream().filter(r -> r.classId().equals("free")).findFirst().orElseThrow();
    assertEquals("Admin", free.grantedBy());
    assertEquals("Alex2", free.playerName());

    assertTrue(licenses.revoke(alex, "dispatch"));
    assertFalse(licenses.revoke(alex, "dispatch"));
    assertEquals(
        List.of("free"), licenses.listByPlayer(alex).stream().map(LicenseRecord::classId).toList());
    assertEquals(1, licenses.listByPlayer(other).size());
  }

  @Test
  void trainingRuns() {
    UUID alex = UUID.randomUUID();
    assertTrue(licenses.trainingByPlayer(alex).isEmpty());
    licenses.saveTraining(new TrainingRecord(alex, "Alex", "dispatch", 1, NOW));
    licenses.saveTraining(new TrainingRecord(alex, "Alex", "dispatch", 2, NOW.plusSeconds(30)));
    licenses.saveTraining(new TrainingRecord(alex, "Alex", "pro", 1, NOW));

    List<TrainingRecord> runs = licenses.trainingByPlayer(alex);
    assertEquals(2, runs.size(), "同一级只留一行");
    TrainingRecord dispatch =
        runs.stream().filter(r -> r.classId().equals("dispatch")).findFirst().orElseThrow();
    assertEquals(2, dispatch.runs());
    assertEquals(NOW.plusSeconds(30), dispatch.lastAt());
    assertTrue(licenses.trainingByPlayer(UUID.randomUUID()).isEmpty());
  }
}
