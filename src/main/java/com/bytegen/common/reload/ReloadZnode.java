package com.bytegen.common.reload;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.annotation.*;

/**
 * Annotation providing a convenient and declarative mechanism for adding zookeeper node property to
 * {@link com.bytegen.common.reload.core.ReloadResourceFactoryProcessor ReloadResourceFactory}.
 * To be used in conjunction with @{@link Configuration} classes.
 * <p>
 * <h3>Example usage</h3>
 * <p>
 * Given a znode {@code /test/property} containing the key/value pair
 * {@code reloadable.stringValue=testValue}, the following {@code @Configuration} class
 * uses {@code @ReloadZnode} to contribute {@code /test/property} to the
 * {@link com.bytegen.common.reload.core.ReloadZnodePropertySupport}.
 * <p>
 * <pre class="code">
 * &#064;Configuration
 * &#064;ReloadZnode(zookeeperServer = "sample.host1.com:2181", zookeeperPath = "/test/property")
 * public class ReloadingPropertyBean {
 * <p>
 * &#064;ReloadValue("reloadable.stringValue")
 * private String stringProperty;
 * <p>
 * public String getStringProperty() {
 * return this.stringProperty;
 * }
 * }
 * </pre>
 * <p>
 * <h3>Resolving properties in {@code <bean>} and {@code @ReloadValue} annotations</h3>
 * <p>
 * In order to resolve properties in {@code <bean>} definitions or {@code @ReloadValue}
 * annotations using properties from a {@code Resource}, one must register a
 * {@link com.bytegen.common.reload.core.ReloadZnodePropertySupport}. This happens automatically
 * when using component-scanning, but must be explicitly registered using a {@code static}
 * {@code @Bean} method when using {@code @Configuration} classes. See the
 * "Working with externalized values" section of @{@link Configuration}'s javadoc and
 * "a note on BeanFactoryPostProcessor-returning @Bean methods" of @{@link Bean}'s javadoc
 * for details and examples.
 * <p>
 * <h3>A note on property overriding with @PropertyZnode</h3>
 * <p>
 * In cases where a given property key exists in more than one {@code /zookeeper/properties}
 * node, the last {@code @ReloadZnode} annotation processed will 'win' and override.
 * The override ordering depends on the order in which these classes are registered
 * with the application context. Once both registered, the override ordering depends
 * on the modifier time.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ReloadZnode {

    /**
     * Indicate the zookeeper server addresses.
     * For example, {@code "sample.host1.com:2181;sample.host2.com:2181;sample.host3.com:2181"}.
     */
    String zookeeperServer() default "";

    /**
     * Indicate the zookeeper authentication to {@link #zookeeperPath() zookeeper node}
     * For example, {@code "username:password"}.
     */
    String zookeeperAuth() default "";

    /**
     * Indicate the zookeeper path of the properties to be loaded.
     * For example, {@code "/reload/property"}.
     * See {@linkplain ReloadZnode above} for examples.
     */
    String[] zookeeperPath();

    /**
     * Indicate if failure to find the a {@link #zookeeperPath() zookeeper node} should be ignored.
     * <p>{@code true} is appropriate if the node is completely optional.
     * Default is {@code false}.
     */
    boolean ignoreResourceNotFound() default false;

    /**
     * A specific character encoding for the given resources, e.g. "UTF-8".
     */
    String encoding() default "";

}
