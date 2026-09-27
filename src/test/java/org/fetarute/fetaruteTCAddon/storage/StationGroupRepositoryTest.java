package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.model.StationGroup;
import org.fetarute.fetaruteTCAddon.company.model.StationGroupMember;
import org.fetarute.fetaruteTCAddon.company.model.StationTransferType;
import org.fetarute.fetaruteTCAddon.company.repository.StationGroupRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.MySqlDialect;
import org.fetarute.fetaruteTCAddon.storage.schema.StorageSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 车站组表与仓库：一站一组、成员随车站/组/公司删除。 */
class StationGroupRepositoryTest {

  @TempDir Path dir;
  private TransitTestStorage storage;
  private StationGroupRepository groups;
  private Company surc;
  private Station ppk;
  private Station wyb;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    groups = storage.provider().stationGroups();
    surc = storage.company("SURC");
    Operator operator = storage.operator(surc, "SURC", null);
    ppk = storage.station(operator, "PPK", "平坪口");
    wyb = storage.station(operator, "WYB", "湾油埠");
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private StationGroup group(String code) {
    // SQLite 按毫秒存时间戳
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    return groups.save(
        new StationGroup(
            UUID.randomUUID(), surc.id(), code, code + "站", Optional.of("En"), Map.of(), now, now));
  }

  private static StationGroupMember member(StationGroup group, Station station, int sort) {
    return new StationGroupMember(
        group.id(), station.id(), StationTransferType.IN_STATION, Optional.of(60), sort);
  }

  @Test
  void savesGroupsAndMembers() {
    StationGroup g = group("PPK");
    groups.saveMember(member(g, ppk, 1));
    groups.saveMember(member(g, wyb, 0));

    assertEquals(Optional.of(g), groups.findById(g.id()));
    assertEquals(Optional.of(g), groups.findByCompanyAndCode(surc.id(), "PPK"));
    assertEquals(List.of(g), groups.listByCompany(surc.id()));
    List<StationGroupMember> members = groups.listMembers(g.id());
    assertEquals(List.of(wyb.id(), ppk.id()), members.stream().map(m -> m.stationId()).toList());
    assertEquals(Optional.of(60), members.get(0).walkSeconds());
    assertEquals(2, groups.listAllMembers().size());

    // 同组再存一次 = 更新
    groups.saveMember(
        new StationGroupMember(
            g.id(), ppk.id(), StationTransferType.SAME_PLATFORM, Optional.empty(), 1));
    StationGroupMember updated = groups.findMemberByStation(ppk.id()).orElseThrow();
    assertEquals(StationTransferType.SAME_PLATFORM, updated.transferType());
    assertTrue(updated.walkSeconds().isEmpty());

    groups.removeMember(g.id(), ppk.id());
    assertTrue(groups.findMemberByStation(ppk.id()).isEmpty());
  }

  @Test
  void stationCannotJoinTwoGroups() {
    StationGroup a = group("A");
    StationGroup b = group("B");
    groups.saveMember(member(a, ppk, 0));

    assertThrows(StorageException.class, () -> groups.saveMember(member(b, ppk, 0)));
    assertEquals(a.id(), groups.findMemberByStation(ppk.id()).orElseThrow().groupId());
  }

  @Test
  void groupCodeIsUniquePerCompany() {
    group("PPK");
    assertThrows(StorageException.class, () -> group("PPK"));
  }

  @Test
  void deletingStationRemovesItsMembership() {
    StationGroup g = group("PPK");
    groups.saveMember(member(g, ppk, 0));
    groups.saveMember(member(g, wyb, 1));

    storage.provider().stations().delete(ppk.id());

    assertEquals(
        List.of(wyb.id()), groups.listMembers(g.id()).stream().map(m -> m.stationId()).toList());
  }

  @Test
  void deletingGroupRemovesMembers() {
    StationGroup g = group("PPK");
    groups.saveMember(member(g, ppk, 0));

    groups.delete(g.id());

    assertTrue(groups.findById(g.id()).isEmpty());
    assertTrue(groups.listAllMembers().isEmpty());
    // 车站本身不受影响，可以加入新组
    StationGroup next = group("PPK2");
    groups.saveMember(member(next, ppk, 0));
    assertEquals(next.id(), groups.findMemberByStation(ppk.id()).orElseThrow().groupId());
  }

  @Test
  void deletingCompanyCascadesToGroups() {
    StationGroup g = group("PPK");
    groups.saveMember(member(g, ppk, 0));

    storage.provider().companies().delete(surc.id());

    assertTrue(groups.findById(g.id()).isEmpty());
    assertTrue(groups.listAllMembers().isEmpty());
  }

  @Test
  void deletesCleanUpMembershipsWithoutForeignKeyCascade(@TempDir Path noFkDir) throws Exception {
    try (TransitTestStorage plain = TransitTestStorage.openWithoutForeignKeys(noFkDir)) {
      StationGroupRepository repo = plain.provider().stationGroups();
      Company surcCo = plain.company("SURC");
      Company ftaCo = plain.company("FTA");
      Operator surcOp = plain.operator(surcCo, "SURC", null);
      Operator ftaOp = plain.operator(ftaCo, "FTA", null);
      Station a = plain.station(surcOp, "A", "甲");
      Station b = plain.station(surcOp, "B", "乙");
      Station c = plain.station(ftaOp, "C", "丙");
      Station d = plain.station(ftaOp, "D", "丁");
      Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
      StationGroup surcGroup =
          repo.save(
              new StationGroup(
                  UUID.randomUUID(), surcCo.id(), "G1", "一", Optional.empty(), Map.of(), now, now));
      StationGroup ftaGroup =
          repo.save(
              new StationGroup(
                  UUID.randomUUID(), ftaCo.id(), "G2", "二", Optional.empty(), Map.of(), now, now));
      repo.saveMember(member(surcGroup, a, 0));
      repo.saveMember(member(surcGroup, c, 1)); // 别家公司的车站在 SURC 的组里
      repo.saveMember(member(ftaGroup, b, 0)); // SURC 的车站在别家的组里
      repo.saveMember(member(ftaGroup, d, 1));

      plain.provider().stations().delete(d.id());
      assertTrue(repo.findMemberByStation(d.id()).isEmpty(), "删车站");

      plain.provider().operators().delete(ftaOp.id());
      assertTrue(repo.findMemberByStation(c.id()).isEmpty(), "删运营商：其车站的成员一并删除");

      plain.provider().companies().delete(surcCo.id());
      assertTrue(repo.findById(surcGroup.id()).isEmpty(), "删公司：本公司的组");
      assertTrue(repo.findMemberByStation(a.id()).isEmpty(), "删公司：本公司组内成员");
      assertTrue(repo.findMemberByStation(b.id()).isEmpty(), "删公司：本公司车站在别家组里的成员");
      assertTrue(repo.findById(ftaGroup.id()).isPresent(), "别家的组不受影响");

      StationGroup other =
          repo.save(
              new StationGroup(
                  UUID.randomUUID(), ftaCo.id(), "G3", "三", Optional.empty(), Map.of(), now, now));
      repo.saveMember(member(other, plain.station(ftaOp, "E", "戊"), 0));
      repo.delete(other.id());
      assertTrue(repo.listMembers(other.id()).isEmpty(), "删组");
    }
  }

  @Test
  void mySqlWritesNeverUseDuplicateKeyUpsert() throws Exception {
    // MySQL 的 ON DUPLICATE KEY UPDATE 对任何唯一键冲突都会触发：车站加入第二个组、同公司同代码建组都会被悄悄改写成覆盖。
    List<String> statements = new java.util.ArrayList<>();
    javax.sql.DataSource dataSource = org.mockito.Mockito.mock(javax.sql.DataSource.class);
    java.sql.Connection connection = org.mockito.Mockito.mock(java.sql.Connection.class);
    java.sql.PreparedStatement statement =
        org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
    org.mockito.Mockito.when(dataSource.getConnection()).thenReturn(connection);
    org.mockito.Mockito.when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            invocation -> {
              statements.add(invocation.getArgument(0));
              return statement;
            });
    org.mockito.Mockito.when(statement.executeUpdate()).thenReturn(0);
    var repo =
        new org.fetarute.fetaruteTCAddon.storage.jdbc.repository.JdbcStationGroupRepository(
            dataSource, new MySqlDialect(), "fta_", message -> {});
    StationGroup g = group("PPK");
    repo.save(g);
    repo.saveMember(member(g, ppk, 0));

    assertTrue(statements.size() >= 4, statements.toString());
    assertTrue(
        statements.stream().noneMatch(sql -> sql.contains("ON DUPLICATE KEY")),
        statements.toString());
    assertTrue(
        statements.stream().anyMatch(sql -> sql.startsWith("UPDATE fta_station_group_members")));
    assertTrue(
        statements.stream()
            .anyMatch(sql -> sql.startsWith("INSERT INTO fta_station_group_members")));
  }

  @Test
  void schemaDeclaresUniquenessInlineForBothBackends() {
    StorageSchema schema = new StorageSchema("fta_");
    for (List<String> ddl :
        List.of(schema.sqliteStatements(), schema.statements(new MySqlDialect()))) {
      String groupsTable = table(ddl, "fta_station_groups (");
      String membersTable = table(ddl, "fta_station_group_members (");
      assertTrue(groupsTable.contains("UNIQUE (company_id, code)"), groupsTable);
      assertTrue(groupsTable.contains("ON DELETE CASCADE"), groupsTable);
      assertTrue(membersTable.contains("station_id"), membersTable);
      assertTrue(membersTable.contains("NOT NULL UNIQUE"), membersTable);
      assertTrue(membersTable.contains("DEFAULT 'IN_STATION'"), membersTable);
      assertTrue(membersTable.contains("PRIMARY KEY (group_id, station_id)"), membersTable);
    }
    // MySQL 不支持 CREATE INDEX IF NOT EXISTS：新表不依赖单独的索引语句
    assertTrue(
        schema.statements(new MySqlDialect()).stream()
            .noneMatch(
                sql ->
                    sql.startsWith("CREATE")
                        && sql.contains("INDEX")
                        && sql.contains("station_group")));
  }

  private static String table(List<String> ddl, String marker) {
    return ddl.stream()
        .filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS " + marker))
        .findFirst()
        .orElseThrow();
  }
}
