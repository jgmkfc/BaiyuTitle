package net.baiyu.title.command.sub;

import dev.jorel.commandapi.CommandAPICommand;
import net.baiyu.title.command.CommandSupport;

import java.util.Objects;

/**
 * {@code /byt help}：多行帮助文本（权限 baiyutitle.command.help，默认 true）。
 */
public final class HelpCommand {

    /** 命令层公用工具。 */
    private final CommandSupport support;

    /**
     * 构造帮助子命令。
     *
     * @param support 命令层公用工具
     */
    public HelpCommand(CommandSupport support) {
        this.support = Objects.requireNonNull(support, "命令层公用工具不能为空");
    }

    /**
     * 构建子命令。
     *
     * @return CommandAPI 子命令
     */
    public CommandAPICommand build() {
        return new CommandAPICommand("help")
                .withPermission("baiyutitle.command.help")
                .executes((sender, args) -> {
                    support.sendLines(sender, "command.help.lines");
                });
    }
}
