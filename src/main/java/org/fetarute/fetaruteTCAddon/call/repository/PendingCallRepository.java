package org.fetarute.fetaruteTCAddon.call.repository;

import java.util.List;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.call.PendingCallRecord;

/** 还没派出的叫车。叫车时写入，派出、取消、超时或作废时删除；启动时整表读回。 */
public interface PendingCallRepository {

  List<PendingCallRecord> listAll();

  /** 按编号新增或更新。 */
  void save(PendingCallRecord call);

  void delete(UUID id);
}
