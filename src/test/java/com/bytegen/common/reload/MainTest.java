package com.bytegen.common.reload;

import com.bytegen.common.reload.bean.ReloadingPropertyBean;
import com.bytegen.common.reload.core.PropertiesZookeeperWatcher;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.test.TestingServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.util.Arrays;
import java.util.Properties;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

/**
 * Hello world!
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = Application.class)
public class MainTest {

    @Resource
    ReloadingPropertyBean reloadingPropertyBean;
    @Resource
    TestServerConfig testServerConfig;

    private CuratorFramework client;
    private Properties loadedProperties;

    private static final String initProperties = "reloadable.intValue=1\n"+
            "reloadable.boolValue=true\n"+
            "reloadable.stringValue=Injected String Value\n"+
            "reloadable.compositeStringValue=Hello, ${reloadable.baseStringValue}!\n"+
            "reloadable.baseStringValue=World\n"+
            "\n"+
            "reloadable.listProperty=Value1;Value2;Value3";

    @Before
    public void setUp() throws Exception {
        loadedProperties = new Properties();
        client = PropertiesZookeeperWatcher.ZooKeeperClientHolder
                .getClient(testServerConfig.getServer().getConnectString(), null);
        client.setData().forPath("/test", initProperties.getBytes());
        loadedProperties.load(new StringReader(initProperties));

        assertThat(this.reloadingPropertyBean.getIntProperty(), is(1));
        assertThat(this.reloadingPropertyBean.getBoolProperty(), is(true));
        assertThat(this.reloadingPropertyBean.getStringProperty(), is("Injected String Value"));
        assertThat(this.reloadingPropertyBean.getCompositeStringProperty(), is("Hello, World!"));
    }

    @After
    public void cleanUp() throws Exception {
        this.loadedProperties.setProperty("reloadable.intValue", "1");
        this.loadedProperties.setProperty("reloadable.boolValue", "true");
        this.loadedProperties.setProperty("reloadable.stringValue", "Injected String Value");
        this.loadedProperties.setProperty("reloadable.baseStringValue", "World");
        this.loadedProperties.setProperty("reloadable.compositeStringValue", "Hello, ${reloadable.baseStringValue}!");
        this.loadedProperties.setProperty("reloadable.listProperty", "Value1;Value2;Value3");

        StringWriter writer = new StringWriter();
        loadedProperties.store(writer, null);
        String string = writer.getBuffer().toString();
        client.setData().forPath("/test", string.getBytes());

        Thread.sleep(300); // this is a hack
        assertThat(this.reloadingPropertyBean.getStringProperty(), is("Injected String Value"));
        assertThat(this.reloadingPropertyBean.getCompositeStringProperty(), is("Hello, World!"));
    }

    @Test
    public void shouldReloadAlteredStringProperty() throws Exception {
        assertThat(this.reloadingPropertyBean.getStringProperty(), is("Injected String Value"));

        this.loadedProperties.setProperty("reloadable.stringValue", "Altered Injected String Value");

        StringWriter writer = new StringWriter();
        loadedProperties.store(writer, null);
        String string = writer.getBuffer().toString();
        client.setData().forPath("/test", string.getBytes());

        Thread.sleep(300); // this is a hack
        assertThat(this.reloadingPropertyBean.getStringProperty(), is("Altered Injected String Value"));
    }

    @Test
    public void shouldReloadAlteredCompositeStringProperty() throws Exception {
        assertThat(this.reloadingPropertyBean.getCompositeStringProperty(), is("Hello, World!"));

        this.loadedProperties.setProperty("reloadable.compositeStringValue", "Goodbye, ${reloadable.baseStringValue}!");
        assertThat(this.loadedProperties.getProperty("reloadable.compositeStringValue"), is("Goodbye, ${reloadable.baseStringValue}!"));

        StringWriter writer = new StringWriter();
        loadedProperties.store(writer, null);
        String string = writer.getBuffer().toString();
        client.setData().forPath("/test", string.getBytes());

        Thread.sleep(300); // this is a hack
        assertThat(this.reloadingPropertyBean.getCompositeStringProperty(), is("Goodbye, World!"));
    }

    @Test
    public void shouldReloadAlteredBaseProperty() throws Exception {
        assertThat(this.reloadingPropertyBean.getCompositeStringProperty(), is("Hello, World!"));

        this.loadedProperties.setProperty("reloadable.baseStringValue", "Universe");
        assertThat(this.loadedProperties.getProperty("reloadable.compositeStringValue"), is("Hello, ${reloadable.baseStringValue}!"));

        StringWriter writer = new StringWriter();
        loadedProperties.store(writer, null);
        String string = writer.getBuffer().toString();
        client.setData().forPath("/test", string.getBytes());

        Thread.sleep(300);
        assertThat(this.reloadingPropertyBean.getCompositeStringProperty(), is("Hello, Universe!"));
    }

    @Test
    public void shouldReloadAlteredListProperty() throws Exception {
        assertThat(this.reloadingPropertyBean.getListProperty(), is(Arrays.asList("Value1", "Value2", "Value3")));

        this.loadedProperties.setProperty("reloadable.listProperty", "Altered Value1;Altered Value2;Altered Value3");

        StringWriter writer = new StringWriter();
        loadedProperties.store(writer, null);
        String string = writer.getBuffer().toString();
        client.setData().forPath("/test", string.getBytes());

        Thread.sleep(300);
        assertThat(this.reloadingPropertyBean.getListProperty(), is(Arrays.asList("Altered Value1", "Altered Value2", "Altered Value3")));
    }
}
