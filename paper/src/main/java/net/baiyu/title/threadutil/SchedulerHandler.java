package net.baiyu.title.threadutil;

import net.baiyu.title.AbstractBaiYuTitle;
import net.baiyu.title.Reloadable;
import io.github.milkdrinkers.threadutil.PlatformBukkit;
import io.github.milkdrinkers.threadutil.Scheduler;

import java.time.Duration;

/**
 * A wrapper handler class for handling thread-util lifecycle.
 */
public class SchedulerHandler implements Reloadable {
    @Override
    public void onLoad(AbstractBaiYuTitle plugin) {
        Scheduler.init(new PlatformBukkit(plugin)); // Initialize thread-util
        Scheduler.setErrorHandler(e -> plugin.getSLF4JLogger().error("[Scheduler]: {}", e.getMessage()));
    }

    @Override
    public void onDisable(AbstractBaiYuTitle plugin) {
        if (Scheduler.isInitialized())
            Scheduler.shutdown(Duration.ofSeconds(60));
    }
}
