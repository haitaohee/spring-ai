package com.hht.context.util;

import com.hht.context.annotation.Bean;
import com.hht.context.annotation.Component;
import com.hht.context.exception.BeanDefinitionException;
import jakarta.annotation.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ClassUtils {

    public static <A extends Annotation>  A findAnnotation(Class<?> target, Class<A> annoClass) {
        //注解是 JVM 动态生成的代理对象，每次 getAnnotation() 返回同一个缓存实例。同一个类的同一个注解每次get是同一个对象
        //不同类或不同注解都是不同的对象
        A a = target.getAnnotation(annoClass);
        for (Annotation anno : target.getAnnotations()) {
            Class<? extends Annotation> annoType = anno.annotationType();
            if (!annoType.getPackageName().equals("java.lang.annotation")) {
                //循环遍历target其他注解，防止注解重复引用，或者继承下来没识别到，target.getAnnotation(annoClass)只识别直接标注的
                //一个类同一个注解加多次会编译报错，除非注解加了@Repeatable。@Bean("hello") @Bean("world")，会加两个对象到容器
                //继承导致多个？注解不继承（默认），加 @Inherited 后子类能读到父类的，但子类自己写了就覆盖，不会叠加

                //需要确定到底用哪个 @Component 的值（Bean 名称），两个冲突了不知道该用哪个。子类覆盖父类说的是 @Inherited 的场景，
                // 而这里是同一类上通过不同路径找到同一注解的冲突，不是继承问题。
                //实际上 Spring 的做法是：不抛异常，而是按一定优先级合并。你这个实现抛异常是更严格的做法，能提前暴露配置错误，也有道理。
                A found = findAnnotation(annoType, annoClass);
                if (found != null) {
                    if (a != null) {
                        throw new BeanDefinitionException("Duplicate @" + annoClass.getSimpleName() + " found on class " + target.getSimpleName());
                    }
                    a = found;
                }
            }
        }
        return a;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static <A extends Annotation> A getAnnotation(Annotation[] annos, Class<A> annoClass) {
        for (Annotation anno : annos) {
            if (annoClass.isInstance(anno)) {
                return (A) anno;
            }
        }
        return null;
    }


    public static String getBeanName(Class<?> clazz) {
        String name = "";
        Component component = clazz.getAnnotation(Component.class);
        if (component != null) {
            name = component.value();
        } else {
            for (Annotation anno : clazz.getAnnotations()) {
                try {
                    if (findAnnotation(anno.annotationType(), Component.class) != null) {
                        name = (String) anno.annotationType().getMethod("value").invoke(anno);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new BeanDefinitionException("Cannot get annotation value.", e);
                }
            }
        }
        if (name.isEmpty()) {
            name = clazz.getSimpleName();
            name = Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }
        return name;
    }

    public static String getBeanName(Method method) {
        Bean bean = method.getAnnotation(Bean.class);
        String name = bean.value();
        if (name.isEmpty()) {
            name = method.getName();
        }
        return name;
    }

    public static Method findAnnotationMethod(Class<?> clazz, Class<? extends Annotation> annoClass) {
        List<Method> methodList = Arrays.stream(clazz.getDeclaredMethods()).filter(method -> method.isAnnotationPresent(annoClass)).map(method -> {
            if (method.getParameterCount() != 0) {
                throw new BeanDefinitionException(
                        String.format("Method '%s' with @%s must not have argument: %s", method.getName(), annoClass.getSimpleName(), clazz.getName()));
            }
            return method;
        }).collect(Collectors.toList());
        if (methodList.isEmpty()) {
            return null;
        }
        if (methodList.size() == 1) {
            return methodList.get(0);
        }
        throw new BeanDefinitionException(String.format("Multiple methods with @%s found in class: %s", annoClass.getSimpleName(), clazz.getName()));
    }
}
