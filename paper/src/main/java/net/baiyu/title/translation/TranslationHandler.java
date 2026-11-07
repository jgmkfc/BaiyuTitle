package net.baiyu.title.translation;

import net.baiyu.title.AbstractBaiYuTitle;
import net.baiyu.title.Reloadable;
import net.baiyu.title.config.ConfigHandler;

import java.util.List;
import java.util.Objects;

/**
 * YAML 语言文件生命周期：启动与白名单重载都以新快照替换旧快照。
 *
 * <p>语言存储由插件自身管理，避免第三方翻译 provider 的一次性初始化限制；
 * {@link Lang} 仍是表现层唯一取词入口。</p>
 */
public final class TranslationHandler implements Reloadable {

    /** 当前不可变语言快照，volatile 替换保证聊天等异步读路径无空窗。 */
    private volatile YamlTranslationStore.Snapshot snapshot =
            YamlTranslationStore.Snapshot.empty();

    private final ConfigHandler configHandler;

    public TranslationHandler(ConfigHandler configHandler) {
        this.configHandler = Objects.requireNonNull(configHandler, "配置处理器不能为空");
    }

    @Override
    public void onEnable(AbstractBaiYuTitle plugin) {
        reload(plugin);
    }

    /**
     * 从数据目录重新读取当前语言与 en_US 回退语言，并原子替换快照。
     *
     * @param plugin 插件实例
     */
    public void reload(AbstractBaiYuTitle plugin) {
        Objects.requireNonNull(plugin, "插件实例不能为空");
        snapshot = YamlTranslationStore.load(
                plugin.getDataPath().resolve("lang"),
                configHandler.getConfig().language,
                plugin.getComponentLogger());
    }

    /** 包内取单行原文。 */
    String raw(String key) {
        return snapshot.raw(key);
    }

    /** 包内取多行原文。 */
    List<String> rawList(String key) {
        return snapshot.list(key);
    }
}
