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

  /** 控制区只能嵌套：同一运营商的两片要么不相交、要么一片整个包含另一片。加成员或移成员后会部分重叠、或两片一模一样的，拒绝并指出冲突的那片；没改库。 */
  @Test
  void zonesMayNestButNeverPartlyOverlap() {
    storage.station(operator, "RVS", "河滨适");
    storage.station(operator, "PTK", "蒲塘桥");
    storage.station(operator, "SPB", "主城湾");
    ControlZone big = create("A");
    for (String code : List.of("PPK", "RVS", "PTK")) {
      assertEquals(Status.ADDED, service.addMember(big, Kind.STATION, code, ANYONE).status());
    }
    ControlZone small = create("A1");
    assertEquals(Status.ADDED, service.addMember(small, Kind.STATION, "RVS", ANYONE).status());
    assertEquals(
        Status.ADDED, service.addMember(small, Kind.STATION, "PPK", ANYONE).status(), "小区整个在大区里");
    ControlZone apart = create("B");
    assertEquals(
        Status.ADDED, service.addMember(apart, Kind.STATION, "SPB", ANYONE).status(), "与别的区不相交");

    ControlZoneService.Result partly = service.addMember(apart, Kind.STATION, "PTK", ANYONE);
    assertEquals(Status.OVERLAP, partly.status(), "B 会一半在 A 里");
    assertEquals(Optional.of(big.id()), partly.conflict().map(ControlZone::id));
    ControlZoneService.Result shrink = service.removeMember(big, Kind.STATION, "RVS", ANYONE);
    assertEquals(Status.OVERLAP, shrink.status(), "A 移走 RVS 后 A1 会露出一半");
    assertEquals(Optional.of(small.id()), shrink.conflict().map(ControlZone::id));
    ControlZone same = create("C");
    service.addMember(same, Kind.STATION, "RVS", ANYONE);
    assertEquals(
        Status.OVERLAP,
        service.addMember(same, Kind.STATION, "PPK", ANYONE).status(),
        "与 A1 范围一模一样");
    assertEquals(3, storage.provider().controlZones().listMembers(big.id()).size(), "被拒的改动没写库");
  }

  /** 两家公司有同代码的运营商：按代码只认先到的那家（与车站目录同一规则），按运营商 ID 各查各的。 */
  @Test
  void directoryResolvesSharedOperatorCodesLikeTheStationDirectory() {
    ControlZone mine = create("NORTH");
    Company other = storage.company("ZZZ");
    Operator twin = storage.operator(other, "SURC", null);
    ControlZone theirs =
        new ControlZoneService(storage.provider())
            .create(twin, "NORTH", "别家北区", Optional.empty(), ANYONE)
            .zone()
            .orElseThrow();
    ControlZoneDirectory directory = new ControlZoneDirectory();
    directory.reload(storage.provider());

    UUID canonical = new StationDirectoryProbe(storage).canonicalOperatorId("SURC").orElseThrow();
    ControlZone expected = canonical.equals(operator.id()) ? mine : theirs;
    assertEquals(expected.id(), directory.find("SURC", "NORTH").orElseThrow().zone().id());
    assertEquals(
        List.of(expected.id()),
        directory.listByOperator("SURC").stream().map(zone -> zone.zone().id()).toList());
    assertEquals(
        List.of(mine.id()),
        directory.listByOperator(operator.id()).stream().map(zone -> zone.zone().id()).toList());
    assertEquals(
        List.of(theirs.id()),
        directory.listByOperator(twin.id()).stream().map(zone -> zone.zone().id()).toList());
  }

  /** 按车站目录的规则找代码对应的运营商：公司、运营商按库里的先后，先到先得。 */
  private record StationDirectoryProbe(TransitTestStorage storage) {
    Optional<UUID> canonicalOperatorId(String code) {
      for (Company company : storage.provider().companies().listAll()) {
        for (Operator candidate : storage.provider().operators().listByCompany(company.id())) {
          if (candidate.code().equalsIgnoreCase(code)) {
            return Optional.of(candidate.id());
          }
        }
      }
      return Optional.empty();
    }
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
