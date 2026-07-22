package com.hht.context.io;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

public class PropertyResolverTest {

    private PropertyResolver resolver;

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.setProperty("app.title", "Spring AI");
        props.setProperty("app.version", "1.0.0");
        props.setProperty("app.port", "8080");
        props.setProperty("app.debug", "true");
        props.setProperty("app.score", "95.5");
        props.setProperty("app.date", "2026-07-15");
        props.setProperty("app.time", "14:30:00");
        props.setProperty("app.datetime", "2026-07-15T14:30:00");
        props.setProperty("app.zoned", "2026-07-15T14:30:00+08:00[Asia/Shanghai]");
        props.setProperty("app.duration", "PT15M");
        props.setProperty("app.zone", "Asia/Shanghai");
        props.setProperty("app.empty", "");
        props.setProperty("app.ref", "${app.title}");
        props.setProperty("app.default", "${app.notexist:Summer}");
        props.setProperty("app.nested", "${app.notexist:${app.title}}");
        resolver = new PropertyResolver(props);
    }

    // ==================== 普通 key 查询 ====================

    @Test
    void testGetPropertyByKey() {
        assertEquals("Spring AI", resolver.getProperty("app.title"));
        assertEquals("1.0.0", resolver.getProperty("app.version"));
    }

    @Test
    void testGetPropertyNotFound() {
        assertNull(resolver.getProperty("app.notexist"));
    }

    @Test
    void testContainsProperty() {
        assertTrue(resolver.containsProperty("app.title"));
        assertFalse(resolver.containsProperty("app.notexist"));
    }

    // ==================== ${xxx} 形式查询 ====================

    @Test
    void testGetPropertyWithPlaceholder() {
        assertEquals("Spring AI", resolver.getProperty("${app.title}"));
    }

    @Test
    void testGetPropertyWithPlaceholderNotFound() {
        assertThrows(NullPointerException.class, () -> resolver.getProperty("${app.notexist}"));
    }

    // ==================== ${xxx:defaultValue} 带默认值 ====================

    @Test
    void testGetPropertyWithDefault() {
        assertEquals("Summer", resolver.getProperty("${app.notexist:Summer}"));
    }

    @Test
    void testGetPropertyWithDefaultWhenExists() {
        assertEquals("Spring AI", resolver.getProperty("${app.title:Fall}"));
    }

    // ==================== 嵌套默认值 ====================

    @Test
    void testGetPropertyWithNestedDefault() {
        assertEquals("Spring AI", resolver.getProperty("${app.notexist:${app.title}}"));
    }

    @Test
    void testGetPropertyWithDeepNestedDefault() {
        assertEquals("Spring AI", resolver.getProperty("${a:${b:${c:${app.title}}}}"));
    }

    // ==================== getProperty(key, defaultValue) ====================

    @Test
    void testGetPropertyWithDefaultValueParam() {
        assertEquals("Spring AI", resolver.getProperty("app.title", "Fall"));
        assertEquals("Fall", resolver.getProperty("app.notexist", "Fall"));
    }

    // ==================== getRequiredProperty ====================

    @Test
    void testGetRequiredProperty() {
        assertEquals("Spring AI", resolver.getRequiredProperty("app.title"));
    }

    @Test
    void testGetRequiredPropertyNotFound() {
        assertThrows(NullPointerException.class, () -> resolver.getRequiredProperty("app.notexist"));
    }

    // ==================== 类型转换 ====================

    @Test
    void testGetPropertyWithTypeConversion() {
        assertEquals(8080, (int) resolver.getProperty("app.port", int.class));
        assertEquals(Integer.valueOf(8080), resolver.getProperty("app.port", Integer.class));
        assertEquals(true, (boolean) resolver.getProperty("app.debug", boolean.class));
        assertEquals(Boolean.TRUE, resolver.getProperty("app.debug", Boolean.class));
        assertEquals(95.5, (double) resolver.getProperty("app.score", double.class), 0.001);
        assertEquals(Double.valueOf(95.5), resolver.getProperty("app.score", Double.class));
    }

    @Test
    void testGetPropertyWithTimeConversion() {
        assertEquals(LocalDate.of(2026, 7, 15), resolver.getProperty("app.date", LocalDate.class));
        assertEquals(LocalTime.of(14, 30, 0), resolver.getProperty("app.time", LocalTime.class));
        assertEquals(LocalDateTime.of(2026, 7, 15, 14, 30, 0),
                resolver.getProperty("app.datetime", LocalDateTime.class));
        assertEquals(Duration.ofMinutes(15), resolver.getProperty("app.duration", Duration.class));
        assertEquals(ZoneId.of("Asia/Shanghai"), resolver.getProperty("app.zone", ZoneId.class));
    }

    @Test
    void testGetPropertyWithTypeAndDefaultValue() {
        assertEquals(8080, (int) resolver.getProperty("app.port", int.class, 3000));
        assertEquals(3000, (int) resolver.getProperty("app.notexist", int.class, 3000));
    }

    @Test
    void testGetRequiredPropertyWithType() {
        assertEquals(8080, (int) resolver.getRequiredProperty("app.port", int.class));
    }

    @Test
    void testGetRequiredPropertyWithTypeNotFound() {
        assertThrows(NullPointerException.class,
                () -> resolver.getRequiredProperty("app.notexist", int.class));
    }

    @Test
    void testTypeConversionUnsupported() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.getProperty("app.title", Thread.class));
    }

    // ==================== 引用其他属性 ====================

    @Test
    void testPropertyReference() {
        assertEquals("Spring AI", resolver.getProperty("app.ref"));
    }

    // ==================== parsePropertyExpr ====================

    @Test
    void testParsePropertyExpr() {
        PropertyExpr expr1 = resolver.parsePropertyExpr("${app.title}");
        assertNotNull(expr1);
        assertEquals("app.title", expr1.key());
        assertNull(expr1.defaultValue());

        PropertyExpr expr2 = resolver.parsePropertyExpr("${app.title:Fall}");
        assertNotNull(expr2);
        assertEquals("app.title", expr2.key());
        assertEquals("Fall", expr2.defaultValue());

        PropertyExpr expr3 = resolver.parsePropertyExpr("app.title");
        assertNull(expr3);
    }

    // ==================== 环境变量（System.getenv） ====================

    @Test
    void testSystemEnvIsLoaded() {
        String javaHome = resolver.getProperty("JAVA_HOME");
        assertNotNull(javaHome, "应该能读到环境变量 JAVA_HOME");
        System.out.println("JAVA_HOME = " + javaHome);
    }

    // ==================== 边界情况 ====================

    @Test
    void testEmptyKey() {
        assertThrows(IllegalArgumentException.class, () -> resolver.notEmpty(""));
    }

    @Test
    void testEmptyValue() {
        assertEquals("", resolver.getProperty("app.empty"));
    }

    @Test
    void testGetPropertyNullKey() {
        assertThrows(NullPointerException.class, () -> resolver.getProperty(null));
    }

    @Test
    void testPropertiesOverrideEnv() {
        String path = resolver.getProperty("PATH");
        if (path == null) {
            path = resolver.getProperty("Path");
        }
        assertNotNull(path, "应该能读到环境变量 PATH");
    }
}
