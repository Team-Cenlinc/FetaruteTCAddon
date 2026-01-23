package org.fetarute.fetaruteTCAddon.display.template.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplate;
import org.fetarute.fetaruteTCAddon.display.template.HudTemplateType;

/** HUD 模板仓库接口。 */
public interface HudTemplateRepository {

  /** 按模板 ID 查询。 */
  Optional<HudTemplate> findById(UUID id);

  /** 按公司 + 类型 + 名称查询。 */
  Optional<HudTemplate> findByCompanyAndName(UUID companyId, HudTemplateType type, String name);

  /** 列出公司下的全部模板。 */
  List<HudTemplate> listByCompany(UUID companyId);

  /** 列出公司下指定类型的模板。 */
  List<HudTemplate> listByCompanyAndType(UUID companyId, HudTemplateType type);

  /** 保存模板（存在则覆盖）。 */
  HudTemplate save(HudTemplate template);

  /** 删除模板。 */
  void delete(UUID id);
}
