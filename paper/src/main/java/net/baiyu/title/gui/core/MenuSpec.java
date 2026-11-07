package net.baiyu.title.gui.core;

import net.baiyu.title.gui.trade.TradeDraft;

import java.util.Objects;
import java.util.UUID;

/**
 * 菜单规格：描述「哪个菜单 + 什么参数」的不可变载体，
 * 返回栈里存的就是它——back 时经 {@link MenuFactory} 按规格重建菜单并
 * 重新 loadData，天然拿到最新数据。
 *
 * <p>sealed + record：{@link MenuFactory#create} 的 switch 借穷尽性检查
 * 保证每个规格都有对应菜单。M3 定义装扮链四个规格、M4 追加商店族四个规格；
 * 后续里程碑的规格记录随菜单实装逐步追加，不预建空壳。</p>
 */
public sealed interface MenuSpec {

    /** 装扮管理菜单（{@code /byt} 默认入口）。 */
    record Wardrobe() implements MenuSpec {
    }

    /**
     * 称号选择菜单。
     *
     * @param slotNo 目标装扮栏位号（1 起）
     */
    record TitleSelect(int slotNo) implements MenuSpec {

        /** 紧凑构造：栏位号必须为正。 */
        public TitleSelect {
            if (slotNo < 1) {
                throw new IllegalArgumentException("栏位号必须≥1，实际 " + slotNo);
            }
        }
    }

    /**
     * 仓库菜单。
     *
     * @param page 页码（1 起）
     */
    record Repository(int page) implements MenuSpec {

        /** 紧凑构造：页码必须为正。 */
        public Repository {
            requirePage(page);
        }
    }

    /**
     * 通用确认菜单；瞬态——不入返回栈。
     *
     * @param request 确认请求（展示信息与确认 / 取消回调）
     */
    record Confirm(ConfirmRequest request) implements MenuSpec {

        /** 紧凑构造：请求必填。 */
        public Confirm {
            Objects.requireNonNull(request, "确认请求不能为空");
        }
    }

    /**
     * 商店菜单。
     *
     * @param page 页码（1 起）
     */
    record Shop(int page) implements MenuSpec {

        /** 紧凑构造：页码必须为正。 */
        public Shop {
            requirePage(page);
        }
    }

    /**
     * 商店详情菜单（商店条目右键进入）。
     *
     * @param titleId 目标称号 id
     */
    record ShopDetail(int titleId) implements MenuSpec {

        /** 紧凑构造：称号 id 必须为正。 */
        public ShopDetail {
            if (titleId < 1) {
                throw new IllegalArgumentException("称号 id 必须≥1，实际 " + titleId);
            }
        }
    }

    /**
     * 付款方式选择菜单（T1-03：OR 逻辑多分支同时满足时由玩家自选付款方式）。
     *
     * @param titleId 目标称号 id
     */
    record ShopPaymentSelect(int titleId) implements MenuSpec {

        /** 紧凑构造：称号 id 必须为正。 */
        public ShopPaymentSelect {
            if (titleId < 1) {
                throw new IllegalArgumentException("称号 id 必须≥1，实际 " + titleId);
            }
        }
    }

    /** 商店筛选菜单（编辑态挂 ShopViewState 暂存）。 */
    record ShopFilter() implements MenuSpec {
    }

    /**
     * 自定义称号付款菜单（业务设计 §1.3.2.3.1.4；付款单选暂存挂
     * {@code GuiSession.customDraft}，铁砧命名完成后经暂存自动前送确认菜单）。
     */
    record CustomTitle() implements MenuSpec {
    }

    /** 商店排序菜单（编辑态挂 ShopViewState 暂存）。 */
    record ShopSort() implements MenuSpec {
    }

    /**
     * 管理主菜单（含隐藏称号）。
     *
     * @param page 页码（1 起）
     */
    record Admin(int page) implements MenuSpec {

        /** 紧凑构造：页码必须为正。 */
        public Admin {
            requirePage(page);
        }
    }

    /**
     * 管理详情（称号编辑器）菜单（19 项分 2 页）。
     *
     * @param titleId    目标称号 id
     * @param editorPage 编辑页码（1 起；当前共 2 页）
     */
    record AdminEditor(int titleId, int editorPage) implements MenuSpec {

        /** 紧凑构造：称号 id 与编辑页码必须为正。 */
        public AdminEditor {
            if (titleId < 1) {
                throw new IllegalArgumentException("称号 id 必须≥1，实际 " + titleId);
            }
            if (editorPage < 1) {
                throw new IllegalArgumentException("编辑页码必须≥1，实际 " + editorPage);
            }
        }
    }

    /**
     * 购买类型选择菜单（暂挂集挂 AdminEditState）。
     *
     * @param titleId 目标称号 id
     */
    record BuyTypeSelect(int titleId) implements MenuSpec {

        /** 紧凑构造：称号 id 必须为正。 */
        public BuyTypeSelect {
            if (titleId < 1) {
                throw new IllegalArgumentException("称号 id 必须≥1，实际 " + titleId);
            }
        }
    }

    /**
     * 稀有度选择菜单（编辑器项 4；「无稀有度」+ config rarities 单选）。
     *
     * @param titleId 目标称号 id
     */
    record RaritySelect(int titleId) implements MenuSpec {

        /** 紧凑构造：称号 id 必须为正。 */
        public RaritySelect {
            if (titleId < 1) {
                throw new IllegalArgumentException("称号 id 必须≥1，实际 " + titleId);
            }
        }
    }

    /**
     * 通用玩家选择菜单（在线玩家单选，回调注入）。
     *
     * <p>裁决记录（回调型规格的入栈方式）：与 {@link Confirm} 持
     * {@link ConfirmRequest} 同承载——回调收在请求 record 里随规格入返回栈；
     * 但不同于 Confirm 的瞬态裁决，本规格<b>正常入栈</b>：从后续菜单
     * back 回来时经工厂按规格重建，在线列表随重建刷新、回调原样复用
     * （「返回列表再选下一个玩家」是选择菜单的自然动线）。</p>
     *
     * @param request 选择请求（提示词键 + 单选回调）
     */
    record PlayerSelect(PlayerSelectRequest request) implements MenuSpec {

        /** 紧凑构造：请求必填。 */
        public PlayerSelect {
            Objects.requireNonNull(request, "玩家选择请求不能为空");
        }
    }

    /**
     * 管理玩家仓库菜单（业务设计 §1.3.2.3.5.1 功能键 B；裁决 §11⑨：
     * 目标玩家所有权只读展示 + 逐条确认收回）。
     *
     * @param targetUuid 目标玩家 UUID
     * @param page       页码（1 起）
     */
    record AdminPlayerRepository(UUID targetUuid, int page) implements MenuSpec {

        /** 紧凑构造：目标必填、页码必须为正。 */
        public AdminPlayerRepository {
            Objects.requireNonNull(targetUuid, "目标玩家 UUID 不能为空");
            requirePage(page);
        }
    }

    /**
     * 报价列表菜单。
     *
     * @param page 页码（1 起）
     */
    record TradeList(int page) implements MenuSpec {

        /** 紧凑构造：页码必须为正。 */
        public TradeList {
            requirePage(page);
        }
    }

    /**
     * 选择交易称号菜单（草稿挂 GuiSession.TradeDraft）。
     *
     * @param side 草稿交易方（SELF = 选己方标的，OTHER = 选对方标的）
     */
    record TradeTitleSelect(TradeDraft.Side side) implements MenuSpec {

        /** 紧凑构造：交易方必填。 */
        public TradeTitleSelect {
            Objects.requireNonNull(side, "草稿交易方不能为空");
        }
    }

    /** 报价详情菜单——草稿态（数据源为会话草稿）。 */
    record TradeDetailDraft() implements MenuSpec {
    }

    /**
     * 报价详情菜单——已建报价（业务设计 §1.3.2.3.4.5；RECEIVER / VIEWER
     * 两态由视图门面按观察者与状态判定）。
     *
     * @param offerId 交易 id（必须为正）
     */
    record TradeDetail(long offerId) implements MenuSpec {

        /** 紧凑构造：交易 id 必须为正。 */
        public TradeDetail {
            if (offerId < 1) {
                throw new IllegalArgumentException("交易 id 必须≥1，实际 " + offerId);
            }
        }
    }

    /**
     * 个人交易记录菜单。
     *
     * @param page 页码（1 起）
     */
    record TradeHistory(int page) implements MenuSpec {

        /** 紧凑构造：页码必须为正。 */
        public TradeHistory {
            requirePage(page);
        }
    }

    /**
     * 全服交易记录菜单（业务设计 §1.3.2.3.5.3；筛选 / 搜索态挂会话
     * TradeLogState）。
     *
     * @param page 页码（1 起）
     */
    record AdminTradeLog(int page) implements MenuSpec {

        /** 紧凑构造：页码必须为正。 */
        public AdminTradeLog {
            requirePage(page);
        }
    }

    /**
     * 购买者列表菜单。
     *
     * <p>裁决记录：设计 02 §3.3 的 {@code BuyerList(titleId,returnTo)} 中
     * returnTo 参数省略——返回栈路由下 {@code back()} 天然回到进入方
     * （商店详情或称号编辑器），规格改携页码以支持翻页后的 back 重放。</p>
     *
     * @param titleId 目标称号 id
     * @param page    页码（1 起）
     */
    record BuyerList(int titleId, int page) implements MenuSpec {

        /** 紧凑构造：称号 id 与页码必须为正。 */
        public BuyerList {
            if (titleId < 1) {
                throw new IllegalArgumentException("称号 id 必须≥1，实际 " + titleId);
            }
            requirePage(page);
        }
    }

    /** 页码参数公共校验（分页类规格共用）。 */
    private static void requirePage(int page) {
        if (page < 1) {
            throw new IllegalArgumentException("页码必须≥1，实际 " + page);
        }
    }
}
