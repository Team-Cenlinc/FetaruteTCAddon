package org.fetarute.fetaruteTCAddon.storage.api;

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

/** 汇总所有仓库实例，供服务层注入。 */
public interface StorageProvider extends AutoCloseable {

  PlayerIdentityRepository playerIdentities();

  CompanyRepository companies();

  CompanyMemberRepository companyMembers();

  OperatorRepository operators();

  LineRepository lines();

  StationRepository stations();

  RouteRepository routes();

  RouteStopRepository routeStops();

  RailNodeRepository railNodes();

  RailEdgeRepository railEdges();

  RailEdgeOverrideRepository railEdgeOverrides();

  RailComponentCautionRepository railComponentCautions();

  RailGraphSnapshotRepository railGraphSnapshots();

  StorageTransactionManager transactionManager();

  @Override
  void close();
}
