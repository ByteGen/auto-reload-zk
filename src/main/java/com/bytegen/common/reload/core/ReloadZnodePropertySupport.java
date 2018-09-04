package com.bytegen.common.reload.core;

import com.bytegen.common.reload.ReloadZnode;
import com.bytegen.common.reload.ReloadValue;
import com.bytegen.common.reload.bean.BeanPropertyHolder;
import com.bytegen.common.reload.conversion.DefaultPropertyConversion;
import com.bytegen.common.reload.conversion.PropertyConversion;
import com.bytegen.common.reload.event.EventNotifier;
import com.bytegen.common.reload.event.EventPublisher;
import com.bytegen.common.reload.event.EventSubscriber;
import com.bytegen.common.reload.event.GuavaEventNotifier;
import com.bytegen.common.reload.resolver.MutablePropertyResolver;
import com.bytegen.common.reload.resolver.PropertiesPropertyResolver;
import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessorAdapter;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.*;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * <p>
 * Processes beans on start up injecting field values marked with {@link ReloadValue} setting the associated annotated property value with properties
 * configured in a {@link ReloadZnode}.
 * </p>
 * <p>
 * The processor also has the ability to reload/re-inject properties from the configured {@link ReloadZnode} which are changed.
 * Once a property is reloaded the associated bean holding that value will have its property updated, no further bean operations are performed on the reloaded
 * bean.
 * </p>
 * <p>
 * The processor will also substitute any properties with values starting with "${" and ending with "}", none recursive.
 * </p>
 */
@Component
public class ReloadZnodePropertySupport extends InstantiationAwareBeanPostProcessorAdapter {
    private static final Logger log = LoggerFactory.getLogger(ReloadZnodePropertySupport.class);

    @Resource
    private Environment environment;
    @Resource
    private ReloadResourceFactoryProcessor reloadResourceFactoryProcessor;

    private final PropertiesPropertyResolver propertyResolver = new PropertiesPropertyResolver();
    private final EventNotifier eventNotifier = GuavaEventNotifier.getInstance();

    private final Map<String, String> resolvedBeanProperty = new HashMap<>();
    private final Map<String, Set<BeanPropertyHolder>> beanPropertySubscriptions = new HashMap<>();

    private final EventPublisher publisher = new ReloadPropertyEventPublisher(propertyResolver, eventNotifier, resolvedBeanProperty);
    private final EventSubscriber subscriber = new ReloadPropertyEventSubscriber(eventNotifier, beanPropertySubscriptions);

    @PostConstruct
    protected void startReloading() {
        log.info("Loading Reloadable Properties zookeeper nodes...");
        List<AnnotatedBeanDefinition> definitions = reloadResourceFactoryProcessor.getReloadZnodeCandidates();
        // None @ReloadZnode annotated bean definition found
        if (CollectionUtils.isEmpty(definitions)) {
            log.info("@ReloadZnode not found, break for reloadable zookeeper property support...!");
            return;
        }

        log.info("Start watching for properties file changes");
        definitions.forEach(bd -> {
            Map<String, Object> attributes = bd.getMetadata()
                    .getAnnotationAttributes(ReloadZnode.class.getCanonicalName());
            processReloadResourceAttributes(new AnnotationAttributes(attributes));
        });
    }

    private String resolveEnvironmentProperty(String text) {
        if (null != text) {
            return environment.resolveRequiredPlaceholders(text);
        }
        return null;
    }

    private void processReloadResourceAttributes(AnnotationAttributes propertySource) throws BeanDefinitionStoreException {
        String encoding = resolveEnvironmentProperty(propertySource.getString("encoding"));
        if (StringUtils.isBlank(encoding)) {
            encoding = "UTF-8";
        }
        String zookeeperServer = resolveEnvironmentProperty(propertySource.getString("zookeeperServer"));
        String zookeeperAuth = resolveEnvironmentProperty(propertySource.getString("zookeeperAuth"));
        String[] znodes = propertySource.getStringArray("zookeeperPath");

        Assert.isTrue(StringUtils.isNotBlank(zookeeperServer), "@ReloadZnode zookeeperServer can not be blank");
        Assert.isTrue(znodes.length > 0, "At least one @ReloadZnode zookeeperPath is required");
        boolean ignoreResourceNotFound = propertySource.getBoolean("ignoreResourceNotFound");

        for (String znode : znodes) {
            try {
                String resolved = resolveEnvironmentProperty(znode);
                if (StringUtils.isBlank(resolved)) {
                    if (log.isInfoEnabled()) {
                        log.warn("Properties znode [" + znode + "] is blank, skipped.");
                    }
                    continue;
                }

                CuratorFramework client = PropertiesZookeeperWatcher.ZooKeeperClientHolder.getClient(zookeeperServer, zookeeperAuth);
                byte[] bytes = client.getData().forPath(znode);
                if (bytes != null) {
                    Properties properties = new Properties();
                    properties.load(new StringReader(new String(bytes)));
                    propertyResolver.addProperties(properties);
                }

                new PropertiesZookeeperWatcher(zookeeperServer, zookeeperAuth, znode, publisher).startWatching();

            } catch (Exception ex) {
                // Resource not found when trying to open it
                if (ignoreResourceNotFound && (ex instanceof FileNotFoundException)) {
                    if (log.isInfoEnabled()) {
                        log.warn("Properties location [" + znode + "] not resolvable: " + ex.getMessage());
                    }
                } else {
                    throw new BeanDefinitionStoreException(
                            "Failed to resolve configuration resource [" + znode + "]", ex);
                }
            }
        }
    }


    @Override
    public boolean postProcessAfterInstantiation(final Object bean, final String beanName) throws BeansException {
        if (log.isDebugEnabled()) {
            log.debug("Setting Reloadable Properties on [{}]", beanName);
        }
        setPropertiesOnBean(bean);
        return true;
    }

    private void setPropertiesOnBean(final Object bean) {
        ReflectionUtils.doWithFields(bean.getClass(), new ReflectionUtils.FieldCallback() {

            @Override
            public void doWith(final Field field) throws IllegalArgumentException, IllegalAccessException {

                final ReloadValue annotation = field.getAnnotation(ReloadValue.class);
                if (null != annotation) {

                    ReflectionUtils.makeAccessible(field);
                    validateFieldNotFinal(bean, field);

                    final String propertyValue = propertyResolver.resolvePlaceholders(annotation.value());
                    validatePropertyAvailableOrDefaultSet(bean, field, annotation, propertyValue);

                    if (null != propertyValue) {
                        log.info("Attempting to convert and set property [{}] on field [{}] for class [{}] to type [{}]",
                                propertyValue, field.getName(), bean.getClass().getCanonicalName(), field.getType());

                        final Object convertedProperty = convertPropertyForField(field, propertyValue, annotation.conversion());

                        log.info("Setting field [{}] of class [{}] with value [{}]",
                                field.getName(), bean.getClass().getCanonicalName(), convertedProperty);

                        field.set(bean, convertedProperty);

                        subscribeBeanToPropertyChangedEvent(annotation.value(), propertyValue, new BeanPropertyHolder(bean, field));
                    } else {
                        log.info("Leaving field [{}] of class [{}] with default value",
                                field.getName(), bean.getClass().getCanonicalName());
                    }
                }
            }
        });
    }

    private void validatePropertyAvailableOrDefaultSet(final Object bean, final Field field, final ReloadValue annotation, final Object propertyValue)
            throws IllegalArgumentException, IllegalAccessException {
        if (null == propertyValue && fieldDoesNotHaveDefault(field, bean)) {
            throw new BeanInitializationException(String.format("No property found for field annotated with @ReloadValue, "
                    + "and no default specified. Property [%s] of class [%s] requires a property named [%s]", field.getName(), bean.getClass()
                    .getCanonicalName(), annotation.value()));
        }
    }

    private void validateFieldNotFinal(final Object bean, final Field field) {
        if (Modifier.isFinal(field.getModifiers())) {
            throw new BeanInitializationException(String.format("Unable to set field [%s] of class [%s] as is declared final", field.getName(), bean.getClass()
                    .getCanonicalName()));
        }
    }

    private boolean fieldDoesNotHaveDefault(final Field field, final Object value) throws IllegalArgumentException, IllegalAccessException {
        try {
            return (null == field.get(value));
        } catch (final NullPointerException e) {
            return true;
        }
    }

    private void subscribeBeanToPropertyChangedEvent(final String propertyName, final String propertyValue, final BeanPropertyHolder fieldProperty) {
        this.resolvedBeanProperty.put(propertyName, propertyValue);
        this.beanPropertySubscriptions.computeIfAbsent(propertyName, k -> new HashSet<>());
        this.beanPropertySubscriptions.get(propertyName).add(fieldProperty);
    }

    // ///////////////////////////////////
    // Utility methods for class access //
    // ///////////////////////////////////

    private Object convertPropertyForField(final Field field, final Object propertyValue, final Class<? extends PropertyConversion> conversionClass) {
        try {
            PropertyConversion conversion;
            if (conversionClass == PropertyConversion.class || conversionClass == DefaultPropertyConversion.class) {
                conversion = DefaultPropertyConversion.getInstance();
            } else {
                conversion = BeanUtils.instantiateClass(conversionClass);
            }

            return conversion.convertPropertyForField(field, propertyValue);
        } catch (final Throwable e) {
            throw new BeanInitializationException(
                    String.format("Unable to convert property for field [%s].  Value [%s] cannot be converted to [%s]",
                            field.getName(), propertyValue, field.getType()), e);
        }
    }
}
