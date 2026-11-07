package net.baiyu.title.gui.admin;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.gui.core.AbstractPagedMenu;
import net.baiyu.title.gui.core.ConfirmRequest;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.service.Actor;
import net.baiyu.title.service.WardrobeView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 管理玩家仓库菜单：
 * 目标玩家的全部有效所有权只读分页展示，点击条目弹通用确认菜单，
 * 确认后经 {@code AdminTitleService.revoke} 收回（同事务清一切栏位引用）。
 *
 * <p>X = 目标玩家持有称号（仓库版 lore，与玩家自视仓库同渲染链）；
 * A / C 翻页；B 红石 = 返回（§11⑥）。数据源为在线档案缓存快照
 * （玩家选择菜单只列在线玩家；目标中途离线则列表随刷新清空，只读降级）。
 * 收回落定后若确认菜单仍在前台则 back 重建本菜单，列表即时反映。</p>
 */
public final class AdminPlayerRepositoryMenu extends AbstractPagedMenu<WardrobeView.OwnedTitle> {

    /** 布局字符画（条目区照 §1.3.2.3.4.2 系菜单、底栏三键照 §1.3.2.3.4.4 式样）。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "#########",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "##A#B#C##");

    /** 目标玩家 UUID。 */
    private final UUID targetUuid;

    /** 目标玩家名（构造时主线程解析；离线且无缓存名时为 {@code ?}）。 */
    private final String targetName;

    /**
     * 构造管理玩家仓库菜单（主线程：解析目标玩家名）。
     *
     * @param ctx        菜单上下文
     * @param targetUuid 目标玩家 UUID
     * @param page       初始页码（1 起）
     */
    public AdminPlayerRepositoryMenu(MenuContext ctx, UUID targetUuid, int page) {
        super(ctx, page);
        this.targetUuid = Objects.requireNonNull(targetUuid, "目标玩家 UUID 不能为空");
        final Player online = Bukkit.getPlayer(targetUuid);
        this.targetName = online != null ? online.getName()
                : Objects.requireNonNullElse(Bukkit.getOfflinePlayer(targetUuid).getName(), "?");
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.admin.player-repo.title", Placeholder.unparsed("player", targetName));
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.AdminPlayerRepository(targetUuid, page());
    }

    /** 翻页写回会话规格：back 重建 / 规格重放落在正确页。 */
    @Override
    protected void onPageChanged(int newPage) {
        ctx.session().setCurrent(new MenuSpec.AdminPlayerRepository(targetUuid, newPage), false);
    }

    /** 异步取目标玩家的有效持有列表（在线档案缓存快照，零 IO）。 */
    @Override
    protected List<WardrobeView.OwnedTitle> fetchEntries() {
        return ctx.deps().wardrobeViews().ownedTitles(targetUuid);
    }

    /** 称号条目：仓库版 lore 只读展示，点击弹确认收回。 */
    @Override
    protected GuiItem renderEntry(WardrobeView.OwnedTitle owned) {
        final TitleItemRenderer.RenderedItem rendered = ctx.deps().renderer().warehouseItem(
                new TitleItemRenderer.OwnedTitleView(
                        owned.title(), owned.expiresOn(), owned.referencedAnywhere()));
        return ctx.deps().itemFactory().item(rendered.material(), rendered.name(),
                rendered.lore(), rendered.glint(), 1,
                event -> confirmRevoke(owned.title()));
    }

    /** 底栏 B 返回键（翻页 A / C 由分页基类放置）。 */
    @Override
    protected void populateExtras(BaseGui gui) {
        gui.setItem(LAYOUT.slot('B'), ctx.deps().buttons().back(event -> back()));
    }

    /** 弹出收回确认（瞬态确认菜单；确认回调发起收回写操作）。 */
    private void confirmRevoke(Title title) {
        final Component titleName = ctx.deps().renderer().nameWithBrackets(title);
        final TagResolver[] resolvers = {
                Placeholder.unparsed("player", targetName),
                Placeholder.component("title", titleName)};
        confirm(ConfirmRequest.of(
                lang("menu.admin.player-repo.confirm.name", resolvers),
                langList("menu.admin.player-repo.confirm.lore", resolvers),
                () -> revoke(title.id(), resolvers)));
    }

    /** 执行收回：落定后反馈结果，确认菜单仍在前台时 back 重建本菜单。 */
    private void revoke(int titleId, TagResolver[] resolvers) {
        final Player admin = ctx.player();
        ctx.deps().adminViews()
                .revoke(targetUuid, titleId, Actor.of(admin.getUniqueId(), admin.getName()))
                .whenComplete((revoked, failure) -> runSync(() -> {
                    if (failure != null) {
                        ctx.deps().plugin().getComponentLogger()
                                .warn("收回称号失败（目标 {}，称号 {}）", targetName, titleId, failure);
                        feedback("menu.admin.editor.failed");
                    } else if (Boolean.TRUE.equals(revoked)) {
                        feedback("menu.admin.player-repo.revoke-success", resolvers);
                    } else {
                        feedback("menu.admin.player-repo.revoke-missing", resolvers);
                    }
                    settleBack();
                }));
    }

    /** 收回终局：确认菜单仍在前台时 back 弹栈重建本菜单（列表即时反映）。 */
    private void settleBack() {
        if (ctx.player().isOnline()
                && ctx.session().currentSpec() instanceof MenuSpec.Confirm) {
            back();
        }
    }
}
