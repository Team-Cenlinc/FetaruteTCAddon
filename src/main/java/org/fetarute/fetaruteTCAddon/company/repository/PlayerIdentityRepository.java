package org.fetarute.fetaruteTCAddon.company.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;

/** 玩家身份仓库接口。 */
public interface PlayerIdentityRepository {

  Optional<PlayerIdentity> findById(UUID id);

  Optional<PlayerIdentity> findByPlayerUuid(UUID playerUuid);

  List<PlayerIdentity> listAll();

  PlayerIdentity save(PlayerIdentity identity);

  void delete(UUID id);
}
