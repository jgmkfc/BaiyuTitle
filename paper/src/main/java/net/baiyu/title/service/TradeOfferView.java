package net.baiyu.title.service;

import net.baiyu.title.domain.trade.TradeStatus;

import java.util.Objects;

/**
 * 交易摘要视图（表现层设计 §0.1：{@link TradeViewService} 预计算，
 * 报价列表 / 个人交易记录 / 全服交易记录三处条目共用）。
 *
 * <p>时间与状态均已在视图层解析为展示串 / 语言键，菜单只做占位符插值；
 * {@code status} 为<b>有效状态</b>——PENDING 且已过过期时刻的报价按
 * EXPIRED 呈现（展示归入"已拒绝"）。</p>
 *
 * @param tradeId        交易 id
 * @param status         有效状态（过期未收敛的 PENDING 视为 EXPIRED）
 * @param statusKey      状态文案语言键（视角差异已折算）
 * @param incoming       观察者是否为目标方（true = 收到的报价）
 * @param initiatorName  发起方名称
 * @param targetName     目标方名称
 * @param counterpartName 对手方名称（观察者视角；管理端视图取目标方名）
 * @param initiatorCount 发起方标的数
 * @param targetCount    目标方标的数
 * @param createdText    创建时刻展示串（本地时区，format.date-time 模板）
 * @param expiresText    过期时刻展示串（本地时区，format.date-time 模板）
 */
public record TradeOfferView(
        long tradeId,
        TradeStatus status,
        String statusKey,
        boolean incoming,
        String initiatorName,
        String targetName,
        String counterpartName,
        int initiatorCount,
        int targetCount,
        String createdText,
        String expiresText) {

    /** 紧凑构造：文本字段必填。 */
    public TradeOfferView {
        Objects.requireNonNull(status, "交易状态不能为空");
        Objects.requireNonNull(statusKey, "状态语言键不能为空");
        Objects.requireNonNull(initiatorName, "发起方名称不能为空");
        Objects.requireNonNull(targetName, "目标方名称不能为空");
        Objects.requireNonNull(counterpartName, "对手方名称不能为空");
        Objects.requireNonNull(createdText, "创建时刻展示串不能为空");
        Objects.requireNonNull(expiresText, "过期时刻展示串不能为空");
    }

    /**
     * 观察者己方标的数（incoming 时己方 = 目标方）。
     *
     * @return 己方标的数
     */
    public int mineCount() {
        return incoming ? targetCount : initiatorCount;
    }

    /**
     * 观察者对方标的数。
     *
     * @return 对方标的数
     */
    public int theirsCount() {
        return incoming ? initiatorCount : targetCount;
    }
}
