package net.baiyu.title.service.group;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.group.PlayerGroup;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.repository.AuditRepository;
import net.baiyu.title.repository.GroupRepository;
import net.baiyu.title.repository.TitleRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.Actor;
import net.baiyu.title.service.TitleCatalogService;
import net.baiyu.title.text.ColorNormalizer;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 组生命周期（GroupService 的内部协作者，方法只允许在写线程调用）。
 * <p>创建：组名 {@code [a-z0-9_]} 校验 → 同事务插组 + 自动建 GROUP 称号
 * （绑定 groupId、visible=false、tradable 领域强制 false）→ 提交后缓存写穿。
 * 删除：同事务软删组 + 软删组称号 + 收回全部持有者的 ACTIVE 所有权
 * （历史完整保留，红线"软删不回收组称号历史"）。
 */
public final class GroupLifecycle {

    /** 组名白名单（服务层限 [a-z0-9_]，长度 1~64 对齐 name 列宽）。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_]{1,64}");

    private final GroupRepository groupRepository;
    private final TitleRepository titleRepository;
    private final AuditRepository auditRepository;
    private final TransactionRunner txRunner;
    private final TitleCatalogCache catalogCache;
    private final PlayerProfileCache profileCache;
    private final GroupTitleOps titleOps;

    /**
     * 构造组生命周期协作者。
     *
     * @param groupRepository 玩家组仓储
     * @param titleRepository 称号仓储
     * @param auditRepository 审计仓储
     * @param txRunner        事务执行器
     * @param catalogCache    称号目录缓存
     * @param profileCache    玩家档案缓存
     * @param titleOps        组称号授予/收回原语
     */
    public GroupLifecycle(GroupRepository groupRepository, TitleRepository titleRepository,
                          AuditRepository auditRepository, TransactionRunner txRunner,
                          TitleCatalogCache catalogCache, PlayerProfileCache profileCache,
                          GroupTitleOps titleOps) {
        this.groupRepository = Objects.requireNonNull(groupRepository, "玩家组仓储不能为空");
        this.titleRepository = Objects.requireNonNull(titleRepository, "称号仓储不能为空");
        this.auditRepository = Objects.requireNonNull(auditRepository, "审计仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
        this.titleOps = Objects.requireNonNull(titleOps, "组称号原语不能为空");
    }

    /**
     * 写线程主体：创建组（组名/称号名校验 → 事务插组建号 → 提交后目录缓存写穿）。
     */
    public GroupCreateResult create(String name, @Nullable String rawTitleName, Actor actor) {
        if (!NAME_PATTERN.matcher(name).matches()) {
            return new GroupCreateResult.InvalidName(name);
        }
        String titleName;
        String titlePlain;
        try {
            String raw = rawTitleName == null || rawTitleName.isBlank() ? name : rawTitleName;
            titleName = ColorNormalizer.normalizeName(raw);
            titlePlain = TitleCatalogService.validateName(titleName);
        } catch (IllegalArgumentException e) {
            return new GroupCreateResult.InvalidName(rawTitleName);
        }
        long now = System.currentTimeMillis();
        GroupCreateResult.Created created = txRunner.inTransaction(c -> {
            if (groupRepository.findByName(c, name).isPresent()) {
                return null; // 组名列 DB UNIQUE：含软删行都算冲突
            }
            int groupId = groupRepository.insert(c,
                    new PlayerGroup(0, name, null, actor.name(), now, null));
            Title fresh = Title.builder()
                    .name(titleName).namePlain(titlePlain).category(TitleCategory.GROUP)
                    .groupId(groupId).visible(false)
                    .createdBy(actor.uuid()).createdByName(actor.name())
                    .createdAt(now).updatedAt(now)
                    .build();
            int titleId = titleRepository.insert(c, fresh);
            auditRepository.insert(c, new AuditRepository.AuditEntry(
                    "GROUP_CREATE", actor.uuid(), actor.name(), null, titleId, null,
                    "player_group", (long) groupId, "{\"name\":\"" + name + "\"}", now));
            return new GroupCreateResult.Created(
                    new PlayerGroup(groupId, name, null, actor.name(), now, null),
                    fresh.toBuilder().id(titleId).build());
        });
        if (created == null) {
            return new GroupCreateResult.DuplicateName(name);
        }
        catalogCache.put(created.title());
        return created;
    }

    /**
     * 写线程主体：删除组（软删组 + 软删组称号 + 全量收回；提交后缓存写穿）。
     */
    public GroupDeleteResult delete(int groupId, Actor actor) {
        long now = System.currentTimeMillis();
        Title title = titleOps.groupTitleOf(groupId).orElse(null);
        Title deletedTitle = title == null ? null
                : title.toBuilder().visible(false).updatedAt(now).deletedAt(now).build();
        DeleteTx tx = txRunner.inTransaction(c -> {
            if (groupRepository.findById(c, groupId).filter(g -> !g.isDeleted()).isEmpty()) {
                return null;
            }
            groupRepository.softDelete(c, groupId, now);
            int revoked = 0;
            if (deletedTitle != null) {
                titleRepository.update(c, deletedTitle);
                titleRepository.softDelete(c, deletedTitle.id(), now);
                for (UUID owner : titleOps.activeHoldersOf(c, deletedTitle.id())) {
                    if (titleOps.revokeInTx(c, deletedTitle.id(), groupId, owner,
                            "GROUP_DELETE", now, actor)) {
                        revoked++;
                    }
                }
            }
            auditRepository.insert(c, new AuditRepository.AuditEntry(
                    "GROUP_DELETE", actor.uuid(), actor.name(), null,
                    deletedTitle == null ? null : deletedTitle.id(), null,
                    "player_group", (long) groupId, null, now));
            return new DeleteTx(revoked);
        });
        if (tx == null) {
            return new GroupDeleteResult.NotFound(groupId);
        }
        if (deletedTitle != null) {
            catalogCache.put(deletedTitle);
            int titleId = deletedTitle.id();
            profileCache.updateAll(profile -> profile.withoutTitle(titleId));
        }
        return new GroupDeleteResult.Deleted(groupId,
                deletedTitle == null ? null : deletedTitle.id(), tx.revokedCount());
    }

    /**
     * 删除事务的提交后回执。
     */
    private record DeleteTx(int revokedCount) {
    }
}
