package net.baiyu.title.domain.redeem;

import java.util.Objects;

/**
 * 兑换码（对应 {@code redeem_code} 表，全列对齐）。
 * <p>码面在服务层大写归一化；使用计数以条件 UPDATE 递减防并发超用
 * （不变量 §十 14：used_count ≤ max_uses，0 = 无限）。
 *
 * @param id            主键（未落库时为 0）
 * @param code          码面（唯一，大写归一化）
 * @param titleId       兑换目标称号 id
 * @param maxUses       总使用次数上限（0 = 无限）
 * @param usedCount     已使用次数
 * @param perPlayerUses 每玩家使用次数上限
 * @param validFrom     生效时刻（可空；空 = 立即生效）
 * @param validUntil    失效时刻（可空；空 = 永久有效）
 * @param enabled       是否启用
 * @param createdBy     创建者名称（可空；控制台创建为空）
 * @param createdAt     创建时刻（UTC epoch 毫秒）
 */
public record RedeemCode(
        long id,
        String code,
        int titleId,
        int maxUses,
        int usedCount,
        int perPlayerUses,
        Long validFrom,
        Long validUntil,
        boolean enabled,
        String createdBy,
        long createdAt) {

    /**
     * 紧凑构造器：码面判空、计数非负校验。
     */
    public RedeemCode {
        Objects.requireNonNull(code, "兑换码码面不能为空");
        if (code.isBlank()) {
            throw new IllegalArgumentException("兑换码码面不能为空白");
        }
        if (maxUses < 0) {
            throw new IllegalArgumentException("总使用次数上限不能为负: " + maxUses);
        }
        if (usedCount < 0) {
            throw new IllegalArgumentException("已使用次数不能为负: " + usedCount);
        }
        if (perPlayerUses < 0) {
            throw new IllegalArgumentException("每玩家使用次数上限不能为负: " + perPlayerUses);
        }
    }

    /**
     * 是否已用尽（maxUses 为 0 表示无限，恒未用尽）。
     *
     * @return 已用尽返回 true
     */
    public boolean isExhausted() {
        return maxUses > 0 && usedCount >= maxUses;
    }

    /**
     * 判断给定时刻是否处于有效期内（端点含）。
     *
     * @param now 当前时刻（UTC epoch 毫秒）
     * @return 处于有效期返回 true
     */
    public boolean isWithinValidity(long now) {
        return (validFrom == null || now >= validFrom) && (validUntil == null || now <= validUntil);
    }

    /**
     * 判断给定时刻整体是否可兑换（启用 + 未用尽 + 有效期内；
     * 每玩家次数限制需查使用记录，由服务层判定）。
     *
     * @param now 当前时刻（UTC epoch 毫秒）
     * @return 可兑换返回 true
     */
    public boolean isRedeemableAt(long now) {
        return enabled && !isExhausted() && isWithinValidity(now);
    }
}
