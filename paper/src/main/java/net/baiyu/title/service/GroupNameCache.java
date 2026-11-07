package net.baiyu.title.service;

import net.baiyu.title.domain.group.PlayerGroup;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 玩家组名内存缓存（paper 侧只读快照）。
 *
 * <p>存在理由（补全零 DB IO 红线）：组名补全跑在命令建议线程、成员资格
 * 权限判定跑在主线程收集——两者都不允许触碰数据库；本缓存以 volatile
 * 不可变快照承载全部未软删组，读方随取随新、永不加锁。</p>
 *
 * <p>刷新时机：装配预热一次；{@code /byt group create|delete} 成功后由命令层
 * 触发全量重载（组变更唯一入口是该命令，增量无必要）；reload 复用目录
 * 预热链路一并刷新。写方只有 DB 读线程回调与主线程，{@code replaceAll}
 * 整体替换快照，无部分可见状态。</p>
 */
public final class GroupNameCache {

    /** 不可变快照（组名字典序 → 组；含 id 反查视图）。 */
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());

    /**
     * 以给定组列表整体替换快照（软删组过滤掉，不参与补全与资格判定）。
     *
     * @param groups 组列表（{@code GroupService.listGroups} 的产物）
     */
    public void replaceAll(List<PlayerGroup> groups) {
        Map<String, PlayerGroup> byName = groups.stream()
                .filter(g -> !g.isDeleted())
                .collect(Collectors.toMap(PlayerGroup::name, g -> g,
                        (a, b) -> a, TreeMap::new));
        Map<Integer, PlayerGroup> byId = byName.values().stream()
                .collect(Collectors.toUnmodifiableMap(PlayerGroup::id, g -> g));
        // 注意保序：Map.copyOf 不保留 TreeMap 的字典序，须用 unmodifiableMap 包装
        this.snapshot = new Snapshot(Collections.unmodifiableMap(byName), byId);
    }

    /**
     * 全部组名（字典序；命令补全数据源，零 DB IO）。
     *
     * @return 组名数组
     */
    public String[] names() {
        return snapshot.byName().keySet().toArray(String[]::new);
    }

    /**
     * 全部组（快照视图；启动权限节点注册用）。
     *
     * @return 组列表
     */
    public List<PlayerGroup> all() {
        return List.copyOf(snapshot.byName().values());
    }

    /**
     * 按组名查组。
     *
     * @param name 组名
     * @return 组；未知组名时为空
     */
    public Optional<PlayerGroup> byName(String name) {
        return Optional.ofNullable(snapshot.byName().get(name));
    }

    /**
     * 按组 id 查组。
     *
     * @param groupId 组 id
     * @return 组；未知 id 时为空
     */
    public Optional<PlayerGroup> byId(int groupId) {
        return Optional.ofNullable(snapshot.byId().get(groupId));
    }

    /**
     * 快照载体（byName 为 TreeMap 拷贝的不可变映射，键已按字典序）。
     *
     * @param byName 组名 → 组
     * @param byId   组 id → 组
     */
    private record Snapshot(Map<String, PlayerGroup> byName, Map<Integer, PlayerGroup> byId) {
    }
}
