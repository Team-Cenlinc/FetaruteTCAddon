package org.fetarute.fetaruteTCAddon.interlink;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 跨服边界的消息接口。传输方式未定（共享数据库的发件箱与租约，或 WebSocket 点对点），这里只定语义；实现必须失效导向安全： 联系不上对端时一律拒绝，列车在边界前停车，绝不“先过去再说”。
 *
 * <p>一次过界：申请边界占用 → 对端授予 → 发送整车快照 → 对端生成后确认 → 本服释放占用并移除列车。
 */
public interface InterlinkGateway {

  /** 对端状态。 */
  enum PeerStatus {
    UNKNOWN,
    ONLINE,
    OFFLINE
  }

  /**
   * 边界占用申请：本服有车要过去。
   *
   * @param requestId 申请 ID（幂等键）
   * @param toServer 对端服务器
   * @param boundary 边界
   * @param trainUid 列车全局 ID
   * @param trainName 列车名（日志用）
   * @param requestedAt 申请时刻（发送方时钟）
   */
  record BoundaryRequest(
      String requestId,
      String toServer,
      RailBoundaryLink boundary,
      String trainUid,
      String trainName,
      Instant requestedAt) {
    public BoundaryRequest {
      Objects.requireNonNull(requestId, "requestId");
      Objects.requireNonNull(toServer, "toServer");
      Objects.requireNonNull(boundary, "boundary");
      Objects.requireNonNull(trainUid, "trainUid");
      Objects.requireNonNull(requestedAt, "requestedAt");
      trainName = trainName == null ? "" : trainName;
    }
  }

  /**
   * 对端的答复。
   *
   * @param requestId 对应的申请
   * @param granted 是否授予
   * @param grantId 授予的占用 ID（释放时用）；拒绝时为空
   * @param reason 拒绝原因
   * @param expiresAt 授予的有效期；过期未移交即作废
   */
  record BoundaryGrant(
      String requestId,
      boolean granted,
      Optional<String> grantId,
      String reason,
      Optional<Instant> expiresAt) {
    public BoundaryGrant {
      Objects.requireNonNull(requestId, "requestId");
      grantId = grantId == null ? Optional.empty() : grantId;
      reason = reason == null ? "" : reason;
      expiresAt = expiresAt == null ? Optional.empty() : expiresAt;
    }

    /** 拒绝。 */
    public static BoundaryGrant denied(String requestId, String reason) {
      return new BoundaryGrant(requestId, false, Optional.empty(), reason, Optional.empty());
    }
  }

  /**
   * 对端收到整车快照后的确认。
   *
   * @param handoffId 快照 ID
   * @param accepted 对端是否已生成列车
   * @param reason 拒绝原因
   */
  record HandoffAck(String handoffId, boolean accepted, String reason) {
    public HandoffAck {
      Objects.requireNonNull(handoffId, "handoffId");
      reason = reason == null ? "" : reason;
    }
  }

  /** 本服的服务器 ID。 */
  String serverId();

  /** 申请对端边界的占用；联系不上时以拒绝完成。 */
  CompletableFuture<BoundaryGrant> requestBoundary(BoundaryRequest request);

  /** 释放一次授予（移交完成或放弃）。 */
  void releaseBoundary(String toServer, String grantId);

  /** 发送整车快照；联系不上时以拒绝完成。 */
  CompletableFuture<HandoffAck> sendHandoff(TrainHandoff handoff);

  /**
   * 收到对端发来的整车快照时回调（在服务器主线程）；返回是否已生成列车。
   *
   * @param receiver 处理快照，返回确认
   */
  void onHandoff(java.util.function.Function<TrainHandoff, HandoffAck> receiver);

  /** 收到对端的边界申请时回调，返回答复。 */
  void onBoundaryRequest(java.util.function.Function<BoundaryRequest, BoundaryGrant> responder);

  /** 对端状态。 */
  PeerStatus peerStatus(String serverId);

  /** 关闭连接。 */
  default void close() {}

  /** 日志出口（实现可选）。 */
  default void setLogger(Consumer<String> logger) {}
}
