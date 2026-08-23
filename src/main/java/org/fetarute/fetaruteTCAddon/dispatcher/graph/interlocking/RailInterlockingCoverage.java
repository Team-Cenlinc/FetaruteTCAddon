package org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking;

/**
 * 联锁索引对输入区间足迹的覆盖状态。
 *
 * @param inputEdgeCount 调用方提供的区间足迹数量
 * @param participatingEdgeCount 使用受支持完整足迹参与计算的规范化区间数量
 * @param complete 是否每个输入区间都具有受支持且完整的足迹
 */
public record RailInterlockingCoverage(
    int inputEdgeCount, int participatingEdgeCount, boolean complete) {}
