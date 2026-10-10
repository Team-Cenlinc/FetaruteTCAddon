package org.fetarute.fetaruteTCAddon.api.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.api.call.CallApi;
import org.fetarute.fetaruteTCAddon.call.CallCatalog;
import org.fetarute.fetaruteTCAddon.call.CallRules;
import org.fetarute.fetaruteTCAddon.call.CallService;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;

/** 叫车 API 的实现：转给叫车服务；叫车服务未建立时按不可用处理。 */
public final class CallApiImpl implements CallApi {

  private final FetaruteTCAddon plugin;

  public CallApiImpl(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  private Optional<CallService> service() {
    return plugin.getCallService();
  }

  @Override
  public boolean enabled() {
    return service().isPresent();
  }

  @Override
  public List<Direction> directionsAt(String operatorCode, String stationCode) {
    if (!Bukkit.isPrimaryThread()) {
      throw new IllegalStateException("CallApi#directionsAt 只能在服务器主线程调用");
    }
    Optional<CallService> calls = service();
    if (calls.isEmpty() || operatorCode == null || stationCode == null) {
      return List.of();
    }
    PidsStationKey station =
        new PidsStationKey(
            operatorCode.trim().toUpperCase(Locale.ROOT),
            stationCode.trim().toUpperCase(Locale.ROOT));
    List<Direction> out = new ArrayList<>();
    for (CallService.CallOption option :
        calls.get().options(station, Set.of(), Optional.empty(), Instant.now())) {
      out.add(direction(option));
    }
    return List.copyOf(out);
  }

  /** 叫车服务的判定换成对外的方向。 */
  static Direction direction(CallService.CallOption option) {
    CallCatalog.CallDirection direction = option.direction();
    CallRules.Verdict verdict = option.verdict();
    Availability availability = availability(verdict.outcome());
    OptionalInt minutes =
        availability == Availability.AVAILABLE
            ? option.plan().map(CallService.CallPlan::etaMinutes).orElse(OptionalInt.empty())
            : verdict.minutes();
    return new Direction(
        direction.key(),
        direction.lineId(),
        direction.lineCode(),
        direction.pattern() == null ? "" : direction.pattern().name(),
        direction.destination().operatorCode(),
        direction.destination().stationCode(),
        direction.platforms(),
        availability,
        minutes);
  }

  /** 不带玩家判定时不会冷却；万一出现按可以叫算。 */
  static Availability availability(CallRules.Outcome outcome) {
    return switch (outcome) {
      case AVAILABLE, COOLDOWN -> Availability.AVAILABLE;
      case NO_SOURCE -> Availability.NO_SOURCE;
      case ALREADY_CALLED -> Availability.ALREADY_CALLED;
      case NEXT_TRAIN_SOON -> Availability.NEXT_TRAIN_SOON;
      case LINE_LIMIT -> Availability.LINE_LIMIT;
    };
  }

  @Override
  public Optional<CallView> callOf(UUID callId) {
    return service().flatMap(calls -> calls.snapshotOf(callId)).map(CallApiImpl::view);
  }

  @Override
  public List<CallView> callsOf(UUID playerId) {
    return service()
        .map(calls -> calls.snapshotsOf(playerId).stream().map(CallApiImpl::view).toList())
        .orElse(List.of());
  }

  static CallView view(CallService.CallSnapshot snapshot) {
    return new CallView(
        snapshot.id(),
        snapshot.playerId(),
        snapshot.station().operatorCode(),
        snapshot.station().stationCode(),
        snapshot.directionKey(),
        snapshot.dispatched() ? State.DISPATCHED : State.PENDING,
        snapshot.trainName(),
        snapshot.createdAt());
  }
}
