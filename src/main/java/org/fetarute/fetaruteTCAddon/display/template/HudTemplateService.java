package org.fetarute.fetaruteTCAddon.display.template;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.display.template.repository.HudLineBindingRepository;
import org.fetarute.fetaruteTCAddon.display.template.repository.HudTemplateRepository;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * HUD 模板服务：缓存模板与绑定，并提供按线路解析模板的能力。
 *
 * <p>展示层仅从该服务读取模板文本，不直接访问数据库。
 */
public final class HudTemplateService {

  private final StorageManager storageManager;
  private final Consumer<String> debugLogger;

  private final Map<TemplateKey, HudTemplate> templateByKey = new HashMap<>();
  private final Map<UUID, HudTemplate> templateById = new HashMap<>();
  private final Map<LineKey, UUID> lineByCode = new HashMap<>();
  private final Map<LineKey, LineInfo> lineInfoByCode = new HashMap<>();
  private final Map<LineBindingKey, UUID> lineBindings = new HashMap<>();

  public HudTemplateService(StorageManager storageManager, Consumer<String> debugLogger) {
    this.storageManager = storageManager;
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
  }

  /** 重载缓存（模板/绑定/线路映射）。 */
  public void reload() {
    clear();
    if (storageManager == null || !storageManager.isReady()) {
      return;
    }
    storageManager
        .provider()
        .ifPresent(
            provider -> {
              loadLines(provider);
              loadTemplates(provider);
              loadBindings(provider);
            });
  }

  /**
   * 按运营商代码 + 线路代码解析绑定的模板（代码不区分大小写）。
   *
   * <p>直通运转换线后，HUD 按列车当前所属的线路取模板，而不是交路本身的线路。
   *
   * @param type 模板类型
   * @param operator 运营商代码
   * @param line 线路代码
   * @return 模板文本；线路未绑定该类型模板时为空
   */
  public Optional<String> resolveTemplate(HudTemplateType type, String operator, String line) {
    if (type == null || operator == null || operator.isBlank() || line == null || line.isBlank()) {
      return Optional.empty();
    }
    UUID lineId = lineByCode.get(new LineKey(operator, line));
    if (lineId == null) {
      return Optional.empty();
    }
    UUID templateId = lineBindings.get(new LineBindingKey(lineId, type));
    if (templateId == null) {
      return Optional.empty();
    }
    HudTemplate template = templateById.get(templateId);
    if (template == null) {
      return Optional.empty();
    }
    return Optional.of(template.content());
  }

  /**
   * 按列车当前所属线路解析绑定的模板（直通运转换线后为新线路）。
   *
   * @param type 模板类型
   * @param line 当前线路；为空时返回空
   * @return 模板文本；线路未绑定该类型模板时为空
   */
  public Optional<String> resolveTemplateForLine(
      HudTemplateType type, Optional<RouteLineChanges.LineRef> line) {
    if (line == null || line.isEmpty()) {
      return Optional.empty();
    }
    return resolveTemplate(type, line.get().operatorCode(), line.get().lineCode());
  }

  /** 按公司 + 类型 + 名称检索模板。 */
  public Optional<HudTemplate> findTemplate(UUID companyId, HudTemplateType type, String name) {
    if (companyId == null || type == null || name == null || name.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(templateByKey.get(new TemplateKey(companyId, type, name)));
  }

  /** 按模板 ID 检索模板。 */
  public Optional<HudTemplate> findTemplate(UUID templateId) {
    if (templateId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(templateById.get(templateId));
  }

  /** 清空本地缓存。 */
  public void clear() {
    templateByKey.clear();
    templateById.clear();
    lineByCode.clear();
    lineInfoByCode.clear();
    lineBindings.clear();
  }

  private void loadLines(StorageProvider provider) {
    try {
      for (Company company : provider.companies().listAll()) {
        if (company == null) {
          continue;
        }
        for (Operator operator : provider.operators().listByCompany(company.id())) {
          if (operator == null) {
            continue;
          }
          for (Line line : provider.lines().listByOperator(operator.id())) {
            if (line == null) {
              continue;
            }
            LineKey key = new LineKey(operator.code(), line.code());
            lineByCode.put(key, line.id());
            lineInfoByCode.put(
                key,
                new LineInfo(
                    line.code(),
                    line.name(),
                    line.secondaryName().orElse(""),
                    line.color().orElse("")));
          }
        }
      }
    } catch (Exception ex) {
      debugLogger.accept("HUD 模板线路映射加载失败: " + ex.getMessage());
    }
  }

  private void loadTemplates(StorageProvider provider) {
    HudTemplateRepository repo = provider.hudTemplates();
    try {
      for (Company company : provider.companies().listAll()) {
        if (company == null) {
          continue;
        }
        for (HudTemplate template : repo.listByCompany(company.id())) {
          if (template == null) {
            continue;
          }
          templateById.put(template.id(), template);
          templateByKey.put(
              new TemplateKey(template.companyId(), template.type(), template.name()), template);
        }
      }
    } catch (Exception ex) {
      debugLogger.accept("HUD 模板缓存加载失败: " + ex.getMessage());
    }
  }

  private void loadBindings(StorageProvider provider) {
    HudLineBindingRepository repo = provider.hudLineBindings();
    try {
      for (HudLineBindingRepository.LineBinding binding : repo.listAll()) {
        if (binding == null) {
          continue;
        }
        lineBindings.put(
            new LineBindingKey(binding.lineId(), binding.type()), binding.templateId());
      }
    } catch (Exception ex) {
      debugLogger.accept("HUD 模板绑定加载失败: " + ex.getMessage());
    }
  }

  /**
   * 按运营商代码 + 线路代码解析线路元信息（代码不区分大小写）。
   *
   * @param operator 运营商代码
   * @param line 线路代码
   * @return 线路元信息；线路不存在时为空
   */
  public Optional<LineInfo> resolveLineInfo(String operator, String line) {
    if (operator == null || operator.isBlank() || line == null || line.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(lineInfoByCode.get(new LineKey(operator, line)));
  }

  private record TemplateKey(UUID companyId, HudTemplateType type, String name) {
    private TemplateKey {
      Objects.requireNonNull(companyId, "companyId");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(name, "name");
    }
  }

  /** 运营商 + 线路代码（统一小写：直通指令里写的代码大小写可能与主数据不同）。 */
  private record LineKey(String operator, String line) {
    private LineKey {
      operator = Objects.requireNonNull(operator, "operator").trim().toLowerCase(Locale.ROOT);
      line = Objects.requireNonNull(line, "line").trim().toLowerCase(Locale.ROOT);
    }
  }

  private record LineBindingKey(UUID lineId, HudTemplateType type) {
    private LineBindingKey {
      Objects.requireNonNull(lineId, "lineId");
      Objects.requireNonNull(type, "type");
    }
  }

  /**
   * 线路元信息，用于模板占位符。
   *
   * <p>{@code secondaryName} 用于 `line_lang2` 双语展示，缺省时回退到主名称。
   */
  public record LineInfo(String code, String name, String secondaryName, String color) {
    public LineInfo {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(name, "name");
      secondaryName = secondaryName == null ? "" : secondaryName;
      color = color == null ? "" : color;
    }
  }
}
