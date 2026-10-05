package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistPlanRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 编组方案表：往返、名字不区分大小写、同一运营商内唯一、不同运营商可同名、随运营商删除。 */
class ConsistPlanRepositoryTest {

  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

  @TempDir Path dir;
  private TransitTestStorage storage;
  private ConsistPlanRepository plans;
  private Operator surc;
  private Operator other;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    plans = storage.provider().consistPlans();
    Company company = storage.company("SURC");
    surc = storage.operator(company, "SURC", null);
    other = storage.operator(company, "SURN", null);
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private ConsistPlan plan(Operator operator, String name, String body) {
    return new ConsistPlan(UUID.randomUUID(), operator.id(), name, body, NOW, NOW);
  }

  @Test
  void roundTripsAndFindsByNameIgnoringCase() {
    ConsistPlan saved = plans.save(plan(surc, "WS 常规", "3 SH_A6\n1 SH_A8 | type=emu"));

    assertEquals(Optional.of(saved), plans.findById(saved.id()));
    assertEquals(Optional.of(saved), plans.findByOperatorAndName(surc.id(), "ws 常规"));
    assertEquals(Optional.empty(), plans.findByOperatorAndName(other.id(), "WS 常规"));
    assertEquals(List.of(saved), plans.listByOperator(surc.id()));

    ConsistPlan edited =
        new ConsistPlan(
            saved.id(), surc.id(), saved.name(), "1 SH_A6", saved.createdAt(), NOW.plusSeconds(5));
    plans.save(edited);
    assertEquals(Optional.of(edited), plans.findById(saved.id()), "同一主键再存一次是更新");
  }

  @Test
  void nameIsUniqueWithinOperatorOnly() {
    plans.save(plan(surc, "Peak", "1 SH_A8"));
    plans.save(plan(other, "Peak", "1 SH_A8"));

    assertThrows(StorageException.class, () -> plans.save(plan(surc, "PEAK", "1 SH_A6")));
    assertEquals(2, plans.listAll().size());
  }

  @Test
  void deletesAndCascadesWithOperator() {
    ConsistPlan kept = plans.save(plan(other, "Peak", "1 SH_A8"));
    ConsistPlan dropped = plans.save(plan(surc, "Peak", "1 SH_A8"));
    plans.delete(kept.id());
    assertTrue(plans.findById(kept.id()).isEmpty());

    storage.provider().operators().delete(surc.id());
    assertTrue(plans.findById(dropped.id()).isEmpty(), "运营商删除时方案一并删除");
  }
}
