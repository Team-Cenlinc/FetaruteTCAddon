package org.fetarute.fetaruteTCAddon.display.template.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateType;

/** HUD 模板绑定仓库：用于按线路绑定展示模板。 */
public interface HudLineBindingRepository {

  /** 查询线路指定类型的绑定记录。 */
  Optional<LineBinding> findByLineAndType(UUID lineId, HudTemplateType type);

  /** 列出指定线路的全部绑定。 */
  List<LineBinding> listByLine(UUID lineId);

  /** 列出全部线路绑定。 */
  List<LineBinding> listAll();

  /** 保存绑定记录（存在则覆盖）。 */
  LineBinding save(LineBinding binding);

  /** 删除指定线路与类型的绑定。 */
  void delete(UUID lineId, HudTemplateType type);

  /** 线路模板绑定记录。 */
  record LineBinding(UUID lineId, HudTemplateType type, UUID templateId, Instant updatedAt) {
    public LineBinding {
      if (lineId == null || type == null || templateId == null || updatedAt == null) {
        throw new IllegalArgumentException("lineId/type/templateId/updatedAt 不能为空");
      }
    }
  }
}
