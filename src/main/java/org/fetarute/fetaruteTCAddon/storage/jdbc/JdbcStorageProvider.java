package org.fetarute.fetaruteTCAddon.storage.jdbc;

import java.lang.reflect.Proxy;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyMemberRepository;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.company.repository.LineRepository;
import org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository;
import org.fetarute.fetaruteTCAddon.company.repository.PlayerIdentityRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteStopRepository;
import org.fetarute.fetaruteTCAddon.company.repository.StationRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransactionManager;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;

/**
 * JDBC 实现的 StorageProvider，占位仓库待后续接入具体 SQL 实现。
 *
 * <p>当前仅提供事务能力与数据源关闭，仓库调用会抛出未实现的 StorageException。
 */
public final class JdbcStorageProvider implements StorageProvider {

  private final DataSource dataSource;
  private final SqlDialect dialect;
  private final StorageTransactionManager transactionManager;

  public JdbcStorageProvider(DataSource dataSource, SqlDialect dialect) {
    this.dataSource = dataSource;
    this.dialect = dialect;
    this.transactionManager = new JdbcStorageTransactionManager(dataSource);
  }

  public DataSource dataSource() {
    return dataSource;
  }

  public SqlDialect dialect() {
    return dialect;
  }

  @Override
  public PlayerIdentityRepository playerIdentities() {
    return unsupported(PlayerIdentityRepository.class);
  }

  @Override
  public CompanyRepository companies() {
    return unsupported(CompanyRepository.class);
  }

  @Override
  public CompanyMemberRepository companyMembers() {
    return unsupported(CompanyMemberRepository.class);
  }

  @Override
  public OperatorRepository operators() {
    return unsupported(OperatorRepository.class);
  }

  @Override
  public LineRepository lines() {
    return unsupported(LineRepository.class);
  }

  @Override
  public StationRepository stations() {
    return unsupported(StationRepository.class);
  }

  @Override
  public RouteRepository routes() {
    return unsupported(RouteRepository.class);
  }

  @Override
  public RouteStopRepository routeStops() {
    return unsupported(RouteStopRepository.class);
  }

  @Override
  public StorageTransactionManager transactionManager() {
    return transactionManager;
  }

  @Override
  public void close() {
    if (dataSource instanceof AutoCloseable closeable) {
      try {
        closeable.close();
      } catch (Exception ex) {
        throw new StorageException("关闭数据源失败", ex);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private <T> T unsupported(Class<T> type) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              throw new StorageException("JDBC 仓库尚未实现: " + type.getSimpleName());
            });
  }
}
