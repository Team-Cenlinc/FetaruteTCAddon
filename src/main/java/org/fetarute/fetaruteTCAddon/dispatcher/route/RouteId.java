package org.fetarute.fetaruteTCAddon.dispatcher.route;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 线路的唯一标识，用于将列车状态与调度计划关联。
 * 推荐编码：Operator:Line:Service，例如 SURN:BS:EXP-01。
 */
public record RouteId(String value) {

    public RouteId {
        Objects.requireNonNull(value, "value");
    }

    public static RouteId of(String raw) {
        return new RouteId(raw);
    }

    @Override
    public @NotNull String toString() {
        return value;
    }
}
