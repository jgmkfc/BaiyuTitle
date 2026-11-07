package net.baiyu.title.service;

import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.group.PlayerGroup;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 组资格桥接（paper 侧）：{@link WardrobeService.MembershipVerifier} 的实现，
 * 兼作登录组对账的权限快照收集器（接线落点）。
 *
 * <p>线程分工：{@link #snapshot} 只在主线程调用（Bukkit 权限系统），把玩家
 * 持有的 {@code title.group.*} 节点冻结进并发映射并返回给对账链；
 * {@link #isActiveMember} 在写线程装扮事务内回调——先查权限快照（零 IO），
 * 未命中再落 member 表权威复核（{@link GroupService#isTableMemberOf}，
 * 读连接来自 Hikari 池，与在途写事务连接并存）。快照缺失（离线 / 未预载）
 * 时权限侧视为不持有，member 表侧仍可放行——语义上收紧而非放宽，
 * 满足「被移出组后 reconcile 前窗口内不可新装扮」。</p>
 */
public final class GroupAccessBridge implements WardrobeService.MembershipVerifier {

    /** 玩家组服务（member 表权威复核）。 */
    private final GroupService groupService;

    /** 称号目录缓存（GROUP 称号 → 组 id 解析）。 */
    private final TitleCatalogCache catalogCache;

    /** 组名缓存（组 id → 权限节点解析，零 DB IO）。 */
    private final GroupNameCache groupNameCache;

    /** 在线玩家的组权限节点快照（登录冻结；退出随档案驱逐）。 */
    private final Map<UUID, Set<String>> heldNodes = new ConcurrentHashMap<>();

    /**
     * 构造组资格桥接。
     *
     * @param groupService   玩家组服务
     * @param catalogCache   称号目录缓存
     * @param groupNameCache 组名缓存
     */
    public GroupAccessBridge(GroupService groupService, TitleCatalogCache catalogCache,
                             GroupNameCache groupNameCache) {
        this.groupService = Objects.requireNonNull(groupService, "玩家组服务不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.groupNameCache = Objects.requireNonNull(groupNameCache, "组名缓存不能为空");
    }

    /**
     * （主线程）收集玩家当前持有的组权限节点：冻结进快照并返回给
     * 登录对账链（{@code GroupService.reconcileOnWriter} 的入参）。
     *
     * @param player 在线玩家
     * @return 持有的 {@code title.group.<name>} 节点集合（不可变）
     */
    public Set<String> snapshot(Player player) {
        Objects.requireNonNull(player, "玩家不能为空");
        Set<String> nodes = new HashSet<>();
        for (PlayerGroup group : groupNameCache.all()) {
            if (player.hasPermission(group.permissionNode())) {
                nodes.add(group.permissionNode());
            }
        }
        Set<String> frozen = Set.copyOf(nodes);
        heldNodes.put(player.getUniqueId(), frozen);
        return frozen;
    }

    /**
     * 驱逐玩家的权限快照（与档案驱逐同步：延迟到点仍离线才调用）。
     *
     * @param uuid 玩家 UUID
     */
    public void evict(UUID uuid) {
        heldNodes.remove(uuid);
    }

    /**
     * （写线程）复核玩家对 GROUP 称号的有效成员资格：
     * 有效成员 = member 表 ∪ 权限持有者。
     *
     * @param player  玩家 UUID
     * @param titleId GROUP 称号 id
     * @return 有效成员返回 true
     */
    @Override
    public boolean isActiveMember(UUID player, int titleId) {
        Integer groupId = catalogCache.byId(titleId)
                .filter(t -> !t.isDeleted() && t.category() == TitleCategory.GROUP)
                .map(Title::groupId).orElse(null);
        if (groupId == null) {
            return false;
        }
        // 权限侧：登录冻结的快照，零 IO
        Set<String> held = heldNodes.get(player);
        if (held != null && groupNameCache.byId(groupId)
                .map(group -> held.contains(group.permissionNode())).orElse(false)) {
            return true;
        }
        // member 表侧：写线程读连接权威复核
        return groupService.isTableMemberOf(player, titleId);
    }
}
