package net.baiyu.title;

/**
 * Implemented in classes that should support being reloaded IE executing the methods during runtime after startup.
 */
public interface Reloadable {
    /**
     * On plugin load.
     */
    default void onLoad(AbstractBaiYuTitle plugin) {
    }

    /**
     * On plugin enable.
     */
    default void onEnable(AbstractBaiYuTitle plugin) {
    }

    /**
     * On plugin disable.
     */
    default void onDisable(AbstractBaiYuTitle plugin) {
    }

}
