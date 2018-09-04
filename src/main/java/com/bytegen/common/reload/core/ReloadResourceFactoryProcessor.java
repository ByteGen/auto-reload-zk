package com.bytegen.common.reload.core;

import com.bytegen.common.reload.ReloadZnode;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * Processes beans on start up collecting property resources configured in a {@link ReloadZnode}.
 * </p>
 */
@Component
public class ReloadResourceFactoryProcessor implements BeanFactoryPostProcessor {

    private List<AnnotatedBeanDefinition> reloadZnodeCandidates = new ArrayList<>();

    public List<AnnotatedBeanDefinition> getReloadZnodeCandidates() {
        return reloadZnodeCandidates;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        String[] candidateNames = beanFactory.getBeanDefinitionNames();
        for (String beanName : candidateNames) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            // String className = bd.getBeanClassName();

            if (bd instanceof AnnotatedBeanDefinition) {
                    /*&& className.equals(((AnnotatedBeanDefinition) bd).getMetadata().getClassName()) &&*/
                if (((AnnotatedBeanDefinition) bd).getMetadata().isAnnotated(ReloadZnode.class.getCanonicalName())) {
                    reloadZnodeCandidates.add((AnnotatedBeanDefinition) bd);
                }
            }
        }
    }
}
