package net.baiyu.title.command.argument;

import dev.jorel.commandapi.arguments.Argument;
import dev.jorel.commandapi.arguments.ArgumentSuggestions;
import dev.jorel.commandapi.arguments.IntegerArgument;
import net.baiyu.title.domain.title.Title;
import net.baiyu.title.service.TitleCatalogService;

import java.util.Objects;

/**
 * 称号 ID 参数工厂：整数参数 + 未软删称号 ID 补全。
 *
 * <p>补全数据取自目录缓存快照（{@link TitleCatalogService#snapshot()}），
 * 零数据库 IO。</p>
 */
public final class TitleIdArgument {

    /** 称号目录服务（只用其缓存快照）。 */
    private final TitleCatalogService catalog;

    /**
     * 构造称号 ID 参数工厂。
     *
     * @param catalog 称号目录服务
     */
    public TitleIdArgument(TitleCatalogService catalog) {
        this.catalog = Objects.requireNonNull(catalog, "称号目录服务不能为空");
    }

    /**
     * 创建一个带补全的称号 ID 参数（下限 1）。
     *
     * @param nodeName 参数节点名
     * @return CommandAPI 整数参数
     */
    public Argument<Integer> create(String nodeName) {
        return new IntegerArgument(nodeName, 1).replaceSuggestions(
                ArgumentSuggestions.strings(info -> catalog.snapshot().stream()
                        .filter(title -> !title.isDeleted())
                        .map(Title::id)
                        .map(String::valueOf)
                        .toArray(String[]::new)));
    }
}
