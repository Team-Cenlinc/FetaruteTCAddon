package org.fetarute.fetaruteTCAddon.storage;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.CompanyStatus;
import org.fetarute.fetaruteTCAddon.company.model.IdentityAuthType;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.PlayerIdentity;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqliteDialect;
import org.fetarute.fetaruteTCAddon.storage.jdbc.HikariDataSourceFactory;
import org.fetarute.fetaruteTCAddon.storage.jdbc.JdbcStorageProvider;
import org.fetarute.fetaruteTCAddon.storage.schema.StorageSchema;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;
import org.sqlite.SQLiteDataSource;

/**
 * 测试用存储：与生产同一套数据源（Hikari + SQLite、外键开启）与建表语句，外加造数方法。
 *
 * <p>{@link #counting()} 返回一个包了计数代理的 provider：经它取到的仓库每调用一次方法计数一次，用来证明某段查询不访问存储。
 */
public final class TransitTestStorage implements AutoCloseable {

  private final AutoCloseable closer;
  private final JdbcStorageProvider provider;
  private final AtomicInteger repositoryCalls = new AtomicInteger();
  private final StorageProvider counting;
  private final UUID ownerIdentityId = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-09-27T00:00:00Z");

  private TransitTestStorage(DataSource dataSource, AutoCloseable closer) {
    this.closer = closer;
    LoggerManager logger = new LoggerManager(Logger.getAnonymousLogger());
    this.provider = new JdbcStorageProvider(dataSource, new SqliteDialect(), "fta_", logger);
    this.counting = countingProxy(provider, repositoryCalls);
  }

  /** 在目录下新建数据库并建表（生产同款数据源，外键开启）。 */
  public static TransitTestStorage open(Path directory) throws SQLException {
    ConfigManager.StorageSettings settings =
        new ConfigManager.StorageSettings(
            ConfigManager.StorageBackend.SQLITE,
            new ConfigManager.SqliteSettings("transit.sqlite"),
            Optional.empty(),
            new ConfigManager.PoolSettings(2, 30000, 600000, 1800000));
    HikariDataSource dataSource =
        HikariDataSourceFactory.create(
            settings, directory.toFile(), new LoggerManager(Logger.getAnonymousLogger()));
    return init(dataSource, dataSource);
  }

  /** 同上，但数据源<b>不开外键</b>（SQLite 默认）：用来证明删除时显式清理了子表，而不是靠外键级联。 */
  public static TransitTestStorage openWithoutForeignKeys(Path directory) throws SQLException {
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + directory.resolve("transit-nofk.sqlite").toAbsolutePath());
    return init(dataSource, () -> {});
  }

  private static TransitTestStorage init(DataSource dataSource, AutoCloseable closer)
      throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      for (String sql : new StorageSchema("fta_").statements(new SqliteDialect())) {
        statement.execute(sql);
      }
    }
    TransitTestStorage storage = new TransitTestStorage(dataSource, closer);
    storage
        .provider
        .playerIdentities()
        .save(
            new PlayerIdentity(
                storage.ownerIdentityId,
                UUID.randomUUID(),
                "Owner",
                IdentityAuthType.ONLINE,
                Optional.empty(),
                Map.of(),
                storage.now,
                storage.now));
    return storage;
  }

  /** 直连的 provider。 */
  public StorageProvider provider() {
    return provider;
  }

  /** 计数代理：经它取到的仓库每次调用都计数。 */
  public StorageProvider counting() {
    return counting;
  }

  /** 经 {@link #counting()} 发生的仓库调用次数。 */
  public int repositoryCalls() {
    return repositoryCalls.get();
  }

  /** 计数清零。 */
  public void resetRepositoryCalls() {
    repositoryCalls.set(0);
  }

  public Company company(String code) {
    Company company =
        new Company(
            UUID.randomUUID(),
            code,
            code + " Company",
            Optional.empty(),
            ownerIdentityId,
            CompanyStatus.ACTIVE,
            0L,
            Map.of(),
            now,
            now);
    provider.companies().save(company);
    return company;
  }

  public Operator operator(Company company, String code, String colorTheme) {
    Operator operator =
        new Operator(
            UUID.randomUUID(),
            code,
            company.id(),
            code + " Operator",
            Optional.empty(),
            Optional.ofNullable(colorTheme),
            0,
            Optional.empty(),
            Map.of(),
            now,
            now);
    provider.operators().save(operator);
    return operator;
  }

  public Station station(Operator operator, String code, String name) {
    Station station =
        new Station(
            UUID.randomUUID(),
            code,
            operator.id(),
            Optional.empty(),
            name,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            Map.of(),
            now,
            now);
    provider.stations().save(station);
    return station;
  }

  public Line line(Operator operator, String code, String name, String color) {
    Line line =
        new Line(
            UUID.randomUUID(),
            code,
            operator.id(),
            name,
            Optional.empty(),
            LineServiceType.METRO,
            Optional.ofNullable(color),
            LineStatus.ACTIVE,
            Optional.empty(),
            Map.of(),
            now,
            now);
    provider.lines().save(line);
    return line;
  }

  public Route route(
      Line line, String code, RoutePatternType pattern, RouteOperationType operationType) {
    Route route =
        new Route(
            UUID.randomUUID(),
            code,
            line.id(),
            code,
            Optional.empty(),
            pattern,
            operationType,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            now,
            now);
    provider.routes().save(route);
    return route;
  }

  /** 写入停靠表；{@code stops} 每项为 {passType, waypointNodeId 或 null, notes 或 null}。 */
  public void stops(Route route, String[]... stops) {
    provider.routeStops().deleteAll(route.id());
    for (int i = 0; i < stops.length; i++) {
      String[] stop = stops[i];
      provider
          .routeStops()
          .save(
              new RouteStop(
                  route.id(),
                  (i + 1) * 10,
                  Optional.empty(),
                  Optional.ofNullable(stop[1]),
                  Optional.empty(),
                  RouteStopPassType.valueOf(stop[0]),
                  Optional.ofNullable(stop.length > 2 ? stop[2] : null)));
    }
  }

  @Override
  public void close() {
    try {
      closer.close();
    } catch (Exception ex) {
      throw new IllegalStateException("关闭测试数据源失败", ex);
    }
  }

  private static StorageProvider countingProxy(StorageProvider target, AtomicInteger calls) {
    return (StorageProvider)
        Proxy.newProxyInstance(
            StorageProvider.class.getClassLoader(),
            new Class<?>[] {StorageProvider.class},
            (proxy, method, args) -> {
              Object result = invoke(method, target, args);
              Class<?> type = method.getReturnType();
              if (result == null || !type.isInterface() || type == StorageProvider.class) {
                return result;
              }
              return Proxy.newProxyInstance(
                  type.getClassLoader(),
                  new Class<?>[] {type},
                  (repoProxy, repoMethod, repoArgs) -> {
                    calls.incrementAndGet();
                    return invoke(repoMethod, result, repoArgs);
                  });
            });
  }

  private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException ex) {
      throw ex.getCause();
    }
  }
}
