package net.baiyu.title.service;

import net.baiyu.title.api.event.TradeCompletedEvent;
import net.baiyu.title.service.trade.AcceptResult;
import net.baiyu.title.service.trade.CreateResult;
import net.baiyu.title.service.trade.OfferItem;
import net.baiyu.title.service.trade.ResolveResult;
import net.baiyu.title.service.trade.TradeBlockReason;
import net.baiyu.title.translation.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 交易执行链（paper 侧主线程编排）：创建 / 同意 / 拒绝三个写操作的
 * 服务调用、结果文案映射与对手方聊天通知；状态机与幂等语义全部由
 * common 层 {@code TradeService} 保证，本类只做主线程动作与玩家反馈。
 *
 * <p>事件契约：{@code TradePreAcceptEvent}（可取消）经 {@link PreAcceptGate}
 * 在主线程、{@code accept} 之前发出，取消即静默中止（报价保持 PENDING）；
 * {@link TradeCompletedEvent} 在成交（commit 之后）回到主线程时派发。</p>
 */
public final class TradeFlow {

    /** 插件实例（主线程调度与日志）。 */
    private final Plugin plugin;

    /** 交易服务（状态机唯一入口）。 */
    private final TradeService trades;

    /** 语言门面（玩家反馈取词）。 */
    private final Lang lang;

    /** 接受前事件闸门（生产装配发 TradePreAcceptEvent；单测注入桩）。 */
    private final PreAcceptGate preGate;

    /**
     * 构造交易执行链。
     *
     * @param plugin  插件实例
     * @param trades  交易服务
     * @param lang    语言门面
     * @param preGate 接受前事件闸门（返回 false = Pre 事件被取消）
     */
    public TradeFlow(Plugin plugin, TradeService trades, Lang lang, PreAcceptGate preGate) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.trades = Objects.requireNonNull(trades, "交易服务不能为空");
        this.lang = Objects.requireNonNull(lang, "语言门面不能为空");
        this.preGate = Objects.requireNonNull(preGate, "接受前事件闸门不能为空");
    }

    /**
     * 创建报价（<b>仅主线程</b>；确认菜单一次性 token 已挡双击）。
     * <p>成功：发起方回执 + 目标方在线时发可点击聊天通知（点击打开报价列表）；
     * 拒绝：按 {@link TradeBlockReason} 映射文案。</p>
     *
     * @param initiator  发起方
     * @param targetUuid 目标方 UUID
     * @param targetName 目标方名称
     * @param mine       发起方标的
     * @param theirs     目标方标的
     * @param onSettled  终局回调（主线程；参数 = 是否创建成功）
     */
    public void create(Player initiator, UUID targetUuid, String targetName,
                       List<OfferItem> mine, List<OfferItem> theirs, Consumer<Boolean> onSettled) {
        Objects.requireNonNull(initiator, "发起方不能为空");
        Objects.requireNonNull(onSettled, "终局回调不能为空");
        trades.createOffer(initiator.getUniqueId(), initiator.getName(),
                        targetUuid, targetName, mine, theirs)
                .whenComplete((result, failure) -> runSync(() -> {
                    if (failure != null) {
                        plugin.getComponentLogger().warn("创建报价失败（发起方 {}，目标 {}）",
                                initiator.getName(), targetName, failure);
                        send(initiator, "command.error.internal");
                        onSettled.accept(false);
                        return;
                    }
                    switch (result) {
                        case CreateResult.Rejected rejected -> {
                            send(initiator, rejectKey(rejected.reason()));
                            onSettled.accept(false);
                        }
                        case CreateResult.Created ignored -> {
                            send(initiator, "trade.create.success",
                                    Placeholder.unparsed("player", targetName));
                            notifyTarget(targetUuid, initiator.getName());
                            onSettled.accept(true);
                        }
                    }
                }));
    }

    /**
     * 同意报价（<b>仅主线程</b>）：先发可取消的 Pre 事件（被取消即静默中止，
     * 报价保持 PENDING，仅回调 {@code onSettled}），再按 {@link AcceptResult}
     * 分支反馈；成交时另通知在线的发起方。
     *
     * @param acceptor  接受者（目标方）
     * @param tradeId   交易 id
     * @param onSettled 终局回调（主线程；恰好一次）
     */
    public void accept(Player acceptor, long tradeId, Runnable onSettled) {
        Objects.requireNonNull(acceptor, "接受者不能为空");
        Objects.requireNonNull(onSettled, "终局回调不能为空");
        if (!preGate.allow(acceptor, tradeId)) {
            onSettled.run();
            return;
        }
        trades.accept(tradeId, Actor.of(acceptor.getUniqueId(), acceptor.getName()))
                .whenComplete((result, failure) -> runSync(() -> {
                    if (failure != null) {
                        plugin.getComponentLogger().warn("同意报价失败（交易 {}，玩家 {}）",
                                tradeId, acceptor.getName(), failure);
                        send(acceptor, "command.error.internal");
                    } else {
                        announceAccept(acceptor, result);
                    }
                    onSettled.run();
                }));
    }

    /**
     * 拒绝报价（<b>仅主线程</b>）：按 {@link ResolveResult} 分支反馈。
     *
     * @param actor     操作者（目标方）
     * @param tradeId   交易 id
     * @param onSettled 终局回调（主线程；恰好一次）
     */
    public void reject(Player actor, long tradeId, Runnable onSettled) {
        Objects.requireNonNull(actor, "操作者不能为空");
        Objects.requireNonNull(onSettled, "终局回调不能为空");
        trades.reject(tradeId, Actor.of(actor.getUniqueId(), actor.getName()))
                .whenComplete((result, failure) -> runSync(() -> {
                    if (failure != null) {
                        plugin.getComponentLogger().warn("拒绝报价失败（交易 {}，玩家 {}）",
                                tradeId, actor.getName(), failure);
                        send(actor, "command.error.internal");
                    } else {
                        switch (result) {
                            case ResolveResult.Resolved ignored -> send(actor, "trade.resolve.rejected");
                            case ResolveResult.AlreadyResolved ignored ->
                                    send(actor, "trade.resolve.already-resolved");
                            case ResolveResult.UnknownTrade ignored -> send(actor, "trade.resolve.unknown");
                            case ResolveResult.NotParticipant ignored ->
                                    send(actor, "trade.resolve.not-participant");
                        }
                    }
                    onSettled.run();
                }));
    }

    /**
     * 创建拒绝原因 → 语言键（DISABLED 与总开关提示共用一键）。
     *
     * @param reason 阻断原因
     * @return 语言键
     */
    public static String rejectKey(TradeBlockReason reason) {
        return reason == TradeBlockReason.DISABLED
                ? "trade.disabled"
                : "trade.reject." + reason.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** 同意结果播报（七分支穷举；成交先派发 Post 事件再通知双方）。 */
    private void announceAccept(Player acceptor, AcceptResult result) {
        switch (result) {
            case AcceptResult.Completed completed -> {
                // Post 事件：成交事务 commit 之后回到主线程派发
                new TradeCompletedEvent(completed.trade().id(),
                        completed.trade().initiatorUuid(), completed.trade().initiatorName(),
                        completed.trade().targetUuid(), completed.trade().targetName())
                        .callEvent();
                send(acceptor, "trade.accept.success",
                        Placeholder.unparsed("player", completed.trade().initiatorName()));
                final Player initiator = Bukkit.getPlayer(completed.trade().initiatorUuid());
                if (initiator != null) {
                    send(initiator, "trade.notify.accepted",
                            Placeholder.unparsed("player", acceptor.getName()));
                }
            }
            case AcceptResult.Invalidated ignored -> send(acceptor, "trade.accept.invalidated");
            case AcceptResult.Expired ignored -> send(acceptor, "trade.accept.expired");
            case AcceptResult.AlreadyResolved ignored -> send(acceptor, "trade.accept.already-resolved");
            case AcceptResult.UnknownTrade ignored -> send(acceptor, "trade.accept.unknown");
            case AcceptResult.NotTarget ignored -> send(acceptor, "trade.accept.not-target");
            case AcceptResult.Disabled ignored -> send(acceptor, "trade.disabled");
        }
    }

    /** 目标方在线时发可点击通知（点击执行 /byt trade 打开报价列表）。 */
    private void notifyTarget(UUID targetUuid, String initiatorName) {
        final Player target = Bukkit.getPlayer(targetUuid);
        if (target == null) {
            return;
        }
        final Component notice = lang.component("trade.notify.received",
                        Placeholder.unparsed("player", initiatorName))
                .clickEvent(ClickEvent.runCommand("/byt trade"))
                .hoverEvent(HoverEvent.showText(lang.component("trade.notify.hover")));
        target.sendMessage(lang.component("prefix").append(notice));
    }

    /** 带前缀的玩家提示。 */
    private void send(Player player, String key, TagResolver... resolvers) {
        player.sendMessage(lang.component("prefix").append(lang.component(key, resolvers)));
    }

    /** 跳回主线程（插件停用中静默丢弃）。 */
    private void runSync(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * 接受前事件闸门（主线程同步调用）：生产装配发 {@code TradePreAcceptEvent}
     * 并返回未被取消；单测注入桩验证"取消则服务不被调"分支。
     */
    @FunctionalInterface
    public interface PreAcceptGate {

        /**
         * 是否放行本次接受。
         *
         * @param acceptor 接受者（目标方）
         * @param tradeId  交易 id
         * @return 放行返回 true；Pre 事件被取消返回 false
         */
        boolean allow(Player acceptor, long tradeId);
    }
}
