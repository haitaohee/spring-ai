package com.hht.context.io;

import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.*;
import java.util.function.Function;

//支持3种查询方式：
//
//按配置的key查询，例如：getProperty("app.title");
//以${abc.xyz}形式的查询，例如，getProperty("${app.title}")，常用于@Value("${app.title}")注入；
//带默认值的，以${abc.xyz:defaultValue}形式的查询，例如，getProperty("${app.title:Summer}")
//默认值可以有嵌套${app.title:${APP_NAME:Summer}}
//spring支持更多，比如：jdbc.url=jdbc:mysql//${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME}，还有#{appBean.version() + 1}，专门的spring-expression库来支持
public class PropertyResolver {

    Logger logger = LoggerFactory.getLogger(getClass());

    Map<String, String> properties = new HashMap<>();

    // 存储Class -> Function:
    Map<Class<?>, Function<String, Object>> converters = new HashMap<>();

    public PropertyResolver(Properties props) {
        this.properties.putAll(System.getenv());
        Set<String> names = props.stringPropertyNames();
        for (String name : names) {
            this.properties.put(name, props.getProperty(name));
        }
        if (logger.isDebugEnabled()) {
            List<String> keys = new ArrayList<>(this.properties.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                logger.debug("PropertyResolver: {} = {}", key, this.properties.get(key));
            }
        }
        // register converters:
        converters.put(String.class, s -> s);
        converters.put(boolean.class, s -> Boolean.parseBoolean(s));
        converters.put(Boolean.class, s -> Boolean.valueOf(s));

        converters.put(byte.class, s -> Byte.parseByte(s));
        converters.put(Byte.class, s -> Byte.valueOf(s));

        converters.put(short.class, s -> Short.parseShort(s));
        converters.put(Short.class, s -> Short.valueOf(s));

        converters.put(int.class, s -> Integer.parseInt(s));
        converters.put(Integer.class, s -> Integer.valueOf(s));

        converters.put(long.class, s -> Long.parseLong(s));
        converters.put(Long.class, s -> Long.valueOf(s));

        converters.put(float.class, s -> Float.parseFloat(s));
        converters.put(Float.class, s -> Float.valueOf(s));

        converters.put(double.class, s -> Double.parseDouble(s));
        converters.put(Double.class, s -> Double.valueOf(s));

        converters.put(LocalDate.class, s -> LocalDate.parse(s));
        converters.put(LocalTime.class, s -> LocalTime.parse(s));
        converters.put(LocalDateTime.class, s -> LocalDateTime.parse(s));
        converters.put(ZonedDateTime.class, s -> ZonedDateTime.parse(s));
        converters.put(Duration.class, s -> Duration.parse(s));
        converters.put(ZoneId.class, s -> ZoneId.of(s));
    }

    public boolean containsProperty(String key) {
        return this.properties.containsKey(key);
    }

    @Nullable
    public <T> T getProperty(String key, Class<T> targetType) {
        String value = getProperty(key);
        if (value == null) {
            return null;
        }
        // 转换为指定类型:
        return convert(targetType, value);
    }

    // 转换到指定Class类型:
    @SuppressWarnings("unchecked")
    <T> T convert(Class<?> clazz, String value) {
        Function<String, Object> fn = this.converters.get(clazz);
        if (fn == null) {
            throw new IllegalArgumentException("Unsupported value type: " + clazz.getName());
        }
        return (T) fn.apply(value);
    }

    public <T> T getProperty(String key, Class<T> targetType, T defaultValue) {
        String value = getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return convert(targetType, value);
    }

    public <T> T getRequiredProperty(String key, Class<T> targetType) {
        T value = getProperty(key, targetType);
        return Objects.requireNonNull(value, "Property '" + key + "' not found.");
    }

    @Nullable
    public String getProperty(String key) {
        PropertyExpr keyExpr = parsePropertyExpr(key);
        if (keyExpr != null) {
            //带默认值，&{xx:xx}查询
            if (keyExpr.defaultValue() != null) {
                return getProperty(keyExpr.key(), keyExpr.defaultValue());
            } else {
                //不带默认值，&{xxxx}查询
                return getRequiredProperty(keyExpr.key());
            }
        }
        //普通key查询
        String value = this.properties.get(key);
        if (value != null) {
            return parseValue(value);
        }
        return value;
    }

    public String getProperty(String key, String defaultValue) {
        String value = getProperty(key);
        return value == null ? parseValue(defaultValue) : value;
    }

    public String getRequiredProperty(String key) {
        String value = getProperty(key);
        return Objects.requireNonNull(value, "Property '" + key + "' not found.");
    }

    //${app.title:${APP_NAME:Summer}} value可能是这种嵌套的，需要递归解析
    //不管几层嵌套，递归结束都解析完了
    String parseValue(String value) {
        PropertyExpr expr = parsePropertyExpr(value);
        if (expr == null) {
            return value;
        }
        if (expr.defaultValue() != null) {
            return getProperty(expr.key(), expr.defaultValue());
        } else {
            return getRequiredProperty(expr.key());
        }
    }

    String notEmpty(String key) {
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Invalid key: " + key);
        }
        return key;
    }

    PropertyExpr parsePropertyExpr(String key) {
        if (key.startsWith("${") && key.endsWith("}")) {
            int n = key.indexOf(':');
            if (n == -1) {
                String k = key.substring(2, key.length() - 1);
                return new PropertyExpr(k, null);
            } else {
                String k = key.substring(2, n);
                return new PropertyExpr(k, key.substring(n + 1, key.length() - 1));
            }
        }
        return null;
    }

}

record PropertyExpr(String key, String defaultValue) {
}
