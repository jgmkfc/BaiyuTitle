package net.baiyu.title.config.typeserializer;

import io.leangen.geantyref.TypeToken;
import org.spongepowered.configurate.serialize.ScalarSerializer;
import org.spongepowered.configurate.serialize.Scalars;
import org.spongepowered.configurate.serialize.SerializationException;

import java.lang.reflect.Type;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * 枚举序列化器：写出时统一小写，读取时大小写不限。
 *
 * <p>Configurate 内置的 {@link Scalars#ENUM} 反序列化本就忽略大小写，但写出时
 * 用 {@link Enum#name()}，配置文件里会留下全大写常量名。服主手改配置时大小写
 * 混用很常见，统一写成小写能少一轮试错。</p>
 *
 * <p>转换固定走 {@link Locale#ROOT}：土耳其语环境下 {@code "I".toLowerCase()}
 * 会得到无点的 {@code ı}，配置里的枚举值会因此变得不可读。</p>
 */
public final class LowercaseEnumSerializer extends ScalarSerializer<Enum<?>> {

    /** 无状态单例（序列化器不持有可变状态）。 */
    public static final LowercaseEnumSerializer INSTANCE = new LowercaseEnumSerializer();

    private LowercaseEnumSerializer() {
        super(new TypeToken<Enum<?>>() {
        });
    }

    /**
     * 读取枚举值，交由 Configurate 内置逻辑处理（自动忽略大小写）。
     *
     * @param type 目标枚举类型
     * @param obj  配置中的原始值
     * @return 对应枚举常量
     * @throws SerializationException 值不匹配任何常量
     */
    @Override
    public Enum<?> deserialize(Type type, Object obj) throws SerializationException {
        return Scalars.ENUM.deserialize(type, obj);
    }

    /**
     * 写出枚举值，统一转小写。
     *
     * @param item          枚举常量
     * @param typeSupported 类型判定（未使用）
     * @return 小写形式的常量名
     */
    @Override
    protected Object serialize(Enum<?> item, Predicate<Class<?>> typeSupported) {
        return item.name().toLowerCase(Locale.ROOT);
    }
}
