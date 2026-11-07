package net.baiyu.title.config;

import net.baiyu.title.config.exception.ConfigValidationException;
import net.baiyu.title.config.migration.Migration;
import net.baiyu.title.service.purchase.RecoveryPolicy;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.interfaces.meta.Exclude;
import org.spongepowered.configurate.NodePath;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigSerializable
public class PluginConfig implements VersionedConfig {
    @Comment("Do not change this value!")
    public int configVersion = 3;

    @Override
    @Exclude
    public int configVersion() {
        return configVersion;
    }

    @Override
    @Exclude
    public @NotNull Map<Integer, Migration> migrations() {
        return Map.of(
                2, Migration.builder()
                        .withAction(NodePath.path("chat", "listener-priority"), (path, node) -> {
                            if ("HIGH".equalsIgnoreCase(node.getString())) {
                                node.set("MONITOR");
                            }
                            return null;
                        })
                        .build(),
                // v3：删除从不生效的配置项——更新检查器（功能未实现）、
                // 获得后自动佩戴（未实现）、自定义称号长度（模板残留，实际按需求
                // 固定 50 码点校验）。残留键会让服主误以为改动有效，一并清掉。
                3, Migration.builder()
                        .delete(NodePath.path("update-checker"))
                        .delete(NodePath.path("title", "is-auto-use"))
                        .delete(NodePath.path("title", "custom-length"))
                        .build());
    }

    @Override
    @Exclude
    public void validate() throws ConfigValidationException {
        try {
            RecoveryPolicy.fromString(purchase.recovery);
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException(
                    "purchase.recovery must be one of: grant, refund, manual (got \""
                            + purchase.recovery + "\")");
        }
        if (trade.expireHours < 1) {
            throw new ConfigValidationException(
                    "trade.expire-hours must be >= 1 (got " + trade.expireHours + ")");
        }
        if (customTitle.itemAmount < 1) {
            throw new ConfigValidationException(
                    "custom-title.item-amount must be >= 1 (got " + customTitle.itemAmount + ")");
        }
        if (customTitle.itemMaterial == null || customTitle.itemMaterial.isBlank()) {
            throw new ConfigValidationException("custom-title.item-material must not be empty");
        }
    }

    @Comment("Language, specify the language file to use, for example `en_US` which will load `/lang/en_US.yml`")
    public String language = "en_US";

    @Comment("Title display settings")
    public TitleSettings title = new TitleSettings();

    @ConfigSerializable
    public static class TitleSettings {
        @Comment("Default title for new players")
        public String defaultTitle = "§f[ §7萌新 §f]";

        @Comment("Prefix brackets for titles")
        public String prefixBrackets = "§f[ ";

        @Comment("Suffix brackets for titles")
        public String suffixBrackets = " §f]";

        @Comment("Show title in chat")
        public boolean isChat = true;

        @Comment("Separator between title and player name in chat")
        public String isChatFormat = " ";

        @Comment("Show title prefix in the tab list (player list name).\nHead/nametag display is intentionally not implemented: it requires hijacking\nScoreboard teams and conflicts with scoreboard/prefix plugins.")
        public boolean isTab = false;

        @Comment("Toggle cooldown in seconds between wardrobe actions (0 to disable)")
        public int togglesCoolDown = 1;
    }

    @Comment("Shop settings")
    public ShopSettings shop = new ShopSettings();

    @ConfigSerializable
    public static class ShopSettings {
        @Comment("Enable the title shop")
        public boolean enabled = true;

        @Comment("Enable custom titles in the shop")
        public boolean customEnabled = true;
    }

    @Comment("Custom title creation prices (business doc 1.3.2.3.1.4).\nThe player picks exactly one payment option in the custom title menu.")
    public CustomTitleSettings customTitle = new CustomTitleSettings();

    /** 自定义称号付款价格（四种付款方式价格全在 config）。 */
    @ConfigSerializable
    public static class CustomTitleSettings {
        @Comment("Vault economy price (option H). 0 or less = this option is free of charge")
        public double vaultPrice = 10000.0;

        @Comment("Title coin price (option I). 0 or less = this option is free of charge")
        public long coinPrice = 1000;

        @Comment("Item payment (option J): namespaced material key of the required item")
        public String itemMaterial = "minecraft:diamond";

        @Comment("Item payment (option J): required amount")
        public int itemAmount = 64;

        @Comment("Permission payment (option K): players holding this node create for free.\nEmpty = the permission option is unavailable")
        public String permissionNode = "baiyutitle.custom.free";
    }

    @Comment("Purchase order settings")
    public PurchaseSettings purchase = new PurchaseSettings();

    /** 购买订单设置（PAYING 孤儿订单启动恢复策略）。 */
    @ConfigSerializable
    public static class PurchaseSettings {
        @Comment("Recovery policy for orders left in PAYING state after a crash.\nOne of: grant (default; fulfil the order in the player's favor),\nrefund (return title coins and release reserved stock),\nmanual (park the order for /byt admin order resolve <id> grant|refund).\nOrders left in RESERVED state are always refunded (they never touched Vault).")
        public String recovery = "grant";
    }

    @Comment("Player-to-player title trading settings")
    public TradeSettings trade = new TradeSettings();

    /** 交易设置（总开关；报价 TTL）。 */
    @ConfigSerializable
    public static class TradeSettings {
        @Comment("Master switch for player-to-player title trading.\nWhen disabled, creating and accepting offers is blocked and trade menu entries are hidden.")
        public boolean enabled = true;

        @Comment("Hours before a pending offer expires, counted from creation.\nExpired offers are shown to players as rejected.")
        public int expireHours = 72;
    }

    @Comment("Chat title rendering settings")
    public ChatSettings chat = new ChatSettings();

    /** 聊天称号渲染设置（聊天切片消费）。 */
    @ConfigSerializable
    public static class ChatSettings {
        @Comment("Bukkit event priority of the chat listener.\nOne of: LOWEST, LOW, NORMAL, HIGH, HIGHEST, MONITOR. MONITOR is recommended so chat-format plugins cannot overwrite title rendering. Requires a server restart to apply.")
        public String listenerPriority = "MONITOR";

        @Comment("Separator inserted between multiple displayed titles in chat")
        public String titleSeparator = " ";
    }

    /** 默认稀有度，从Timoris到Violis逐渐稀有。 */
    @Comment("Rarity names, ordered from lowest to highest.\nTitles reference rarities by name; entries removed from this list degrade\naffected titles to \"no rarity\" in chat hover display and sorting.")
    public List<String> rarities = List.of("Timoris", "Amoris", "Doloris", "Mortis", "Oblivionis", "Violis");

    @Comment("Player groups for title slot limits.\nGroups are evaluated top-to-bottom; the first group whose permission the player\nhas decides their max simultaneous displayed titles. A group with an empty\npermission acts as the fallback for everyone.")
    public Map<String, GroupSettings> groups = defaultGroups();

    /** 玩家分组（多称号栏位上限按组配置）。 */
    @ConfigSerializable
    public static class GroupSettings {
        @Comment("Permission node required for this group (empty = fallback group)")
        public String permission = "";

        @Comment("Max simultaneously displayed titles (wardrobe slot limit)")
        public int maxTitles = 1;
    }

    /** 默认分组：VIP 3 栏位、普通玩家 1 栏位。 */
    private static Map<String, GroupSettings> defaultGroups() {
        final Map<String, GroupSettings> map = new LinkedHashMap<>();
        final GroupSettings vip = new GroupSettings();
        vip.permission = "baiyutitle.group.vip";
        vip.maxTitles = 3;
        map.put("VIP", vip);
        final GroupSettings fallback = new GroupSettings();
        fallback.permission = "";
        fallback.maxTitles = 1;
        map.put("Default", fallback);
        return map;
    }

    @Comment("Menu settings")
    public MenuSettings menu = new MenuSettings();

    /** 菜单设置（顶栏 P 键返回服务器主菜单）。 */
    @ConfigSerializable
    public static class MenuSettings {
        @Comment("Command executed when clicking the \"server main menu\" button (P) in menus.\nWithout leading slash, e.g. \"cd\". Empty = the button only shows a hint.")
        public String mainMenuCommand = "cd";
    }

    @Comment("Background task settings")
    public TaskSettings tasks = new TaskSettings();

    /** 定时任务设置（调度器不随 reload 重建，改动需重启）。 */
    @ConfigSerializable
    public static class TaskSettings {
        @Comment("Interval in seconds between sweeps that expire outdated titles.\nRequires a server restart to apply.")
        public int expirySweepSeconds = 60;

        @Comment("Interval in seconds between sweeps that expire outdated trade offers.\nRequires a server restart to apply.")
        public int tradeSweepSeconds = 300;
    }
}
