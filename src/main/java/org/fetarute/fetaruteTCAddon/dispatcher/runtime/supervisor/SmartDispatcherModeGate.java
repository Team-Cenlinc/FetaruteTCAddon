package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

/**
 * Smart Dispatcher 模式到运行时副作用权限的唯一映射。
 *
 * <p>OBSERVE_ONLY 与 OFF 都必须是零副作用；ENFORCE 只开放对应 effect class 明确声明的副作用。Smart action
 * 必须先归类，再由这里决定能否落地，避免诊断逻辑绕过模式开关直接修改运行时状态。
 */
public final class SmartDispatcherModeGate {

  private static final EffectPermissions NO_EFFECTS =
      new EffectPermissions(false, false, false, false, false, false, false, false);
  private static final EffectPermissions SIGNAL_ADVISORY_EFFECTS =
      new EffectPermissions(true, true, false, false, false, false, false, false);
  private static final EffectPermissions SIGNAL_CONSTRAINT_EFFECTS =
      new EffectPermissions(true, true, false, false, false, false, false, false);
  private static final EffectPermissions SIGNAL_REEVALUATION_REQUEST_EFFECTS =
      new EffectPermissions(false, false, false, false, false, false, false, true);
  private static final EffectPermissions OCCUPANCY_MUTATION_EFFECTS =
      new EffectPermissions(false, false, false, false, false, true, false, false);
  private static final EffectPermissions DESTROY_EFFECTS =
      new EffectPermissions(false, false, false, false, false, false, true, false);

  private SmartDispatcherModeGate() {}

  /** 返回指定模式与副作用等级下允许触达的运行时状态。 */
  public static EffectPermissions permissions(
      SmartDispatcherMode mode, DispatchEffectClass effectClass) {
    SmartDispatcherMode safeMode = mode == null ? SmartDispatcherMode.OBSERVE_ONLY : mode;
    DispatchEffectClass safeEffect =
        effectClass == null ? DispatchEffectClass.DIAGNOSTIC_ONLY : effectClass;
    if (safeMode != SmartDispatcherMode.ENFORCE) {
      return NO_EFFECTS;
    }
    return switch (safeEffect) {
      case SIGNAL_ADVISORY -> SIGNAL_ADVISORY_EFFECTS;
      case SIGNAL_CONSTRAINT -> SIGNAL_CONSTRAINT_EFFECTS;
      case SIGNAL_REEVALUATION_REQUEST -> SIGNAL_REEVALUATION_REQUEST_EFFECTS;
      case OCCUPANCY_MUTATION -> OCCUPANCY_MUTATION_EFFECTS;
      case DESTROY_ACTION -> DESTROY_EFFECTS;
      default -> NO_EFFECTS;
    };
  }

  /** 当前 mode/effect 是否会因为没有任何真实副作用权限而被抑制。 */
  public static boolean suppresses(SmartDispatcherMode mode, DispatchEffectClass effectClass) {
    EffectPermissions permissions = permissions(mode, effectClass);
    return !permissions.canChangeAspect()
        && !permissions.canChangeTargetSpeed()
        && !permissions.canClearDestination()
        && !permissions.canInvalidateToken()
        && !permissions.canSetMovementInhibited()
        && !permissions.canMutateOccupancy()
        && !permissions.canDestroy()
        && !permissions.canRequestSignalReevaluation();
  }

  /**
   * 判断已登记 action 是否可以触达其声明的运行时效果。
   *
   * <p>该方法是所有 Smart Dispatcher effect chain 共用的类型化入口。未登记或显式禁止的 action 即使携带看似允许的 effect class
   * 也必须被拒绝，避免 health、admission 等调用方仅凭字符串或 effect class 绕开动作注册表。
   */
  public static boolean allows(SmartDispatcherMode mode, DispatchAction action) {
    if (action == null || !action.executableDispatcherAction()) {
      return false;
    }
    EffectPermissions permissions = permissions(mode, action.effectClass());
    return switch (action.effectClass()) {
      case SIGNAL_ADVISORY, SIGNAL_CONSTRAINT -> permissions.canChangeAspect()
          || permissions.canChangeTargetSpeed();
      case SIGNAL_REEVALUATION_REQUEST -> permissions.canRequestSignalReevaluation();
      case OCCUPANCY_MUTATION -> permissions.canMutateOccupancy();
      case DESTROY_ACTION -> permissions.canDestroy();
      default -> false;
    };
  }

  /** 某个 Smart action 在当前模式下允许触达的运行时副作用。 */
  public record EffectPermissions(
      boolean canChangeAspect,
      boolean canChangeTargetSpeed,
      boolean canClearDestination,
      boolean canInvalidateToken,
      boolean canSetMovementInhibited,
      boolean canMutateOccupancy,
      boolean canDestroy,
      boolean canRequestSignalReevaluation) {}
}
