package net.baiyu.title.domain.group;

import java.util.Objects;
import java.util.UUID;

/**
 * 玩家组成员（对应 {@code player_group_member} 表，PK(group_id, player_uuid)）。
 *
 * @param groupId    所属组 id
 * @param playerUuid 玩家 UUID
 * @param playerName 玩家名称缓存
 * @param addedBy    添加者名称（可空；控制台添加为空）
 * @param addedAt    添加时刻（UTC epoch 毫秒）
 */
public record GroupMember(
        int groupId,
        UUID playerUuid,
        String playerName,
        String addedBy,
        long addedAt) {

    /**
     * 紧凑构造器：必填字段判空。
     */
    public GroupMember {
        Objects.requireNonNull(playerUuid, "玩家 UUID 不能为空");
        Objects.requireNonNull(playerName, "玩家名称不能为空");
    }
}
