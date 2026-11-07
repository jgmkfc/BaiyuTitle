package net.baiyu.title.service;

import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.wardrobe.SlotMode;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 装扮视图对象（{@link WardrobeViewService#getView} 的产出）：
 * 服务预计算的不可变快照，菜单只渲染与转发点击、不做业务判断。
 *
 * @param maxSlots    栏位上限（主线程预解析的权限快照）
 * @param slots       栏位视图（1..maxSlots 逐个，升序）
 * @param ownedTitles 持有称号视图（按称号 id 升序）
 */
public record WardrobeView(int maxSlots, List<SlotView> slots, List<OwnedTitle> ownedTitles) {

    /** 紧凑构造：列表冻结。 */
    public WardrobeView {
        slots = List.copyOf(slots);
        ownedTitles = List.copyOf(ownedTitles);
    }

    /**
     * 单栏位视图。
     *
     * @param slotNo     栏位号（1 起）
     * @param enabled    是否启用
     * @param mode       栏位模式
     * @param fixedTitle 固定佩戴的有效称号（默认称号 / 引用已失效 / 随机模式时为 null）
     * @param randomPool 随机候选池中仍有效的称号 id（升序；非随机模式为空集）
     */
    public record SlotView(int slotNo, boolean enabled, SlotMode mode,
                           @Nullable Title fixedTitle, Set<Integer> randomPool) {

        /** 紧凑构造：模式必填、池冻结。 */
        public SlotView {
            Objects.requireNonNull(mode, "栏位模式不能为空");
            randomPool = Set.copyOf(randomPool);
        }

        /** @return 是否随机模式 */
        public boolean isRandom() {
            return mode == SlotMode.RANDOM;
        }
    }

    /**
     * 持有称号视图（仓库 / 称号选择菜单条目源）。
     *
     * @param title              称号（目录快照，未软删）
     * @param expiresOn          到期日期（null = 永久）
     * @param referencedAnywhere 是否被任一栏位引用（仓库上下文的"已装扮"判据）
     */
    public record OwnedTitle(Title title, @Nullable LocalDate expiresOn, boolean referencedAnywhere) {

        /** 紧凑构造：称号必填。 */
        public OwnedTitle {
            Objects.requireNonNull(title, "称号不能为空");
        }
    }
}
