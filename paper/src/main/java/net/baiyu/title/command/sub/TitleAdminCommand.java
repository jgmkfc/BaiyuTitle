package net.baiyu.title.command.sub;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.GreedyStringArgument;
import io.github.milkdrinkers.colorparser.paper.ColorParser;
import net.baiyu.title.command.CommandSupport;
import net.baiyu.title.command.argument.TitleIdArgument;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.domain.title.TitleCategory;
import net.baiyu.title.service.TitleCatalogService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * {@code /byt title ...}：称号目录管理（权限 baiyutitle.admin）。
 *
 * <ul>
 *   <li>{@code create <name>} —— 创建普通称号（名称三格式混色，服务侧归一化）</li>
 *   <li>{@code delete <id>} —— 软删（历史保留，同事务卸下全服装扮引用）</li>
 *   <li>{@code list} —— 列出全部未删称号（读缓存快照，零 DB IO）</li>
 * </ul>
 */
public final class TitleAdminCommand {

    /** 命令层公用工具。 */
    private final CommandSupport support;

    /** 称号目录服务。 */
    private final TitleCatalogService catalogService;

    /** 称号 ID 参数工厂。 */
    private final TitleIdArgument titleIds;

    /**
     * 构造称号管理子命令。
     *
     * @param support        命令层公用工具
     * @param catalogService 称号目录服务
     * @param titleIds       称号 ID 参数工厂
     */
    public TitleAdminCommand(CommandSupport support, TitleCatalogService catalogService,
                             TitleIdArgument titleIds) {
        this.support = Objects.requireNonNull(support, "命令层公用工具不能为空");
        this.catalogService = Objects.requireNonNull(catalogService, "称号目录服务不能为空");
        this.titleIds = Objects.requireNonNull(titleIds, "称号 ID 参数工厂不能为空");
    }

    /**
     * 构建子命令树。
     *
     * @return CommandAPI 子命令
     */
    public CommandAPICommand build() {
        return new CommandAPICommand("title")
                .withPermission("baiyutitle.admin")
                .withSubcommands(buildCreate(), buildDelete(), buildList());
    }

    /** {@code create <name>}：贪婪字符串吃下含空格/色码的完整名称。 */
    private CommandAPICommand buildCreate() {
        return new CommandAPICommand("create")
                .withArguments(new GreedyStringArgument("name"))
                .executes((sender, args) -> {
                    final String rawName = (String) args.get("name");
                    catalogService.create(rawName, TitleCategory.NORMAL, support.actorOf(sender))
                            .whenComplete((title, failure) -> support.runSync(() -> {
                                if (failure != null) {
                                    replyRejection(sender, "command.title.create-failed", failure);
                                    return;
                                }
                                support.send(sender, "command.title.create-success",
                                        Placeholder.unparsed("id", String.valueOf(title.id())),
                                        Placeholder.component("name", nameComponent(title)));
                            }));
                });
    }

    /** {@code delete <id>}：软删。 */
    private CommandAPICommand buildDelete() {
        return new CommandAPICommand("delete")
                .withArguments(titleIds.create("id"))
                .executes((sender, args) -> {
                    final int titleId = (int) args.get("id");
                    catalogService.delete(titleId)
                            .whenComplete((title, failure) -> support.runSync(() -> {
                                if (failure != null) {
                                    replyRejection(sender, "command.title.delete-failed", failure);
                                    return;
                                }
                                support.send(sender, "command.title.delete-success",
                                        Placeholder.unparsed("id", String.valueOf(titleId)),
                                        Placeholder.component("name", nameComponent(title)));
                            }));
                });
    }

    /** {@code list}：缓存快照按 id 升序列出未删称号。 */
    private CommandAPICommand buildList() {
        return new CommandAPICommand("list")
                .executes((sender, args) -> {
                    final List<Title> titles = catalogService.snapshot().stream()
                            .filter(title -> !title.isDeleted())
                            .sorted(Comparator.comparingInt(Title::id))
                            .toList();
                    if (titles.isEmpty()) {
                        support.send(sender, "command.title.list-empty");
                        return;
                    }
                    support.send(sender, "command.title.list-header",
                            Placeholder.unparsed("count", String.valueOf(titles.size())));
                    for (Title title : titles) {
                        support.sendPlain(sender, "command.title.list-entry",
                                Placeholder.unparsed("id", String.valueOf(title.id())),
                                Placeholder.component("name", nameComponent(title)));
                    }
                });
    }

    /**
     * 业务拒绝（IllegalArgumentException，如名称超长 / 称号不存在）回发具体原因；
     * 其余异常走统一失败收尾。
     */
    private void replyRejection(CommandSender sender, String key, Throwable failure) {
        final Throwable cause = support.unwrap(failure);
        if (cause instanceof IllegalArgumentException) {
            support.send(sender, key, Placeholder.unparsed("reason", String.valueOf(cause.getMessage())));
        } else {
            support.handleFailure(sender, failure);
        }
    }

    /** 称号名组件（存储值已是 MiniMessage，直接解析）。 */
    private static Component nameComponent(Title title) {
        return ColorParser.of(title.name()).build();
    }
}
