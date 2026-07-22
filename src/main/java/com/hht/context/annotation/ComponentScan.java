package com.hht.context.annotation;


import java.lang.annotation.*;

/**
 * @Retention(RetentionPolicy.RUNTIME)
 *   注解保留到运行时，可通过反射读取。Spring 启动时需要通过反射扫描
 *   @ComponentScan 注解获取包路径，所以必须是 RUNTIME。
 *   其他取值：SOURCE（编译后丢弃）、CLASS（保留在 .class 但 JVM 不加载）。
 *   source使用场景：
 *   @Override          // 编译器检查是否真的重写了父类方法，编译后不需要
 *  @SuppressWarnings  // 告诉编译器忽略某个警告，编译后没意义
 *  class使用场景：
 * @Generated（lombok的是） 编译时：注解处理器（如 Lombok、MapStruct）生成代码，在生成的类上标记 @Generated("Lombok")
 * 编译后：字节码后处理工具（如 JaCoCo 代码覆盖率）扫描 .class 文件，跳过所有带 @Generated 的类，不对自动生成的代码统计覆盖率
 *
 *
 * @Target(ElementType.TYPE)
 *   注解只能用在类/接口/枚举上。@ComponentScan 是配置启动类的，放方法或字段上没意义。
 *   其他常用取值：METHOD、FIELD、PARAMETER 等。
 *
 * @Documented
 *   生成 Javadoc 时会把此注解也包含进去，方便看文档的人知道这个类被 @ComponentScan 标注了。
 *   纯标记作用，不影响运行。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface ComponentScan {

    /**
     * Package names to scan. Default to current package.
     */
    String[] value() default {};
}
