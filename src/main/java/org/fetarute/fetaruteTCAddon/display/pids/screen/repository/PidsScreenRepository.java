package org.fetarute.fetaruteTCAddon.display.pids.screen.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;

/** 站台屏仓库。屏幕数量有全服上限，启动时整表读入内存，之后只按条写回。 */
public interface PidsScreenRepository {

  Optional<PidsScreen> findById(UUID id);

  /** 全部屏幕。 */
  List<PidsScreen> listAll();

  /**
   * 按 ID 新增或更新。
   *
   * @throws org.fetarute.fetaruteTCAddon.storage.api.StorageException 同一位置与朝向已有另一块屏幕
   */
  PidsScreen save(PidsScreen screen);

  void delete(UUID id);
}
