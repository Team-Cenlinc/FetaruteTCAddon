package org.fetarute.fetaruteTCAddon.company.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.fetarute.fetaruteTCAddon.company.api.StationGroupService.Status;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.model.StationGroup;
import org.fetarute.fetaruteTCAddon.company.model.StationGroupMember;
import org.fetarute.fetaruteTCAddon.company.model.StationTransferType;
import org.fetarute.fetaruteTCAddon.storage.TransitTestStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 车站组的增删改与权限：组归公司，跨公司加成员要同时能管理两家公司。 */
class StationGroupServiceTest {

  @TempDir Path dir;
  private TransitTestStorage storage;
  private StationGroupService service;
  private Company surc;
  private Company fta;
  private Operator surcOp;
  private Operator ftaOp;
  private Station surcPpk;
  private Station surcWyb;
  private Station ftaPpk;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    service = new StationGroupService(storage.provider());
    surc = storage.company("SURC");
    fta = storage.company("FTA");
    surcOp = storage.operator(surc, "SURC", null);
    ftaOp = storage.operator(fta, "FTA", null);
    surcPpk = storage.station(surcOp, "PPK", "平坪口");
    surcWyb = storage.station(surcOp, "WYB", "湾油埠");
    ftaPpk = storage.station(ftaOp, "PPK", "坪洲");
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private static Predicate<UUID> manages(Company... companies) {
    Set<UUID> ids = new java.util.HashSet<>();
    for (Company company : companies) {
      ids.add(company.id());
    }
    return ids::contains;
  }

  private StationGroup create(String code) {
    StationGroupService.Result result =
        service.create(surc, code, "平坪口", Optional.of("Ping Ping Hau"), manages(surc));
    assertEquals(Status.CREATED, result.status());
    return result.group().orElseThrow();
  }

  @Test
  void createsGroupOwnedByCompanyAndRejectsDuplicateCode() {
    StationGroupService.Result created =
        service.create(surc, " PPK ", " 平坪口 ", Optional.of("Ping Ping Hau"), manages(surc));
    assertEquals(Status.CREATED, created.status());
    StationGroup group = created.group().orElseThrow();
    assertEquals(surc.id(), group.companyId());
    assertEquals("PPK", group.code());
    assertEquals(Optional.of("Ping Ping Hau"), group.secondaryName());
    assertEquals(StationGroupChange.Kind.CREATED, created.change().orElseThrow().kind());
    assertEquals(group.id(), service.findGroup("ppk").unique().orElseThrow().id());

    assertEquals(
        Status.DUPLICATE,
        service.create(surc, "ppk", "重复", Optional.empty(), manages(surc)).status(),
        "组代码在公司内不区分大小写唯一");
    assertEquals(
        Status.NO_PERMISSION,
        service.create(surc, "X", "无权", Optional.empty(), manages(fta)).status());
    assertEquals(
        Status.INVALID,
        service.create(surc, "A:B", "冒号", Optional.empty(), manages(surc)).status());
  }

  @Test
  void addsUpdatesAndRemovesMembers() {
    StationGroup group = create("PPK");

    StationGroupService.Result added =
        service.addMember(
            group, surcPpk, StationTransferType.IN_STATION, Optional.empty(), manages(surc));
    assertEquals(Status.ADDED, added.status());
    assertEquals(0, added.member().orElseThrow().sortOrder());
    assertEquals(StationGroupChange.Kind.MEMBER_ADDED, added.change().orElseThrow().kind());

    StationGroupService.Result second =
        service.addMember(
            group, surcWyb, StationTransferType.OUT_OF_STATION, Optional.of(120), manages(surc));
    assertEquals(1, second.member().orElseThrow().sortOrder(), "新成员排在已有成员之后");

    StationGroupService.Result updated =
        service.addMember(
            group, surcPpk, StationTransferType.SAME_PLATFORM, Optional.of(10), manages(surc));
    assertEquals(Status.UPDATED, updated.status());
    StationGroupMember member = updated.member().orElseThrow();
    assertEquals(StationTransferType.SAME_PLATFORM, member.transferType());
    assertEquals(0, member.sortOrder(), "更新保留原排序");

    StationGroupService.Result removed = service.removeMember(group, surcWyb, manages(surc));
    assertEquals(Status.REMOVED, removed.status());
    assertEquals(Status.NOT_MEMBER, service.removeMember(group, surcWyb, manages(surc)).status());
    assertEquals(
        List.of(surcPpk.id()),
        storage.provider().stationGroups().listMembers(group.id()).stream()
            .map(StationGroupMember::stationId)
            .toList());
  }

  @Test
  void stationCannotJoinASecondGroup() {
    StationGroup first = create("PPK");
    StationGroup second = create("PPK2");
    service.addMember(
        first, surcPpk, StationTransferType.IN_STATION, Optional.empty(), manages(surc));

    StationGroupService.Result result =
        service.addMember(
            second, surcPpk, StationTransferType.IN_STATION, Optional.empty(), manages(surc));

    assertEquals(Status.IN_OTHER_GROUP, result.status());
    assertEquals(first.id(), result.group().orElseThrow().id(), "报告车站已在的组");
    assertTrue(result.change().isEmpty());
  }

  @Test
  void crossCompanyAddRequiresManagingBothCompanies() {
    StationGroup group = create("PPK");

    StationGroupService.Result denied =
        service.addMember(
            group, ftaPpk, StationTransferType.OUT_OF_STATION, Optional.of(90), manages(surc));
    assertEquals(Status.CROSS_COMPANY_DENIED, denied.status());
    assertEquals(Optional.of(fta.id()), denied.stationCompanyId());
    assertTrue(storage.provider().stationGroups().findMemberByStation(ftaPpk.id()).isEmpty());

    // 只能管理车站那一方也不行：组不归它
    assertEquals(
        Status.NO_PERMISSION,
        service
            .addMember(
                group, ftaPpk, StationTransferType.OUT_OF_STATION, Optional.empty(), manages(fta))
            .status());

    StationGroupService.Result allowed =
        service.addMember(
            group, ftaPpk, StationTransferType.OUT_OF_STATION, Optional.of(90), manages(surc, fta));
    assertEquals(Status.ADDED, allowed.status());
  }

  @Test
  void removingRequiresManagingTheGroupCompany() {
    StationGroup group = create("PPK");
    service.addMember(
        group, ftaPpk, StationTransferType.OUT_OF_STATION, Optional.empty(), manages(surc, fta));

    assertEquals(
        Status.NO_PERMISSION,
        service.removeMember(group, ftaPpk, manages(fta)).status(),
        "只能管理车站所属公司不够：编辑需要组所属公司的权限");
    assertEquals(Status.REMOVED, service.removeMember(group, ftaPpk, manages(surc)).status());
  }

  @Test
  void deleteRequiresGroupCompanyAndRemovesMembers() {
    StationGroup group = create("PPK");
    service.addMember(
        group, surcPpk, StationTransferType.IN_STATION, Optional.empty(), manages(surc));

    assertEquals(Status.NO_PERMISSION, service.delete(group, manages(fta)).status());
    StationGroupService.Result deleted = service.delete(group, manages(surc));
    assertEquals(Status.DELETED, deleted.status());
    assertEquals(StationGroupChange.Kind.DELETED, deleted.change().orElseThrow().kind());
    assertTrue(storage.provider().stationGroups().listAllMembers().isEmpty());
  }

  @Test
  void lookupsResolveQualifiedAndAmbiguousCodes() {
    create("PPK");
    StationGroupService.Result ftaGroup =
        service.create(fta, "PPK", "坪洲", Optional.empty(), manages(fta));
    assertEquals(Status.CREATED, ftaGroup.status());

    assertTrue(service.findGroup("ppk").ambiguous(), "两家公司都有 PPK");
    assertEquals(fta.id(), service.findGroup("FTA:PPK").unique().orElseThrow().companyId());
    assertTrue(service.findGroup("NONE").unique().isEmpty());

    // 运营商：优先组所属公司，其次全局唯一
    storage.operator(fta, "SURC", null);
    assertEquals(
        surcOp.id(),
        service.findOperator("surc", Optional.of(surc.id())).unique().orElseThrow().id());
    assertTrue(service.findOperator("SURC", Optional.empty()).ambiguous());
    assertEquals(
        ftaOp.id(),
        service.findOperator("FTA", Optional.of(surc.id())).unique().orElseThrow().id());
    assertEquals(
        fta.id(),
        service.findOperator("FTA:SURC", Optional.empty()).unique().orElseThrow().companyId());

    // 代码未命中时接受 UUID
    StationGroup surcGroup = service.findGroup("SURC:PPK").unique().orElseThrow();
    assertEquals(
        surcGroup.id(), service.findGroup(surcGroup.id().toString()).unique().orElseThrow().id());
    assertEquals(
        ftaOp.id(),
        service.findOperator(ftaOp.id().toString(), Optional.empty()).unique().orElseThrow().id());
    assertTrue(service.findGroup(UUID.randomUUID().toString()).unique().isEmpty());
  }
}
