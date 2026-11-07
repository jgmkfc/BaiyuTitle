package net.baiyu.title.service;

import net.baiyu.title.domain.group.PlayerGroup;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

import java.util.Collection;
import java.util.Objects;

/**
 * 组权限节点注册器（paper 侧）：向 Bukkit 声明 {@code title.group.<name>} 节点。
 *
 * <p>注册只影响默认值与内省展示（默认 {@link PermissionDefault#FALSE}——
 * 组资格必须显式授权），权限插件对未注册节点同样可授可查；
 * 因此注册失败不致命，撞已注册节点静默跳过。</p>
 *
 * <p>线程约定：全部方法只在主线程调用（启动装配 / 组命令回调）。</p>
 */
public final class GroupPermissionRegistrar {

    /** Bukkit 插件管理器（权限注册表归属）。 */
    private final PluginManager pluginManager;

    /**
     * 构造组权限节点注册器。
     *
     * @param pluginManager Bukkit 插件管理器
     */
    public GroupPermissionRegistrar(PluginManager pluginManager) {
        this.pluginManager = Objects.requireNonNull(pluginManager, "插件管理器不能为空");
    }

    /**
     * 批量注册（启动装配：把库中全部未软删组的节点一次声明）。
     *
     * @param groups 组列表
     */
    public void registerAll(Collection<PlayerGroup> groups) {
        for (PlayerGroup group : groups) {
            register(group.name());
        }
    }

    /**
     * 注册单个组的权限节点（已注册则跳过）。
     *
     * @param groupName 组名
     */
    public void register(String groupName) {
        String node = PlayerGroup.permissionNodeOf(groupName);
        if (pluginManager.getPermission(node) == null) {
            pluginManager.addPermission(new Permission(node, PermissionDefault.FALSE));
        }
    }

    /**
     * 注销单个组的权限节点（删除组后调用；未注册时 no-op）。
     *
     * @param groupName 组名
     */
    public void unregister(String groupName) {
        pluginManager.removePermission(PlayerGroup.permissionNodeOf(groupName));
    }
}
