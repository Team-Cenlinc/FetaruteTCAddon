package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMember;
import org.fetarute.fetaruteTCAddon.company.model.CompanyStatus;
import org.fetarute.fetaruteTCAddon.company.model.IdentityAuthType;
import org.fetarute.fetaruteTCAddon.company.model.MemberRole;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyMemberRepository;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.company.repository.PlayerIdentityRepository;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;
import org.junit.jupiter.api.Test;

/**
 * 集成用的 JDBC 写入测试：将多实体写入同一个 sqlite 文件，方便手动检查。
 *
 * <p>输出文件固定为 test/data/integration/test.sqlite，不会被 JdbcRepositoryTest 的清理逻辑覆盖。
 */
final class JdbcRepositoryIntegrationTest {

  private static final Path TEST_DB = Path.of("test/data/integration/test.sqlite").toAbsolutePath();

  @Test
  void shouldWriteIntegrationSqliteFile() throws Exception {
    Path parent = TEST_DB.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.deleteIfExists(TEST_DB);

    StorageManager manager =
        new StorageManager(null, new LoggerManager(Logger.getAnonymousLogger()));
    try (StorageProvider provider = setupProvider(manager)) {
      PlayerIdentityRepository playerRepo = provider.playerIdentities();
      CompanyRepository companyRepo = provider.companies();
      CompanyMemberRepository memberRepo = provider.companyMembers();

      Instant now = Instant.now();
      UUID ownerIdentityId = UUID.randomUUID();
      UUID ownerPlayerUuid = UUID.randomUUID();

      playerRepo.save(
          new PlayerIdentity(
              ownerIdentityId,
              ownerPlayerUuid,
              "IntegrationOwner",
              IdentityAuthType.ONLINE,
              Optional.of("integration"),
              Map.of("seed", "integration-test"),
              now,
              now));

      UUID companyId = UUID.randomUUID();
      companyRepo.save(
          new Company(
              companyId,
              "INT",
              "Integration Company",
              Optional.empty(),
              ownerIdentityId,
              CompanyStatus.ACTIVE,
              12345L,
              Map.of("source", "integration-test"),
              now,
              now));

      memberRepo.save(
          new CompanyMember(
              companyId,
              ownerIdentityId,
              EnumSet.of(MemberRole.OWNER),
              now,
              Optional.of(Map.of("can_dispatch", true))));

      assertTrue(playerRepo.findByPlayerUuid(ownerPlayerUuid).isPresent());
      assertTrue(companyRepo.findByCode("INT").isPresent());
      assertTrue(memberRepo.findMembership(companyId, ownerIdentityId).isPresent());
    } finally {
      manager.shutdown();
    }
  }

  private StorageProvider setupProvider(StorageManager manager) {
    ConfigManager.StorageSettings settings =
        new ConfigManager.StorageSettings(
            ConfigManager.StorageBackend.SQLITE,
            new ConfigManager.SqliteSettings(TEST_DB.toString()),
            Optional.empty(),
            new ConfigManager.PoolSettings(5, 30000, 600000, 1800000));
    ConfigManager.ConfigView view =
        new ConfigManager.ConfigView(
            4,
            false,
            "zh_CN",
            settings,
            new ConfigManager.GraphSettings(8.0),
            new ConfigManager.AutoStationSettings("BLOCK_NOTE_BLOCK_BELL", 1.0f, 1.2f),
            new ConfigManager.RuntimeSettings(10, 2, 4.0),
            new ConfigManager.TrainConfigSettings(
                "emu",
                new ConfigManager.TrainTypeSettings(12.0, 6.0, 0.8, 1.0),
                new ConfigManager.TrainTypeSettings(11.0, 5.5, 0.7, 0.9),
                new ConfigManager.TrainTypeSettings(10.0, 5.0, 0.6, 0.8),
                new ConfigManager.TrainTypeSettings(13.0, 6.5, 0.9, 1.1)));
    manager.apply(view);
    return manager.provider().orElseThrow();
  }
}
