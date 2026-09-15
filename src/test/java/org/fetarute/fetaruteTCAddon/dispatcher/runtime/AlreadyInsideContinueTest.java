package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 已在单线区内、出口已找到但落在硬授权窗口之外时，必须放行续行。
 *
 * <p>成因：{@code evaluateSmartSingleCorridorAdmission} 的"已在区内"分支 （{@code
 * hasClaimByTrain}）先做了对向屏障检查——真正的安全性质在那里守住—— 然后又因为"出口不在硬授权窗口内"把车拒掉，于是把它锁死在区内。
 *
 * <p>实服代价（同一形态、三轮、三辆不同的车）：
 *
 * <ul>
 *   <li>第十二轮 {@code SURC-WS-LC-7203} 整轮 74 分钟、到站 0 次
 *   <li>第十六轮 {@code SURC-WS-LC-6212} 449 秒
 *   <li>第十七轮 {@code SURC-WS-LC-6650} <b>3260 秒（54 分钟）</b>， {@code blockedBy=[]} 没有任何车挡它，整条 WS
 *       线在它之后到站归零
 * </ul>
 *
 * <p>为什么不是"把窗口延到出口"：{@code buildHardAuthorityContext} 的契约明写方向性 {@code single:*}
 * <b>不能扩张为整段单线独占</b>，那会毁掉长单线区的通过能力。 单线区本来就靠"持方向锁 + 局部窗口逐段推进"通过。
 */
class AlreadyInsideContinueTest {

  /**
   * 判别核心：**"出口在窗口外"与"证不出有出口"必须得到相反的结果**。
   *
   * <p>只断言前者放行是不够的——那证明不了 fail-closed 那一半还在，而那一半才是这道门存在的理由。
   */
  @Test
  void exitOutsideWindowIsAllowedButUnprovenExitStillFailsClosed() {
    // 出口找到了，只是在硬窗口之外 —— 放行。
    assertTrue(
        RuntimeDispatchService.exitFoundOutsideHardAuthority(
            result("exit-visible-outside-hard-authority", 4)),
        "出口已找到、仅在窗口外，必须放行");
    assertTrue(
        RuntimeDispatchService.exitFoundOutsideHardAuthority(
            result("target-boundary-outside-hard-authority", 6)),
        "目标边界已找到、仅在窗口外，同样放行");

    // 扩展之后仍然找不到出口 —— 这是真的证不出，必须继续 fail-closed。
    assertFalse(
        RuntimeDispatchService.exitFoundOutsideHardAuthority(
            result("exit-not-visible-after-extension", -1)),
        "证不出路径会离开该区时不得放行");
    assertFalse(
        RuntimeDispatchService.exitFoundOutsideHardAuthority(result("missing-plan", -1)),
        "缺计划时不得放行");
  }

  /**
   * 原因字符串与出口下标必须**同时**成立。
   *
   * <p>只信其中一个，将来任何一边的语义被悄悄改掉都会变成静默放行——而这道门放宽错了， 后果是把车放进它证明不了能离开的单线区。
   */
  @Test
  void reasonStringAloneIsNotEnough() {
    assertFalse(
        RuntimeDispatchService.exitFoundOutsideHardAuthority(
            result("exit-visible-outside-hard-authority", -1)),
        "原因说在窗口外、却没带回出口下标 —— 不得放行");
  }

  @Test
  void nullResultFailsClosed() {
    assertFalse(RuntimeDispatchService.exitFoundOutsideHardAuthority(null));
  }

  private static EntryLookaheadEvaluator.Result result(String reason, int exitAfter) {
    return new EntryLookaheadEvaluator.Result(
        true, 8, "single:zone", 0, -1, true, exitAfter, false, reason);
  }
}
