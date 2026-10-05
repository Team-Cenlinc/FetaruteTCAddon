package org.fetarute.fetaruteTCAddon.display.pids.bulletin.repository;

import java.util.List;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.display.pids.bulletin.PidsBulletin;

/** 站台屏公告仓库。公告不多，启动时整表读入内存，之后只按条写回。 */
public interface PidsBulletinRepository {

  /** 全部公告（含未开始与已结束的）。 */
  List<PidsBulletin> listAll();

  /** 按编号新增或更新。 */
  PidsBulletin save(PidsBulletin bulletin);

  void delete(UUID id);
}
