package net.baiyu.title.command;

import dev.jorel.commandapi.CommandAPI;
import dev.jorel.commandapi.CommandAPIPaperConfig;
import net.baiyu.title.BaiYuTitle;

/**
 * CommandAPI 生命周期壳。
 *
 * <p>CommandAPI 不允许二次 {@code onLoad}——用类内静态标记做一次性守卫；
 * 插件内 {@code /byt reload} 走白名单序列根本不触碰本类，守卫只防御
 * 服务器级 {@code /reload} 等把插件 onLoad 再次拉起的场景。</p>
 */
public final class CommandHandler {

    /** onLoad 一次性守卫（同一类加载器生命周期内只允许初始化一次）。 */
    private static boolean loaded = false;

    private CommandHandler() {
    }

    /**
     * 初始化 CommandAPI（主类 {@code onLoad} 调用；重复调用为 no-op）。
     *
     * @param plugin 插件实例
     */
    public static void onLoadOnce(BaiYuTitle plugin) {
        if (loaded) {
            plugin.getComponentLogger().warn("检测到重复的 CommandAPI onLoad 调用，已忽略。");
            return;
        }
        CommandAPI.onLoad(new CommandAPIPaperConfig(plugin).silentLogs(true));
        loaded = true;
    }

    /**
     * 启用 CommandAPI（主类 {@code onEnable}、命令注册之前调用）。
     */
    public static void enable() {
        CommandAPI.onEnable();
    }

    /**
     * 停用 CommandAPI（主类 {@code onDisable} 调用）。
     */
    public static void disable() {
        CommandAPI.onDisable();
    }
}
