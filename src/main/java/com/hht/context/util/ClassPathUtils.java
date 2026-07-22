package com.hht.context.util;

import com.hht.context.io.InputStreamCallback;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 从 classpath 读取资源的工具类。
 *
 * 注意：ClassLoader.getResourceAsStream() 的路径不能以 / 开头，否则返回 null。
 * 这里先去掉开头 / 做兼容处理，确保始终从 classpath 根目录查找。
 * 而 Class.getResourceAsStream() 才是带 / 表示根目录、不带 / 表示相对于当前类所在包。
 * https://www.cnblogs.com/wzbury/p/13570166.html
 */
public class ClassPathUtils {

    /**
     * 从 classpath 读取资源，通过回调函数处理 InputStream。
     * try-with-resources 确保流一定被关闭。
     *
     * @param path                资源路径（如 "application.yml"、"db.properties"），可带或不带开头 /
     * @param inputStreamCallback 处理流的回调
     * @param <T>                 返回值类型
     * @return 回调函数返回的结果
     * @throws FileNotFoundException 资源不存在时抛出
     * @throws UncheckedIOException  读取失败时抛出
     */
    public static <T> T readInputStream(String path, InputStreamCallback<T> inputStreamCallback) {
        // 去掉开头 /：ClassLoader.getResourceAsStream() 的路径不应以 / 开头
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        // ClassLoader.getResourceAsStream() 从 classpath 根目录搜索，
        // 自动覆盖 target/classes、src/main/resources 以及所有依赖 JAR 中的资源
        try (InputStream input = getContextClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new FileNotFoundException("File not found in classpath: " + path);
            }
            return inputStreamCallback.doWithInputStream(input);
        } catch (IOException e) {
            e.printStackTrace();
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 从 classpath 读取文本文件，返回文件内容字符串。
     *
     * @param path 资源路径
     * @return 文件内容（UTF-8 编码）
     */
    public static String readString(String path) {
        return readInputStream(path, (input) -> {
            byte[] data = input.readAllBytes();
            return new String(data, StandardCharsets.UTF_8);
        });
    }

    static ClassLoader getContextClassLoader() {
        ClassLoader cl = null;
        cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ClassPathUtils.class.getClassLoader();
        }
        return cl;
    }
}
