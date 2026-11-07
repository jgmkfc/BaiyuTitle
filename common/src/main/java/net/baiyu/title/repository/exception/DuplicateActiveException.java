package net.baiyu.title.repository.exception;

import java.util.UUID;

/**
 * 重复 ACTIVE 所有权异常（不变量 §十 1：每 (owner, title) 至多一条 ACTIVE）。
 * <p>由 {@code OwnershipRepository.insertGrant} 在唯一约束
 * {@code UNIQUE(owner_uuid, title_id, active_marker)} 冲突时转抛，
 * 供服务层识别"已持有同款 ACTIVE"并给出业务提示。
 */
public class DuplicateActiveException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** 冲突的持有者 UUID。 */
    private final UUID ownerUuid;

    /** 冲突的称号 id。 */
    private final int titleId;

    /**
     * 构造重复 ACTIVE 异常。
     *
     * @param ownerUuid 持有者 UUID
     * @param titleId   称号 id
     * @param cause     根因（唯一约束冲突的 SQLException）
     */
    public DuplicateActiveException(UUID ownerUuid, int titleId, Throwable cause) {
        super("玩家已持有该称号的 ACTIVE 所有权: owner=" + ownerUuid + ", titleId=" + titleId, cause);
        this.ownerUuid = ownerUuid;
        this.titleId = titleId;
    }

    /**
     * 冲突的持有者 UUID。
     *
     * @return 持有者 UUID
     */
    public UUID ownerUuid() {
        return ownerUuid;
    }

    /**
     * 冲突的称号 id。
     *
     * @return 称号 id
     */
    public int titleId() {
        return titleId;
    }
}
