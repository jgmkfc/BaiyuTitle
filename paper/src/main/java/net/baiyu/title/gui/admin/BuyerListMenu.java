package net.baiyu.title.gui.admin;

import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;
import net.baiyu.title.gui.core.AbstractPagedMenu;
import net.baiyu.title.gui.core.MenuContext;
import net.baiyu.title.gui.core.MenuLayout;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.repository.OrderRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 购买者列表菜单（业务设计 §1.3.2.3.5.2 项 19「已售统计」/
 * §1.3.2.3.2.1 商店详情 I 键；表现层设计 §4「购买者列表」行）。
 *
 * <p>X = 买家玩家头（名称 + 购买时间 lore，只读不响应点击；同一玩家
 * 多次购买产生多行）；A / C 翻页；B 红石 = 返回（§11⑥——back 弹栈
 * 回到进入方：商店详情或称号编辑器）。数据源为 FULFILLED 订单
 * （按履约时刻倒序），经 DbExecutor 读池查询、异步 loadData 阶段
 * 同步等待结果——JDBC 仍只发生在 DB 线程。</p>
 */
public final class BuyerListMenu extends AbstractPagedMenu<OrderRepository.FulfilledBuyer> {

    /** 布局字符画（条目区 ×4 + 底栏 {@code ##A#B#C##}）。 */
    private static final MenuLayout LAYOUT = MenuLayout.of(
            "#########",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "#XXXXXXX#",
            "##A#B#C##");

    /** 目标称号 id。 */
    private final int titleId;

    /**
     * 构造购买者列表菜单。
     *
     * @param ctx     菜单上下文
     * @param titleId 目标称号 id
     * @param page    初始页码（1 起）
     */
    public BuyerListMenu(MenuContext ctx, int titleId, int page) {
        super(ctx, page);
        this.titleId = titleId;
    }

    @Override
    protected MenuLayout layout() {
        return LAYOUT;
    }

    @Override
    protected Component title() {
        return lang("menu.admin.buyer-list.title",
                Placeholder.unparsed("id", Integer.toString(titleId)));
    }

    @Override
    public MenuSpec spec() {
        return new MenuSpec.BuyerList(titleId, page());
    }

    /** 翻页写回会话规格：back 重建 / 规格重放落在正确页。 */
    @Override
    protected void onPageChanged(int newPage) {
        ctx.session().setCurrent(new MenuSpec.BuyerList(titleId, newPage), false);
    }

    /** 异步取已履约买家清单（读池查询；本方法在 Bukkit 异步线程阻塞等待）。 */
    @Override
    protected List<OrderRepository.FulfilledBuyer> fetchEntries() {
        return ctx.deps().adminViews().fulfilledBuyers(titleId).join();
    }

    /** 买家条目：玩家头 + 购买时间 lore，只读。 */
    @Override
    protected GuiItem renderEntry(OrderRepository.FulfilledBuyer buyer) {
        return ctx.deps().itemFactory().playerHead(
                Bukkit.getOfflinePlayer(buyer.playerUuid()),
                lang("menu.admin.buyer-list.entry.name",
                        Placeholder.unparsed("player", buyer.playerName())),
                langList("menu.admin.buyer-list.entry.lore",
                        Placeholder.parsed("time", timeText(buyer.fulfilledAt()))),
                null);
    }

    /** 底栏 B 返回键（翻页 A / C 由分页基类放置）。 */
    @Override
    protected void populateExtras(BaseGui gui) {
        gui.setItem(LAYOUT.slot('B'), ctx.deps().buttons().back(event -> back()));
    }

    /** 履约时刻（UTC 存储）→ 本地时区日期时间文案（format.date-time 值级插值）。 */
    private String timeText(long epochMillis) {
        final LocalDateTime time = Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault()).toLocalDateTime();
        return ctx.deps().lang().raw("format.date-time")
                .replace("<year>", Integer.toString(time.getYear()))
                .replace("<month>", Integer.toString(time.getMonthValue()))
                .replace("<day>", Integer.toString(time.getDayOfMonth()))
                .replace("<hour>", String.format("%02d", time.getHour()))
                .replace("<minute>", String.format("%02d", time.getMinute()));
    }
}
