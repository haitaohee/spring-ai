package com.hht.context.resource;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ResourceResolver 单元测试
 */
public class ResourceResolverTest {

    // ==================== getResources 返回格式测试 ====================

    @Test
    void testGetResourcesReturnsDirectoryPath() throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> en = cl.getResources("com/hht/context/resource");
        List<String> urls = new ArrayList<>();
        while (en.hasMoreElements()) {
            urls.add(en.nextElement().toString());
        }
        System.out.println("=== getResources 返回的 URL 格式 ===");
        urls.forEach(System.out::println);
        assertFalse(urls.isEmpty(), "应该至少找到一个资源");
        assertTrue(urls.get(0).startsWith("file:"), "文件系统中的资源应以 file: 开头");
    }

    @Test
    void testGetResourcesForNonExistentPackage() throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> en = cl.getResources("com/nonexistent/package");
        assertFalse(en.hasMoreElements(), "不存在的包应返回空枚举");
    }

    // ==================== Files.walk 返回格式测试 ====================

    @Test
    void testFilesWalkReturnsDirectoriesAndFiles() throws IOException {
        var root = Paths.get("target/classes/com/hht/context/resource");
        if (!Files.exists(root)) {
            System.out.println("目录不存在，跳过测试: " + root.toAbsolutePath());
            return;
        }
        List<String> all = new ArrayList<>();
        List<String> dirs = new ArrayList<>();
        List<String> files = new ArrayList<>();

        try (var stream = Files.walk(root)) {
            stream.forEach(p -> {
                String s = p.toString();
                all.add(s);
                if (Files.isDirectory(p)) dirs.add(s);
                if (Files.isRegularFile(p)) files.add(s);
            });
        }

        System.out.println("=== Files.walk 返回的全部内容 ===");
        all.forEach(System.out::println);
        System.out.println("\n=== 目录 ===");
        dirs.forEach(System.out::println);
        System.out.println("\n=== 文件 ===");
        files.forEach(System.out::println);

        assertTrue(dirs.contains(root.toString()), "walk 应包含起始目录本身");
        dirs.forEach(d -> assertFalse(d.endsWith("\\") || d.endsWith("/"),
                "目录路径末尾不应有斜杠: " + d));
    }

    // ==================== 完整 scan 流程测试 ====================

    @Test
    void testScanCurrentPackage() {
        ResourceResolver resolver = new ResourceResolver("com.hht.context.resource");
        List<Resource> resources = resolver.scan(r -> r);

        System.out.println("=== 扫描到的资源 ===");
        resources.forEach(r -> System.out.println("path=" + r.path() + ", name=" + r.name()));

        assertFalse(resources.isEmpty(), "应扫描到至少一个资源");
        boolean foundResource = resources.stream()
                .anyMatch(r -> r.name().contains("Resource") && r.name().endsWith(".class"));
        boolean foundResolver = resources.stream()
                .anyMatch(r -> r.name().contains("ResourceResolver") && r.name().endsWith(".class"));
        assertTrue(foundResource, "应包含 Resource.class");
        assertTrue(foundResolver, "应包含 ResourceResolver.class");
    }

    @Test
    void testScanWithMapper() {
        ResourceResolver resolver = new ResourceResolver("com.hht.context.resource");
        List<String> classNames = resolver.scan(r -> {
            if (r.name().endsWith(".class")) return r.name();
            return null;
        });

        System.out.println("=== 扫描到的 .class 文件名 ===");
        classNames.forEach(System.out::println);

        assertFalse(classNames.isEmpty(), "应扫描到 .class 文件");
        classNames.forEach(name -> assertTrue(name.endsWith(".class"), "应只包含 .class 文件"));
    }

    @Test
    void testScanReturnsNullFiltersOut() {
        ResourceResolver resolver = new ResourceResolver("com.hht.context.resource");
        List<Resource> all = resolver.scan(r -> r);
        List<Resource> none = resolver.scan(r -> null);

        System.out.println("=== 全收集数量: " + all.size() + ", 全过滤数量: " + none.size() + " ===");
        assertTrue(all.size() > 0, "全收集应有结果");
        assertEquals(0, none.size(), "全过滤应为空");
    }

    // ==================== 工具方法测试 ====================

    @Test
    void testRemoveTrailingSlash() {
        ResourceResolver resolver = new ResourceResolver("");
        assertEquals("abc", resolver.removeTrailingSlash("abc/"));
        assertEquals("abc", resolver.removeTrailingSlash("abc\\"));
        assertEquals("abc", resolver.removeTrailingSlash("abc"));
        assertEquals("a/b/c", resolver.removeTrailingSlash("a/b/c/"));
    }

    @Test
    void testRemoveLeadingSlash() {
        ResourceResolver resolver = new ResourceResolver("");
        assertEquals("abc", resolver.removeLeadingSlash("/abc"));
        assertEquals("abc", resolver.removeLeadingSlash("\\abc"));
        assertEquals("abc", resolver.removeLeadingSlash("abc"));
    }

    @Test
    void testUriToStringDecode() throws Exception {
        URI uri = new URI("file:/D:/project/my%20dir/classes");
        ResourceResolver resolver = new ResourceResolver("");
        String decoded = resolver.uriToString(uri);
        System.out.println("=== URI decode 结果: " + decoded + " ===");
        assertTrue(decoded.contains("my dir"), "应解码空格为实际空格");
        assertFalse(decoded.contains("%20"), "不应包含 %20 编码");
    }

    // ==================== 路径截取逻辑测试 ====================

    @Test
    void testUriBaseStrCalculation() {
        String uriStr = "file:/D:/project/target/classes/com/hht/context";
        String basePackagePath = "com/hht/context";

        String uriBaseStr = uriStr.substring(0, uriStr.length() - basePackagePath.length());
        System.out.println("=== uriBaseStr: " + uriBaseStr + " ===");
        assertEquals("file:/D:/project/target/classes/", uriBaseStr);

        if (uriBaseStr.startsWith("file:")) {
            uriBaseStr = uriBaseStr.substring(5);
        }
        System.out.println("=== 去掉 file: 后: [" + uriBaseStr + "] ===");
        assertEquals("/D:/project/target/classes/", uriBaseStr);
    }

    // ==================== 子包遍历验证 ====================

    @Test
    void testScanFindsSubPackages() {
        ResourceResolver resolver = new ResourceResolver("com.hht.context");
        List<Resource> resources = resolver.scan(r -> r);

        System.out.println("=== 扫描 com.hht.context 包下的所有资源 ===");
        resources.forEach(r -> System.out.println("  " + r.name()));

        boolean hasCurrentPackage = resources.stream()
                .anyMatch(r -> r.name().contains("ComponentScan"));
        boolean hasSubPackage = resources.stream()
                .anyMatch(r -> r.name().contains("resource/Resource"));

        System.out.println("当前包有 ComponentScan: " + hasCurrentPackage);
        System.out.println("子包有 Resource: " + hasSubPackage);
    }
}
