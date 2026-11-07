package net.baiyu.title.gui.core;

import net.baiyu.title.gui.admin.AdminEditState;
import net.baiyu.title.gui.admin.TradeLogState;
import net.baiyu.title.gui.shop.CustomTitleDraft;
import net.baiyu.title.gui.shop.ShopViewState;
import net.baiyu.title.gui.trade.TradeDraft;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * 每玩家 GUI 会话状态容器。
 *
 * <p>承载：返回栈（Deque&lt;MenuSpec&gt;，上限 {@value #HISTORY_LIMIT}）、
 * 当前菜单规格（含瞬态标记）、栏位选择暂存 {@link SlotSelectState}、
 * 商店视图状态 {@link ShopViewState}、navigating / expectingInput
 * 仲裁标记（§3.5）与 lastActiveAt。TradeDraft、CustomTitleDraft、
 * AdminEditState 等其余状态字段随后续里程碑追加。</p>
 *
 * <p>线程纪律：全部读写只在主线程发生（GUI 事件、路由、同步 sweep），
 * 类本身不做同步。</p>
 */
public final class GuiSession {

    /** 返回栈深度上限（超出时丢弃最旧一层）。 */
    public static final int HISTORY_LIMIT = 10;

    /** 返回栈：栈顶 = 上一个菜单的规格（当前菜单不在栈内）。 */
    private final Deque<MenuSpec> history = new ArrayDeque<>();

    /** 当前打开菜单的规格；无菜单时为 null。 */
    private @Nullable MenuSpec currentSpec;

    /** 当前菜单是否瞬态（瞬态菜单在导航离开时不入返回栈）。 */
    private boolean currentTransient;

    /** 导航标记：菜单切换引发的 InventoryClose 不按玩家主动 ESC 处理（§3.5）。 */
    private boolean navigating;

    /** 输入标记：进入铁砧 / 聊天输入引发的关闭不按玩家主动 ESC 处理（§3.5）。 */
    private boolean expectingInput;

    /** 最近活跃时间（UTC epoch 毫秒；供空闲清扫判定）。 */
    private long lastActiveAt;

    /** 称号选择菜单的暂存状态；不在选择流程中时为 null。 */
    private @Nullable SlotSelectState slotSelect;

    /** 商店视图状态（筛选 / 排序）；首次进入商店链时惰性创建。 */
    private @Nullable ShopViewState shopView;

    /** 管理端购买类型编辑暂挂；不在编辑流程中时为 null。 */
    private @Nullable AdminEditState adminEdit;

    /** 报价草稿；不在创建报价流程中时为 null。 */
    private @Nullable TradeDraft tradeDraft;

    /** 全服交易记录菜单的筛选 / 搜索态；首次进入时惰性创建。 */
    private @Nullable TradeLogState tradeLog;

    /** 自定义称号创建草稿；首次进入付款菜单时惰性创建。 */
    private @Nullable CustomTitleDraft customDraft;

    /** 创建会话并记为活跃（仅 {@link GuiSessionManager} 调用）。 */
    GuiSession() {
        touch();
    }

    /** 刷新最近活跃时间为当前时刻。 */
    public void touch() {
        lastActiveAt = System.currentTimeMillis();
    }

    /** @return 最近活跃时间（UTC epoch 毫秒） */
    public long lastActiveAt() {
        return lastActiveAt;
    }

    /** @return 当前打开菜单的规格；无菜单时为 null */
    public @Nullable MenuSpec currentSpec() {
        return currentSpec;
    }

    /** @return 当前菜单是否瞬态（无菜单时恒为 false） */
    public boolean isCurrentTransient() {
        return currentSpec != null && currentTransient;
    }

    /**
     * 记录当前菜单（路由 open/replace/back 时调用）。
     *
     * @param spec        菜单规格
     * @param isTransient 是否瞬态
     */
    public void setCurrent(MenuSpec spec, boolean isTransient) {
        this.currentSpec = Objects.requireNonNull(spec, "菜单规格不能为空");
        this.currentTransient = isTransient;
    }

    /**
     * 压入返回栈；超出 {@value #HISTORY_LIMIT} 时丢弃最旧一层。
     *
     * @param spec 上一个菜单的规格
     */
    public void pushHistory(MenuSpec spec) {
        Objects.requireNonNull(spec, "菜单规格不能为空");
        if (history.size() >= HISTORY_LIMIT) {
            history.removeLast();
        }
        history.push(spec);
    }

    /** @return 弹出栈顶规格；栈空时返回 null */
    public @Nullable MenuSpec popHistory() {
        return history.poll();
    }

    /** @return 返回栈当前深度 */
    public int historyDepth() {
        return history.size();
    }

    /** 清空导航状态（返回栈 + 当前菜单；ESC 仲裁与 closeAll 调用）。 */
    public void clearNavigation() {
        history.clear();
        currentSpec = null;
        currentTransient = false;
    }

    /** @return navigating 标记当前值 */
    public boolean isNavigating() {
        return navigating;
    }

    /**
     * 设置 navigating 标记（AbstractMenu 在 gui.open 前后括起）。
     *
     * @param navigating 新值
     */
    public void setNavigating(boolean navigating) {
        this.navigating = navigating;
    }

    /**
     * 消费 navigating 标记：返回当前值并复位为 false（ESC 仲裁用）。
     *
     * @return 消费前的标记值
     */
    public boolean consumeNavigating() {
        final boolean value = navigating;
        navigating = false;
        return value;
    }

    /** @return expectingInput 标记当前值 */
    public boolean isExpectingInput() {
        return expectingInput;
    }

    /**
     * 设置 expectingInput 标记（输入会话管理器在开启 / 结束输入时调用）。
     *
     * @param expectingInput 新值
     */
    public void setExpectingInput(boolean expectingInput) {
        this.expectingInput = expectingInput;
    }

    /** @return 栏位选择暂存状态；不在选择流程中时为 null */
    public @Nullable SlotSelectState slotSelect() {
        return slotSelect;
    }

    /**
     * 开始一段栏位选择流程（进入称号选择菜单时调用，覆盖旧状态）。
     *
     * @param slotNo 目标栏位号（1 起）
     * @return 新建的暂存状态
     */
    public SlotSelectState beginSlotSelect(int slotNo) {
        final SlotSelectState state = new SlotSelectState(slotNo);
        this.slotSelect = state;
        return state;
    }

    /** 丢弃栏位选择暂存（应用完成或 ESC 放弃时调用）。 */
    public void clearSlotSelect() {
        this.slotSelect = null;
    }

    /**
     * 商店视图状态（筛选 / 排序扩展字段）：
     * 首次访问惰性创建，跨商店 / 筛选 / 排序菜单往返保持，随会话失效丢弃。
     *
     * @return 本会话的商店视图状态
     */
    public ShopViewState shopView() {
        if (shopView == null) {
            shopView = new ShopViewState();
        }
        return shopView;
    }

    /** @return 管理端购买类型编辑暂挂；不在编辑流程中时为 null */
    public @Nullable AdminEditState adminEdit() {
        return adminEdit;
    }

    /**
     * 开始一段管理端购买类型编辑（进入购买类型选择菜单时调用，覆盖旧状态）。
     *
     * @param state 编辑暂挂状态
     */
    public void beginAdminEdit(AdminEditState state) {
        this.adminEdit = Objects.requireNonNull(state, "编辑暂挂状态不能为空");
    }

    /** 丢弃管理端编辑暂挂（应用完成 / 取消 / ESC 放弃时调用）。 */
    public void clearAdminEdit() {
        this.adminEdit = null;
    }

    /** @return 报价草稿；不在创建报价流程中时为 null */
    public @Nullable TradeDraft tradeDraft() {
        return tradeDraft;
    }

    /**
     * 开始一段创建报价流程（选择交易玩家后调用，覆盖旧草稿）。
     *
     * @param draft 报价草稿
     */
    public void beginTradeDraft(TradeDraft draft) {
        this.tradeDraft = Objects.requireNonNull(draft, "报价草稿不能为空");
    }

    /** 丢弃报价草稿（创建成功 / 取消 / ESC 放弃时调用）。 */
    public void clearTradeDraft() {
        this.tradeDraft = null;
    }

    /**
     * 全服交易记录菜单态（筛选 / 搜索扩展字段）：
     * 首次访问惰性创建，跨翻页 / back 重建保持，随会话失效丢弃。
     *
     * @return 本会话的交易记录菜单态
     */
    public TradeLogState tradeLog() {
        if (tradeLog == null) {
            tradeLog = new TradeLogState();
        }
        return tradeLog;
    }

    /**
     * 自定义称号创建草稿：首次访问惰性创建，
     * 跨付款菜单 / 铁砧命名 / 确认菜单往返保持，ESC 放弃时清空。
     *
     * @return 本会话的自定义称号草稿
     */
    public CustomTitleDraft customDraft() {
        if (customDraft == null) {
            customDraft = new CustomTitleDraft();
        }
        return customDraft;
    }

    /** 丢弃自定义称号草稿（玩家主动 ESC 付款菜单时调用）。 */
    public void clearCustomDraft() {
        this.customDraft = null;
    }

    /**
     * 空闲判定（清扫器用）：距最近活跃超过给定时长，且无打开菜单、无输入会话。
     *
     * @param now        当前时刻（UTC epoch 毫秒）
     * @param idleMillis 空闲阈值（毫秒）
     * @return 是否可被清扫移除
     */
    public boolean isIdle(long now, long idleMillis) {
        return now - lastActiveAt >= idleMillis && currentSpec == null && !expectingInput;
    }
}
