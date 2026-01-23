package org.fetarute.fetaruteTCAddon.display.template;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * HUD 模板实体：用于保存可渲染文本（MiniMessage）与模板类型。
 *
 * <p>模板内容为纯文本字符串，渲染时由展示层负责占位符替换与 MiniMessage 解析。
 */
public record HudTemplate(
    UUID id,
    UUID companyId,
    HudTemplateType type,
    String name,
    String content,
    Instant createdAt,
    Instant updatedAt) {

  public HudTemplate {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(companyId, "companyId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
