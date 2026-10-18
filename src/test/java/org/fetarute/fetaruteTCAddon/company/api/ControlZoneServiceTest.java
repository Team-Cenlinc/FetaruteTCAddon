package org.fetarute.fetaruteTCAddon.company.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.fetarute.fetaruteTCAddon.company.api.ControlZoneService.Status;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.ControlZone;
import org.fetarute.fetaruteTCAddon.company.model.ControlZoneMember;
import org.fetarute.fetaruteTCAddon.company.model.ControlZoneMember.Kind;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.storage.TransitTestStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 控制区的增删改：校验、权限、成员核对，以及内存目录的重读。 */
class ControlZoneServiceTest {

  private static final Predicate<UUID> ANYONE = companyId -> true;

  @TempDir Path dir;
  private TransitTestStorage storage;
  private ControlZoneService service;
  private Operator operator;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    service = new ControlZoneService(storage.provider());
    Company surc = storage.company("SURC");
    operator = storage.operator(surc, "SURC", null);
    storage.station(operator, "PPK", "平坪口");
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private ControlZone create(String code) {
    return service
        .create(operator, code, "北区", Optional.of(" North "), ANYONE)
        .zone()
        .orElseThrow();
  }

  @Test
  void createsZonesWithUniqueUpperCaseCodes() {
    ControlZone zone = create("north");

    assertEquals("NORTH", zone.code());
    assertEquals(Optional.of("North"), zone.secondaryName());
    assertEquals(
        Optional.of(zone.id()), service.find(operator, "North").map(ControlZone::id), "查找不分大小写");
    assertEquals(
        Status.DUPLICATE,
        service.create(operator, "NORTH", "北", Optional.empty(), ANYONE).status());
    assertEquals(
        Status.INVALID, service.create(operator, "A:B", "甲", Optional.empty(), ANYONE).status());
    assertEquals(
        Status.INVALID, service.create(operator, "A", " ", Optional.empty(), ANYONE).status());
    assertEquals(
        Status.NO_PERMISSION,
        service.create(operator, "SOUTH", "南区", Optional.empty(), companyId -> false).status());
  }

  @Test
  void membersMustBeKnownStationsAndAreNotDuplicated() {
    ControlZone zone = create("NORTH");

    assertEquals(Status.ADDED, service.addMember(zone, Kind.STATION, "ppk", ANYONE).status());
    assertEquals(
        Status.ALREADY_MEMBER, service.addMember(zone, Kind.STATION, "PPK", ANYONE).status());
    assertEquals(Status.NOT_FOUND, service.addMember(zone, Kind.STATION, "XYZ", ANYONE).status());
    assertEquals(Status.ADDED, service.addMember(zone, Kind.DEPOT, "ppd", ANYONE).status());
    assertEquals(
        List.of(
            new ControlZoneMember(zone.id(), Kind.STATION, "PPK", 0),
            new ControlZoneMember(zone.id(), Kind.DEPOT, "PPD", 1)),
        storage.provider().controlZones().listMembers(zone.id()));

    assertEquals(Status.REMOVED, service.removeMember(zone, Kind.DEPOT, "ppd", ANYONE).status());
    assertEquals(Status.NOT_MEMBER, service.removeMember(zone, Kind.DEPOT, "PPD", ANYONE).status());
    assertEquals(
        Status.NO_PERMISSION,
        service.removeMember(zone, Kind.STATION, "PPK", companyId -> false).status());
  }

  @Test
  void directoryReloadsZonesWithMembersByOperatorCode() {
    ControlZone zone = create("NORTH");
    service.addMember(zone, Kind.STATION, "PPK", ANYONE);
    ControlZoneDirectory directory = new ControlZoneDirectory();
    directory.reload(storage.provider());

    ControlZoneDirectory.Zone found = directory.find("surc", "north").orElseThrow();
    assertEquals("SURC", found.operatorCode());
    assertEquals(
        List.of(new ControlZoneMember(zone.id(), Kind.STATION, "PPK", 0)), found.members());
    assertEquals(List.of(found), directory.listByOperator("SURC"));

    service.rename(zone, "北站场", Optional.empty(), ANYONE);
    directory.reload(storage.provider());
    assertEquals("北站场", directory.find("SURC", "NORTH").orElseThrow().zone().name());

    assertEquals(Status.DELETED, service.delete(zone, ANYONE).status());
    directory.reload(storage.provider());
    assertTrue(directory.find("SURC", "NORTH").isEmpty());
  }
}
