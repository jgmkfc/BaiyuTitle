package net.baiyu.title.service;

import net.baiyu.title.domain.trade.TradeStatus;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 报价详情视图（表现层设计 §0.1：{@link TradeViewService} 预计算，
 * 报价详情菜单 RECEIVER / VIEWER 两态共用；DRAFT 态走会话草稿不经此）。
 *
 * <p>标的名称取<b>下单时快照</b>（归一化 MiniMessage，已套 config 括号段）
 * 而非目录现名——报价不可二次修改，展示与当时所见一致；图标按目录现状
 * 解析，称号已软删则兜底 NAME_TAG。</p>
 *
 * @param tradeId        交易 id
 * @param status         有效状态（过期未收敛的 PENDING 视为 EXPIRED）
 * @param statusKey      状态文案语言键
 * @param initiatorUuid  发起方 UUID
 * @param initiatorName  发起方名称
 * @param targetUuid     目标方 UUID
 * @param targetName     目标方名称
 * @param role           观察者角色（决定菜单三态中的 RECEIVER / VIEWER）
 * @param initiatorItems 发起方标的视图列表
 * @param targetItems    目标方标的视图列表
 * @param createdText    创建时刻展示串
 * @param expiresText    过期时刻展示串
 * @param resolvedByText 解决者展示串（未解决时为占位文案）
 */
public record TradeDetailView(
        long tradeId,
        TradeStatus status,
        String statusKey,
        UUID initiatorUuid,
        String initiatorName,
        UUID targetUuid,
        String targetName,
        Role role,
        List<ItemView> initiatorItems,
        List<ItemView> targetItems,
        String createdText,
        String expiresText,
        String resolvedByText) {

    /** 紧凑构造：判空与标的列表防御拷贝。 */
    public TradeDetailView {
        Objects.requireNonNull(status, "交易状态不能为空");
        Objects.requireNonNull(statusKey, "状态语言键不能为空");
        Objects.requireNonNull(initiatorUuid, "发起方 UUID 不能为空");
        Objects.requireNonNull(initiatorName, "发起方名称不能为空");
        Objects.requireNonNull(targetUuid, "目标方 UUID 不能为空");
        Objects.requireNonNull(targetName, "目标方名称不能为空");
        Objects.requireNonNull(role, "观察者角色不能为空");
        initiatorItems = List.copyOf(Objects.requireNonNull(initiatorItems, "发起方标的列表不能为空"));
        targetItems = List.copyOf(Objects.requireNonNull(targetItems, "目标方标的列表不能为空"));
        Objects.requireNonNull(createdText, "创建时刻展示串不能为空");
        Objects.requireNonNull(expiresText, "过期时刻展示串不能为空");
        Objects.requireNonNull(resolvedByText, "解决者展示串不能为空");
    }

    /** 观察者角色（目标方对 PENDING 报价可同意 / 拒绝）。 */
    public enum Role {

        /** 接收方（PENDING 报价的目标方；C 同意 / D 拒绝）。 */
        RECEIVER,

        /** 只读观察者（发起方回看、管理端、终态报价——§11⑤ 仅附魔浏览标记）。 */
        VIEWER
    }

    /**
     * 标的条目视图（名称快照组件 + 目录现状图标）。
     *
     * @param ownershipId 所有权实例 id（浏览标记的键）
     * @param titleId     称号 id
     * @param name        展示名称组件（快照 + config 括号段）
     * @param icon        图标材质（目录缺失兜底 NAME_TAG）
     */
    public record ItemView(long ownershipId, int titleId, Component name, Material icon) {

        /** 紧凑构造：判空。 */
        public ItemView {
            Objects.requireNonNull(name, "展示名称不能为空");
            Objects.requireNonNull(icon, "图标材质不能为空");
        }
    }
}
