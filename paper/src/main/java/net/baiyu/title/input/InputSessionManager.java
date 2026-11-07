package net.baiyu.title.input;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.baiyu.title.gui.core.GuiSessionManager;
import net.baiyu.title.gui.core.MenuRouter;
import net.baiyu.title.translation.Lang;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 输入会话统一管理器：
 * 聊天输入（管理员向）在本类实现，铁砧与手持物品输入分别委托
 * {@link AnvilInputHandler}/{@link HeldItemInputHandler}；对外提供 open/isCapturing/cancel/cancelAll
 * 门面，每玩家同时至多一个会话（新顶旧、旧会话按取消收尾）。
 *
 * <p>聊天会话：{@code AsyncChatEvent @LOWEST} 拦截该玩家下一条消息并取消
 * 事件（异步线程只读会话表 + 调度，处理统一跳主线程）；
 * {@code cancel}/{@code stop}（忽略大小写、容许前后空白）→ 取消；
 * 校验失败→报错并保持监听重试（超时重新计时）；{@link #INPUT_TIMEOUT}
 * 无输入自动取消并提示。完成/取消后经 router.replace(returnTo) 回菜单。</p>
 *
 * <p>注册：本类与 {@link #anvilHandler()} 由 ListenerHandler 一并注册；
 * open/cancel 门面仅主线程调用。</p>
 */
public final class InputSessionManager implements Listener {

    /** 文本与持物输入超时时长。 */
    public static final Duration INPUT_TIMEOUT = Duration.ofSeconds(60);

    /** @deprecated 输入类型现已不止聊天；请使用 {@link #INPUT_TIMEOUT}。 */
    @Deprecated
    public static final Duration CHAT_TIMEOUT = INPUT_TIMEOUT;

    /** 每秒 tick 数（Bukkit 调度单位换算）。 */
    private static final long TICKS_PER_SECOND = 20L;

    /** 公共支撑（仲裁标记 / 结束导航 / 消息 / 回调安全执行）。 */
    private final InputSupport support;

    /** 铁砧输入子处理器。 */
    private final AnvilInputHandler anvilHandler;

    /** 手持物品输入子处理器。 */
    private final HeldItemInputHandler heldItemHandler;

    /** 聊天会话表：主线程写、异步聊天线程读 → 并发表；值为会话对象。 */
    private final Map<UUID, ChatSession> chatSessions = new ConcurrentHashMap<>();

    /**
     * 构造输入会话管理器（组合根装配）。
     *
     * @param plugin   插件实例
     * @param router   菜单路由
     * @param sessions GUI 会话管理器
     * @param lang     语言门面
     */
    public InputSessionManager(Plugin plugin, MenuRouter router, GuiSessionManager sessions, Lang lang) {
        this.support = new InputSupport(plugin, router, sessions, lang);
        this.anvilHandler = new AnvilInputHandler(support);
        this.heldItemHandler = new HeldItemInputHandler(support);
    }

    /** @return 铁砧输入子监听器（ListenerHandler 与本类一并注册） */
    public AnvilInputHandler anvilHandler() {
        return anvilHandler;
    }

    /** @return 手持物品输入子监听器 */
    public HeldItemInputHandler heldItemHandler() {
        return heldItemHandler;
    }

    /**
     * 开启聊天输入会话（仅主线程）：顶掉旧会话 → 置 expectingInput 标记 →
     * 关当前菜单（§3.5 仲裁保返回栈）→ 发提示并开始监听 + 超时计时。
     *
     * @param player  目标玩家
     * @param request 聊天输入请求
     */
    public void openChatInput(Player player, ChatInputRequest request) {
        Objects.requireNonNull(player, "玩家不能为空");
        Objects.requireNonNull(request, "聊天输入请求不能为空");
        cancel(player.getUniqueId());
        support.beginExpecting(player);
        player.closeInventory();
        final ChatSession session = new ChatSession(request);
        chatSessions.put(player.getUniqueId(), session);
        scheduleTimeout(player, session);
        support.send(player, request.promptKey());
        support.send(player, "input.hint",
                Placeholder.unparsed("seconds", String.valueOf(INPUT_TIMEOUT.toSeconds())));
    }

    /**
     * 开启铁砧输入会话（仅主线程）：顶掉旧会话 → 置 expectingInput 标记 →
     * 打开铁砧虚拟视图（左槽命名牌）。
     *
     * @param player  目标玩家
     * @param request 铁砧输入请求
     */
    public void openAnvilInput(Player player, AnvilInputRequest request) {
        Objects.requireNonNull(player, "玩家不能为空");
        Objects.requireNonNull(request, "铁砧输入请求不能为空");
        cancel(player.getUniqueId());
        support.beginExpecting(player);
        anvilHandler.open(player, request);
    }

    /** 开启手持物品左键确认会话（仅主线程）。 */
    public void openHeldItemInput(Player player, HeldItemInputRequest request) {
        Objects.requireNonNull(player, "玩家不能为空");
        Objects.requireNonNull(request, "手持物品输入请求不能为空");
        cancel(player.getUniqueId());
        support.beginExpecting(player);
        heldItemHandler.open(player, request);
    }

    /**
     * @param uuid 玩家 UUID
     * @return 该玩家当前是否处于输入会话（聊天、铁砧或持物确认）
     */
    public boolean isCapturing(UUID uuid) {
        return chatSessions.containsKey(uuid) || anvilHandler.isCapturing(uuid)
                || heldItemHandler.isCapturing(uuid);
    }

    /**
     * 行政取消某玩家的输入会话（仅主线程；幂等）：执行 onCancel 回调、
     * 复位仲裁标记，<b>不</b>导航回菜单、不发提示——退出监听、reload、
     * 新会话顶旧共用本语义。
     *
     * @param uuid 玩家 UUID
     */
    public void cancel(UUID uuid) {
        Objects.requireNonNull(uuid, "玩家 UUID 不能为空");
        final ChatSession chat = chatSessions.remove(uuid);
        if (chat != null) {
            chat.cancelTimeout();
            support.runCancel(chat.request.onCancel());
        }
        anvilHandler.cancelQuietly(uuid);
        heldItemHandler.cancelQuietly(uuid);
        support.clearExpecting(uuid);
    }

    /** 行政取消全部输入会话（reload 序列头部 / onDisable 收尾调用）。 */
    public void cancelAll() {
        final Set<UUID> all = new HashSet<>(chatSessions.keySet());
        all.addAll(anvilHandler.capturedIds());
        all.addAll(heldItemHandler.capturedIds());
        for (final UUID uuid : all) {
            cancel(uuid);
        }
    }

    /**
     * 聊天拦截（LOWEST：抢在其他插件处理之前吞掉本条消息）。
     * <p>异步线程：只读会话表、取消事件、序列化纯文本，处理跳主线程。</p>
     *
     * @param event 聊天事件
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        final Player player = event.getPlayer();
        final ChatSession session = chatSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        event.setCancelled(true);
        final String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(support.plugin(), () -> handleChatMessage(player, session, text));
    }

    /** 主线程处理一条被拦截的聊天输入。 */
    private void handleChatMessage(Player player, ChatSession session, String rawText) {
        final UUID uuid = player.getUniqueId();
        if (chatSessions.get(uuid) != session) {
            return; // 会话已被顶替 / 取消——迟到消息静默丢弃
        }
        final String input = rawText.trim();
        if (isCancelInput(input)) {
            endChat(uuid, session);
            support.send(player, "input.cancelled");
            support.runCancel(session.request.onCancel());
            support.finishReturn(player, session.request.returnTo());
            return;
        }
        final var error = session.request.validator().check(input);
        if (error.isPresent()) {
            support.sendError(player, error.get());
            session.cancelTimeout();
            scheduleTimeout(player, session); // 重试重新计时
            return;
        }
        endChat(uuid, session);
        support.runComplete(session.request.onComplete(), input);
        if (session.request.returnAfterComplete() || !isCapturing(uuid)) {
            support.finishReturn(player, session.request.returnTo());
        }
    }

    /** 摘除聊天会话并停掉超时计时。 */
    private void endChat(UUID uuid, ChatSession session) {
        chatSessions.remove(uuid);
        session.cancelTimeout();
    }

    /** 挂一轮超时计时：到点仍是同一会话则自动取消并提示（不回菜单）。 */
    private void scheduleTimeout(Player player, ChatSession session) {
        session.timeoutTask = Bukkit.getScheduler().runTaskLater(support.plugin(), () -> {
            if (!chatSessions.remove(player.getUniqueId(), session)) {
                return;
            }
            support.dropNavigation(player.getUniqueId());
            if (player.isOnline()) {
                support.send(player, "input.timeout");
            }
            support.runCancel(session.request.onCancel());
        }, INPUT_TIMEOUT.toSeconds() * TICKS_PER_SECOND);
    }

    /** 所有文本输入会话共用的取消口令判断。 */
    static boolean isCancelInput(String input) {
        return input.equalsIgnoreCase("cancel") || input.equalsIgnoreCase("stop");
    }

    /** 聊天会话载体：请求 + 可重置的超时任务。 */
    private static final class ChatSession {

        /** 输入请求。 */
        final ChatInputRequest request;

        /** 超时任务（校验失败重试时重置）。 */
        @Nullable
        BukkitTask timeoutTask;

        ChatSession(ChatInputRequest request) {
            this.request = request;
        }

        /** 停掉当前超时任务（幂等）。 */
        void cancelTimeout() {
            if (timeoutTask != null) {
                timeoutTask.cancel();
                timeoutTask = null;
            }
        }
    }
}
