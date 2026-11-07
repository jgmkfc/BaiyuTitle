package net.baiyu.title.config.migration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.transformation.TransformAction;

import java.util.Objects;

/**
 * {@link TransformAction} 的构造工具。
 *
 * <p>用于 {@link Migration.Builder} 的路径操作实现——构建器的 fluent 方法
 * （rename / move / relocate / delete / copy / setValue）已覆盖绝大多数场景，
 * 这里保留的是更底层的逃生口：需要自定义路径变换时直接构造动作对象。</p>
 *
 * <p>动作的返回值决定节点去向：返回新路径数组表示移动，返回 {@code null}
 * 表示保持原位，抛异常则中断整次迁移。</p>
 */
public final class MigrationUtil {

    private MigrationUtil() {
    }

    /**
     * 原地改写路径的最后一段，父级不变。
     *
     * @param newKey 新键名
     * @return 改写末段的动作；根节点（空路径）原样返回
     */
    @NotNull
    public static TransformAction rename(@NotNull String newKey) {
        Objects.requireNonNull(newKey, "新键名不能为空");
        return (path, value) -> {
            final Object[] segments = path.array();
            if (segments.length == 0) {
                return null;
            }
            final Object[] moved = segments.clone();
            moved[moved.length - 1] = newKey;
            return moved;
        };
    }

    /**
     * 把节点整体搬到另一个路径，原键名丢弃。
     *
     * @param newPath 完整的目标路径
     * @return 替换整条路径的动作
     */
    @NotNull
    public static TransformAction renameAndMove(@NotNull String... newPath) {
        Objects.requireNonNull(newPath, "目标路径不能为空");
        final Object[] target = newPath.clone();
        return (path, value) -> target.clone();
    }

    /**
     * 把节点挂到新父级下，原键名保留为最后一段。
     *
     * @param newParent 新的父级路径
     * @return 重定父级的动作；对根节点调用会抛异常（根节点没有父级可挂）
     * @throws ConfigurateException 路径为空（即试图移动根节点）
     */
    @NotNull
    public static TransformAction moveTo(@NotNull String... newParent) {
        Objects.requireNonNull(newParent, "父级路径不能为空");
        final Object[] prefix = newParent.clone();
        return (path, value) -> {
            final Object[] segments = path.array();
            if (segments.length == 0) {
                throw new ConfigurateException(value, "根节点不可移动");
            }
            final Object[] moved = new Object[prefix.length + 1];
            System.arraycopy(prefix, 0, moved, 0, prefix.length);
            moved[prefix.length] = segments[segments.length - 1];
            return moved;
        };
    }

    /**
     * 把命中的节点覆盖为固定值，路径保持不变。
     *
     * @param value 要写入的值；{@code null} 表示清空该节点
     * @return 写入并留在原位的动作
     */
    @NotNull
    public static TransformAction setValue(@Nullable Object value) {
        return (path, node) -> {
            node.set(value);
            return null;
        };
    }
}
