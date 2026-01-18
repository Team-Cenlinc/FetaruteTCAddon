package org.fetarute.fetaruteTCAddon.storage.jdbc;

import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyMemberInviteRepository;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyMemberRepository;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.company.repository.LineRepository;
import org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository;
import org.fetarute.fetaruteTCAddon.company.repository.PlayerIdentityRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteStopRepository;
import org.fetarute.fetaruteTCAddon.company.repository.StationRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailComponentCautionRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeOverrideRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransactionManager;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;
import org.fetarute.fetaruteTCAddon.storage.jdbc.repository.JdbcCompanyMemberInviteRepository;
import org.fetarute.fetaruteTCAddon.storage.jdbc.repository.JdbcCompanyMemberRepository;
import org.fetarute.fetaruteTCAddon.storage.jdbc.repository.JdbcCompanyRepository;
import org.fetarute.fetaruteTCAddon.storage.jdbc.repository.JdbcLineRepository;
import org.fetarute.fetaruteTCAddon.storage.jdbc.repository.JdbcOperatorRepository;
import org.fetarute.fetaruteTCAddon.storage.jdbc.repository.JdbcPlayerIdentityRepository;
import org.fetarute.fetaruteTCAddon.storage.jdbc.repository.JdbcRailComponentCautionRepository;
import org.fetarute.fetaruteTCAddon.storage.jdbc.repository.JdbcRailEdgeOverrideRepository;
import org.fetarute.fetaruteTCAddon.storage.jdbc.repository.JdbcRailEdgeRepository;
import org.fetarute.fetaruteTCAddon.storage.jdbc.repository.JdbcRailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.storage.jdbc.repository.JdbcRailNodeRepository;
import org.fetarute.fetaruteTCAddon.storage.jdbc.repository.JdbcRouteRepository;
import org.fetarute.fetaruteTCAddon.storage.jdbc.repository.JdbcRouteStopRepository;
import org.fetarute.fetaruteTCAddon.storage.jdbc.repository.JdbcStationRepository;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;

/**
 * JDBC 实现的 StorageProvider，负责暴露各类 JDBC 仓库与事务能力。
 *
 * <p>若某类仓库尚未实现，将抛出未实现的 StorageException。
 */
public final class JdbcStorageProvider implements StorageProvider {

  private final DataSource dataSource;
  private final SqlDialect dialect;
  private final StorageTransactionManager transactionManager;
  private final PlayerIdentityRepository playerIdentityRepository;
  private final CompanyRepository companyRepository;
  private final CompanyMemberRepository companyMemberRepository;
  private final CompanyMemberInviteRepository companyMemberInviteRepository;
  private final OperatorRepository operatorRepository;
  private final LineRepository lineRepository;
  private final StationRepository stationRepository;
  private final RouteRepository routeRepository;
  private final RouteStopRepository routeStopRepository;
  private final RailNodeRepository railNodeRepository;
  private final RailEdgeRepository railEdgeRepository;
  private final RailEdgeOverrideRepository railEdgeOverrideRepository;
  private final RailComponentCautionRepository railComponentCautionRepository;
  private final RailGraphSnapshotRepository railGraphSnapshotRepository;

  public JdbcStorageProvider(
      DataSource dataSource, SqlDialect dialect, String tablePrefix, LoggerManager logger) {
    this.dataSource = dataSource;
    this.dialect = dialect;
    this.transactionManager = new JdbcStorageTransactionManager(dataSource);
    this.playerIdentityRepository =
        new JdbcPlayerIdentityRepository(dataSource, dialect, tablePrefix, logger::debug);
    this.companyRepository =
        new JdbcCompanyRepository(dataSource, dialect, tablePrefix, logger::debug);
    this.companyMemberRepository =
        new JdbcCompanyMemberRepository(dataSource, dialect, tablePrefix, logger::debug);
    this.companyMemberInviteRepository =
        new JdbcCompanyMemberInviteRepository(dataSource, dialect, tablePrefix, logger::debug);
    this.operatorRepository =
        new JdbcOperatorRepository(dataSource, dialect, tablePrefix, logger::debug);
    this.lineRepository = new JdbcLineRepository(dataSource, dialect, tablePrefix, logger::debug);
    this.stationRepository =
        new JdbcStationRepository(dataSource, dialect, tablePrefix, logger::debug);
    this.routeRepository = new JdbcRouteRepository(dataSource, dialect, tablePrefix, logger::debug);
    this.routeStopRepository =
        new JdbcRouteStopRepository(dataSource, dialect, tablePrefix, logger::debug);
    this.railNodeRepository =
        new JdbcRailNodeRepository(dataSource, dialect, tablePrefix, logger::debug);
    this.railEdgeRepository =
        new JdbcRailEdgeRepository(dataSource, dialect, tablePrefix, logger::debug);
    this.railEdgeOverrideRepository =
        new JdbcRailEdgeOverrideRepository(dataSource, dialect, tablePrefix, logger::debug);
    this.railComponentCautionRepository =
        new JdbcRailComponentCautionRepository(dataSource, dialect, tablePrefix, logger::debug);
    this.railGraphSnapshotRepository =
        new JdbcRailGraphSnapshotRepository(dataSource, dialect, tablePrefix, logger::debug);
  }

  public DataSource dataSource() {
    return dataSource;
  }

  public SqlDialect dialect() {
    return dialect;
  }

  @Override
  public PlayerIdentityRepository playerIdentities() {
    return playerIdentityRepository;
  }

  @Override
  public CompanyRepository companies() {
    return companyRepository;
  }

  @Override
  public CompanyMemberRepository companyMembers() {
    return companyMemberRepository;
  }

  @Override
  public CompanyMemberInviteRepository companyMemberInvites() {
    return companyMemberInviteRepository;
  }

  @Override
  public OperatorRepository operators() {
    return operatorRepository;
  }

  @Override
  public LineRepository lines() {
    return lineRepository;
  }

  @Override
  public StationRepository stations() {
    return stationRepository;
  }

  @Override
  public RouteRepository routes() {
    return routeRepository;
  }

  @Override
  public RouteStopRepository routeStops() {
    return routeStopRepository;
  }

  @Override
  public RailNodeRepository railNodes() {
    return railNodeRepository;
  }

  @Override
  public RailEdgeRepository railEdges() {
    return railEdgeRepository;
  }

  @Override
  public RailEdgeOverrideRepository railEdgeOverrides() {
    return railEdgeOverrideRepository;
  }

  @Override
  public RailComponentCautionRepository railComponentCautions() {
    return railComponentCautionRepository;
  }

  @Override
  public RailGraphSnapshotRepository railGraphSnapshots() {
    return railGraphSnapshotRepository;
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
}
