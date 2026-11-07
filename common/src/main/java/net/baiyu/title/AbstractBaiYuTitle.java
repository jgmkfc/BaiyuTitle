package net.baiyu.title;

import net.baiyu.title.config.ConfigHandler;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public abstract class AbstractBaiYuTitle extends JavaPlugin {
    private static AbstractBaiYuTitle instance;

    /**
     * Gets plugin instance.
     *
     * @return the plugin instance
     */
    public static AbstractBaiYuTitle getInstance() {
        return AbstractBaiYuTitle.instance;
    }

    AbstractBaiYuTitle() {
        AbstractBaiYuTitle.instance = this;
    }

    /**
     * Gets config handler.
     *
     * @return the config handler
     */
    public abstract @NotNull ConfigHandler getConfigHandler();
}
