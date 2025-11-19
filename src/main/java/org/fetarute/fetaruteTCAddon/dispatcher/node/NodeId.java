package org.fetarute.fetaruteTCAddon.dispatcher.node;

import java.util.Objects;

/**
 * 代表轨道网络中的一个逻辑节点（站台、道岔、车库等）的唯一标识。
 * 推荐遵循 <运营商>:<from>:<to>:<track>:<seq> 的编码方式，
 * 例如 SURN:PTK:GPT:1:00、SURN:S:PTK:1:00（站咽喉）、SURN:D:LVT:1:00（Depot throat）。
 */
public record NodeId(String value) {

    public NodeId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * 简化构造入口，便于后续统一校验逻辑。
     */
    public static NodeId of(String raw) {
        return new NodeId(raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
