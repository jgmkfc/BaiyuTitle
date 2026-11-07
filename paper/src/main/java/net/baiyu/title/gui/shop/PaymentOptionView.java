package net.baiyu.title.gui.shop;

import net.baiyu.title.domain.condition.ConditionType;
import net.kyori.adventure.text.Component;

import java.util.Objects;

/**
 * 自定义称号付款选项视图（表现层设计 §0.1 CustomTitleFlow.options 的产出；
 * 业务设计 §1.3.2.3.1.4 H/I/J/K 四选项）。
 *
 * <p>由 {@code CustomTitleFlow} 主线程预计算，付款菜单只渲染与转发点击：
 * 展示物品由菜单按角色固定（H 金锭 / I 绿宝石 / J 草方块 / K 命令方块），
 * 不可用选项置灰 BARRIER（设计 02 §9：Vault 缺失 H 置灰；权限节点未配置
 * K 置灰；物品材质非法 J 置灰）。</p>
 *
 * @param payment   付款方式（VAULT / COIN / ITEM / PERMISSION 四选一）
 * @param available 该选项当前是否可选
 * @param value     应付展示文本组件（lang {@code menu.custom.price.*} 预组装；
 *                  物品方式含 translatable 物品名组件，由客户端按自身语言翻译）
 */
public record PaymentOptionView(ConditionType payment, boolean available, Component value) {

    /** 紧凑构造：付款方式与展示文本必填。 */
    public PaymentOptionView {
        Objects.requireNonNull(payment, "付款方式不能为空");
        Objects.requireNonNull(value, "应付展示文本不能为空");
    }
}
