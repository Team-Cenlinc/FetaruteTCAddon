package org.fetarute.fetaruteTCAddon.interlink;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** 未配置跨服时的网关：一律拒绝，对端一律离线。列车因此停在边界前，不会越界。 */
public final class NoopInterlinkGateway implements InterlinkGateway {

  static final String REASON = "interlink-not-configured";

  private final String serverId;

  public NoopInterlinkGateway(String serverId) {
    this.serverId = serverId == null ? "" : serverId;
  }

  @Override
  public String serverId() {
    return serverId;
  }

  @Override
  public CompletableFuture<BoundaryGrant> requestBoundary(BoundaryRequest request) {
    return CompletableFuture.completedFuture(BoundaryGrant.denied(request.requestId(), REASON));
  }

  @Override
  public void releaseBoundary(String toServer, String grantId) {}

  @Override
  public CompletableFuture<HandoffAck> sendHandoff(TrainHandoff handoff) {
    return CompletableFuture.completedFuture(new HandoffAck(handoff.handoffId(), false, REASON));
  }

  @Override
  public void onHandoff(Function<TrainHandoff, HandoffAck> receiver) {}

  @Override
  public void onBoundaryRequest(Function<BoundaryRequest, BoundaryGrant> responder) {}

  @Override
  public PeerStatus peerStatus(String serverId) {
    return PeerStatus.OFFLINE;
  }
}
