package net.baiyu.title.config.typeserializer;

import io.leangen.geantyref.TypeToken;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code Map<String, Object>} 的 Configurate 序列化器。
 *
 * <p>用于「键固定、值类型不定」的配置段：同一层里允许同时出现数字、布尔与
 * 字符串，也允许再套一层映射。典型场景是数据库连接的高级属性，
 * 不同驱动认不同的键，无法用固定字段表达。</p>
 *
 * <p>反序列化用 {@link LinkedHashMap} 而非 {@link java.util.HashMap}：
 * 配置项在文件里的书写顺序对服主有意义，回写时保持原序可减少无谓的 diff。</p>
 */
public final class StringObjectMapSerializer implements TypeSerializer<Map<String, Object>> {

    /** 无状态单例（序列化器本身不持有可变状态，可跨配置实例复用）。 */
    public static final StringObjectMapSerializer INSTANCE = new StringObjectMapSerializer();

    /** 目标类型标识：{@code Map<String, Object>}。 */
    public static final TypeToken<Map<String, Object>> TYPE_TOKEN =
            new TypeToken<Map<String, Object>>() {
            };

    /**
     * 把配置节点读取为映射。非映射节点返回空映射，不抛异常——配置写错时
     * 由上层做默认值回退，比在解析阶段中断更友好。
     *
     * @param type 目标类型（未使用，接口要求）
     * @param node 配置节点
     * @return 键值映射；嵌套映射递归展开
     */
    @Override
    public Map<String, Object> deserialize(Type type, ConfigurationNode node) {
        if (!node.isMap()) {
            return new LinkedHashMap<>();
        }
        final Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> child : node.childrenMap().entrySet()) {
            final String key = String.valueOf(child.getKey());
            final ConfigurationNode childNode = child.getValue();
            values.put(key, childNode.isMap()
                    ? deserialize(type, childNode)
                    : childNode.raw());
        }
        return values;
    }

    /**
     * 把映射写回配置节点。嵌套映射递归处理；值为 null 时显式写入空节点，
     * 确保被移除的键不会残留在文件里。
     *
     * @param type 目标类型（未使用，接口要求）
     * @param obj  待写入映射
     * @param node 目标节点
     * @throws SerializationException 底层写入失败
     */
    @Override
    @SuppressWarnings("unchecked")
    public void serialize(Type type, Map<String, Object> obj, ConfigurationNode node)
            throws SerializationException {
        if (obj == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : obj.entrySet()) {
            final ConfigurationNode childNode = node.node(entry.getKey());
            final Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                serialize(type, (Map<String, Object>) nested, childNode);
            } else {
                childNode.set(value);
            }
        }
    }
}
