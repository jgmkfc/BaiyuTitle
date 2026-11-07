package net.baiyu.title.input;

import net.baiyu.title.gui.core.GuiSession;
import net.baiyu.title.gui.core.GuiSessionManager;
import net.baiyu.title.gui.core.MenuRouter;
import net.baiyu.title.gui.core.MenuSpec;
import net.baiyu.title.translation.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 输入会话公共支撑（包内共享）：ESC 仲裁标记、结束导航、消息发送与
 * 回调安全执行——聊天侧 {@link InputSessionManager} 与铁砧侧
 * {@link AnvilInputHandler} 复用同一份收尾语义。
 *
 * <p>线程纪律：全部方法仅主线程调用（聊天异步事件先跳主线程再进来）。</p>
 */
final class InputSupport {

    /** 插件实例（调度器归属与日志出口）。 */
    private final Plugin plugin;

    /** 菜单路由（结束后 replace 回 returnTo）。 */
    private final MenuRouter router;

    /** GUI 会话管理器（expectingInput 仲裁标记归属）。 */
    private final GuiSessionManager sessions;

    /** 语言门面。 */
    private final Lang lang;

    /** 构造输入会话支撑。 */
    InputSupport(Plugin plugin, MenuRouter router, GuiSessionManager sessions, Lang lang) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.router = Objects.requireNonNull(router, "菜单路由不能为空");
        this.sessions = Objects.requireNonNull(sessions, "GUI 会话管理器不能为空");
        this.lang = Objects.requireNonNull(lang, "语言门面不能为空");
    }

    /** @return 插件实例 */
    Plugin plugin() {
        return plugin;
    }

    /**
     * 开输入前置（§3.5 仲裁）：先置 expectingInput 标记，随后的菜单关闭
     * 走「输入切换」分支——不触发 onClosed、不清返回栈。
     */
    void beginExpecting(Player player) {
        sessions.get(player).setExpectingInput(true);
    }

    /** 复位 expectingInput 标记（只窥视不创建会话）。 */
    void clearExpecting(UUID uuid) {
        final GuiSession session = sessions.peek(uuid);
        if (session != null) {
            session.setExpectingInput(false);
        }
    }

    /** 丢弃导航残留（超时等不回菜单的收尾：复位标记 + 清返回栈）。 */
    void dropNavigation(UUID uuid) {
        clearExpecting(uuid);
        final GuiSession session = sessions.peek(uuid);
        if (session != null) {
            session.clearNavigation();
        }
    }

    /**
     * 会话结束导航：有 returnTo 且玩家在线 → {@code router.replace} 重开菜单
     * （重建即重新 loadData，天然最新数据）；否则丢弃导航残留并关闭
     * 可能残留的输入视图（铁砧完成路径）。
     */
    void finishReturn(Player player, @Nullable MenuSpec returnTo) {
        clearExpecting(player.getUniqueId());
        if (returnTo != null && player.isOnline()) {
            router.replace(player, returnTo);
            return;
        }
        final GuiSession session = sessions.peek(player.getUniqueId());
        if (session != null) {
            session.clearNavigation();
        }
        if (player.isOnline()) {
            player.closeInventory();
        }
    }

    /** 发送带插件前缀的 lang 消息。 */
    void send(Player player, String key, TagResolver... resolvers) {
        player.sendMessage(lang.component("prefix").append(lang.component(key, resolvers)));
    }

    /** 发送校验错误（占位符转 unparsed 解析器后渲染）。 */
    void sendError(Player player, InputError error) {
        final TagResolver[] resolvers = error.placeholders().entrySet().stream()
                .map(entry -> Placeholder.unparsed(entry.getKey(), entry.getValue()))
                .toArray(TagResolver[]::new);
        send(player, error.langKey(), resolvers);
    }

    /** 取标题组件（铁砧视图标题等）。 */
    Component title(String key) {
        return lang.component(key);
    }

    /** 取物品名组件（关闭斜体装饰）。 */
    Component itemName(String key) {
        return lang.component(key).decoration(TextDecoration.ITALIC, false);
    }

    /** 安全执行取消回调：异常只告警不外抛（收尾路径不可被业务异常打断）。 */
    void runCancel(Runnable onCancel) {
        try {
            onCancel.run();
        } catch (Exception e) {
            plugin.getComponentLogger().warn("输入会话取消回调执行失败。", e);
        }
    }

    /** 安全执行完成回调：异常只告警不外抛。 */
    void runComplete(Consumer<String> onComplete, String input) {
        try {
            onComplete.accept(input);
        } catch (Exception e) {
            plugin.getComponentLogger().warn("输入会话完成回调执行失败（输入 [{}]）。", input, e);
        }
    }

    /** 安全执行手持物品完成回调：异常只告警不外抛。 */
    void runComplete(Consumer<ItemStack> onComplete, ItemStack input) {
        try {
            onComplete.accept(input);
        } catch (Exception e) {
            plugin.getComponentLogger().warn("手持物品输入会话完成回调执行失败。", e);
        }
    }
}
