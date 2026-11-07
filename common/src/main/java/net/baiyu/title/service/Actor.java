package net.baiyu.title.service;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * 操作者标识（审计与流水的 actor 字段来源）。
 * <p>玩家/管理员操作带 UUID；控制台与系统任务无 UUID，只有名称
 * （actor_name 为管理员名或 SYSTEM）。
 *
 * @param uuid 操作者 UUID（可空；控制台/系统为空）
 * @param name 操作者名称（非空）
 */
public record Actor(@Nullable UUID uuid, String name) {

    /** 系统操作者名称（周期任务/自动收敛用）。 */
    private static final String SYSTEM_NAME = "SYSTEM";

    /** 控制台操作者名称。 */
    private static final String CONSOLE_NAME = "CONSOLE";

    /**
     * 紧凑构造器：名称判空。
     */
    public Actor {
        Objects.requireNonNull(name, "操作者名称不能为空");
    }

    /**
     * 玩家/管理员操作者。
     *
     * @param uuid 操作者 UUID
     * @param name 操作者名称
     * @return 操作者标识
     */
    public static Actor of(UUID uuid, String name) {
        return new Actor(uuid, name);
    }

    /**
     * 系统操作者（周期任务/自动收敛）。
     *
     * @return SYSTEM 操作者
     */
    public static Actor system() {
        return new Actor(null, SYSTEM_NAME);
    }

    /**
     * 控制台操作者。
     *
     * @return CONSOLE 操作者
     */
    public static Actor console() {
        return new Actor(null, CONSOLE_NAME);
    }
}
