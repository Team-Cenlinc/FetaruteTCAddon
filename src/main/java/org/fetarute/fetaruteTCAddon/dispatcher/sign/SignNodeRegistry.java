package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 保存牌子注册的节点定义，供调度图构建或 TC 路由同步使用。
 */
public final class SignNodeRegistry {

    private final ConcurrentMap<String, SignNodeDefinition> definitions = new ConcurrentHashMap<>();

    public void put(Block block, SignNodeDefinition definition) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(definition, "definition");
        // 以世界 UUID + 坐标拼键，避免多世界冲突
        definitions.put(key(block), definition);
    }

    public Optional<SignNodeDefinition> get(Block block) {
        Objects.requireNonNull(block, "block");
        return Optional.ofNullable(definitions.get(key(block)));
    }

    public void remove(Block block) {
        Objects.requireNonNull(block, "block");
        definitions.remove(key(block));
    }

    public Map<String, SignNodeDefinition> snapshot() {
        // 返回快照以保护内部可变状态
        return Map.copyOf(definitions);
    }

    public void clear() {
        definitions.clear();
    }

    private String key(Block block) {
        Location location = block.getLocation();
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("牌子所在方块缺少世界信息");
        }
        // 直接使用 block 坐标的离散键，不参与浮点运算
        return world.getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }
}
