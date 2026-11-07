package net.baiyu.title.service.trade;

/**
 * 交易阻断原因（创建报价拒绝与成交复核失败共用的机器可读原因码；
 * paper 侧按原因码映射 lang 文案）。
 */
public enum TradeBlockReason {

    /** 交易总开关关闭（config trade.enabled）。 */
    DISABLED,

    /** 不能与自己交易。 */
    SELF_TRADE,

    /** 双方标的同时为空。 */
    EMPTY_OFFER,

    /** 任一侧标的超过每侧上限（不变量 §十 7：每侧 ≤ 12）。 */
    TOO_MANY_ITEMS,

    /** 同一所有权实例在报价中出现多次。 */
    DUPLICATE_ITEM,

    /** 称号不存在或已软删。 */
    TITLE_NOT_FOUND,

    /** 称号不可交易（tradable=false，或 BOND/CUSTOM/GROUP 类别恒不可交易）。 */
    NOT_TRADABLE,

    /** 标的不属于该侧玩家的 ACTIVE 所有权（含已转走/已回收/称号 id 不匹配）。 */
    ITEM_NOT_OWNED,

    /** 标的所有权已到期（渲染层视角已失效，仅待周期收敛）。 */
    ITEM_EXPIRED,

    /** 接收方已 ACTIVE 持有转入称号且未在本单转出（成交必违唯一约束，不变量 §十 1）。 */
    RECIPIENT_ALREADY_OWNS
}
