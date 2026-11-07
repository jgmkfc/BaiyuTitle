package net.baiyu.title.domain.title;

import net.baiyu.title.domain.condition.PurchaseCondition;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@link Title} 构建器：用于编辑单字段后重建不可变实例。
 * <p>默认值对齐 V1 表列 DEFAULT（category=NORMAL、combine_logic=AND、visible=TRUE、
 * tradable=TRUE、icon_material=minecraft:name_tag、hold_duration_days=0、
 * limit_mode=NONE、sold_count=0）。
 * <p>所有不变量校验集中在 {@link Title} 紧凑构造器，{@link #build()} 时统一触发。
 */
public final class TitleBuilder {

    private int id;
    private String name;
    private String namePlain;
    private TitleCategory category = TitleCategory.NORMAL;
    private CombineLogic combineLogic = CombineLogic.AND;
    private boolean visible = true;
    private boolean tradable = true;
    private String rarity;
    private List<String> description = List.of();
    private String iconMaterial = "minecraft:name_tag";
    private int holdDurationDays;
    private LimitMode limitMode = LimitMode.NONE;
    private LocalDate windowStart;
    private LocalDate windowEnd;
    private Integer totalCap;
    private int soldCount;
    private Integer groupId;
    private UUID createdBy;
    private String createdByName;
    private long createdAt;
    private long updatedAt;
    private Long deletedAt;
    private List<PurchaseCondition> conditions = List.of();
    private Set<Integer> bondRequirements = Set.of();

    /** 创建带默认值的空白构建器。 */
    TitleBuilder() {
    }

    /** 以既有称号预填全部字段。 */
    TitleBuilder(Title source) {
        this.id = source.id();
        this.name = source.name();
        this.namePlain = source.namePlain();
        this.category = source.category();
        this.combineLogic = source.combineLogic();
        this.visible = source.visible();
        this.tradable = source.tradable();
        this.rarity = source.rarity();
        this.description = source.description();
        this.iconMaterial = source.iconMaterial();
        this.holdDurationDays = source.holdDurationDays();
        this.limitMode = source.limitMode();
        this.windowStart = source.windowStart();
        this.windowEnd = source.windowEnd();
        this.totalCap = source.totalCap();
        this.soldCount = source.soldCount();
        this.groupId = source.groupId();
        this.createdBy = source.createdBy();
        this.createdByName = source.createdByName();
        this.createdAt = source.createdAt();
        this.updatedAt = source.updatedAt();
        this.deletedAt = source.deletedAt();
        this.conditions = source.conditions();
        this.bondRequirements = source.bondRequirements();
    }

    /** 设置主键。 */
    public TitleBuilder id(int id) { this.id = id; return this; }

    /** 设置归一化 MiniMessage 名称。 */
    public TitleBuilder name(String name) { this.name = name; return this; }

    /** 设置剥色纯文本名称。 */
    public TitleBuilder namePlain(String namePlain) { this.namePlain = namePlain; return this; }

    /** 设置称号种类。 */
    public TitleBuilder category(TitleCategory category) { this.category = category; return this; }

    /** 设置条件组合逻辑。 */
    public TitleBuilder combineLogic(CombineLogic combineLogic) { this.combineLogic = combineLogic; return this; }

    /** 设置商店可见性。 */
    public TitleBuilder visible(boolean visible) { this.visible = visible; return this; }

    /** 设置可交易性（BOND/CUSTOM/GROUP 构建时会被强制覆写为 false）。 */
    public TitleBuilder tradable(boolean tradable) { this.tradable = tradable; return this; }

    /** 设置稀有度名称字符串（可空）。 */
    public TitleBuilder rarity(String rarity) { this.rarity = rarity; return this; }

    /** 设置描述多行文本。 */
    public TitleBuilder description(List<String> description) { this.description = description; return this; }

    /** 设置图标材质 namespaced key。 */
    public TitleBuilder iconMaterial(String iconMaterial) { this.iconMaterial = iconMaterial; return this; }

    /** 设置持有有效期天数（0 = 永久）。 */
    public TitleBuilder holdDurationDays(int holdDurationDays) { this.holdDurationDays = holdDurationDays; return this; }

    /** 设置获取限制模式。 */
    public TitleBuilder limitMode(LimitMode limitMode) { this.limitMode = limitMode; return this; }

    /** 设置窗口起始日期（仅 WINDOW 有效）。 */
    public TitleBuilder windowStart(LocalDate windowStart) { this.windowStart = windowStart; return this; }

    /** 设置窗口截止日期（仅 WINDOW 有效）。 */
    public TitleBuilder windowEnd(LocalDate windowEnd) { this.windowEnd = windowEnd; return this; }

    /** 设置售卖总数上限（仅 TOTAL 有效）。 */
    public TitleBuilder totalCap(Integer totalCap) { this.totalCap = totalCap; return this; }

    /** 设置已售份数。 */
    public TitleBuilder soldCount(int soldCount) { this.soldCount = soldCount; return this; }

    /** 设置所属玩家组 id（仅 GROUP 有效）。 */
    public TitleBuilder groupId(Integer groupId) { this.groupId = groupId; return this; }

    /** 设置创建者 UUID（可空）。 */
    public TitleBuilder createdBy(UUID createdBy) { this.createdBy = createdBy; return this; }

    /** 设置创建者名称缓存（可空）。 */
    public TitleBuilder createdByName(String createdByName) { this.createdByName = createdByName; return this; }

    /** 设置创建时刻（UTC epoch 毫秒）。 */
    public TitleBuilder createdAt(long createdAt) { this.createdAt = createdAt; return this; }

    /** 设置更新时刻（UTC epoch 毫秒）。 */
    public TitleBuilder updatedAt(long updatedAt) { this.updatedAt = updatedAt; return this; }

    /** 设置软删时刻（可空）。 */
    public TitleBuilder deletedAt(Long deletedAt) { this.deletedAt = deletedAt; return this; }

    /** 设置购买条件列表（仅 NORMAL 有效）。 */
    public TitleBuilder conditions(List<PurchaseCondition> conditions) { this.conditions = conditions; return this; }

    /** 设置羁绊需求称号 id 集合（仅 BOND 有效）。 */
    public TitleBuilder bondRequirements(Set<Integer> bondRequirements) { this.bondRequirements = bondRequirements; return this; }

    /**
     * 构建不可变称号实例（触发 {@link Title} 紧凑构造器的全部不变量校验）。
     *
     * @return 新的不可变称号
     */
    public Title build() {
        return new Title(id, name, namePlain, category, combineLogic, visible, tradable,
                rarity, description, iconMaterial, holdDurationDays, limitMode,
                windowStart, windowEnd, totalCap, soldCount, groupId,
                createdBy, createdByName, createdAt, updatedAt, deletedAt,
                conditions, bondRequirements);
    }
}
