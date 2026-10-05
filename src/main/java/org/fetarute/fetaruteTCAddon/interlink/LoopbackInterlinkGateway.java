package org.fetarute.fetaruteTCAddon.interlink;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 进程内的网关：若干个实例挂在同一个 {@link Hub} 上互发消息，供测试与协议演练。真正的传输（共享数据库、WebSocket）实现同一个接口。
 *
 * <p>快照经编码再解码后才交给对端，与跨进程时一样检查格式版本。
 */
public final class LoopbackInterlinkGateway implements InterlinkGateway {

  /** 一组互通的网关。 */
  public static final class Hub {
    private final Map<String, LoopbackInterlinkGateway> members = new ConcurrentHashMap<>();
  }

  private final Hub hub;
  private final String serverId;
  private volatile Function<TrainHandoff, HandoffAck> handoffReceiver;
  private volatile Function<BoundaryRequest, BoundaryGrant> boundaryResponder;

  public LoopbackInterlinkGateway(Hub hub, String serverId) {
    this.hub = java.util.Objects.requireNonNull(hub, "hub");
    this.serverId = java.util.Objects.requireNonNull(serverId, "serverId");
    hub.members.put(serverId, this);
  }

  @Override
  public String serverId() {
    return serverId;
  }

  @Override
  public CompletableFuture<BoundaryGrant> requestBoundary(BoundaryRequest request) {
    LoopbackInterlinkGateway peer = hub.members.get(request.toServer());
    Function<BoundaryRequest, BoundaryGrant> responder =
        peer == null ? null : peer.boundaryResponder;
    if (responder == null) {
      return CompletableFuture.completedFuture(
          BoundaryGrant.denied(request.requestId(), "peer-offline"));
    }
    return CompletableFuture.completedFuture(responder.apply(request));
  }

  @Override
  public void releaseBoundary(String toServer, String grantId) {}

  @Override
  public CompletableFuture<HandoffAck> sendHandoff(TrainHandoff handoff) {
    LoopbackInterlinkGateway peer = hub.members.get(handoff.toServer());
    Function<TrainHandoff, HandoffAck> receiver = peer == null ? null : peer.handoffReceiver;
    if (receiver == null) {
      return CompletableFuture.completedFuture(
          new HandoffAck(handoff.handoffId(), false, "peer-offline"));
    }
    return CompletableFuture.completedFuture(
        TrainHandoffCodec.decode(TrainHandoffCodec.encode(handoff))
            .map(receiver)
            .orElseGet(() -> new HandoffAck(handoff.handoffId(), false, "decode-failed")));
  }

  @Override
  public void onHandoff(Function<TrainHandoff, HandoffAck> receiver) {
    this.handoffReceiver = receiver;
  }

  @Override
  public void onBoundaryRequest(Function<BoundaryRequest, BoundaryGrant> responder) {
    this.boundaryResponder = responder;
  }

  @Override
  public PeerStatus peerStatus(String other) {
    return hub.members.containsKey(other) ? PeerStatus.ONLINE : PeerStatus.OFFLINE;
  }

  @Override
  public void close() {
    hub.members.remove(serverId, this);
  }
}
