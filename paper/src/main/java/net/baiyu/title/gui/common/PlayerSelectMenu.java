package net.baiyu.title.gui.common;

import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.gui.core.AbstractPagedMenu;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.gui.core.PlayerSelectRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 通用玩家选择菜单：在线玩家头颅单选，
 * 选中后的动作由 {@link PlayerSelectRequest} 回调注入（本期消费者 =
 * 管理端「管理玩家仓库」；M7 交易链复用）。
 *
 * <p>X = 在线玩家头（字母 → 数字 → 符号排序，{@link PlayerNameOrder}）。
 * 布局按设计字符画整圈边框、无翻页键——28 格以内的在线玩家可选，
 * 超出部分不展示（设计未给翻页位）。在线列表在构造时主线程收集快照
 * （异步 loadData 禁触 Bukkit API），点击时复核目标仍在线。</p>
 */
public final class PlayerSelectMenu extends AbstractPagedMenu<Player> {

    /** 布局字符画。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "#########",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#########");

    /** 选择请求（单选回调）。 */
    private final PlayerSelectRequest request;

    /** 在线玩家快照（构造时主线程收集并排序；异步 fetchEntries 只读它）。 */
    private final List<Player> onlineSnapshot;

    /**
     * 构造玩家选择菜单（主线程：收集在线玩家快照）。
     *
     * @param ctx     菜单上下文
     * @param request 选择请求
     */
    public PlayerSelectMenu(MenuContext ctx, PlayerSelectRequest request) {
        super(ctx, 1);
        this.request = Objects.requireNonNull(request, "玩家选择请求不能为空");
        this.onlineSnapshot = Bukkit.getOnlinePlayers().stream()
                .map(Player.class::cast)
                .sorted(Comparator.comparing(Player::getName, PlayerNameOrder.COMPARATOR))
                .toList();
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.player-select.title");
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.PlayerSelect(request);
    }

    /** 异步取条目：构造时收集的在线快照（零 Bukkit 触碰）。 */
    @Override
    protected List<Player> fetchEntries() {
        return onlineSnapshot;
    }

    /** 玩家头条目：点击复核在线后回调注入方（离线竞态仅提示并刷新）。 */
    @Override
    protected GuiItem renderEntry(Player target) {
        return ctx.deps().itemFactory().playerHead(target,
                lang("menu.player-select.entry.name",
                        Placeholder.unparsed("player", target.getName())),
                langList("menu.player-select.entry.lore"),
                event -> select(target));
    }

    /** 单选：目标已离线时提示并刷新列表，仍在线则交回调决定去向。 */
    private void select(Player target) {
        if (!target.isOnline()) {
            feedback("menu.player-select.offline");
            refresh();
            return;
        }
        request.onSelect().accept(target);
    }
}
