package net.baiyu.title.gui.render;

/**
 * 渲染门面测试夹具：以真实 zh_CN.yml 模板组装完整的
 * {@link TitleItemRenderer}（含三个 lore 组装器；配置提供器在
 * 纯函数重载路径上不被触碰）。
 */
final class RendererFixtures {

    private RendererFixtures() {
    }

    /** 构建被测渲染门面（每次全新实例，测试间无共享状态）。 */
    static TitleItemRenderer renderer() {
        LoreComposer loreComposer = new LoreComposer(ZhLoreTemplateFixture.zhTemplates());
        ShopLoreComposer shopLoreComposer = new ShopLoreComposer(ZhLoreTemplateFixture.zhTemplates());
        return new TitleItemRenderer(loreComposer, shopLoreComposer,
                new AdminLoreComposer(ZhLoreTemplateFixture.zhTemplates(),
                        loreComposer, shopLoreComposer),
                () -> null);
    }
}
