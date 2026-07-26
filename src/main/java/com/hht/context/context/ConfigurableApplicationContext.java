package com.hht.context.context;

import jakarta.annotation.Nullable;

import java.util.List;

//中间接口，给框架内部用
//BeanPostProcessor的实现类之类的可以使用
public interface ConfigurableApplicationContext extends ApplicationContext{

    List<BeanDefinition> findBeanDefinitions(Class<?> type);

    @Nullable
    BeanDefinition findBeanDefinition(Class<?> type);

    @Nullable
    BeanDefinition findBeanDefinition(String name);

    @Nullable
    BeanDefinition findBeanDefinition(String name, Class<?> requiredType);

    Object createBeanAsEarlySingleton(BeanDefinition def);

}
