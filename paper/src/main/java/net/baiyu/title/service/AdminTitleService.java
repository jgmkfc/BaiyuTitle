package net.baiyu.title.service;

import net.baiyu.title.domain.condition.CoinCondition;
import net.baiyu.title.domain.condition.ConditionType;
import net.baiyu.title.domain.condition.ItemCondition;
import net.baiyu.title.domain.condition.PermissionCondition;
import net.baiyu.title.domain.condition.PurchaseCondition;
import net.baiyu.title.domain.condition.VaultCondition;
import net.baiyu.title.domain.title.CombineLogic;
import net.baiyu.title.domain.title.LimitMode;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.gui.admin.BuyType;
import net.baiyu.title.repository.OrderRepository;
import net.baiyu.title.repository.TransactionRunner;
import net.baiyu.title.service.executor.DbExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 管理端称号编辑门面：管理详情菜单 19 项操作
 * 适配 {@link TitleCatalogService}，并产出编辑器视图 {@link TitleEditorView}；
 * 兼收管理菜单族的两项跨服务操作——管理玩家仓库收回（{@link #revoke}）
 * 与购买者列表查询（{@link #fulfilledBuyers}）。
 *
 * <p>线程约定：查询（{@link #allTitles} / {@link #editorView}）只读缓存快照，
 * 任意线程可调；变更方法主线程发起（读手持物品的两项<b>必须</b>主线程），
 * 内部经目录服务调度到 DB 写线程并返回 future。读-改-写以调用时缓存现值为基，
 * 管理端单人编辑场景可接受；最终不变量仍由领域层在写线程守住。</p>
 *
 * <p>清价联动：{@link #setBuyTypes} 经
 * {@code setConditions} 结构化实现——被取消类型的价格条件整条清除；
 * 价格设 0 = 清除该条件（免费 = 条件空集的结构约定）。</p>
 */
public final class AdminTitleService {

    /** 管理端物品价格最大数量：36 格背包 × 64 堆叠。 */
    public static final int MAX_ITEM_COST_AMOUNT = 2_304;

    /** 称号目录服务（目录写操作的唯一落点）。 */
    private final TitleCatalogService catalog;

    /** 所有权服务（管理玩家仓库的收回入口）。 */
    private final OwnershipService ownerships;

    /** 购买订单仓储（购买者列表查询）。 */
    private final OrderRepository orders;

    /** 事务执行器（读路径借连接）。 */
    private final TransactionRunner txRunner;

    /** 数据库执行器（读池调度）。 */
    private final DbExecutor dbExecutor;

    /**
     * 构造管理端称号编辑门面。
     *
     * @param catalog    称号目录服务
     * @param ownerships 所有权服务
     * @param orders     购买订单仓储
     * @param txRunner   事务执行器
     * @param dbExecutor 数据库执行器
     */
    public AdminTitleService(TitleCatalogService catalog, OwnershipService ownerships,
                             OrderRepository orders, TransactionRunner txRunner,
                             DbExecutor dbExecutor) {
        this.catalog = Objects.requireNonNull(catalog, "称号目录服务不能为空");
        this.ownerships = Objects.requireNonNull(ownerships, "所有权服务不能为空");
        this.orders = Objects.requireNonNull(orders, "购买订单仓储不能为空");
        this.txRunner = Objects.requireNonNull(txRunner, "事务执行器不能为空");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "数据库执行器不能为空");
    }

    /** @return 全部未软删称号（含隐藏，id 升序）——管理主菜单数据源 */
    public List<Title> allTitles() {
        return catalog.snapshot();
    }

    /**
     * 编辑器视图（19 项当前值展示素材；纯缓存读取，异步 loadData 可调）。
     *
     * @param titleId 称号 id
     * @return 视图；称号不存在或已软删时为空
     */
    public Optional<TitleEditorView> editorView(int titleId) {
        return catalog.findById(titleId).filter(t -> !t.isDeleted()).map(TitleEditorView::new);
    }

    /** 项 1 重命名（归一化与长度校验由目录服务负责）。 */
    public CompletableFuture<Title> rename(int titleId, String rawName) {
        return catalog.rename(titleId, rawName);
    }

    /** 项 2 设置简介：{@code |} 分行后落库。 */
    public CompletableFuture<Title> setDescription(int titleId, String rawInput) {
        Objects.requireNonNull(rawInput, "简介输入不能为空");
        return catalog.setDescription(titleId, Arrays.asList(rawInput.split("\\|", -1)));
    }

    /** 项 3 录入指定物品为图标（物品由持物左键输入会话在主线程捕获）。 */
    public CompletableFuture<Title> setIcon(int titleId, ItemStack item) {
        final ItemStack captured = Objects.requireNonNull(item, "图标物品不能为空");
        if (captured.getType().isAir()) {
            return failed("图标物品为空，无法录入图标: id=" + titleId);
        }
        return catalog.setIcon(titleId, captured.getType().getKey().toString());
    }

    /**
     * @deprecated 请由持物输入会话捕获点击瞬间的物品，再调用 {@link #setIcon(int, ItemStack)}。
     */
    @Deprecated
    public CompletableFuture<Title> setIconFromHand(Player player, int titleId) {
        ItemStack hand = Objects.requireNonNull(player, "玩家不能为空").getInventory().getItemInMainHand();
        return setIcon(titleId, hand);
    }

    /** 项 4 设置稀有度（null = 无稀有度）。 */
    public CompletableFuture<Title> setRarity(int titleId, String rarity) {
        return catalog.setRarity(titleId, rarity);
    }

    /** 项 5 切换公开状态（可见 ↔ 隐藏）。 */
    public CompletableFuture<Title> toggleVisible(int titleId) {
        return withCurrent(titleId, current -> catalog.setVisible(titleId, !current.visible()));
    }

    /**
     * 项 6 应用购买类型集（清价联动）：被取消类型的价格条件整条清除，
     * 新启用类型待管理员录入价格后才产生条件行（免费/羁绊/组称号互斥）。
     * <p>校验与条件表推演内聚 {@link BuyTypeEdits#plan}（纯函数专测覆盖）；
     * 种类锁定称号与现状一致时空操作成功，否则以参数异常完成。</p>
     */
    public CompletableFuture<Title> setBuyTypes(int titleId, Set<BuyType> types) {
        Objects.requireNonNull(types, "购买类型集不能为空");
        return withCurrent(titleId, current -> switch (BuyTypeEdits.plan(current, types)) {
            case BuyTypeEdits.Plan.Reject reject -> failed(reject.reason());
            case BuyTypeEdits.Plan.NoOp ignored -> CompletableFuture.completedFuture(current);
            case BuyTypeEdits.Plan.Apply apply ->
                    catalog.setConditions(titleId, apply.logic(), apply.conditions());
        });
    }

    /** 项 7 切换组合逻辑（AND ↔ OR；条件列表原样保留）。 */
    public CompletableFuture<Title> toggleLogic(int titleId) {
        return withCurrent(titleId, current -> catalog.setConditions(titleId,
                current.combineLogic() == CombineLogic.AND ? CombineLogic.OR : CombineLogic.AND,
                current.conditions()));
    }

    /** 项 8 切换可交易性（BOND/CUSTOM/GROUP 由领域层强制 false，菜单侧禁用态拦截）。 */
    public CompletableFuture<Title> toggleTradable(int titleId) {
        return withCurrent(titleId, current -> catalog.setTradable(titleId, !current.tradable()));
    }

    /** 项 9 设置持有有效期天数（0 = 永久）。 */
    public CompletableFuture<Title> setHoldDuration(int titleId, int days) {
        return catalog.setHoldDuration(titleId, days);
    }

    /** 项 11 设置金币价格（0 = 清除该条件）。 */
    public CompletableFuture<Title> setVaultPrice(int titleId, BigDecimal amount) {
        Objects.requireNonNull(amount, "金额不能为空");
        return replaceCondition(titleId, ConditionType.VAULT,
                amount.signum() == 0 ? null : new VaultCondition(amount));
    }

    /** 项 12 设置称号币价格（0 = 清除该条件）。 */
    public CompletableFuture<Title> setCoinPrice(int titleId, long amount) {
        return replaceCondition(titleId, ConditionType.COIN,
                amount == 0 ? null : new CoinCondition(amount));
    }

    /**
     * 项 13 录入物品价格（仅主线程；空物品或非法数量以参数异常完成）。
     * <p>物品归一为单件序列化（{@code asOne()}），数量由聊天输入给出——
     * 与购买判定按 {@code isSimilar} 跨背包计数的口径一致。</p>
     */
    public CompletableFuture<Title> setItemCost(int titleId, ItemStack item, int amount) {
        ItemStack costItem = Objects.requireNonNull(item, "物品价格不能为空");
        if (costItem.getType().isAir()) {
            return failed("手持物品为空，无法录入物品价格: id=" + titleId);
        }
        if (amount < 1 || amount > MAX_ITEM_COST_AMOUNT) {
            return failed("物品价格数量必须在 1 到 " + MAX_ITEM_COST_AMOUNT + " 之间: " + amount);
        }
        return replaceCondition(titleId, ConditionType.ITEM,
                new ItemCondition(ItemStackCodec.encode(costItem.asOne()), amount));
    }

    /** 项 14 设置权限节点条件。 */
    public CompletableFuture<Title> setPermissionNode(int titleId, String node) {
        return replaceCondition(titleId, ConditionType.PERMISSION, new PermissionCondition(node));
    }

    /** 项 15 全量替换羁绊需求（存在性/自引用校验由目录服务在写线程执行）。 */
    public CompletableFuture<Title> setBondRequirements(int bondId, Set<Integer> required) {
        return catalog.setBondRequirements(bondId, required);
    }

    /**
     * 项 16 限定模式切换：WINDOW/TOTAL → NONE（清除当前限定及其字段）。
     * <p>裁决偏离说明（三态循环）：切入 WINDOW/TOTAL 要求对应
     * 字段已存在，而领域层在模式切换时清空另一模式字段——正向循环结构上不可达；
     * 项 17/18 录入数据即自动启用对应模式，本项承担「清除限定」一步。</p>
     */
    public CompletableFuture<Title> cycleLimitMode(int titleId) {
        return withCurrent(titleId, current -> current.limitMode() == LimitMode.NONE
                ? failed("当前无限定可清除，请先经获取窗口/总数上限录入数据: id=" + titleId)
                : catalog.setLimitMode(titleId, LimitMode.NONE));
    }

    /** 项 17 设置获取窗口（闭区间；单日期输入的起始补全由输入解析层完成）。 */
    public CompletableFuture<Title> setWindow(int titleId, LocalDate start, LocalDate end) {
        return catalog.setWindow(titleId, start, end);
    }

    /** 项 18 设置售卖总数上限（自动切换 TOTAL 模式）。 */
    public CompletableFuture<Title> setTotalCap(int titleId, int cap) {
        return catalog.setTotalCap(titleId, cap);
    }

    /**
     * 管理玩家仓库：收回目标玩家的称号（同事务清一切栏位引用 + 审计；
     * 菜单侧已经确认菜单二次确认——裁决 §11⑨）。
     *
     * @param owner   目标玩家 UUID
     * @param titleId 称号 id
     * @param actor   管理员操作者
     * @return 收回成功 true；目标无该称号 ACTIVE 所有权时 false
     */
    public CompletableFuture<Boolean> revoke(UUID owner, int titleId, Actor actor) {
        return ownerships.revoke(owner, titleId, "管理员收回", actor);
    }

    /**
     * 购买者列表（项 19 / 商店详情 I 键数据源）：某称号的全部已履约买家，
     * 按履约时刻倒序（读池查询，不走事务）。
     *
     * @param titleId 称号 id
     * @return 买家行列表 future（DB 读线程完成）
     */
    public CompletableFuture<List<OrderRepository.FulfilledBuyer>> fulfilledBuyers(int titleId) {
        return dbExecutor.submitRead(() ->
                txRunner.withConnection(c -> orders.findFulfilledBuyers(c, titleId)));
    }

    /** 单类型价格条件的增改/清除骨架（replacement=null 表示清除）。 */
    private CompletableFuture<Title> replaceCondition(int titleId, ConditionType type,
                                                      PurchaseCondition replacement) {
        return withCurrent(titleId, current -> catalog.setConditions(titleId, current.combineLogic(),
                replacement == null
                        ? ConditionEdits.without(current.conditions(), type)
                        : ConditionEdits.upsert(current.conditions(), replacement)));
    }

    /** 读-改骨架：取缓存现值后应用变换；称号缺失以参数异常完成。 */
    private CompletableFuture<Title> withCurrent(
            int titleId, java.util.function.Function<Title, CompletableFuture<Title>> change) {
        return catalog.findById(titleId).filter(t -> !t.isDeleted())
                .map(change)
                .orElseGet(() -> failed("称号不存在或已删除: id=" + titleId));
    }

    /** 以参数异常完成的 future（错误消息仅入日志，玩家提示走菜单 lang 键）。 */
    private static CompletableFuture<Title> failed(String message) {
        return CompletableFuture.failedFuture(new IllegalArgumentException(message));
    }
}
