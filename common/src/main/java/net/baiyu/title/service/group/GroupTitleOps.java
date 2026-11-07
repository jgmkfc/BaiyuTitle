package net.baiyu.title.service.group;

import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.ownership.GrantSource;
import net.baiyu.title.domain.ownership.Ownership;
import net.baiyu.title.domain.ownership.OwnershipStatus;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.OwnershipRepository;
import net.baiyu.title.repository.SlotRepository;
import net.baiyu.title.service.Actor;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 组称号授予/收回原语（GroupService 的内部协作者，方法只允许在写线程调用方的
 * 事务内使用）。
 * <p>组称号语义：授予恒 ACTIVE 直至移出
 * （expiresAt=null）、source=GROUP、sourceRef=组 id；收回 = endActive(REVOKED)
 * + 卸下全部装扮引用 + 审计（同事务，不变量 §十 10）。
 */
public final class GroupTitleOps {

    /** 所有权仓储。 */
    private final OwnershipRepository ownershipRepository;

    /** 装扮栏位仓储（收回时清引用）。 */
    private final SlotRepository slotRepository;

    /** 审计仓储。 */
    private final AuditRepository auditRepository;

    /** 称号目录缓存（组称号定位）。 */
    private final TitleCatalogCache catalogCache;

    /**
     * 构造组称号原语（依赖与 GroupService 同源）。
     *
     * @param ownershipRepository 所有权仓储
     * @param slotRepository      装扮栏位仓储
     * @param auditRepository     审计仓储
     * @param catalogCache        称号目录缓存
     */
    public GroupTitleOps(OwnershipRepository ownershipRepository, SlotRepository slotRepository,
                         AuditRepository auditRepository, TitleCatalogCache catalogCache) {
        this.ownershipRepository = Objects.requireNonNull(ownershipRepository, "所有权仓储不能为空");
        this.slotRepository = Objects.requireNonNull(slotRepository, "装扮栏位仓储不能为空");
        this.auditRepository = Objects.requireNonNull(auditRepository, "审计仓储不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
    }

    /**
     * 定位组对应的 GROUP 称号（目录缓存扫描；软删称号不返回）。
     *
     * @param groupId 组 id
     * @return 组称号；不存在或已软删时为空
     */
    public Optional<Title> groupTitleOf(int groupId) {
        return catalogCache.all().stream()
                .filter(t -> t.category() == TitleCategory.GROUP)
                .filter(t -> t.groupId() != null && t.groupId() == groupId)
                .findFirst();
    }

    /**
     * 事务内枚举某组称号的全部 ACTIVE 持有者（删除组时全量收回用；
     * 复用 findOwnersHavingAll 的单元素集合语义）。
     *
     * @param c       事务连接
     * @param titleId 组称号 id
     * @return 持有者 UUID 列表
     * @throws SQLException JDBC 失败时
     */
    public List<UUID> activeHoldersOf(Connection c, int titleId) throws SQLException {
        return ownershipRepository.findOwnersHavingAll(c, Set.of(titleId));
    }

    /**
     * 事务内授予组称号（先查重：已持有 ACTIVE 时零副作用返回空）。
     *
     * @param c          事务连接
     * @param titleId    组称号 id
     * @param groupId    组 id（落 source_ref）
     * @param player     玩家 UUID
     * @param playerName 玩家名称
     * @param now        当前时刻（UTC epoch 毫秒）
     * @param actor      操作者
     * @return 新所有权 id；已持有时为空
     * @throws SQLException JDBC 失败时
     */
    public @Nullable Long grantInTx(Connection c, int titleId, int groupId, UUID player,
                                    String playerName, long now, Actor actor) throws SQLException {
        if (ownershipRepository.findActive(c, player, titleId).isPresent()) {
            return null;
        }
        long ownershipId = ownershipRepository.insertGrant(c, new Ownership(0, titleId, player,
                playerName, GrantSource.GROUP, (long) groupId, now, null,
                OwnershipStatus.ACTIVE, null, null));
        auditRepository.insert(c, new AuditRepository.AuditEntry(
                "GROUP_GRANT", actor.uuid(), actor.name(), player, titleId, ownershipId,
                "player_group", (long) groupId, null, now));
        return ownershipId;
    }

    /**
     * 事务内收回组称号（endActive(REVOKED) + 卸下全部装扮引用 + 审计）。
     *
     * @param c       事务连接
     * @param titleId 组称号 id
     * @param groupId 组 id（审计引用）
     * @param player  玩家 UUID
     * @param reason  收回原因（落 ended_reason 列）
     * @param now     当前时刻（UTC epoch 毫秒）
     * @param actor   操作者
     * @return 实际收回返回 true；玩家无 ACTIVE 所有权时 false
     * @throws SQLException JDBC 失败时
     */
    public boolean revokeInTx(Connection c, int titleId, int groupId, UUID player,
                              String reason, long now, Actor actor) throws SQLException {
        Optional<Ownership> active = ownershipRepository.findActive(c, player, titleId);
        if (active.isEmpty()
                || !ownershipRepository.endActive(c, active.get().id(),
                        OwnershipStatus.REVOKED, reason, now)) {
            return false;
        }
        slotRepository.clearTitleEverywhere(c, player, titleId);
        auditRepository.insert(c, new AuditRepository.AuditEntry(
                "GROUP_REVOKE", actor.uuid(), actor.name(), player, titleId, active.get().id(),
                "player_group", (long) groupId, reason, now));
        return true;
    }
}
