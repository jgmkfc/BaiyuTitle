package net.baiyu.title.gui.shop;

import net.baiyu.title.domain.title.Title;
import net.baiyu.title.service.purchase.Eligibility;
import net.baiyu.title.service.purchase.PurchaseSnapshot;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 商店条目视图对象：由 {@code ShopQueryService} 预计算，
 * 菜单与渲染层只消费不判断——购买资格、个人状态与展示素材全部就绪。
 *
 * <p>渲染契约：{@code eligibility} 三分支决定 lore 底行
 * （可购买 → 点击购买、已拥有 → 跳转仓库、不可购 → 无法购买）；
 * {@code snapshot} 提供个人状态区余额 / 物品数 / 权限持有依据；
 * {@code itemNames} 为条件物品的本地化名组件（键与 {@code ItemCondition#itemBase64}
 * 逐字节一致，同一序列化产物；值为 translatable 组件，由客户端按自身语言翻译）。</p>
 *
 * @param title            称号聚合（目录缓存快照）
 * @param eligibility      购买资格判定结果（{@code EligibilityEngine} 输出）
 * @param equipped         已拥有时是否装扮中（被任一栏位固定引用或随机池收录）
 * @param snapshot         玩家购买上下文快照（个人状态区渲染依据）
 * @param itemNames        条件物品 Base64 → 本地化物品名组件
 * @param groupName        所属组名（仅 GROUP 称号；组模块接入前恒为 null，
 *                         null 时所属组行整行省略）
 * @param bondRequirements 羁绊需求视图（仅 BOND 称号；按称号 id 升序）
 */
public record ShopEntryView(
        Title title,
        Eligibility eligibility,
        boolean equipped,
        PurchaseSnapshot snapshot,
        Map<String, Component> itemNames,
        @Nullable String groupName,
        List<BondRequirementView> bondRequirements) {

    /** 紧凑构造：判空 + 防御性拷贝。 */
    public ShopEntryView {
        Objects.requireNonNull(title, "称号不能为空");
        Objects.requireNonNull(eligibility, "购买资格不能为空");
        Objects.requireNonNull(snapshot, "上下文快照不能为空");
        itemNames = itemNames == null ? Map.of() : Map.copyOf(itemNames);
        bondRequirements = bondRequirements == null ? List.of() : List.copyOf(bondRequirements);
    }

    /**
     * 羁绊需求条目视图（业务设计 §1.3.2.4.1 羁绊示例：
     * 需要称号列表 + 个人状态区逐项 拥有/暂无）。
     *
     * @param name  需求称号名称（归一化 MiniMessage，未加括号段）
     * @param owned 玩家是否 ACTIVE 持有该需求称号
     */
    public record BondRequirementView(String name, boolean owned) {

        /** 紧凑构造：名称必填。 */
        public BondRequirementView {
            Objects.requireNonNull(name, "需求称号名称不能为空");
        }
    }
}
