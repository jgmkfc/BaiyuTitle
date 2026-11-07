package net.baiyu.title.command;

import net.baiyu.title.BaiYuTitle;
import net.baiyu.title.service.Actor;
import net.baiyu.title.translation.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * 命令层公用工具：消息发送（统一带前缀）、主线程回跳、玩家名解析与操作者构造。
 *
 * <p>线程约定：命令执行器跑在主线程；服务返回的 future 在 DB 线程完成，
 * 回发消息一律经 {@link #runSync} 跳回主线程（插件停用中则静默丢弃）。</p>
 */
public final class CommandSupport {

    /** 插件实例（主线程调度归属）。 */
    private final BaiYuTitle plugin;

    /** 语言门面。 */
    private final Lang lang;

    /**
     * 构造命令层公用工具。
     *
     * @param plugin 插件实例
     * @param lang   语言门面
     */
    public CommandSupport(BaiYuTitle plugin, Lang lang) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.lang = Objects.requireNonNull(lang, "语言门面不能为空");
    }

    /**
     * 发送带插件前缀的单行消息。
     *
     * @param receiver  接收者
     * @param key       语言键
     * @param resolvers 占位符解析器
     */
    public void send(CommandSender receiver, String key, TagResolver... resolvers) {
        receiver.sendMessage(lang.component("prefix").append(lang.component(key, resolvers)));
    }

    /**
     * 发送不带前缀的单行消息（列表条目等由 lang 完整控制样式的行）。
     *
     * @param receiver  接收者
     * @param key       语言键
     * @param resolvers 占位符解析器
     */
    public void sendPlain(CommandSender receiver, String key, TagResolver... resolvers) {
        receiver.sendMessage(lang.component(key, resolvers));
    }

    /**
     * 逐行发送多行文本（帮助等；不带前缀，行内容由 lang 完整控制）。
     *
     * @param receiver 接收者
     * @param key      语言键（值须为字符串数组）
     */
    public void sendLines(CommandSender receiver, String key) {
        for (Component line : lang.list(key)) {
            receiver.sendMessage(line);
        }
    }

    /**
     * 取原始语言模板（占位符字符串预组装等场景；{@code Lang.raw} 便捷转发）。
     *
     * @param key 语言键
     * @return 原始文本
     */
    public String raw(String key) {
        return lang.raw(key);
    }

    /**
     * 跳回主线程执行（插件停用中则丢弃——停服窗口内不再回发消息）。
     *
     * @param task 主线程任务
     */
    public void runSync(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * 统一的异常收尾：控制台记录完整堆栈，向发送者回发通用失败消息。
     * <p>在主线程调用（外层先 {@link #runSync}）。</p>
     *
     * @param sender  命令发送者
     * @param failure future 的异常（自动解包 {@link CompletionException}）
     */
    public void handleFailure(CommandSender sender, Throwable failure) {
        plugin.getComponentLogger().warn("命令执行失败", unwrap(failure));
        send(sender, "command.error.internal");
    }

    /**
     * 解包 future 组合异常，取出根因。
     *
     * @param failure 原始异常
     * @return 根因异常
     */
    public Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    /**
     * 玩家名解析：在线精确匹配优先，其次本地离线缓存（不做网络查询）。
     * <p>仅主线程调用（Bukkit API）。</p>
     *
     * @param name 玩家名
     * @return 解析结果；本服务器从未见过该玩家时为空
     */
    public Optional<ResolvedPlayer> resolve(String name) {
        final Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(new ResolvedPlayer(online.getUniqueId(), online.getName()));
        }
        final OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && cached.getName() != null) {
            return Optional.of(new ResolvedPlayer(cached.getUniqueId(), cached.getName()));
        }
        return Optional.empty();
    }

    /**
     * 由命令发送者构造审计操作者。
     *
     * @param sender 命令发送者
     * @return 玩家→(UUID, 名字)；控制台→CONSOLE
     */
    public Actor actorOf(CommandSender sender) {
        return sender instanceof Player player
                ? Actor.of(player.getUniqueId(), player.getName())
                : Actor.console();
    }

    /**
     * 玩家名解析结果。
     *
     * @param uuid 玩家 UUID
     * @param name 玩家名（在线取实时名，离线取缓存名）
     */
    public record ResolvedPlayer(UUID uuid, String name) {
    }
}
