package com.kairui.tools.sqlexport.parameter;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.ResultHandler;
import org.springframework.core.ResolvableType;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 生成基础示例参数，实体仅填充简单字段。 */
public class SampleValueFactory {
    private static final int MAX_DEPTH = 4;
    private static final Instant SAMPLE_INSTANT = Instant.parse("2026-01-02T03:04:05Z");

    /**
     * 按 Mapper 方法声明构造示例实参，再交给 MyBatis 转换为实际可用的参数对象。
     * <p>
     * BaseMapper 的实体泛型用于推断 Serializable 主键参数的类型；默认 Wrapper 不携带实体，
     * 避免示例字段及带默认值的 getter 被框架转换成查询条件。
     * 分页参数使用第一页、每页十条的 Page。RowBounds 和 ResultHandler 是 MyBatis
     * 的特殊参数，不参与 SQL 参数命名，因此这里只提供空值。
     * 最后的 ParamNameResolver 保持 {@code @Param}、通用参数别名以及单参数集合包装规则
     * 与 MyBatis 一致，避免手工组装参数名后与 XML 或注入 SQL 不匹配。
     */
    public Object parameters(Configuration configuration, Class<?> mapperClass, Method method) {
        Class<?> entityClass = ResolvableType.forClass(mapperClass).as(BaseMapper.class).getGeneric(0).resolve();
        Object[] arguments = new Object[method.getParameterCount()];
        for (int i = 0; i < arguments.length; i++) {
            Class<?> raw = method.getParameterTypes()[i];
            ResolvableType type = ResolvableType.forMethodParameter(method, i, mapperClass);
            if (RowBounds.class.isAssignableFrom(raw) || ResultHandler.class.isAssignableFrom(raw)) {
                arguments[i] = null;
            } else if (Wrapper.class.isAssignableFrom(raw)) {
                Wrapper<?> wrapper = UpdateWrapper.class.isAssignableFrom(raw)
                        ? new UpdateWrapper<>() : new QueryWrapper<>();
                if (!raw.isInstance(wrapper)) {
                    throw unsupported(type, "不支持的 Wrapper 子类型");
                }
                arguments[i] = wrapper;
            } else if (IPage.class.isAssignableFrom(raw)) {
                Page<Object> page = new Page<>(1, 10);
                if (!raw.isInstance(page)) {
                    throw unsupported(type, "不支持的分页子类型");
                }
                arguments[i] = page;
            } else if (raw == Serializable.class && entityClass != null) {
                arguments[i] = create(idType(entityClass), 0, new LinkedHashSet<>());
            } else if (Collection.class.isAssignableFrom(raw) && entityClass != null
                    && type.as(Collection.class).getGeneric(0).resolve() == Serializable.class) {
                try {
                    Collection<Object> ids = collection(raw, type);
                    ids.add(create(idType(entityClass), 0, new LinkedHashSet<>()));
                    arguments[i] = ids;
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalArgumentException("无法构造主键集合样例 " + type, ex);
                }
            } else {
                arguments[i] = create(type, 0, new LinkedHashSet<>());
            }
        }
        return new ParamNameResolver(configuration, method).getNamedParams(arguments);
    }

    /** 按给定反射类型生成一个确定的示例值，不代表真实业务数据。 */
    public Object value(Type type) {
        return create(ResolvableType.forType(type), 0, new LinkedHashSet<>());
    }

    /**
     * 递归构造类型样例：简单类型取固定值，数组、集合和 Map 各放入一个样例元素。
     * DTO 构造完成后只填充可写的简单字段，不主动实例化其关联对象。
     * depth 和当前递归路径中的 visiting 用于截断过深结构及循环引用，截断位置返回 null；
     * 尚无构造策略的类型则抛出异常，由上层记录提取失败。
     */
    private Object create(ResolvableType type, int depth, Set<Class<?>> visiting) {
        Class<?> raw = type.resolve();
        if (raw == null) {
            throw unsupported(type, "类型未解析");
        }
        if (raw == Object.class || raw == Serializable.class) {
            return 1L;
        }
        if (raw == String.class || raw == CharSequence.class) return "sample";
        if (raw == boolean.class || raw == Boolean.class) return true;
        if (raw == char.class || raw == Character.class) return 'A';
        if (raw == byte.class || raw == Byte.class) return (byte) 1;
        if (raw == short.class || raw == Short.class) return (short) 1;
        if (raw == int.class || raw == Integer.class) return 1;
        if (raw == long.class || raw == Long.class) return 1L;
        if (raw == float.class || raw == Float.class) return 1.0F;
        if (raw == double.class || raw == Double.class) return 1.0D;
        if (raw == BigDecimal.class) return BigDecimal.ONE;
        if (raw == BigInteger.class) return BigInteger.ONE;
        if (raw == UUID.class) return UUID.fromString("00000000-0000-0000-0000-000000000001");
        if (raw == Date.class) return Date.from(SAMPLE_INSTANT);
        if (raw == java.sql.Date.class) return java.sql.Date.valueOf("2026-01-02");
        if (raw == Time.class) return Time.valueOf("03:04:05");
        if (raw == Timestamp.class) return Timestamp.from(SAMPLE_INSTANT);
        if (raw == Instant.class) return SAMPLE_INSTANT;
        if (raw == LocalDate.class) return LocalDate.of(2026, 1, 2);
        if (raw == LocalTime.class) return LocalTime.of(3, 4, 5);
        if (raw == LocalDateTime.class) return LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        if (raw == OffsetDateTime.class) return SAMPLE_INSTANT.atOffset(ZoneOffset.UTC);
        if (raw == ZonedDateTime.class) return SAMPLE_INSTANT.atZone(ZoneOffset.UTC);
        if (raw.isEnum()) {
            Object[] constants = raw.getEnumConstants();
            if (constants.length == 0) throw unsupported(type, "空枚举");
            return constants[0];
        }
        // 循环引用与过深的关联只截断该字段，不影响其他简单字段的样例。
        if (depth >= MAX_DEPTH || visiting.contains(raw)) return null;
        visiting.add(raw);
        try {
            if (raw.isArray()) {
                Object array = Array.newInstance(raw.getComponentType(), 1);
                Array.set(array, 0, create(type.getComponentType(), depth + 1, visiting));
                return array;
            }
            if (Collection.class.isAssignableFrom(raw)) {
                Collection<Object> values = collection(raw, type);
                ResolvableType element = type.as(Collection.class).getGeneric(0);
                values.add(create(element.resolve() == null ? ResolvableType.forClass(Long.class) : element,
                        depth + 1, visiting));
                return values;
            }
            if (Map.class.isAssignableFrom(raw)) {
                Map<Object, Object> values = map(raw, type);
                ResolvableType key = type.as(Map.class).getGeneric(0);
                ResolvableType item = type.as(Map.class).getGeneric(1);
                values.put(create(key.resolve() == null ? ResolvableType.forClass(String.class) : key,
                                depth + 1, visiting),
                        create(item.resolve() == null ? ResolvableType.forClass(Long.class) : item,
                                depth + 1, visiting));
                return values;
            }
            if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers()) || raw.getName().startsWith("java.")) {
                throw unsupported(type, "没有明确的样例构造策略");
            }
            Object entity = constructEntity(raw, type, depth, visiting);
            for (Class<?> owner = raw; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
                for (Field field : owner.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers()) || field.isSynthetic()) continue;
                    ResolvableType fieldType = ResolvableType.forField(field, raw);
                    if (!simple(field.getType())) continue;
                    field.setAccessible(true);
                    field.set(entity, create(fieldType, depth + 1, visiting));
                }
            }
            return entity;
        } catch (ReflectiveOperationException | SecurityException ex) {
            throw new IllegalArgumentException("无法构造样例类型 " + type, ex);
        } finally {
            visiting.remove(raw);
        }
    }

    private boolean simple(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type == String.class || type == Boolean.class
                || type == Character.class || Number.class.isAssignableFrom(type)
                || Date.class.isAssignableFrom(type) || type.getName().startsWith("java.time.") || type == UUID.class;
    }

    /** 沿实体继承链取首个标注 TableId 或名为 id 的字段类型，未找到时使用 Long。 */
    private ResolvableType idType(Class<?> entityClass) {
        for (Class<?> owner = entityClass; owner != null; owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                if (field.isAnnotationPresent(TableId.class) || field.getName().equals("id")) {
                    return ResolvableType.forField(field, entityClass);
                }
            }
        }
        return ResolvableType.forClass(Long.class);
    }

    @SuppressWarnings("unchecked")
    private Collection<Object> collection(Class<?> raw, ResolvableType type) throws ReflectiveOperationException {
        if (raw.isAssignableFrom(ArrayList.class)) return new ArrayList<>();
        if (raw.isAssignableFrom(LinkedHashSet.class)) return new LinkedHashSet<>();
        if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) throw unsupported(type, "不支持的集合类型");
        return (Collection<Object>) instantiate(raw, type);
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> map(Class<?> raw, ResolvableType type) throws ReflectiveOperationException {
        if (raw.isAssignableFrom(LinkedHashMap.class)) return new LinkedHashMap<>();
        if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) throw unsupported(type, "不支持的 Map 类型");
        return (Map<Object, Object>) instantiate(raw, type);
    }

    /**
     * 优先使用无参构造函数；没有无参构造函数时，尝试静态 builder() 和 build() 入口。
     * builder 只接收有同名、同字段类型公开方法的简单字段样例，缺少对应方法的字段跳过。
     * 这样可为带 final 字段的 DTO 提供样例，而无需反射改写其不可变字段。
     */
    private Object constructEntity(Class<?> raw, ResolvableType type, int depth,
                                   Set<Class<?>> visiting) throws ReflectiveOperationException {
        try {
            raw.getDeclaredConstructor();
            return instantiate(raw, type);
        } catch (NoSuchMethodException ignored) {
            // Lombok Builder 等不可变 DTO 通过自身构造入口赋值，不修改 final 字段。
            Method builderMethod;
            try {
                builderMethod = raw.getDeclaredMethod("builder");
            } catch (NoSuchMethodException e) {
                throw unsupported(type, "缺少无参构造函数或 builder 构造入口");
            }
            if (!Modifier.isStatic(builderMethod.getModifiers())) throw unsupported(type, "builder 不是静态方法");
            builderMethod.setAccessible(true);
            Object builder = builderMethod.invoke(null);
            for (Class<?> owner = raw; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
                for (Field field : owner.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic() || (!simple(field.getType()))) continue;
                    Method setter;
                    try {
                        setter = builder.getClass().getMethod(field.getName(), field.getType());
                    } catch (NoSuchMethodException e) {
                        continue;
                    }
                    setter.setAccessible(true);
                    setter.invoke(builder, create(ResolvableType.forField(field, raw), depth + 1, visiting));
                }
            }
            Method build = builder.getClass().getMethod("build");
            build.setAccessible(true);
            return build.invoke(builder);
        }
    }

    private Object instantiate(Class<?> raw, ResolvableType type) throws ReflectiveOperationException {
        Constructor<?> constructor;
        try {
            constructor = raw.getDeclaredConstructor();
        } catch (NoSuchMethodException ex) {
            throw unsupported(type, "缺少无参构造函数");
        }
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private IllegalArgumentException unsupported(ResolvableType type, String reason) {
        return new IllegalArgumentException(reason + ": " + type);
    }
}
