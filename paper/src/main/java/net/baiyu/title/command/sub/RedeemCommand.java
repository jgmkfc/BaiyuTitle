package net.baiyu.title.command.sub;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.IntegerArgument;
import dev.jorel.commandapi.arguments.StringArgument;
import net.baiyu.title.cache.TitleCatalogCache;
import net.baiyu.title.command.CommandSupport;
import net.baiyu.title.command.argument.TitleIdArgument;
import net.baiyu.title.gui.render.TitleItemRenderer;
import net.baiyu.title.service.RedeemCardItems;
import net.baiyu.title.service.RedeemCodeFlow;
import net.baiyu.title.service.RedeemService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * {@code /byt redeem|code ...}：兑换系统入口。
 *
 * <ul>
 *   <li>{@code redeem} —— 铁砧输入兑换码（baiyutitle.use，仅玩家）</li>
 *   <li>{@code redeem create <titleId> [maxUses] [perPlayer] [expireDays]}
 *       —— 创建兑换码，码面随机生成（baiyutitle.admin）</li>
 *   <li>{@code redeem card <titleId> [holdDays]} —— 发称号卡到执行者背包，
 *       背包满则脚下掉落（baiyutitle.admin，仅玩家）</li>
 *   <li>{@code redeem disable <code>} —— 停用兑换码（baiyutitle.admin）</li>
 * </ul>
 *
 * <p>裁决：maxUses / perPlayer 默认 1（防手滑无限码；0 = 显式无限）；
 * expireDays 省略 = 永久有效；生成码面用去混淆字符集
 * （无 0/O/1/I）三段四位，服务端大写归一化后落库。</p>
 */
public final class RedeemCommand {

    /** 码面字符集（去 0/O/1/I 混淆字符）。 */
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** 码面段长与段数（XXXX-XXXX-XXXX）。 */
    private static final int CODE_SEGMENT_LENGTH = 4;

    /** 码面段数。 */
    private static final int CODE_SEGMENTS = 3;

    /** 一天的毫秒数（expireDays 换算）。 */
    private static final long DAY_MILLIS = 86_400_000L;

    /** 命令层公用工具。 */
    private final CommandSupport support;

    /** 玩家兑换码输入与兑换反馈流程。 */
    private final RedeemCodeFlow redeemCodes;

    /** 兑换服务。 */
    private final RedeemService redeemService;

    /** 称号目录缓存（称号取名）。 */
    private final TitleCatalogCache catalogCache;

    /** 称号物品渲染门面（带括号称号名）。 */
    private final TitleItemRenderer renderer;

    /** 称号卡物品编解码器。 */
    private final RedeemCardItems cardItems;

    /** 称号 ID 参数工厂。 */
    private final TitleIdArgument titleIds;

    /**
     * 构造兑换子命令。
     *
     * @param support       命令层公用工具
     * @param redeemCodes   玩家兑换码输入与兑换反馈流程
     * @param redeemService 兑换服务
     * @param catalogCache  称号目录缓存
     * @param renderer      称号物品渲染门面
     * @param cardItems     称号卡物品编解码器
     * @param titleIds      称号 ID 参数工厂
     */
    public RedeemCommand(CommandSupport support, RedeemCodeFlow redeemCodes,
                         RedeemService redeemService, TitleCatalogCache catalogCache,
                         TitleItemRenderer renderer, RedeemCardItems cardItems,
                         TitleIdArgument titleIds) {
        this.support = Objects.requireNonNull(support, "命令层公用工具不能为空");
        this.redeemCodes = Objects.requireNonNull(redeemCodes, "兑换码执行链不能为空");
        this.redeemService = Objects.requireNonNull(redeemService, "兑换服务不能为空");
        this.catalogCache = Objects.requireNonNull(catalogCache, "称号目录缓存不能为空");
        this.renderer = Objects.requireNonNull(renderer, "称号物品渲染门面不能为空");
        this.cardItems = Objects.requireNonNull(cardItems, "称号卡编解码器不能为空");
        this.titleIds = Objects.requireNonNull(titleIds, "称号 ID 参数工厂不能为空");
    }

    /**
     * 构建子命令树。
     *
     * @return CommandAPI 子命令（别名 code）
     */
    public CommandAPICommand build() {
        return new CommandAPICommand("redeem")
                .withAliases("code")
                .withPermission("baiyutitle.use")
                .withSubcommands(buildCreate(), buildCard(), buildDisable())
                .executesPlayer((player, args) -> {
                    redeemCodes.open(player, null);
                });
    }

    /** {@code create <titleId> [maxUses] [perPlayer] [expireDays]}。 */
    private CommandAPICommand buildCreate() {
        return new CommandAPICommand("create")
                .withPermission("baiyutitle.admin")
                .withArguments(titleIds.create("titleId"))
                .withOptionalArguments(new IntegerArgument("maxUses", 0),
                        new IntegerArgument("perPlayer", 0),
                        new IntegerArgument("expireDays", 1))
                .executes((sender, args) -> {
                    final int titleId = (int) args.get("titleId");
                    final int maxUses = (Integer) args.getOptional("maxUses").orElse(1);
                    final int perPlayer = (Integer) args.getOptional("perPlayer").orElse(1);
                    final Integer expireDays = (Integer) args.getOptional("expireDays").orElse(null);
                    final Long validUntil = expireDays == null
                            ? null : System.currentTimeMillis() + expireDays * DAY_MILLIS;
                    redeemService.createCode(generateCode(), titleId, maxUses, perPlayer,
                                    null, validUntil, support.actorOf(sender))
                            .whenComplete(replyOn(sender, created ->
                                    support.send(sender, "command.redeem.create-success",
                                            Placeholder.unparsed("code", created.code()),
                                            Placeholder.component("name", titleName(titleId)),
                                            Placeholder.unparsed("uses", limitText(maxUses)),
                                            Placeholder.unparsed("per", limitText(perPlayer)),
                                            Placeholder.unparsed("expiry", expiryText(expireDays)))));
                });
    }

    /** {@code card <titleId> [holdDays]}：发到执行者背包，满则脚下掉落。 */
    private CommandAPICommand buildCard() {
        return new CommandAPICommand("card")
                .withPermission("baiyutitle.admin")
                .withArguments(titleIds.create("titleId"))
                .withOptionalArguments(new IntegerArgument("holdDays", 0))
                .executesPlayer((player, args) -> {
                    final int titleId = (int) args.get("titleId");
                    final Integer holdDays = (Integer) args.getOptional("holdDays").orElse(null);
                    redeemService.issueCard(titleId, holdDays, support.actorOf(player))
                            .whenComplete(replyOn(player, card -> {
                                if (!player.isOnline()) {
                                    return; // 卡片已落库未交付；卡片 UUID 在审计日志可查
                                }
                                final Component name = titleName(titleId);
                                final ItemStack item = cardItems.build(card, name);
                                player.getInventory().addItem(item).values().forEach(rest ->
                                        player.getWorld().dropItemNaturally(player.getLocation(), rest));
                                support.send(player, "command.redeem.card-success",
                                        Placeholder.component("name", name),
                                        Placeholder.unparsed("duration",
                                                cardItems.durationText(holdDays)));
                            }));
                });
    }

    /** {@code disable <code>}。 */
    private CommandAPICommand buildDisable() {
        return new CommandAPICommand("disable")
                .withPermission("baiyutitle.admin")
                .withArguments(new StringArgument("code"))
                .executes((sender, args) -> {
                    final String code = (String) args.get("code");
                    redeemService.disableCode(code, support.actorOf(sender))
                            .whenComplete(replyOn(sender, disabled -> support.send(sender,
                                    disabled ? "command.redeem.disable-success"
                                            : "command.redeem.disable-not-found",
                                    Placeholder.unparsed("code",
                                            code.trim().toUpperCase(Locale.ROOT)))));
                });
    }

    /**
     * 异步收尾模板：完成后跳主线程；管理端参数非法（IllegalArgumentException）
     * 回发原因文案，其余失败统一收尾。
     */
    private <T> BiConsumer<T, Throwable> replyOn(CommandSender sender, Consumer<T> reply) {
        return (result, failure) -> support.runSync(() -> {
            if (failure == null) {
                reply.accept(result);
                return;
            }
            if (support.unwrap(failure) instanceof IllegalArgumentException invalid) {
                support.send(sender, "command.redeem.failed",
                        Placeholder.unparsed("reason", String.valueOf(invalid.getMessage())));
            } else {
                support.handleFailure(sender, failure);
            }
        });
    }

    /** 随机码面：去混淆字符集三段四位（如 {@code K7KF-2MQ9-XW4H}）。 */
    private static String generateCode() {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final StringBuilder builder = new StringBuilder(CODE_SEGMENTS * (CODE_SEGMENT_LENGTH + 1));
        for (int segment = 0; segment < CODE_SEGMENTS; segment++) {
            if (segment > 0) {
                builder.append('-');
            }
            for (int i = 0; i < CODE_SEGMENT_LENGTH; i++) {
                builder.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
            }
        }
        return builder.toString();
    }

    /** 用量上限展示：0 = 无限。 */
    private String limitText(int limit) {
        return limit == 0 ? support.raw("command.redeem.unlimited") : String.valueOf(limit);
    }

    /** 有效期展示：省略 = 永久；n = n 天。 */
    private String expiryText(Integer expireDays) {
        return expireDays == null
                ? support.raw("format.permanent")
                : support.raw("format.duration-days").replace("<days>", String.valueOf(expireDays));
    }

    /** 目录缓存取称号展示名（缺失退回 #id）。 */
    private Component titleName(int titleId) {
        return catalogCache.byId(titleId)
                .map(renderer::nameWithBrackets)
                .orElseGet(() -> Component.text("#" + titleId));
    }
}
