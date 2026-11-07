package net.baiyu.title.command.sub;

import dev.jorel.commandapi.CommandAPICommand;
import net.baiyu.title.bootstrap.ReloadCoordinator;
import net.baiyu.title.command.CommandSupport;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.Objects;

/**
 * {@code /byt reload}：白名单式重载（权限 baiyutitle.admin）。
 *
 * <p>只刷新 config / lang / 称号目录缓存；命令、监听器、调度器不重建；
 * database.yml 变更仅提示需重启（{@link ReloadCoordinator} 的既定语义）。</p>
 */
public final class ReloadCommand {

    /** 命令层公用工具。 */
    private final CommandSupport support;

    /** 重载协调器。 */
    private final ReloadCoordinator reloadCoordinator;

    /**
     * 构造重载子命令。
     *
     * @param support           命令层公用工具
     * @param reloadCoordinator 重载协调器
     */
    public ReloadCommand(CommandSupport support, ReloadCoordinator reloadCoordinator) {
        this.support = Objects.requireNonNull(support, "命令层公用工具不能为空");
        this.reloadCoordinator = Objects.requireNonNull(reloadCoordinator, "重载协调器不能为空");
    }

    /**
     * 构建子命令。
     *
     * @return CommandAPI 子命令
     */
    public CommandAPICommand build() {
        return new CommandAPICommand("reload")
                .withPermission("baiyutitle.admin")
                .executes((sender, args) -> {
                    reloadCoordinator.reload().whenComplete(
                        (result, failure) -> support.runSync(() -> {
                            if (failure != null) {
                                support.send(sender, "command.reload.failed", Placeholder.unparsed(
                                        "reason", String.valueOf(support.unwrap(failure).getMessage())));
                                return;
                            }
                            support.send(sender, "command.reload.success", Placeholder.unparsed(
                                    "count", String.valueOf(result.reloadedTitles())));
                            if (result.databaseChanged()) {
                                support.send(sender, "command.reload.database-changed");
                            }
                        }));
                });
    }
}
