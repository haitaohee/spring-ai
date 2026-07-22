package com.hht.context.resource;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

//扫描指定目录，获取某一类型的资源
public class ResourceResolver {

    Logger logger = LoggerFactory.getLogger(getClass());

    //看onenote，有详解path
    String basePackage;

    public ResourceResolver(String basePackage) {
        this.basePackage = basePackage;
    }

    public <R> List<R> scan(Function<Resource, R> mapper) {
        String basePackagePath = this.basePackage.replace(".", "/");
        String path = basePackagePath;
        try {
            List<R> collector = new ArrayList<>();
            scan0(basePackagePath, path, collector, mapper);
            return collector;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 递归扫描指定包路径下的所有资源。
     *
     * @param basePackagePath 基础包路径（如 com/hht/context），用于计算相对路径
     * @param path            当前要扫描的路径（首次调用时等于 basePackagePath，递归时变为子目录路径），其实也不会有递归调用
     * @param collector       收集扫描结果的列表
     * @param mapper          将 Resource 映射为目标类型 R 的函数
     */
    <R> void scan0(String basePackagePath, String path, List<R> collector, Function<Resource, R> mapper) throws IOException, URISyntaxException {
        logger.atDebug().log("scan path: {}", path);
        //返回的是 ClassLoader 搜索路径中，所有匹配 path 这个资源名的 URL 集合。会扫描classpath下所有名为path的资源
        //假设你的 classpath 包含：也就是说会扫描自己的代码以及依赖中包名或者类名是path的所有资源，path不是系统路径是类似类的全限定名那种
        //target/classes/（编译输出目录）
        //spring-core.jar
        //my-lib.jar
        //调用 getResources("com/hht/context") 后，会返回所有包含 com/hht/context 这个包路径的 URL：
        Enumeration<URL> en = getContextClassLoader().getResources(path);
        while (en.hasMoreElements()) {
            URL url = en.nextElement();

            // --- 为什么要把 URL 转成 URI？ ---
            // URL.toURI() 会对特殊字符（如空格、中文）进行正确的编码处理，
            // 并且 URI 提供了更丰富的路径解析能力（如 getSchemeSpecificPart()、resolve() 等），
            // 更适合做字符串截取和路径拼接操作。而 URL 直接 toString() 可能会保留
            // 不规范的编码形式，导致后续路径处理出错。

            //简言之就是后面会对资源路径做字符串处理，所以需要先转uri再decode为string，避免编码错误
            URI uri = url.toURI();

            // --- uriToString 里的 URLDecoder.decode 是什么意思？ ---
            // URL 中的特殊字符（如中文、空格）会被编码为 %XX 的形式（URL Encoding）。
            // 例如 "我的项目" 会被编码为 "%E6%88%91%E7%9A%84%E9%A1%B9%E7%9B%AE"。
            // URLDecoder.decode 就是将这些 %XX 还原为原始字符，得到人类可读的真实路径字符串，
            // 方便后续做字符串截取、比较等操作。

            //几个移除斜杠的方法，debug其实都没执行，不同classloader可能有不同的效果
            //这些方法本质上是防御性编程，保证在不同操作系统、不同 ClassLoader 实现、不同运行环境下都能正常工作。
            String uriStr = removeTrailingSlash(uriToString(uri));

            // 截取基础路径部分：从完整 URI 字符串中剥离掉 basePackagePath，
            // 得到包路径的"根目录"。例如：
            //   uriStr         = "file:/D:/project/target/classes/com/hht/context"
            //   basePackagePath = "com/hht/context"
            //   uriBaseStr      = "file:/D:/project/target/classes/"
            String uriBaseStr = uriStr.substring(0, uriStr.length() - basePackagePath.length());

            // 如果 uriBaseStr 以 "file:" 开头，去掉这个前缀，得到纯文件系统路径。
            // 例如 "file:/D:/project/target/classes/" -> "/D:/project/target/classes/"
            if (uriBaseStr.startsWith("file:")) {
                uriBaseStr = uriBaseStr.substring(5);
            }

            if (uriStr.startsWith("jar:")) {
                // JAR 包中的资源，使用 jar 协议的特殊处理方式
                scanFile(true, uriBaseStr, jarUriToPath(basePackagePath, uri), collector, mapper);
            } else {
                // 文件系统中的资源，直接用 Paths.get(uri) 获取本地文件路径
                scanFile(false, uriBaseStr, Paths.get(uri), collector, mapper);
            }
        }
    }

    Path jarUriToPath(String basePackagePath, URI jarUri) throws IOException {
        return FileSystems.newFileSystem(jarUri, Map.of()).getPath(basePackagePath);
    }

    <R> void scanFile(boolean isJar, String base, Path root, List<R> collector, Function<Resource, R> mapper) throws IOException {
        String baseDir = removeTrailingSlash(base);
        //Files.walk()递归遍历目录树，返回一个 Stream<Path> 流，包含指定起始路径下的所有文件和子目录。
        //遍历当前目录及其子目录，返回所有文件，文件夹等，filter过滤后只剩下文件
        Files.walk(root).filter(Files::isRegularFile).forEach(file -> {
            Resource res = null;
            if (isJar) {
                res = new Resource(baseDir, removeLeadingSlash(file.toString()));
            } else {
                String path = file.toString();
                //只剩下文件名，带后缀的，所有文件类型。只管扫描，返回所有资源mapper负责处理过滤转换
                String name = removeLeadingSlash(path.substring(baseDir.length()));
                //path:D:\java_project\spring-ai\target\test-classes\com\hht\context\resource\ResourceResolverTest.class
                //name:com\hht\context\resource\ResourceResolverTest.class
                res = new Resource("file:" + path, name);
            }
            logger.atDebug().log("found resource: {}", res);
            R r = mapper.apply(res);
            if (r != null) {
                collector.add(r);
            }
        });
    }


    //看onenote-spring-手写spring（一）——加载资源
    ClassLoader getContextClassLoader() {
        ClassLoader cl = null;
        cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = getClass().getClassLoader();
        }
        return cl;
    }

    /**
     * 将 URI 转为字符串，并对 URL 编码进行解码。
     * URL 中特殊字符（中文、空格等）会被编码为 %XX，decode 将其还原为可读字符。
     */
    String uriToString(URI uri) {
        return URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8);
    }

    String removeLeadingSlash(String s) {
        if (s.startsWith("/") || s.startsWith("\\")) {
            s = s.substring(1);
        }
        return s;
    }

    /**
     * 移除字符串末尾的斜杠（/ 或 \）。
     *
     * --- 为什么要两次 remove 操作？ ---
     * 1. 第一次：在 scan0 中对 uriStr 调用 removeTrailingSlash，目的是统一去掉末尾斜杠，
     *    避免后续 substring 截取时因为末尾斜杠的存在/不存在导致计算出的 uriBaseStr 不一致。
     *    例如 "file:/path/classes/" 和 "file:/path/classes" 截掉 "classes" 后结果不同。
     *
     * 2. 第二次：在 scanFile 方法内部，对每个子目录/文件路径也会调用此方法，
     *    保证拼接后的路径格式统一，不出现 "//" 双斜杠的问题。
     *    （scanFile 方法会在递归扫描子目录时使用）
     */
    String removeTrailingSlash(String s) {
        if (s.endsWith("/") || s.endsWith("\\")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}

