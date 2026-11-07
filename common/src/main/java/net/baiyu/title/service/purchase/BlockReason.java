package net.baiyu.title.service.purchase;

/**
 * 不可购买原因（{@link Eligibility.CannotBuy} 的原因集元素）。
 * <p>原因是"集合"语义：一个称号可同时命中多条（如"隐藏 + 售罄"），
 * 表现层按需挑选展示；业务语义对照 Baiyu Title.md §1.3.2.3.1.2 筛选维度
 * 与 §1.3.2.4.1 底行「无法购买」。
 */
public enum BlockReason {

    /** 称号种类不支持商店直购（GROUP 走组资格、BOND 自动授予、CUSTOM 走自定义流程）。 */
    NOT_PURCHASABLE,

    /** 称号已软删（不可见/不可购，不变量 §十 13）。 */
    DELETED,

    /** 称号处于隐藏状态（visible = false）。 */
    HIDDEN,

    /** 不在获取窗口期内（WINDOW 限定，闭区间，业务 §1.1.2.5）。 */
    OUTSIDE_WINDOW,

    /** 已售罄（TOTAL 限定达到上限，业务 §1.1.2.5）。 */
    SOLD_OUT,

    /** Vault 经济不可用（金币类条件降级不可购）。 */
    ECONOMY_UNAVAILABLE,

    /** 购买条件组合不满足（明细见 {@link Eligibility#conditionStatus()}）。 */
    CONDITIONS_UNMET
}
