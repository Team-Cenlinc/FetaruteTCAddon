package org.fetarute.fetaruteTCAddon.drive.cab;

/**
 * 推进车上系统一 tick 所需的会话状态。
 *
 * @param auxPowered 辅助电源是否接通
 * @param mainCircuitPowered 主电路是否得电（受电、主断路器与辅助电源都接通），决定有没有电制动
 * @param brakeDemand 制动力度：常用全制动为 1，紧急制动可超过 1，不制动为 0
 * @param failSafe 是否为失效导向安全的制动（紧急制动、无人驾驶时的自动制动、停放制动）：全部由空气制动承担，不用电制动
 * @param emergency 是否为紧急制动（机车的制动管排风）
 * @param speedBps 这一步开始时的速度（格/秒），决定电制动能不能用
 * @param stopped 列车是否已停稳
 * @param attended 驾驶员是否在座操作（离座或制动停车阶段不计警惕、不发生随机故障）
 * @param running 列车是否已启动（启动流程全部接通），未启动时不发生随机故障
 */
public record CabTick(
    boolean auxPowered,
    boolean mainCircuitPowered,
    double brakeDemand,
    boolean failSafe,
    boolean emergency,
    double speedBps,
    boolean stopped,
    boolean attended,
    boolean running) {}
