package net.baiyu.title.repository.exception;

/**
 * 仓储层运行时异常（{@link java.sql.SQLException} 的非受检包装）。
 * <p>由 {@code TransactionRunner} 等基础设施在边界处统一包装，
 * 服务层无需在签名上传染受检异常。
 */
public class RepositoryException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * 构造仓储异常。
     *
     * @param message 异常消息
     */
    public RepositoryException(String message) {
        super(message);
    }

    /**
     * 构造仓储异常。
     *
     * @param message 异常消息
     * @param cause   根因（通常为 {@link java.sql.SQLException}）
     */
    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
