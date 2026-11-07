package net.baiyu.title.domain.trade;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 交易聚合（对应 {@code trade} 表+ 标的条目）。
 * <p>交易期间不锁称号，成交事务内全量复核。
 * <p>构造时校验每侧标的 ≤ 12（不变量 §十 7 前半）；"双侧不同时为空"由服务层在
 * 创建报价时校验（历史分页查询可能不装载标的，记录级不强制）。
 *
 * @param id            主键（未落库时为 0）
 * @param initiatorUuid 发起方 UUID
 * @param initiatorName 发起方名称缓存
 * @param targetUuid    目标方 UUID
 * @param targetName    目标方名称缓存
 * @param status        交易状态
 * @param createdAt     创建时刻（UTC epoch 毫秒）
 * @param expiresAt     过期时刻（config trade.offer-ttl，默认 72h）
 * @param resolvedAt    解决时刻（可空；PENDING 时为空）
 * @param resolvedBy    解决者名称（可空；接受/拒绝的玩家名或管理员名）
 * @param failReason    失败原因（可空）
 * @param items         交易标的列表（防御性拷贝）
 */
public record Trade(
        long id,
        UUID initiatorUuid,
        String initiatorName,
        UUID targetUuid,
        String targetName,
        TradeStatus status,
        long createdAt,
        long expiresAt,
        Long resolvedAt,
        String resolvedBy,
        String failReason,
        List<TradeItem> items) {

    /** 每侧标的数量上限（不变量 §十 7）。 */
    public static final int MAX_ITEMS_PER_SIDE = 12;

    /**
     * 紧凑构造器：判空、防御性拷贝、每侧标的数量校验。
     */
    public Trade {
        Objects.requireNonNull(initiatorUuid, "发起方 UUID 不能为空");
        Objects.requireNonNull(initiatorName, "发起方名称不能为空");
        Objects.requireNonNull(targetUuid, "目标方 UUID 不能为空");
        Objects.requireNonNull(targetName, "目标方名称不能为空");
        Objects.requireNonNull(status, "交易状态不能为空");
        items = items == null ? List.of() : List.copyOf(items);
        for (TradeSide side : TradeSide.values()) {
            long count = items.stream().filter(item -> item.side() == side).count();
            if (count > MAX_ITEMS_PER_SIDE) {
                throw new IllegalArgumentException(
                        "交易每侧标的至多 " + MAX_ITEMS_PER_SIDE + " 件: " + side + "=" + count);
            }
        }
    }

    /**
     * 取指定交易方的标的列表。
     *
     * @param side 交易方
     * @return 该侧标的（不可变列表）
     */
    public List<TradeItem> itemsOf(TradeSide side) {
        Objects.requireNonNull(side, "交易方不能为空");
        return items.stream().filter(item -> item.side() == side).toList();
    }

    /**
     * 是否待处理状态。
     *
     * @return PENDING 返回 true
     */
    public boolean isPending() {
        return status == TradeStatus.PENDING;
    }

    /**
     * 判断在给定时刻报价是否已过期（仅对 PENDING 状态有意义，
     * 过期清扫由 TradeService.sweepExpired 迁移为 EXPIRED）。
     *
     * @param now 当前时刻（UTC epoch 毫秒）
     * @return 已过过期时刻返回 true
     */
    public boolean isExpiredAt(long now) {
        return expiresAt < now;
    }
}
