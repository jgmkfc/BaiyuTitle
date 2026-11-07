package net.baiyu.title.service;

import net.baiyu.title.api.event.TitleGrantEvent;
import net.baiyu.title.api.event.TitleRevokeEvent;
import net.baiyu.title.domain.ownership.GrantSource;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;

/**
 * 授予 / 回收 Post 事件桥接（paper 侧）：实现 common 层的提交后钩子，
 * 把写线程回调切到主线程后派发 {@link TitleGrantEvent} / {@link TitleRevokeEvent}。
 *
 * <p>线程契约：钩子回调发生在写线程、事务 commit 之后；
 * 本类只做入队切主线程，事件恒在主线程派发。插件停用窗口内丢弃不再入队。</p>
 */
public final class GrantEventBridge
        implements OwnershipService.PostGrantHook, OwnershipService.PostRevokeHook {

    /** 插件实例（主线程调度归属与启用判定）。 */
    private final Plugin plugin;

    /**
     * 构造授予 / 回收事件桥接。
     *
     * @param plugin 插件实例
     */
    public GrantEventBridge(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
    }

    /**
     * 授予事务提交后回调（写线程）：切主线程派发 {@link TitleGrantEvent}。
     *
     * @param owner   持有者 UUID
     * @param titleId 称号 id
     * @param source  授予来源
     */
    @Override
    public void onGranted(UUID owner, int titleId, GrantSource source) {
        runOnMain(() -> new TitleGrantEvent(owner, titleId, source.name()).callEvent());
    }

    /**
     * 回收事务提交后回调（写线程）：切主线程派发 {@link TitleRevokeEvent}。
     *
     * @param owner   原持有者 UUID
     * @param titleId 称号 id
     * @param reason  回收原因
     */
    @Override
    public void onRevoked(UUID owner, int titleId, String reason) {
        runOnMain(() -> new TitleRevokeEvent(owner, titleId, reason).callEvent());
    }

    /** 入队主线程（插件停用中静默丢弃）。 */
    private void runOnMain(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
