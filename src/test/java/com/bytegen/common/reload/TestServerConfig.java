package com.bytegen.common.reload;

import com.bytegen.common.reload.core.PropertiesZookeeperWatcher;
import org.apache.curator.test.TestingServer;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

/**
 * User: xiang
 * Date: 2018/9/4
 * Desc:
 */
@Component
public class TestServerConfig implements BeanFactoryPostProcessor {

    private TestingServer server;

    private static final String initProperties = "reloadable.intValue=1\n" +
            "reloadable.boolValue=true\n" +
            "reloadable.stringValue=Injected String Value\n" +
            "reloadable.compositeStringValue=Hello, ${reloadable.baseStringValue}!\n" +
            "reloadable.baseStringValue=World\n" +
            "\n" +
            "reloadable.listProperty=Value1;Value2;Value3";

    TestingServer getServer() {
        return server;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        try {
            server = new TestingServer();
            System.getProperties().setProperty("test_server", server.getConnectString());
            PropertiesZookeeperWatcher.ZooKeeperClientHolder
                    .getClient(server.getConnectString(), null)
                    .create().orSetData().forPath("/test", initProperties.getBytes());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
