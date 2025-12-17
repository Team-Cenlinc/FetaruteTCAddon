package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.CompanyMember;
import org.fetarute.fetaruteTCAddon.company.model.CompanyStatus;
import org.fetarute.fetaruteTCAddon.company.model.IdentityAuthType;
import org.fetarute.fetaruteTCAddon.company.model.MemberRole;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyMemberRepository;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository;
import org.fetarute.fetaruteTCAddon.company.repository.PlayerIdentityRepository;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class JdbcRepositoryTest {

  private static final Path TEST_DB = Path.of("test/data/test.sqlite").toAbsolutePath();
  private StorageManager manager;

  @BeforeEach
  void setUp() throws Exception {
    Path dir = TEST_DB.getParent();
    Files.createDirectories(dir);
    // 清理旧的 sqlite 文件，避免之前按用例命名的残留文件干扰检查
    try (var stream = Files.list(dir)) {
      stream
          .filter(path -> path.getFileName().toString().endsWith(".sqlite"))
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (Exception ex) {
                  throw new IllegalStateException("无法清理测试数据库文件: " + path, ex);
                }
              });
    }
    // 本轮测试用的 DB 在结束后保留便于手动检查
  }

  @AfterEach
  void tearDown() {
    if (manager != null) {
      manager.shutdown();
    }
  }

  @Test
  void shouldPersistPlayerIdentity() {
    StorageProvider provider = setupProvider(TEST_DB);
    PlayerIdentityRepository repository = provider.playerIdentities();

    UUID id = UUID.randomUUID();
    UUID playerUuid = UUID.randomUUID();
    Instant now = Instant.now();
    PlayerIdentity identity =
        new PlayerIdentity(
            id,
            playerUuid,
            "Steve",
            IdentityAuthType.ONLINE,
            Optional.of("ext"),
            Map.of("region", "CN"),
            now,
            now);

    repository.save(identity);

    PlayerIdentity loaded = repository.findById(id).orElseThrow();
    assertEquals(identity.id(), loaded.id());
    assertEquals(identity.playerUuid(), loaded.playerUuid());
    assertEquals("Steve", loaded.name());
    assertEquals("CN", loaded.metadata().get("region"));
    assertTrue(repository.findByPlayerUuid(playerUuid).isPresent());
  }

  @Test
  void shouldPersistCompany() {
    StorageProvider provider = setupProvider(TEST_DB);
    PlayerIdentityRepository playerRepo = provider.playerIdentities();
    CompanyRepository companyRepo = provider.companies();

    UUID ownerId = UUID.randomUUID();
    Instant now = Instant.now();
    PlayerIdentity identity =
        new PlayerIdentity(
            ownerId,
            UUID.randomUUID(),
            "Owner",
            IdentityAuthType.ONLINE,
            Optional.empty(),
            Map.of(),
            now,
            now);
    playerRepo.save(identity);

    Company company =
        new Company(
            UUID.randomUUID(),
            "FTA",
            "Fetarute Transit",
            Optional.of("FTA Co."),
            ownerId,
            CompanyStatus.ACTIVE,
            1_000_000L,
            Map.of("tier", "A"),
            now,
            now);

    companyRepo.save(company);

    Company loaded = companyRepo.findByCode("FTA").orElseThrow();
    assertEquals(company.id(), loaded.id());
    assertEquals(ownerId, loaded.ownerIdentityId());
    assertEquals("A", loaded.metadata().get("tier"));
    assertFalse(companyRepo.listByOwner(ownerId).isEmpty());
  }

  @Test
  void shouldSupportRepositoriesInsideTransaction() {
    StorageProvider provider = setupProvider(TEST_DB);
    PlayerIdentityRepository playerRepo = provider.playerIdentities();
    CompanyRepository companyRepo = provider.companies();

    UUID ownerId = UUID.randomUUID();
    UUID ownerPlayerUuid = UUID.randomUUID();
    Instant now = Instant.now();

    PlayerIdentity identity =
        new PlayerIdentity(
            ownerId,
            ownerPlayerUuid,
            "Owner",
            IdentityAuthType.ONLINE,
            Optional.empty(),
            Map.of(),
            now,
            now);

    Company company =
        new Company(
            UUID.randomUUID(),
            "FTA",
            "Fetarute Transit",
            Optional.empty(),
            ownerId,
            CompanyStatus.ACTIVE,
            1_000_000L,
            Map.of(),
            now,
            now);

    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () ->
            provider
                .transactionManager()
                .execute(
                    () -> {
                      playerRepo.save(identity);
                      companyRepo.save(company);
                      return null;
                    }));

    assertTrue(playerRepo.findByPlayerUuid(ownerPlayerUuid).isPresent());
    assertTrue(companyRepo.findByCode("FTA").isPresent());
  }

  @Test
  void shouldPersistCompanyMembersAndEnforceForeignKeys() {
    StorageProvider provider = setupProvider(TEST_DB);
    PlayerIdentityRepository playerRepo = provider.playerIdentities();
    CompanyRepository companyRepo = provider.companies();
    CompanyMemberRepository memberRepo = provider.companyMembers();

    UUID ownerId = UUID.randomUUID();
    UUID ownerPlayerUuid = UUID.randomUUID();
    Instant now = Instant.now();
    playerRepo.save(
        new PlayerIdentity(
            ownerId,
            ownerPlayerUuid,
            "Owner",
            IdentityAuthType.ONLINE,
            Optional.empty(),
            Map.of(),
            now,
            now));

    UUID companyId = UUID.randomUUID();
    companyRepo.save(
        new Company(
            companyId,
            "FTA",
            "Fetarute Transit",
            Optional.empty(),
            ownerId,
            CompanyStatus.ACTIVE,
            0L,
            Map.of(),
            now,
            now));

    CompanyMember member =
        new CompanyMember(
            companyId,
            ownerId,
            EnumSet.of(MemberRole.OWNER),
            now,
            Optional.of(Map.of("can_dispatch", true)));
    memberRepo.save(member);

    CompanyMember loaded = memberRepo.findMembership(companyId, ownerId).orElseThrow();
    assertEquals(companyId, loaded.companyId());
    assertEquals(ownerId, loaded.playerIdentityId());
    assertTrue(loaded.roles().contains(MemberRole.OWNER));
    assertEquals(true, loaded.permissions().orElseThrow().get("can_dispatch"));
    assertFalse(memberRepo.listMembers(companyId).isEmpty());
    assertFalse(memberRepo.listMemberships(ownerId).isEmpty());

    CompanyMember invalid =
        new CompanyMember(
            UUID.randomUUID(), UUID.randomUUID(), Set.of(MemberRole.VIEWER), now, Optional.empty());
    assertThrows(StorageException.class, () -> memberRepo.save(invalid));
  }

  @Test
  void shouldPersistOperator() {
    StorageProvider provider = setupProvider(TEST_DB);
    PlayerIdentityRepository playerRepo = provider.playerIdentities();
    CompanyRepository companyRepo = provider.companies();
    OperatorRepository operatorRepo = provider.operators();

    UUID ownerId = UUID.randomUUID();
    Instant now = Instant.now();
    playerRepo.save(
        new PlayerIdentity(
            ownerId,
            UUID.randomUUID(),
            "Owner",
            IdentityAuthType.ONLINE,
            Optional.empty(),
            Map.of(),
            now,
            now));

    UUID companyId = UUID.randomUUID();
    companyRepo.save(
        new Company(
            companyId,
            "FTA",
            "Fetarute Transit",
            Optional.empty(),
            ownerId,
            CompanyStatus.ACTIVE,
            0L,
            Map.of(),
            now,
            now));

    Operator operator =
        new Operator(
            UUID.randomUUID(),
            "SURN",
            companyId,
            "Sunrail",
            Optional.of("SR"),
            Optional.of("dark_aqua"),
            10,
            Optional.of("desc"),
            Map.of("tier", "A"),
            now,
            now);
    operatorRepo.save(operator);

    Operator loaded = operatorRepo.findById(operator.id()).orElseThrow();
    assertEquals(operator.code(), loaded.code());
    assertEquals(operator.companyId(), loaded.companyId());
    assertEquals(10, loaded.priority());
    assertEquals("A", loaded.metadata().get("tier"));

    assertTrue(operatorRepo.findByCompanyAndCode(companyId, "SURN").isPresent());
    assertFalse(operatorRepo.listByCompany(companyId).isEmpty());
  }

  private StorageProvider setupProvider(Path dbFile) {
    ConfigManager.StorageSettings settings =
        new ConfigManager.StorageSettings(
            ConfigManager.StorageBackend.SQLITE,
            new ConfigManager.SqliteSettings(dbFile.toString()),
            Optional.empty(),
            new ConfigManager.PoolSettings(5, 30000, 600000, 1800000));
    ConfigManager.ConfigView view = new ConfigManager.ConfigView(1, false, "zh_CN", settings);
    manager = new StorageManager(null, new LoggerManager(Logger.getAnonymousLogger()));
    manager.apply(view);
    return manager.provider().orElseThrow();
  }
}
