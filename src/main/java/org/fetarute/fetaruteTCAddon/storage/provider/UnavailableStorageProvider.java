package org.fetarute.fetaruteTCAddon.storage.provider;

import java.lang.reflect.Proxy;
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
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransaction;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransactionManager;

/**
 * 后端尚未准备就绪时的占位 Provider，所有操作都会抛出 StorageException。
 *
 * <p>用于启动阶段或缺少实现时提示调用方“存储不可用”，防止误写到空实现。
 */
public final class UnavailableStorageProvider implements StorageProvider {

  private final String reason;

  public UnavailableStorageProvider(String reason) {
    this.reason = reason == null ? "存储后端尚未初始化" : reason;
  }

  private <T> T unsupported(Class<T> type) {
    Object proxy =
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (ignoredProxy, ignoredMethod, ignoredArgs) -> {
              throw new StorageException(reason);
            });
    return type.cast(proxy);
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
  public CompanyMemberInviteRepository companyMemberInvites() {
    return unsupported(CompanyMemberInviteRepository.class);
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
  public RailNodeRepository railNodes() {
    return unsupported(RailNodeRepository.class);
  }

  @Override
  public RailEdgeRepository railEdges() {
    return unsupported(RailEdgeRepository.class);
  }

  @Override
  public RailEdgeOverrideRepository railEdgeOverrides() {
    return unsupported(RailEdgeOverrideRepository.class);
  }

  @Override
  public RailComponentCautionRepository railComponentCautions() {
    return unsupported(RailComponentCautionRepository.class);
  }

  @Override
  public RailGraphSnapshotRepository railGraphSnapshots() {
    return unsupported(RailGraphSnapshotRepository.class);
  }

  @Override
  public StorageTransactionManager transactionManager() {
    return new StorageTransactionManager() {
      @Override
      public StorageTransaction begin() {
        throw new StorageException(reason);
      }
    };
  }

  @Override
  public void close() {
    // 占位实现无需释放资源
  }
}
