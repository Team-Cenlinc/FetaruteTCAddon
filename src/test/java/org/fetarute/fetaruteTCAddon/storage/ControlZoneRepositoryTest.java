package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.ControlZone;
import org.fetarute.fetaruteTCAddon.company.model.ControlZoneMember;
import org.fetarute.fetaruteTCAddon.company.model.ControlZoneMember.Kind;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.repository.ControlZoneRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 控制区表与仓库：代码在运营商内唯一、成员按排序读出、随控制区/运营商/公司删除。 */
class ControlZoneRepositoryTest {

  @TempDir Path dir;
  private TransitTestStorage storage;
  private ControlZoneRepository zones;
  private Company surc;
  private Operator operator;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    zones = storage.provider().controlZones();
    surc = storage.company("SURC");
    operator = storage.operator(surc, "SURC", null);
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private ControlZone zone(ControlZoneRepository repo, Operator owner, String code) {
    // SQLite 按毫秒存时间戳
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    return repo.save(
        new ControlZone(
            UUID.randomUUID(),
            owner.id(),
            code,
            code + "区",
            Optional.of("En"),
            Map.of(),
            now,
            now));
  }

  @Test
  void savesZonesAndMembersInOrder() {
    ControlZone north = zone(zones, operator, "NORTH");
    zones.saveMember(new ControlZoneMember(north.id(), Kind.STATION, "ppk", 1));
    zones.saveMember(new ControlZoneMember(north.id(), Kind.DEPOT, "PPD", 0));

    assertEquals(Optional.of(north), zones.findById(north.id()));
    assertEquals(Optional.of(north), zones.findByOperatorAndCode(operator.id(), "NORTH"));
    assertEquals(List.of(north), zones.listByOperator(operator.id()));
    assertEquals(
        List.of(
            new ControlZoneMember(north.id(), Kind.DEPOT, "PPD", 0),
            new ControlZoneMember(north.id(), Kind.STATION, "PPK", 1)),
        zones.listMembers(north.id()),
        "按排序读出，代码大写");

    ControlZone renamed =
        new ControlZone(
            north.id(),
            operator.id(),
            "NORTH",
            "北区",
            Optional.empty(),
            Map.of(),
            north.createdAt(),
            north.updatedAt());
    zones.save(renamed);
    assertEquals(Optional.of(renamed), zones.findById(north.id()), "同 ID 再存一次为更新");

    zones.removeMember(north.id(), Kind.DEPOT, "PPD");
    assertEquals(1, zones.listAllMembers().size());
  }

  @Test
  void codeIsUniquePerOperator() {
    zone(zones, operator, "NORTH");
    assertThrows(StorageException.class, () -> zone(zones, operator, "NORTH"));
    zone(zones, storage.operator(surc, "SURN", null), "NORTH");
  }

  @Test
  void deletingZoneRemovesMembers() {
    ControlZone north = zone(zones, operator, "NORTH");
    zones.saveMember(new ControlZoneMember(north.id(), Kind.STATION, "PPK", 0));

    zones.delete(north.id());

    assertTrue(zones.findById(north.id()).isEmpty());
    assertTrue(zones.listAllMembers().isEmpty());
  }

  @Test
  void deletesCleanUpZonesWithoutForeignKeyCascade(@TempDir Path noFkDir) throws Exception {
    try (TransitTestStorage plain = TransitTestStorage.openWithoutForeignKeys(noFkDir)) {
      ControlZoneRepository repo = plain.provider().controlZones();
      Company surcCo = plain.company("SURC");
      Company ftaCo = plain.company("FTA");
      Operator surcOp = plain.operator(surcCo, "SURC", null);
      Operator ftaOp = plain.operator(ftaCo, "FTA", null);
      ControlZone a = zone(repo, surcOp, "A");
      ControlZone b = zone(repo, ftaOp, "B");
      repo.saveMember(new ControlZoneMember(a.id(), Kind.STATION, "X", 0));
      repo.saveMember(new ControlZoneMember(b.id(), Kind.STATION, "Y", 0));

      plain.provider().operators().delete(ftaOp.id());
      assertTrue(repo.findById(b.id()).isEmpty(), "删运营商：其控制区");
      assertTrue(repo.listMembers(b.id()).isEmpty(), "删运营商：其控制区成员");
      assertTrue(repo.findById(a.id()).isPresent(), "别家的控制区不受影响");

      plain.provider().companies().delete(surcCo.id());
      assertTrue(repo.findById(a.id()).isEmpty(), "删公司：其运营商的控制区");
      assertTrue(repo.listAllMembers().isEmpty(), "删公司：其运营商的控制区成员");
    }
  }
}
