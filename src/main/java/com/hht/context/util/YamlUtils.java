package com.hht.context.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * YAML 解析工具类，基于 SnakeYAML。
 *
 * SnakeYAML 默认会自动推断值的类型，比如 "true" → boolean、"123" → int。
 * 这里通过 NoImplicitResolver 禁用了所有隐式类型转换，YAML 中的所有值
 * 都会被当作字符串处理，后续在 PropertyResolver 中统一做类型转换。
 *
 * @see <a href="https://github.com/snakeyaml/snakeyaml">SnakeYAML</a>
 */
@SuppressWarnings("unused")
public class YamlUtils {

    /**
     * 从 classpath 加载 YAML 文件，返回嵌套的 Map 结构。
     * 例如 application.yml：
     * <pre>
     *   app:
     *     title: Summer
     *     port: 8080
     * </pre>
     * 返回：{app={title=Summer, port=8080}}
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadYaml(String path) {
        // LoaderOptions: 控制加载（读取）行为，如最大别名数（防 YAML 炸弹攻击）、是否允许重复 key 等
        var loaderOptions = new LoaderOptions();
        // DumperOptions: 控制输出（序列化）行为，如缩进、行宽等，这里只读不写，但 Yaml 构造器要求传入
        var dumperOptions = new DumperOptions();
        // Representer: 将 Java 对象序列化为 YAML 节点，DumperOptions 控制输出格式
        var representer = new Representer(dumperOptions);
        // 禁用 SnakeYAML 的隐式类型推断，所有值当作字符串
        var resolver = new NoImplicitResolver();
        // Constructor: 将 YAML 节点反序列化为 Java 对象，LoaderOptions 控制加载安全策略
        // 四个参数：Constructor(反序列化)、Representer(序列化)、DumperOptions(输出控制)、LoaderOptions(加载控制)、Resolver(类型推断)
        //不传不行，传了又没设参数——本质上就是凑够构造器参数。如果只是想简单读个 YAML，直接用 new Yaml() 就够了，但那样会触发 SnakeYAML
        // 的隐式类型转换（"true" → boolean 之类的），所以必须传自定义的 Resolver，连带其他参数也得跟着传。
        var yaml = new Yaml(new Constructor(loaderOptions), representer, dumperOptions, loaderOptions, resolver);
        return ClassPathUtils.readInputStream(path, (input) -> {
            return (Map<String, Object>) yaml.load(input);
        });
    }

    /**
     * 加载 YAML 并扁平化为单层 Map（key 用 . 连接）。
     * 例如 {app={title=Summer}} 扁平化为 {"app.title"="Summer"}，
     * 方便直接用 Properties 风格查询。
     */
    public static Map<String, Object> loadYamlAsPlainMap(String path) {
        Map<String, Object> data = loadYaml(path);
        Map<String, Object> plain = new LinkedHashMap<>();
        convertTo(data, "", plain);
        return plain;
    }

    /**
     * 递归将嵌套 Map 扁平化。
     * Map 类型递归展开，List 类型直接保留，其他类型转 String。
     */
    static void convertTo(Map<String, Object> source, String prefix, Map<String, Object> plain) {
        for (String key : source.keySet()) {
            Object value = source.get(key);
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> subMap = (Map<String, Object>) value;
                convertTo(subMap, prefix + key + ".", plain);
            } else if (value instanceof List) {
                plain.put(prefix + key, value);
            } else {
                plain.put(prefix + key, value.toString());
            }
        }
    }
}

/**
 * 禁用 SnakeYAML 所有隐式类型解析。
 * 默认情况下 SnakeYAML 会把 "true" 自动转 boolean、"123" 自动转 int，
 * 清空 yamlImplicitResolvers 后所有值保持为字符串，由 PropertyResolver 统一转换。
 */
class NoImplicitResolver extends Resolver {

    public NoImplicitResolver() {
        super();
        super.yamlImplicitResolvers.clear();
    }
}
