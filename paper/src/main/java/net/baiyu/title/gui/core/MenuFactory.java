package net.baiyu.title.gui.core;

import net.baiyu.title.gui.admin.AdminMenu;
import net.baiyu.title.gui.admin.AdminPlayerRepositoryMenu;
import net.baiyu.title.gui.admin.AdminTitleEditorMenu;
import net.baiyu.title.gui.admin.AdminTradeLogMenu;
import net.baiyu.title.gui.admin.BuyTypeSelectMenu;
import net.baiyu.title.gui.admin.BuyerListMenu;
import net.baiyu.title.gui.admin.RaritySelectMenu;
import net.baiyu.title.gui.common.ConfirmMenu;
import net.baiyu.title.gui.common.PlayerSelectMenu;
import net.baiyu.title.gui.repository.RepositoryMenu;
import net.baiyu.title.gui.shop.CustomTitlePaymentMenu;
import net.baiyu.title.gui.shop.ShopDetailMenu;
import net.baiyu.title.gui.shop.ShopFilterMenu;
import net.baiyu.title.gui.shop.ShopMenu;
import net.baiyu.title.gui.shop.ShopPaymentSelectMenu;
import net.baiyu.title.gui.shop.ShopSortMenu;
import net.baiyu.title.gui.trade.TradeDetailDraftMenu;
import net.baiyu.title.gui.trade.TradeDetailOfferMenu;
import net.baiyu.title.gui.trade.TradeHistoryMenu;
import net.baiyu.title.gui.trade.TradeListMenu;
import net.baiyu.title.gui.trade.TradeTitleSelectMenu;
import net.baiyu.title.gui.wardrobe.TitleSelectMenu;
import net.baiyu.title.gui.wardrobe.WardrobeMenu;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * 菜单工厂：{@link MenuSpec} → {@link AbstractMenu}
 * 的<b>唯一</b> switch——sealed 穷尽性保证新增规格必须在此接线，
 * 菜单类之间禁止静态 open() 互相硬引用。
 *
 * <p>M3 已接线：装扮管理 / 称号选择 / 仓库 三菜单 + 通用确认；
 * M4 追加：商店 / 详情 / 筛选 / 排序 四菜单；
 * 交易 / 管理等菜单规格随后续里程碑追加分支。</p>
 */
public final class MenuFactory {

    /** 菜单依赖聚合（下发给每个菜单实例）。 */
    private final MenuDeps deps;

    /**
     * 构造菜单工厂。
     *
     * @param deps 菜单依赖聚合
     */
    public MenuFactory(MenuDeps deps) {
        this.deps = Objects.requireNonNull(deps, "菜单依赖不能为空");
    }

    /**
     * 按规格构建菜单实例（每次全新构建；back 重建即重新 loadData）。
     *
     * @param player 目标玩家
     * @param spec   菜单规格
     * @return 菜单实例（尚未 open）
     */
    public AbstractMenu create(Player player, MenuSpec spec) {
        Objects.requireNonNull(player, "玩家不能为空");
        Objects.requireNonNull(spec, "菜单规格不能为空");
        final MenuContext ctx = context(player);
        return switch (spec) {
            case MenuSpec.Wardrobe ignored -> new WardrobeMenu(ctx);
            case MenuSpec.TitleSelect select -> new TitleSelectMenu(ctx, select.slotNo());
            case MenuSpec.Repository repository -> new RepositoryMenu(ctx, repository.page());
            case MenuSpec.Confirm confirm -> new ConfirmMenu(ctx, confirm.request());
            case MenuSpec.Shop shop -> new ShopMenu(ctx, shop.page());
            case MenuSpec.ShopDetail detail -> new ShopDetailMenu(ctx, detail.titleId());
            case MenuSpec.ShopFilter ignored -> new ShopFilterMenu(ctx);
            case MenuSpec.ShopSort ignored -> new ShopSortMenu(ctx);
            case MenuSpec.ShopPaymentSelect select -> new ShopPaymentSelectMenu(ctx, select.titleId());
            case MenuSpec.CustomTitle ignored -> new CustomTitlePaymentMenu(ctx);
            case MenuSpec.Admin admin -> new AdminMenu(ctx, admin.page());
            case MenuSpec.AdminEditor editor ->
                    new AdminTitleEditorMenu(ctx, editor.titleId(), editor.editorPage());
            case MenuSpec.BuyTypeSelect select -> new BuyTypeSelectMenu(ctx, select.titleId());
            case MenuSpec.RaritySelect select -> new RaritySelectMenu(ctx, select.titleId());
            case MenuSpec.PlayerSelect select -> new PlayerSelectMenu(ctx, select.request());
            case MenuSpec.AdminPlayerRepository repo ->
                    new AdminPlayerRepositoryMenu(ctx, repo.targetUuid(), repo.page());
            case MenuSpec.BuyerList list -> new BuyerListMenu(ctx, list.titleId(), list.page());
            case MenuSpec.TradeList list -> new TradeListMenu(ctx, list.page());
            case MenuSpec.TradeTitleSelect select -> new TradeTitleSelectMenu(ctx, select.side());
            case MenuSpec.TradeDetailDraft ignored -> new TradeDetailDraftMenu(ctx);
            case MenuSpec.TradeDetail detail -> new TradeDetailOfferMenu(ctx, detail.offerId());
            case MenuSpec.TradeHistory history -> new TradeHistoryMenu(ctx, history.page());
            case MenuSpec.AdminTradeLog log -> new AdminTradeLogMenu(ctx, log.page());
        };
    }

    /** 为目标玩家组装菜单上下文（会话经管理器按需创建）。 */
    private MenuContext context(Player player) {
        return new MenuContext(player, deps, deps.sessionManager().get(player));
    }
}
