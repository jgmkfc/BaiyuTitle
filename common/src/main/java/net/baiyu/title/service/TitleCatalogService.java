package net.baiyu.title.service;

import net.baiyu.title.cache.PlayerProfileCache;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.repository.SlotRepository;
import net.baiyu.title.repository.TitleRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.executor.DbExecutor;
import net.baiyu.title.text.ColorNormalizer;
import net.baiyu.title.text.PlainTextExtractor;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

/**
 * 称号目录服务：称号的创建/编辑/软删唯一入口。
 * <p>模式统一：写操作 = 写线程事务 + 提交后缓存写穿；参数校验失败以
 * {@link IllegalArgumentException} 异常完成 future。
 * <p>服务层强制的不变量（§十）：BOND/CUSTOM/GROUP 恒 tradable=false（领域层覆写）；
 * 免费 = 条件空集；limit_mode 切换时另一模式字段由领域层联动清理。
 */
public final class TitleCatalogService {

    /** 称号名剥色后最大码点数。 */
    private static final int MAX_NAME_CODE_POINTS = 50;

    /** 称号仓储。 */
    private final TitleRepository titleRepository;

    /** 装扮栏位仓储（软删时同事务卸下全部装扮引用）。 */
    private final SlotRepository slotRepository;

    /** 事务执行器。 */
    private final TransactionRunner txRunner;

    /** 数据库执行器。 */
    private final DbExecutor dbExecutor;

    /** 称号目录缓存（写穿目标）。 */
    private final TitleCatalogCache catalogCache;

    /** 玩家档案缓存（软删提交后清除在线玩家的栏位引用）。 */
    private final PlayerProfileCache profileCache;

    /**
     * 构造称号目录服务。
     *
     * @param titleRepository 称号仓储
     * @param slotRepository  装扮栏位仓储
     * @param txRunner        事务执行器
     * @param dbExecutor      数据库执行器
     * @param catalogCache    称号目录缓存
     * @param profileCache    玩家档案缓存
     */
    public TitleCatalogService(TitleRepository titleRepository, SlotRepository slotRepository,
                               TransactionRunner txRunner, DbExecutor dbExecutor,
                               TitleCatalogCache catalogCache, PlayerProfileCache profileCache) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "称号仓储不能为空");
        this.slotRepository = Objects.requireNonNull(slotRepository, "装扮栏位仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "数据库执行器不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.profileCache = Objects.requireNonNull(profileCache, "玩家档案缓存不能为空");
    }

    /**
     * 创建称号：名称归一化为 MiniMessage + 剥色长度校验 → 写线程事务插入 → 缓存写穿。
     * <p>默认值：所有新称号均不上架（visible=false），防止购买条件尚未配置时被免费获取；
     * 可交易性由领域层按种类强制。
     *
     * @param rawName  原始名称（三格式颜色码混用均可）
     * @param category 称号种类
     * @param creator  创建者
     * @return 含生成 id 的称号
     */
    public CompletableFuture<Title> create(String rawName, TitleCategory category, Actor creator) {
        Objects.requireNonNull(category, "称号种类不能为空");
        Objects.requireNonNull(creator, "创建者不能为空");
        String name = ColorNormalizer.normalizeName(rawName);
        String plain = validateName(name);
        return dbExecutor.submitWrite(() -> {
            long now = System.currentTimeMillis();
            Title fresh = Title.builder()
                    .name(name).namePlain(plain).category(category)
                    .visible(false)
                    .createdBy(creator.uuid()).createdByName(creator.name())
                    .createdAt(now).updatedAt(now)
                    .build();
            int id = txRunner.inTransaction(c -> titleRepository.insert(c, fresh));
            Title created = fresh.toBuilder().id(id).build();
            catalogCache.put(created);
            return created;
        });
    }

    /** 重命名：归一化 + 剥色长度校验后写入 name / name_plain 双列。 */
    public CompletableFuture<Title> rename(int titleId, String rawName) {
        String name = ColorNormalizer.normalizeName(rawName);
        String plain = validateName(name);
        return edit(titleId, t -> t.toBuilder().name(name).namePlain(plain).build());
    }

    /** 设置描述多行文本（{@code |} 分行由调用方先转 List）。 */
    public CompletableFuture<Title> setDescription(int titleId, List<String> description) {
        List<String> lines = description == null ? List.of() : List.copyOf(description);
        return edit(titleId, t -> t.toBuilder().description(lines).build());
    }

    /** 设置图标材质 namespaced key。 */
    public CompletableFuture<Title> setIcon(int titleId, String iconMaterial) {
        Objects.requireNonNull(iconMaterial, "图标材质不能为空");
        return edit(titleId, t -> t.toBuilder().iconMaterial(iconMaterial).build());
    }

    /** 设置稀有度名称字符串（null = 清除）。 */
    public CompletableFuture<Title> setRarity(int titleId, String rarity) {
        return edit(titleId, t -> t.toBuilder().rarity(rarity).build());
    }

    /** 设置商店可见性。 */
    public CompletableFuture<Title> setVisible(int titleId, boolean visible) {
        return edit(titleId, t -> t.toBuilder().visible(visible).build());
    }

    /** 设置可交易性（BOND/CUSTOM/GROUP 由领域层强制覆写为 false，不变量 §十 5）。 */
    public CompletableFuture<Title> setTradable(int titleId, boolean tradable) {
        return edit(titleId, t -> t.toBuilder().tradable(tradable).build());
    }

    /** 设置持有有效期天数（0 = 永久；只影响此后新授予，存量所有权不追溯）。 */
    public CompletableFuture<Title> setHoldDuration(int titleId, int holdDurationDays) {
        return edit(titleId, t -> t.toBuilder().holdDurationDays(holdDurationDays).build());
    }

    /**
     * 切换获取限制模式：NONE 联动清空窗口与总数上限（领域层保证）；
     * 切到 WINDOW/TOTAL 要求对应字段已存在，否则以参数异常完成。
     */
    public CompletableFuture<Title> setLimitMode(int titleId, LimitMode limitMode) {
        Objects.requireNonNull(limitMode, "获取限制模式不能为空");
        return edit(titleId, t -> t.toBuilder().limitMode(limitMode).build());
    }

    /** 设置获取窗口（闭区间，天粒度；自动切换 limit_mode=WINDOW 并清空总数上限）。 */
    public CompletableFuture<Title> setWindow(int titleId, LocalDate windowStart, LocalDate windowEnd) {
        return edit(titleId, t -> t.toBuilder()
                .limitMode(LimitMode.WINDOW).windowStart(windowStart).windowEnd(windowEnd).build());
    }

    /** 设置售卖总数上限（≥1；自动切换 limit_mode=TOTAL 并清空窗口）。 */
    public CompletableFuture<Title> setTotalCap(int titleId, int totalCap) {
        return edit(titleId, t -> t.toBuilder().limitMode(LimitMode.TOTAL).totalCap(totalCap).build());
    }

    /**
     * 全量替换购买条件（仅 NORMAL；空集 = 免费，不变量 §十 2）。
     * 条件行与主行 combine_logic 同事务落库。
     */
    public CompletableFuture<Title> setConditions(int titleId, CombineLogic logic,
                                                  List<PurchaseCondition> conditions) {
        Objects.requireNonNull(logic, "条件组合逻辑不能为空");
        List<PurchaseCondition> conds = conditions == null ? List.of() : List.copyOf(conditions);
        return edit(titleId, t -> {
            if (t.category() != TitleCategory.NORMAL && !conds.isEmpty()) {
                throw new IllegalArgumentException("仅普通称号可设置购买条件: id=" + titleId);
            }
            return t.toBuilder().combineLogic(logic).conditions(conds).build();
        }, (c, updated) -> titleRepository.replaceConditions(c, titleId, updated.conditions()));
    }

    /**
     * 全量替换羁绊需求（仅 BOND；需求非空、不含自身、每项必须存在，不变量 §十 6）。
     * <p>M8 接入 BondService 后由 paper 侧在本 future 完成后触发 backfill。
     */
    public CompletableFuture<Title> setBondRequirements(int bondId, Set<Integer> required) {
        Set<Integer> req = required == null ? Set.of() : Set.copyOf(required);
        return edit(bondId, t -> {
            if (t.category() != TitleCategory.BOND) {
                throw new IllegalArgumentException("仅羁绊称号可设置羁绊需求: id=" + bondId);
            }
            if (req.isEmpty()) {
                throw new IllegalArgumentException("羁绊需求不能为空: id=" + bondId);
            }
            for (Integer requiredId : req) {
                if (catalogCache.byId(requiredId).filter(x -> !x.isDeleted()).isEmpty()) {
                    throw new IllegalArgumentException("羁绊需求称号不存在或已删除: id=" + requiredId);
                }
            }
            return t.toBuilder().bondRequirements(req).build();
        }, (c, updated) -> titleRepository.replaceBondRequirements(c, bondId, updated.bondRequirements()));
    }

    /**
     * 软删称号：清可见性 + 置 deleted_at + 同事务卸下全部玩家的装扮引用。
     * 提交后同步清除在线玩家档案缓存中的栏位引用。
     */
    public CompletableFuture<Title> delete(int titleId) {
        return dbExecutor.submitWrite(() -> {
            Title current = requireExisting(titleId);
            long now = System.currentTimeMillis();
            Title deleted = current.toBuilder().visible(false).updatedAt(now).deletedAt(now).build();
            txRunner.inTransaction(c -> {
                titleRepository.update(c, deleted);
                titleRepository.softDelete(c, titleId, now);
                slotRepository.clearTitleForAllPlayers(c, titleId);
                return null;
            });
            catalogCache.put(deleted);
            profileCache.updateAll(profile -> profile.withSlotRefsCleared(titleId));
            return deleted;
        });
    }

    /**
     * 启动装载：全量读库（含软删行，供历史渲染取名）并整体替换缓存。
     * <p>走写线程与编辑操作串行化，避免 reload 与在途编辑交错。
     *
     * @return 装载的称号总数
     */
    public CompletableFuture<Integer> loadAllIntoCache() {
        return dbExecutor.submitWrite(() -> {
            List<Title> all = txRunner.withConnection(c -> titleRepository.loadAll(c, true));
            catalogCache.replaceAll(all);
            return all.size();
        });
    }

    /** 同步只读快照：全部未软删称号（含隐藏），按 id 升序。 */
    public List<Title> snapshot() {
        return catalogCache.all();
    }

    /** 同步只读查询：按 id 取称号（含软删行）。 */
    public Optional<Title> findById(int titleId) {
        return catalogCache.byId(titleId);
    }

    /**
     * 通用编辑骨架（无额外事务写入）。
     */
    private CompletableFuture<Title> edit(int titleId, UnaryOperator<Title> change) {
        return edit(titleId, change, (c, updated) -> { });
    }

    /**
     * 通用编辑骨架：写线程取缓存现值 → 变换重建（触发领域不变量校验）→
     * 事务 update + 额外写入 → 提交后缓存写穿。
     */
    private CompletableFuture<Title> edit(int titleId, UnaryOperator<Title> change, TxWork extra) {
        return dbExecutor.submitWrite(() -> {
            Title current = requireExisting(titleId);
            Title updated = change.apply(current).toBuilder()
                    .updatedAt(System.currentTimeMillis()).build();
            txRunner.inTransaction(c -> {
                titleRepository.update(c, updated);
                extra.run(c, updated);
                return null;
            });
            catalogCache.put(updated);
            return updated;
        });
    }

    /**
     * 从缓存取未软删称号；不存在或已软删时抛参数异常。
     */
    private Title requireExisting(int titleId) {
        return catalogCache.byId(titleId)
                .filter(t -> !t.isDeleted())
                .orElseThrow(() -> new IllegalArgumentException("称号不存在或已删除: id=" + titleId));
    }

    /**
     * 名称校验（名称规则唯一实现，自定义订单链同样复用）：
     * 剥色后非空且 ≤ {@value MAX_NAME_CODE_POINTS} 码点。
     *
     * @param normalizedName 已归一化的 MiniMessage 名称
     * @return 剥色纯文本（name_plain 列值）
     * @throws IllegalArgumentException 剥色后为空或超长时
     */
    public static String validateName(String normalizedName) {
        String plain = PlainTextExtractor.extract(normalizedName);
        if (plain.isBlank()) {
            throw new IllegalArgumentException("称号名称剥色后不能为空");
        }
        if (plain.codePointCount(0, plain.length()) > MAX_NAME_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "称号名称剥色后不能超过 " + MAX_NAME_CODE_POINTS + " 码点: " + plain);
        }
        return plain;
    }

    /**
     * 编辑事务内的额外写入（如条件/羁绊需求替换）。
     */
    @FunctionalInterface
    private interface TxWork {

        /**
         * 在编辑事务内执行额外写入。
         *
         * @param c       事务连接
         * @param updated 已重建的新称号
         * @throws SQLException JDBC 失败时
         */
        void run(Connection c, Title updated) throws SQLException;
    }
}
