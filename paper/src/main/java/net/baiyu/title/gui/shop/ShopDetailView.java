package net.baiyu.title.gui.shop;

import java.util.Objects;

/**
 * 商店详情视图对象：详情菜单的 T 键完整称号副本
 * 复用 {@link ShopEntryView} 渲染；H（创建时间 / 获取窗口 / 持有有效期）与
 * J（隐藏信息）的数据全部取自 {@code entry().title()} 的目录缓存快照。
 *
 * <p>I 键统计信息（购买次数 / 购买玩家总数 / 购买者分页列表）需要订单表
 * 聚合查询，随详情菜单实装（buyers(titleId, page) 异步入口）扩展本视图，
 * 本阶段不预置空壳字段。</p>
 *
 * @param entry 商店条目视图（含称号聚合与购买资格）
 */
public record ShopDetailView(ShopEntryView entry) {

    /** 紧凑构造：条目必填。 */
    public ShopDetailView {
        Objects.requireNonNull(entry, "商店条目不能为空");
    }
}
