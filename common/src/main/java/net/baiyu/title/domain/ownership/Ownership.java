package net.baiyu.title.domain.ownership;

import java.util.Objects;
import java.util.UUID;

/**
 * 称号所有权实例（对应 {@code title_ownership} 表）。
 * <p>一行 = 一次持有实例，只追加不删除；每 (owner, title) 至多一条 ACTIVE
 * （DB UNIQUE(owner_uuid, title_id, active_marker) 结构化保证，
 * active_marker 为持久化细节不进领域模型）。
 *
 * @param id          主键（未落库时为 0）
 * @param titleId     称号 id
 * @param ownerUuid   持有者 UUID
 * @param ownerName   持有者名称缓存
 * @param source      授予来源
 * @param sourceRef   来源引用 id（可空；如订单 id / 交易 id）
 * @param acquiredAt  获得时刻（UTC epoch 毫秒）
 * @param expiresAt   到期时刻（可空；空 = 永久）
 * @param status      所有权状态
 * @param endedAt     结束时刻（可空；ACTIVE 时为空）
 * @param endedReason 结束原因（可空）
 */
public record Ownership(
        long id,
        int titleId,
        UUID ownerUuid,
        String ownerName,
        GrantSource source,
        Long sourceRef,
        long acquiredAt,
        Long expiresAt,
        OwnershipStatus status,
        Long endedAt,
        String endedReason) {

    /**
     * 紧凑构造器：必填字段判空。
     */
    public Ownership {
        Objects.requireNonNull(ownerUuid, "持有者 UUID 不能为空");
        Objects.requireNonNull(ownerName, "持有者名称不能为空");
        Objects.requireNonNull(source, "授予来源不能为空");
        Objects.requireNonNull(status, "所有权状态不能为空");
    }

    /**
     * 是否永久持有（无到期时刻）。
     *
     * @return 永久返回 true
     */
    public boolean isPermanent() {
        return expiresAt == null;
    }

    /**
     * 判断在给定时刻是否已到期（永久持有恒为 false）。
     * <p>与渲染层判定一致：{@code expires_at < now} 视为到期。
     *
     * @param now 当前时刻（UTC epoch 毫秒）
     * @return 已到期返回 true
     */
    public boolean isExpired(long now) {
        return expiresAt != null && expiresAt < now;
    }

    /**
     * 是否为 ACTIVE 状态（不含到期时刻判定，如需同时判定请配合 {@link #isExpired(long)}）。
     *
     * @return ACTIVE 返回 true
     */
    public boolean isActive() {
        return status == OwnershipStatus.ACTIVE;
    }
}
