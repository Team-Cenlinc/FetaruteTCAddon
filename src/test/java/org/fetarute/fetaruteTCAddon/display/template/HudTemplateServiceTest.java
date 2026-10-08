package org.fetarute.fetaruteTCAddon.display.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.CompanyStatus;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.company.repository.LineRepository;
import org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository;
import org.fetarute.fetaruteTCAddon.display.template.repository.HudLineBindingRepository;
import org.fetarute.fetaruteTCAddon.display.template.repository.HudTemplateRepository;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

class HudTemplateServiceTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  /** 插件日志：用字段持有，否则 Logger 只被弱引用，挂上的 Handler 可能随它一起被回收。 */
  private static final java.util.logging.Logger PLUGIN_LOGGER =
      java.util.logging.Logger.getLogger("FetaruteTCAddon");

  /** 重载读库中途出错：沿用上一次的缓存，HUD 不能因此失去模板。 */
  @Test
  void failedReloadKeepsPreviousTemplates() {
    UUID companyId = UUID.randomUUID();
    UUID operatorId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID templateId = UUID.randomUUID();
    Company company =
        new Company(
            companyId,
            "CO",
            "Company",
            Optional.empty(),
            UUID.randomUUID(),
            CompanyStatus.ACTIVE,
            0L,
            Map.of(),
            NOW,
            NOW);
    Operator operator =
        new Operator(
            operatorId,
            "OP",
            companyId,
            "Operator",
            Optional.empty(),
            Optional.empty(),
            0,
            Optional.empty(),
            Map.of(),
            NOW,
            NOW);
    Line line =
        new Line(
            lineId,
            "L1",
            operatorId,
            "Line",
            Optional.empty(),
            LineServiceType.METRO,
            Optional.empty(),
            LineStatus.ACTIVE,
            Optional.empty(),
            Map.of(),
            NOW,
            NOW);
    HudTemplate template =
        new HudTemplate(
            templateId, companyId, HudTemplateType.BOSSBAR, "default", "<line>", NOW, NOW);

    CompanyRepository companies = mock(CompanyRepository.class);
    OperatorRepository operators = mock(OperatorRepository.class);
    LineRepository lines = mock(LineRepository.class);
    HudTemplateRepository templates = mock(HudTemplateRepository.class);
    HudLineBindingRepository bindings = mock(HudLineBindingRepository.class);
    when(companies.listAll()).thenReturn(List.of(company));
    when(operators.listByCompany(companyId)).thenReturn(List.of(operator));
    when(lines.listByOperator(operatorId)).thenReturn(List.of(line));
    when(templates.listByCompany(companyId)).thenReturn(List.of(template));
    when(bindings.listAll())
        .thenReturn(
            List.of(
                new HudLineBindingRepository.LineBinding(
                    lineId, HudTemplateType.BOSSBAR, templateId, NOW)))
        .thenThrow(new StorageException("读库失败"));

    StorageProvider provider = mock(StorageProvider.class);
    when(provider.companies()).thenReturn(companies);
    when(provider.operators()).thenReturn(operators);
    when(provider.lines()).thenReturn(lines);
    when(provider.hudTemplates()).thenReturn(templates);
    when(provider.hudLineBindings()).thenReturn(bindings);
    StorageManager storageManager = mock(StorageManager.class);
    when(storageManager.isReady()).thenReturn(true);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    HudTemplateService service = new HudTemplateService(storageManager, message -> {});

    service.reload();
    assertEquals(
        Optional.of("<line>"), service.resolveTemplate(HudTemplateType.BOSSBAR, "op", "l1"));

    // 第二次重载：线路与模板读到了，绑定读库出错。
    List<java.util.logging.LogRecord> warnings = new ArrayList<>();
    java.util.logging.Handler capture =
        new java.util.logging.Handler() {
          @Override
          public void publish(java.util.logging.LogRecord record) {
            warnings.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    PLUGIN_LOGGER.addHandler(capture);
    try {
      service.reload();
    } finally {
      PLUGIN_LOGGER.removeHandler(capture);
    }

    assertEquals(
        Optional.of("<line>"), service.resolveTemplate(HudTemplateType.BOSSBAR, "op", "l1"));
    assertEquals(Optional.of(template), service.findTemplate(templateId));
    assertTrue(service.resolveLineInfo("OP", "L1").isPresent());
    assertTrue(
        warnings.stream()
            .anyMatch(
                record ->
                    record.getLevel() == java.util.logging.Level.WARNING
                        && record.getMessage().contains("沿用上一次的缓存")));
  }
}
