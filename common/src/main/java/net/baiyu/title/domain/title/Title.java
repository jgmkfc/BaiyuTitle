package net.baiyu.title.domain.title;

import net.baiyu.title.domain.condition.PurchaseCondition;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 称号聚合根（对应 {@code title} 表+ 条件/羁绊需求聚合装载）。
 * <p>不可变 record：集合字段构造时防御性拷贝；编辑单字段请使用 {@link #toBuilder()}。
 * <p>构造时校验/归一化的不变量：
 * <ul>
 *   <li>2：每条件类型至多一条；条件表仅 NORMAL 使用（其余种类静默清空）。</li>
 *   <li>3：limit_mode 三选一；WINDOW ⇒ 截止日非空；TOTAL ⇒ totalCap ≥ 1；非对应模式的字段静默置空。</li>
 *   <li>5：BOND/CUSTOM/GROUP 的 tradable 恒 false（静默强制覆写）。</li>
 *   <li>6：羁绊需求不含自身；羁绊需求仅 BOND 使用（其余种类静默清空）。非空校验由服务层负责
 *       （创建 BOND 称号后再设置需求的过渡态需要放行）。</li>
 * </ul>
 *
 * @param id               主键（未落库时为 0）
 * @param name             归一化 MiniMessage 名称
 * @param namePlain        剥色纯文本名称（搜索/长度/排序用）
 * @param category         称号种类
 * @param combineLogic     购买条件组合逻辑
 * @param visible          商店可见性（GROUP/CUSTOM 默认 false，由服务层决定）
 * @param tradable         可交易性（BOND/CUSTOM/GROUP 恒 false）
 * @param rarity           稀有度名称字符串（可空；运行时按 config 列表位置解析排序）
 * @param description      描述多行文本（{@code |} 输入已转多行）
 * @param iconMaterial     图标材质 namespaced key（默认 minecraft:name_tag）
 * @param holdDurationDays 持有有效期天数（0 = 永久）
 * @param limitMode        获取限制模式
 * @param windowStart      窗口起始日期（仅 WINDOW；可空 = 只有截止日）
 * @param windowEnd        窗口截止日期（仅 WINDOW；WINDOW 时必填）
 * @param totalCap         售卖总数上限（仅 TOTAL；TOTAL 时必填且 ≥ 1）
 * @param soldCount        已售份数（预留计数器）
 * @param groupId          所属玩家组 id（仅 GROUP；可空）
 * @param createdBy        创建者 UUID（可空；控制台创建为空）
 * @param createdByName    创建者名称缓存（可空）
 * @param createdAt        创建时刻（UTC epoch 毫秒）
 * @param updatedAt        更新时刻（UTC epoch 毫秒）
 * @param deletedAt        软删时刻（可空；非空即已软删）
 * @param conditions       购买条件列表（仅 NORMAL；空集 = 免费）
 * @param bondRequirements 羁绊需求称号 id 集合（仅 BOND）
 */
public record Title(
        int id,
        String name,
        String namePlain,
        TitleCategory category,
        CombineLogic combineLogic,
        boolean visible,
        boolean tradable,
        String rarity,
        List<String> description,
        String iconMaterial,
        int holdDurationDays,
        LimitMode limitMode,
        LocalDate windowStart,
        LocalDate windowEnd,
        Integer totalCap,
        int soldCount,
        Integer groupId,
        UUID createdBy,
        String createdByName,
        long createdAt,
        long updatedAt,
        Long deletedAt,
        List<PurchaseCondition> conditions,
        Set<Integer> bondRequirements) {

    /**
     * 紧凑构造器：判空、防御性拷贝、不变量校验与静默归一化。
     */
    public Title {
        Objects.requireNonNull(name, "称号名称不能为空");
        Objects.requireNonNull(namePlain, "称号纯文本名称不能为空");
        Objects.requireNonNull(category, "称号种类不能为空");
        Objects.requireNonNull(combineLogic, "条件组合逻辑不能为空");
        Objects.requireNonNull(iconMaterial, "图标材质不能为空");
        Objects.requireNonNull(limitMode, "获取限制模式不能为空");
        if (holdDurationDays < 0) {
            throw new IllegalArgumentException("持有有效期天数不能为负: " + holdDurationDays);
        }
        if (soldCount < 0) {
            throw new IllegalArgumentException("已售份数不能为负: " + soldCount);
        }

        // 防御性拷贝（null 视为空集合）
        description = description == null ? List.of() : List.copyOf(description);
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        bondRequirements = bondRequirements == null ? Set.of() : Set.copyOf(bondRequirements);

        // 不变量 5：BOND/CUSTOM/GROUP 强制不可交易（静默覆写）
        if (category != TitleCategory.NORMAL) {
            tradable = false;
        }
        // 条件表仅 NORMAL 使用（GROUP/BOND/CUSTOM 条件恒空集，静默清空）
        if (category != TitleCategory.NORMAL) {
            conditions = List.of();
        }
        // 羁绊需求仅 BOND 使用（静默清空）
        if (category != TitleCategory.BOND) {
            bondRequirements = Set.of();
        }
        // 不变量 2：每条件类型至多一条
        long distinctTypes = conditions.stream().map(PurchaseCondition::type).distinct().count();
        if (distinctTypes != conditions.size()) {
            throw new IllegalArgumentException("同一条件类型至多一条购买条件");
        }
        // 不变量 6：羁绊需求不得包含自身（id 未落库为 0 时跳过）
        if (id > 0 && bondRequirements.contains(id)) {
            throw new IllegalArgumentException("羁绊需求不能包含称号自身: id=" + id);
        }
        // 不变量 3：limit_mode 三选一，非对应模式字段静默置空
        switch (limitMode) {
            case NONE -> {
                windowStart = null;
                windowEnd = null;
                totalCap = null;
            }
            case WINDOW -> {
                if (windowEnd == null) {
                    throw new IllegalArgumentException("WINDOW 模式必须提供窗口截止日期");
                }
                if (windowStart != null && windowStart.isAfter(windowEnd)) {
                    throw new IllegalArgumentException(
                            "窗口起始日期不能晚于截止日期: " + windowStart + " > " + windowEnd);
                }
                totalCap = null;
            }
            case TOTAL -> {
                if (totalCap == null || totalCap < 1) {
                    throw new IllegalArgumentException("TOTAL 模式必须提供 ≥ 1 的售卖总数上限: " + totalCap);
                }
                windowStart = null;
                windowEnd = null;
            }
        }
    }

    /**
     * 是否已软删（软删后不可见/不可购/不可装扮，历史完整保留）。
     *
     * @return 已软删返回 true
     */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * 是否免费称号（设计裁决：FREE ⇔ 条件空集，仅对 NORMAL 有意义，
     * 结构上保证"免费不可组合"）。
     *
     * @return 免费返回 true
     */
    public boolean isFree() {
        return category == TitleCategory.NORMAL && conditions.isEmpty();
    }

    /**
     * 判断给定日期是否处于获取窗口内（闭区间；非 WINDOW 模式恒为 true）。
     *
     * @param today 服务器本地当天日期
     * @return 可获取返回 true
     */
    public boolean isWithinWindow(LocalDate today) {
        if (limitMode != LimitMode.WINDOW) {
            return true;
        }
        return new AcquireWindow(windowStart, windowEnd).contains(today);
    }

    /**
     * 是否已售罄（仅 TOTAL 模式有意义；非 TOTAL 恒为 false）。
     *
     * @return 售罄返回 true
     */
    public boolean isSoldOut() {
        return limitMode == LimitMode.TOTAL && totalCap != null && soldCount >= totalCap;
    }

    /**
     * 获取窗口值对象（仅 WINDOW 模式存在）。
     *
     * @return 窗口值对象；非 WINDOW 模式返回空
     */
    public Optional<AcquireWindow> acquireWindow() {
        if (limitMode != LimitMode.WINDOW) {
            return Optional.empty();
        }
        return Optional.of(new AcquireWindow(windowStart, windowEnd));
    }

    /**
     * 以当前值为基础创建构建器，便于编辑单字段后重建不可变实例。
     *
     * @return 预填当前所有字段的构建器
     */
    public TitleBuilder toBuilder() {
        return new TitleBuilder(this);
    }

    /**
     * 创建带默认值的空白构建器（默认值对齐 V1 表列 DEFAULT）。
     *
     * @return 新构建器
     */
    public static TitleBuilder builder() {
        return new TitleBuilder();
    }
}
