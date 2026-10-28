package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.ResolvedPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.ServicePattern;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.TimetablePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.TimetablePlanBinding;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.TimetablePlanRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan.TimetablePlans;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.dialect.MySqlDialect;
import org.fetarute.fetaruteTCAddon.storage.dialect.PostgresDialect;
import org.fetarute.fetaruteTCAddon.storage.schema.StorageSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 运行计划书表与绑定表：往返、唯一约束、改绑、级联删除，以及编表入口 {@link TimetablePlans#resolve}。 */
class TimetablePlanRepositoryTest {

  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
  private static final String WEEKDAY_BODY =
      "[时段]\nWS_Full 06:00-07:00 300" + "\f" + "[定点]\n05:32 WS-2C_FullR\n24:30 WS-9 加开";

  @TempDir Path dir;
  private TransitTestStorage storage;
  private TimetablePlanRepository plans;
  private Operator surc;
  private Operator other;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    plans = storage.provider().timetablePlans();
    Company company = storage.company("SURC");
    surc = storage.operator(company, "SURC", null);
    other = storage.operator(company, "SURN", null);
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private TimetablePlan plan(Operator operator, String code, String body) {
    return new TimetablePlan(UUID.randomUUID(), operator.id(), code, null, body, NOW, NOW);
  }

  @Test
  void roundTripsPlans() {
    TimetablePlan saved = plans.save(plan(surc, "weekday", WEEKDAY_BODY));

    assertEquals("weekday", saved.name(), "未起名时显示名与 code 相同");
    assertEquals(Optional.of(saved), plans.findById(saved.id()));
    assertEquals(Optional.of(saved), plans.findByOperatorAndCode(surc.id(), "weekday"));
    assertEquals(Optional.empty(), plans.findByOperatorAndCode(other.id(), "weekday"));

    TimetablePlan edited =
        new TimetablePlan(
            saved.id(),
            surc.id(),
            "weekday",
            "工作日",
            "[定点]\n06:00 WS-1",
            saved.createdAt(),
            NOW.plusSeconds(5));
    plans.save(edited);
    assertEquals(Optional.of(edited), plans.findById(saved.id()), "同一主键再存一次是更新");
    assertEquals(List.of(edited), plans.listByOperator(surc.id()));
  }

  @Test
  void codeIsUniqueWithinOperatorOnly() {
    plans.save(plan(surc, "weekday", WEEKDAY_BODY));
    plans.save(plan(other, "weekday", WEEKDAY_BODY));

    assertThrows(StorageException.class, () -> plans.save(plan(surc, "weekday", "")));
    TimetablePlan weekend = plans.save(plan(surc, "weekend", WEEKDAY_BODY));
    assertEquals(
        List.of("weekday", "weekend"),
        plans.listByOperator(surc.id()).stream().map(TimetablePlan::code).toList());
    assertEquals(weekend, plans.listByOperator(surc.id()).get(1));
  }

  @Test
  void bindsRebindsAndUnbinds() {
    TimetablePlan weekday = plans.save(plan(surc, "weekday", WEEKDAY_BODY));
    TimetablePlan weekend = plans.save(plan(surc, "weekend", WEEKDAY_BODY));

    plans.bind(new TimetablePlanBinding(surc.id(), "T1", weekday.id()));
    plans.bind(new TimetablePlanBinding(surc.id(), "T2", weekday.id()));
    assertEquals(
        List.of("T1", "T2"),
        plans.listBindingsByPlan(weekday.id()).stream()
            .map(TimetablePlanBinding::timetableCode)
            .toList());

    plans.bind(new TimetablePlanBinding(surc.id(), "T2", weekend.id()));
    assertEquals(
        Optional.of(new TimetablePlanBinding(surc.id(), "T2", weekend.id())),
        plans.findBinding(surc.id(), "T2"),
        "同一编号再绑是改绑");
    assertEquals(2, plans.listBindingsByOperator(surc.id()).size());
    assertTrue(plans.findBinding(other.id(), "T1").isEmpty(), "绑定按运营商隔离");

    assertTrue(plans.unbind(surc.id(), "T1"));
    assertFalse(plans.unbind(surc.id(), "T1"));
    assertTrue(plans.findBinding(surc.id(), "T1").isEmpty());
  }

  @Test
  void deletingPlanOrOperatorCascades() {
    TimetablePlan weekday = plans.save(plan(surc, "weekday", WEEKDAY_BODY));
    TimetablePlan weekend = plans.save(plan(surc, "weekend", WEEKDAY_BODY));
    plans.bind(new TimetablePlanBinding(surc.id(), "T1", weekday.id()));
    plans.bind(new TimetablePlanBinding(surc.id(), "T2", weekend.id()));

    plans.delete(weekday.id());
    assertTrue(plans.findById(weekday.id()).isEmpty());
    assertTrue(plans.findBinding(surc.id(), "T1").isEmpty(), "计划书删除时绑定一并删除");

    storage.provider().operators().delete(surc.id());
    assertTrue(plans.findById(weekend.id()).isEmpty(), "运营商删除时计划书一并删除");
    assertTrue(plans.findBinding(surc.id(), "T2").isEmpty());
  }

  @Test
  void resolveReturnsBoundPattern() {
    TimetablePlan weekday = plans.save(plan(surc, "weekday", WEEKDAY_BODY));
    plans.bind(new TimetablePlanBinding(surc.id(), "T1", weekday.id()));

    ResolvedPlan resolved =
        TimetablePlans.resolve(storage.provider(), surc.id(), " T1 ").orElseThrow();
    assertEquals("weekday", resolved.planCode());
    assertTrue(resolved.ok());
    assertEquals(1, resolved.pattern().bands().size());
    assertEquals(88_200, resolved.pattern().pins().get(1).secondOfDay());

    assertTrue(TimetablePlans.resolve(storage.provider(), surc.id(), "T9").isEmpty());
    assertTrue(TimetablePlans.resolve(storage.provider(), other.id(), "T1").isEmpty());
    assertTrue(TimetablePlans.resolve(storage.provider(), surc.id(), " ").isEmpty());
  }

  @Test
  void resolveReportsBrokenBodyWithNonePattern() {
    TimetablePlan broken = plans.save(plan(surc, "broken", "[时段]\nWS_Full 07:00-06:00 300"));
    plans.bind(new TimetablePlanBinding(surc.id(), "T1", broken.id()));

    ResolvedPlan resolved =
        TimetablePlans.resolve(storage.provider(), surc.id(), "T1").orElseThrow();
    assertFalse(resolved.ok());
    assertEquals(ServicePattern.NONE, resolved.pattern());
    assertTrue(resolved.issues().get(0).startsWith("第 1 页第 2 行："), resolved.issues().toString());
  }

  @Test
  void schemaRendersForEveryDialect() {
    StorageSchema schema = new StorageSchema("fta_");
    for (List<String> ddl :
        List.of(
            schema.sqliteStatements(),
            schema.statements(new MySqlDialect()),
            schema.statements(new PostgresDialect()))) {
      String plansTable = table(ddl, "fta_timetable_plans");
      String bindingsTable = table(ddl, "fta_timetable_plan_bindings");
      assertTrue(plansTable.contains("UNIQUE (operator_id, code)"), plansTable);
      assertTrue(
          bindingsTable.contains("PRIMARY KEY (operator_id, timetable_code)"), bindingsTable);
      assertTrue(bindingsTable.contains("REFERENCES fta_timetable_plans(id) ON DELETE CASCADE"));
    }
  }

  private static String table(List<String> ddl, String name) {
    return ddl.stream()
        .filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS " + name + " "))
        .findFirst()
        .orElseThrow();
  }
}
