package com.hht.context.context;

import com.hht.context.annotation.*;
import com.hht.context.exception.BeanCreationException;
import com.hht.context.exception.BeanDefinitionException;
import com.hht.context.exception.BeanNotOfRequiredTypeException;
import com.hht.context.exception.NoSuchBeanDefinitionException;
import com.hht.context.exception.NoUniqueBeanDefinitionException;
import com.hht.context.exception.UnsatisfiedDependencyException;
import com.hht.context.io.PropertyResolver;
import com.hht.context.io.ResourceResolver;
import com.hht.context.util.ClassUtils;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 注意：这里只实现了传入首配置类的componentscan和import扫描！！！！import注解一定要在传入的类上，componentscan可以在传入配置类的父类
 * 基本上就是只扫描启动类的这俩注解。spring是要递归的，每次扫描出来的新类上如果有componentscan和import，还会递归都扫描。
 */
public class AnnotationConfigApplicationContext {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected final PropertyResolver propertyResolver;
    protected final Map<String, BeanDefinition> beans;

    private Set<String> creatingBeanNames;

    public AnnotationConfigApplicationContext(Class<?> configClass, PropertyResolver propertyResolver) {
        this.propertyResolver = propertyResolver;

        // 扫描获取所有Bean的Class类型:
        final Set<String> beanClassNames = scanForClassNames(configClass);

        // 创建Bean的定义:
        this.beans = createBeanDefinitions(beanClassNames);
        this.creatingBeanNames = new HashSet<>();

        this.beans.values().stream()
            //sorted是为了按照Order注解的顺序执行创建
            .filter(this::isConfigurationDefinition).sorted().map(def -> {
                createBeanAsEarlySingleton(def);
                return def.getName();
            }).collect(Collectors.toList());

        // 创建其他普通Bean:
        createNormalBeans();

        if (logger.isDebugEnabled()) {
            this.beans.values().stream().sorted().forEach(def -> {
                logger.debug("bean initialized: {}", def);
            });
        }

        this.beans.values().forEach(def -> {
            injectBean(def);
        });

        this.beans.values().forEach(def -> {
            initBean(def);
        });
    }

    /**
     * 注入依赖但不调用init方法
     */
    void injectBean(BeanDefinition def) {
        try {
            injectProperties(def, def.getBeanClass(), def.getInstance());
        } catch (ReflectiveOperationException e) {
            throw new BeanCreationException(e);
        }
    }

    void injectProperties(BeanDefinition def, Class<?> clazz, Object bean) throws ReflectiveOperationException {
        for (Field f : clazz.getDeclaredFields()) {
            tryInjectProperties(def, clazz, bean, f);
        }
        for (Method m : clazz.getDeclaredMethods()) {
            tryInjectProperties(def, clazz, bean, m);
        }
        // 在父类查找Field和Method并注入:
        Class<?> superClazz = clazz.getSuperclass();
        if (superClazz != null) {
            // 递归调用:
            injectProperties(def, superClazz, bean);
        }
    }

    // 注入单个属性
    void tryInjectProperties(BeanDefinition def, Class<?> clazz, Object bean, AccessibleObject acc)
        throws ReflectiveOperationException  {
        Value value = acc.getAnnotation(Value.class);
        Autowired autowired = acc.getAnnotation(Autowired.class);
        if (value == null && autowired == null) {
            return;
        }

        Field field = null;
        Method method = null;
        if (acc instanceof Field f) {
            checkFieldOrMethod(f);
            f.setAccessible(true);
            field = f;
        }
        if (acc instanceof Method m) {
            checkFieldOrMethod(m);
            //方法上面的注解只能set一个参数的值
            if (m.getParameters().length != 1) {
                throw new BeanDefinitionException(
                    String.format("Cannot inject a non-setter method %s for bean '%s': %s", m.getName(), def.getName(), def.getBeanClass().getName()));
            }
            m.setAccessible(true);
            method = m;
        }

        String accessibleName = field != null ? field.getName() : method.getName();
        Class<?> accessibleType = field != null ? field.getType() : method.getParameterTypes()[0];

        if (value != null && autowired != null) {
            throw new BeanCreationException(String.format("Cannot specify both @Autowired and @Value when inject %s.%s for bean '%s': %s",
                clazz.getSimpleName(), accessibleName, def.getName(), def.getBeanClass().getName()));
        }

        // @Value注入:
        if (value != null) {
            Object propValue = this.propertyResolver.getRequiredProperty(value.value(), accessibleType);
            if (field != null) {
                logger.atDebug().log("Field injection: {}.{} = {}", def.getBeanClass().getName(), accessibleName, propValue);
                field.set(bean, propValue);
            }
            if (method != null) {
                logger.atDebug().log("Method injection: {}.{} ({})", def.getBeanClass().getName(), accessibleName, propValue);
                method.invoke(bean, propValue);
            }
        }

        // @Autowired注入:
        if (autowired != null) {
            String name = autowired.name();
            boolean required = autowired.value();
            Object depends = name.isEmpty() ? findBean(accessibleType) : findBean(name, accessibleType);
            if (required && depends == null) {
                throw new UnsatisfiedDependencyException(String.format("Dependency bean not found when inject %s.%s for bean '%s': %s", clazz.getSimpleName(),
                    accessibleName, def.getName(), def.getBeanClass().getName()));
            }
            if (depends != null) {
                if (field != null) {
                    logger.atDebug().log("Field injection: {}.{} = {}", def.getBeanClass().getName(), accessibleName, depends);
                    field.set(bean, depends);
                }
                if (method != null) {
                    logger.atDebug().log("Mield injection: {}.{} ({})", def.getBeanClass().getName(), accessibleName, depends);
                    method.invoke(bean, depends);
                }
            }
        }
    }

    void initBean(BeanDefinition def) {
        callMethod(def.getInstance(), def.getInitMethod(), def.getInitMethodName());
    }

    void checkFieldOrMethod(Member m) {
        int mod = m.getModifiers();
        if (Modifier.isStatic(mod)) {
            throw new BeanDefinitionException("Cannot inject static field: " + m);
        }
        if (Modifier.isFinal(mod)) {
            if (m instanceof Field field) {
                throw new BeanDefinitionException("Cannot inject final field: " + field);
            }
            //代理对象无法重写 final 方法，导致注入逻辑在代理对象上失效，字段为 null。
            if (m instanceof Method method) {
                logger.warn(
                    "Inject final method should be careful because it is not called on target bean when bean is proxied and may cause NullPointerException.");
            }
        }
    }

    /**
     * 创建普通的Bean
     */
    void createNormalBeans() {
        // 获取BeanDefinition列表:
        List<BeanDefinition> defs = this.beans.values().stream()
            // filter bean definitions by not instantiation:
            .filter(def -> def.getInstance() == null).sorted().collect(Collectors.toList());

        defs.forEach(def -> {
            // 如果Bean未被创建(可能在其他Bean的构造方法注入前被创建):
            if (def.getInstance() == null) {
                // 创建Bean:
                createBeanAsEarlySingleton(def);
            }
        });
    }

    /**
     * 创建一个Bean，但不进行字段和方法级别的注入（只注入构造方法需要的参数）。如果创建的Bean不是Configuration，则在构造方法中注入的依赖Bean会自动创建。
     */
    public Object createBeanAsEarlySingleton(BeanDefinition def) {
        if (!creatingBeanNames.add(def.getName())) {
            throw new UnsatisfiedDependencyException(String.format("Circular dependency detected when create bean '%s'", def.getName()));
        }
        Executable createFn = def.getFactoryMethod() == null ? def.getConstructor() : def.getFactoryMethod();
        final Parameter[] parameters = createFn.getParameters();
        final Annotation[][] parameterAnnotations = createFn.getParameterAnnotations();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            final Parameter param = parameters[i];
            final Annotation[] paramAnnos = parameterAnnotations[i];
            final Value value = ClassUtils.getAnnotation(paramAnnos, Value.class);
            final Autowired autowired = ClassUtils.getAnnotation(paramAnnos, Autowired.class);
            //spring无此限制，配置类也可以autowired，比如注入一些参数用于创建其他bean。我们这里是直接先创建的Configuration，不能注入其他类，所以禁用了
            //Spring 中 @Configuration 完全支持构造器注入 @Autowired。Spring 的依赖处理顺序更复杂（先处理 @Configuration，
            // 用 CGLIB 代理增强后再解决依赖），不存在这个问题。这是这个手写 Spring 的简化限制。
            if (isConfigurationDefinition(def) && autowired != null) {
                throw new BeanCreationException(
                    String.format("Cannot specify @Autowired when create @Configuration bean '%s': %s.", def.getName(), def.getBeanClass().getName()));
            }
            //spring对于这种处理也可能报错，看具体情况
            if (value != null && autowired != null) {
                throw new BeanCreationException(
                    String.format("Cannot specify both @Autowired and @Value when create bean '%s': %s.", def.getName(), def.getBeanClass().getName()));
            }
            //构造方法参数必须有值
            //真实 Spring 默认行为：引用类型参数隐式视为 @Autowired，基础类型没有value的话就会报错。
            // 作者这里强制显式标注，简化了判断逻辑（不需要处理「没标注的参数该去哪找值」），但过于严格。
            if (value == null && autowired == null) {
                throw new BeanCreationException(
                    String.format("Must specify @Autowired or @Value when create bean '%s': %s.", def.getName(), def.getBeanClass().getName()));
            }

            Class<?> type = param.getType();
            if (value != null) {
                args[i] = this.propertyResolver.getRequiredProperty(value.value(), type);
            } else {
                String name = autowired.name();
                boolean required = autowired.value();
                BeanDefinition dependsOnDef = name.isEmpty() ? findBeanDefinition(type) : findBeanDefinition(name, type);
                if (required && dependsOnDef == null) {
                    throw new BeanCreationException(String.format("Missing autowired bean with type '%s' when create bean '%s': %s.", type.getName(),
                        def.getName(), def.getBeanClass().getName()));
                }
                if (dependsOnDef != null) {
                    Object autowiredBeanInstance = dependsOnDef.getInstance();
                    //再次校验当前类不是配置类
                    if (autowiredBeanInstance == null && !isConfigurationDefinition(def)) {
                        autowiredBeanInstance = createBeanAsEarlySingleton(dependsOnDef);
                    }
                    args[i] = autowiredBeanInstance;
                } else {
                    args[i] = null;
                }
            }
        }
        Object instance = null;
        if (def.getFactoryMethod() == null) {
            try {
                instance = def.getConstructor().newInstance(args);
            } catch (Exception e) {
                throw new BeanCreationException(String.format("Exception when create bean '%s': %s", def.getName(), def.getBeanClass().getName()), e);
            }
        } else {
            Object configInstance = getBean(def.getFactoryName());
            try {
                instance = def.getFactoryMethod().invoke(configInstance, args);
            } catch (Exception e) {
                throw new BeanCreationException(String.format("Exception when create bean '%s': %s", def.getName(), def.getBeanClass().getName()), e);
            }
        }
        def.setInstance(instance);
        return def.getInstance();
    }


    /**
     * 根据扫描的ClassName创建BeanDefinition
     */
    Map<String, BeanDefinition> createBeanDefinitions(Set<String> classNameSet) {
        Map<String, BeanDefinition> defs = new HashMap<>();
        for (String className : classNameSet) {
            // 获取Class:
            Class<?> clazz = null;
            try {
                clazz = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new BeanCreationException(e);
            }
            if (clazz.isAnnotation() || clazz.isEnum() || clazz.isInterface() || clazz.isRecord()) {
                continue;
            }
            // 是否标注@Component?
            Component component = ClassUtils.findAnnotation(clazz, Component.class);
            if (component != null) {
                logger.atDebug().log("found component: {}", clazz.getName());
                //获取类修饰符
                int mod = clazz.getModifiers();
                if (Modifier.isAbstract(mod)) {
                    throw new BeanDefinitionException("@Component class " + clazz.getName() + " must not be abstract.");
                }
                if (Modifier.isPrivate(mod)) {
                    throw new BeanDefinitionException("@Component class " + clazz.getName() + " must not be private.");
                }

                String beanName = ClassUtils.getBeanName(clazz);
                var def = new BeanDefinition(beanName, clazz, getSuitableConstructor(clazz), getOrder(clazz), clazz.isAnnotationPresent(Primary.class),
                        // named init / destroy method:
                        null, null,
                        // init method:
                        ClassUtils.findAnnotationMethod(clazz, PostConstruct.class),
                        // destroy method:
                        ClassUtils.findAnnotationMethod(clazz, PreDestroy.class));
                addBeanDefinitions(defs, def);
                logger.atDebug().log("define bean: {}", def);

                Configuration configuration = ClassUtils.findAnnotation(clazz, Configuration.class);
                if (configuration != null) {
                    scanFactoryMethods(beanName, clazz, defs);
                }
            }
        }
        return defs;
    }

    /**
     * Get public constructor or non-public constructor as fallback.
     */
    //spring是只有一个构造函数就直接用，不止一个，就找@Autowired标记的，否则就用无参，都没有就报错
    Constructor<?> getSuitableConstructor(Class<?> clazz) {
        //只返回public的构造方法
        Constructor<?>[] cons = clazz.getConstructors();
        if (cons.length == 0) {
            //所有声明的构造方法
            cons = clazz.getDeclaredConstructors();
            if (cons.length != 1) {
                throw new BeanDefinitionException("More than one constructor found in class " + clazz.getName() + ".");
            }
        }
        if (cons.length != 1) {
            throw new BeanDefinitionException("More than one public constructor found in class " + clazz.getName() + ".");
        }
        return cons[0];
    }

    /**
     * Scan factory method that annotated with @Bean:
     *
     * <code>
     * &#64;Configuration
     * public class Hello {
     *     @Bean
     *     ZoneId createZone() {
     *         return ZoneId.of("Z");
     *     }
     * }
     * </code>
     */
    void scanFactoryMethods(String factoryBeanName, Class<?> clazz, Map<String, BeanDefinition> defs) {
        for (Method method : clazz.getDeclaredMethods()) {
            Bean bean = method.getAnnotation(Bean.class);
            if (bean != null) {
                int mod = method.getModifiers();
                if (Modifier.isAbstract(mod)) {
                    throw new BeanDefinitionException("@Bean method " + clazz.getName() + "." + method.getName() + " must not be abstract.");
                }
                if (Modifier.isFinal(mod)) {
                    throw new BeanDefinitionException("@Bean method " + clazz.getName() + "." + method.getName() + " must not be final.");
                }
                if (Modifier.isPrivate(mod)) {
                    throw new BeanDefinitionException("@Bean method " + clazz.getName() + "." + method.getName() + " must not be private.");
                }
                Class<?> beanClass = method.getReturnType();
                if (beanClass.isPrimitive()) {
                    throw new BeanDefinitionException("@Bean method " + clazz.getName() + "." + method.getName() + " must not return primitive type.");
                }
                if (beanClass == void.class || beanClass == Void.class) {
                    throw new BeanDefinitionException("@Bean method " + clazz.getName() + "." + method.getName() + " must not return void.");
                }
                var def = new BeanDefinition(ClassUtils.getBeanName(method), beanClass, factoryBeanName, method, getOrder(method),
                        method.isAnnotationPresent(Primary.class),
                        // init method:
                        bean.initMethod().isEmpty() ? null : bean.initMethod(),
                        // destroy method:
                        bean.destroyMethod().isEmpty() ? null : bean.destroyMethod(),
                        // @PostConstruct / @PreDestroy method:
                        null, null);
                addBeanDefinitions(defs, def);
                logger.atDebug().log("define bean: {}", def);
            }
        }
    }

    /**
     * Check and add bean definitions.
     */
    void addBeanDefinitions(Map<String, BeanDefinition> defs, BeanDefinition def) {
        if (defs.put(def.getName(), def) != null) {
            throw new BeanDefinitionException("Duplicate bean name: " + def.getName());
        }
    }

    /**
     * Get order by:
     *
     * <code>
     * &#64;Order(100)
     * &#64;Component
     * public class Hello {}
     * </code>
     */
    int getOrder(Class<?> clazz) {
        Order order = clazz.getAnnotation(Order.class);
        return order == null ? Integer.MAX_VALUE : order.value();
    }

    public <T> T getBean(String name) {
        BeanDefinition def = this.beans.get(name);
        if (def == null) {
            throw new NoSuchBeanDefinitionException(String.format("No bean defined with name '%s'.", name));
        }
        return (T) def.getRequiredInstance();
    }

    /**
     * Get order by:
     *
     * <code>
     * &#64;Order(100)
     * &#64;Bean
     * Hello createHello() {
     *     return new Hello();
     * }
     * </code>
     */
    int getOrder(Method method) {
        Order order = method.getAnnotation(Order.class);
        return order == null ? Integer.MAX_VALUE : order.value();
    }

    /**
     * Do component scan and return class names.
     */
    protected Set<String> scanForClassNames(Class<?> configClass) {
        // 获取要扫描的package名称:
        ComponentScan scan = ClassUtils.findAnnotation(configClass, ComponentScan.class);
        //指定包或者默认启动类的包
        String[] packages = scan == null || scan.value().length == 0 ? new String[]{configClass.getPackage().getName()} : scan.value();
        Set<String> classNameSet = new HashSet<>();

        for (String aPackage : packages) {
            ResourceResolver resourceResolver = new ResourceResolver(aPackage);
            List<String> classList = resourceResolver.scan(res -> {
                String name = res.name();
                if (name.endsWith(".class")) {
                    return name.substring(0, name.length() - 6).replace('/', '.').replace("\\", ".");
                }
                return null;
            });
            classNameSet.addAll(classList);
        }

        // 查找@Import(Xyz.class):
        Import importConfig = configClass.getAnnotation(Import.class);
        if (importConfig != null) {
            for (Class<?> importConfigClass : importConfig.value()) {
                String importClassName = importConfigClass.getName();
                if (classNameSet.contains(importClassName)) {
                    logger.warn("ignore import: " + importClassName + " for it is already been scanned.");
                } else {
                    logger.debug("class found by import: {}", importClassName);
                    classNameSet.add(importClassName);
                }
            }
        }
        return classNameSet;
    }

    boolean isConfigurationDefinition(BeanDefinition def) {
        return ClassUtils.findAnnotation(def.getBeanClass(), Configuration.class) != null;
    }

    /**
     * 根据Name查找BeanDefinition，如果Name不存在，返回null
     */
    @Nullable
    public BeanDefinition findBeanDefinition(String name) {
        return this.beans.get(name);
    }

    /**
     * 根据Name和Type查找BeanDefinition，如果Name不存在，返回null，如果Name存在，但Type不匹配，抛出异常。
     */
    @Nullable
    public BeanDefinition findBeanDefinition(String name, Class<?> requiredType) {
        BeanDefinition def = findBeanDefinition(name);
        if (def == null) {
            return null;
        }
        if (requiredType.isAssignableFrom(def.getBeanClass())) {
            return def;
        }
        throw new BeanNotOfRequiredTypeException(String.format("Autowire required type '%s' but bean '%s' has actual type '%s'.", requiredType.getName(),
                name, def.getBeanClass().getName()));
    }

    @Nullable
    @SuppressWarnings("unchecked")
    protected <T> T findBean(String name, Class<T> requiredType) {
        BeanDefinition def = findBeanDefinition(name, requiredType);
        if (def == null) {
            return null;
        }
        return (T) def.getRequiredInstance();
    }

    @Nullable
    @SuppressWarnings("unchecked")
    protected <T> T findBean(Class<T> requiredType) {
        BeanDefinition def = findBeanDefinition(requiredType);
        if (def == null) {
            return null;
        }
        return (T) def.getRequiredInstance();
    }

    /**
     * 根据Type查找若干个BeanDefinition，返回0个或多个。
     */
    public List<BeanDefinition> findBeanDefinitions(Class<?> type) {
        return this.beans.values().stream()
                //A.isAssignableFrom(B) B是否是A的子类或子接口，即A a = new B()成立
                //b instanceof A，即A a = b成立
                //A.class.isInstance(b)，即A a = b成立
                //这三个等价
                .filter(bean -> type.isAssignableFrom(bean.getBeanClass()))
                .sorted().collect(Collectors.toList());
    }

    /**
     * 根据Type查找某个BeanDefinition，如果不存在返回null，如果存在多个返回@Primary标注的一个，如果有多个@Primary标注，或没有@Primary标注但找到多个，均抛出NoUniqueBeanDefinitionException
     */
    @Nullable
    public BeanDefinition findBeanDefinition(Class<?> type) {
        List<BeanDefinition> beanDefinitions = findBeanDefinitions(type);
        if (beanDefinitions.isEmpty()) {
            return null;
        }
        if (beanDefinitions.size() == 1) {
            return beanDefinitions.get(0);
        }
        List<BeanDefinition> pri = beanDefinitions.stream().filter(bean -> bean.isPrimary()).collect(Collectors.toList());
        if (pri.size() == 1) {
            return pri.get(0);
        }
        if (pri.isEmpty()) {
            throw  new NoUniqueBeanDefinitionException(String.format("Multiple bean with type '%s' found, but no @Primary specified.", type.getName()));

        } else {
            throw new NoUniqueBeanDefinitionException(String.format("Multiple bean with type '%s' found, and multiple @Primary specified.", type.getName()));
        }
    }

    private void callMethod(Object beanInstance, Method method, String namedMethod) {
        // 调用init/destroy方法:
        if (method != null) {
            try {
                method.invoke(beanInstance);
            } catch (ReflectiveOperationException e) {
                throw new BeanCreationException(e);
            }
        } else if (namedMethod != null) {
            // 查找initMethod/destroyMethod="xyz"，注意是在实际类型中查找:
            Method named = ClassUtils.getNamedMethod(beanInstance.getClass(), namedMethod);
            named.setAccessible(true);
            try {
                named.invoke(beanInstance);
            } catch (ReflectiveOperationException e) {
                throw new BeanCreationException(e);
            }
        }
    }
}
