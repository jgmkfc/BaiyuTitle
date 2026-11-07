package net.baiyu.title.repository;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 运行时表名拼接器（迁移 SQL 用 Flyway 占位符 {@code ${tablePrefix}}，
 * 运行时 SQL 用本类统一拼接前缀）。
 * <p>返回值带 ANSI 双引号（与迁移 SQL 的标识符风格一致，H2 MySQL 模式 /
 * SQLite / MySQL(ANSI SQL_MODE) / MariaDB 四方言通用），可直接嵌入 SQL 字符串。
 * <p>前缀白名单校验 {@code [A-Za-z0-9_]{0,24}}，从根上排除标识符注入。
 */
public final class TableNames {

    /** 表前缀白名单（长度 0~24 的字母数字下划线；空串 = 无前缀）。 */
    private static final Pattern PREFIX_PATTERN = Pattern.compile("[A-Za-z0-9_]{0,24}");

    /** 已校验的表前缀。 */
    private final String tablePrefix;

    /**
     * 构造表名拼接器。
     *
     * @param tablePrefix 表前缀（建议 {@code byt_}；空串表示无前缀）
     * @throws IllegalArgumentException 前缀含非法字符或超长时
     */
    public TableNames(String tablePrefix) {
        Objects.requireNonNull(tablePrefix, "表前缀不能为 null（无前缀请传空串）");
        if (!PREFIX_PATTERN.matcher(tablePrefix).matches()) {
            throw new IllegalArgumentException(
                    "表前缀只允许 [A-Za-z0-9_] 且长度不超过 24: " + tablePrefix);
        }
        this.tablePrefix = tablePrefix;
    }

    /**
     * 拼前缀并加 ANSI 双引号。
     */
    private String quoted(String baseName) {
        return "\"" + tablePrefix + baseName + "\"";
    }

    /** 称号主表（V1）。 */
    public String title() {
        return quoted("title");
    }

    /** 购买条件表（V1）。 */
    public String titleCondition() {
        return quoted("title_condition");
    }

    /** 羁绊需求表（V1）。 */
    public String titleBondRequirement() {
        return quoted("title_bond_requirement");
    }

    /** 所有权实例表（V2）。 */
    public String titleOwnership() {
        return quoted("title_ownership");
    }

    /** 装扮栏位表（V2）。 */
    public String playerSlot() {
        return quoted("player_slot");
    }

    /** 随机栏位候选池表（V2）。 */
    public String playerSlotPool() {
        return quoted("player_slot_pool");
    }

    /** 称号币余额表（V3）。 */
    public String playerCoin() {
        return quoted("player_coin");
    }

    /** 称号币流水表（V3）。 */
    public String coinLog() {
        return quoted("coin_log");
    }

    /** 购买订单表（V3）。 */
    public String purchaseOrder() {
        return quoted("purchase_order");
    }

    /** 交易单表（V4）。 */
    public String trade() {
        return quoted("trade");
    }

    /** 交易标的表（V4）。 */
    public String tradeItem() {
        return quoted("trade_item");
    }

    /** 玩家组表（V5）。 */
    public String playerGroup() {
        return quoted("player_group");
    }

    /** 组成员表（V5）。 */
    public String playerGroupMember() {
        return quoted("player_group_member");
    }

    /** 兑换码表（V5）。 */
    public String redeemCode() {
        return quoted("redeem_code");
    }

    /** 兑换码使用记录表（V5）。 */
    public String redeemCodeUse() {
        return quoted("redeem_code_use");
    }

    /** 称号卡表（V5）。 */
    public String redeemCard() {
        return quoted("redeem_card");
    }

    /** 审计日志表（V5）。 */
    public String auditLog() {
        return quoted("audit_log");
    }
}
