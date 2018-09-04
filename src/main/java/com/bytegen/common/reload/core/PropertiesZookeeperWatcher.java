package com.bytegen.common.reload.core;

import com.bytegen.common.reload.event.EventPublisher;
import org.apache.commons.lang3.StringUtils;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanInitializationException;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The watching process does not start by default, initiation is triggered by calling <code>startWatching()</code>
 */
public class PropertiesZookeeperWatcher {
    private static Logger log = LoggerFactory.getLogger(PropertiesZookeeperWatcher.class);

    private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

    private final String zkServer;
    private final String zkAuth;
    private final String zkNode;

    private final EventPublisher eventPublisher;

    public PropertiesZookeeperWatcher(String zkServer, String zkAuth, String zkNode, EventPublisher eventPublisher) {
        if (null == eventPublisher) {
            throw new BeanInitializationException("Event publisher not setup...");
        }
        if (StringUtils.isBlank(zkServer)) {
            throw new BeanInitializationException("ZooKeeper server must not be blank");
        }
        if (StringUtils.isBlank(zkNode)) {
            throw new BeanInitializationException("ZooKeeper node must not be null");
        }

        this.zkServer = zkServer;
        this.zkAuth = zkAuth;
        this.zkNode = zkNode;
        this.eventPublisher = eventPublisher;
    }

    public void startWatching() throws Exception {
        log.debug("Try start watching zookeeper [{}], node [{}]", zkServer, zkNode);
        ZookeeperWatcherHolder.startPropertiesNodeCache(zkServer, zkAuth, zkNode, eventPublisher);
    }

    public void stop() {
        try {
            log.debug("Try close zookeeper node cache [{}], node [{}]", zkServer, zkNode);
            ZookeeperWatcherHolder.stopPropertiesNodeCache(zkServer, zkAuth, zkNode, eventPublisher);
        } catch (final Exception e) {
            log.error("Unable to stop zookeeper watcher", e);
        }
    }

    ////////////////////////////////
    ///    zookeeper watcher    ////
    ////////////////////////////////
    private static class ZookeeperWatcherHolder {
        private static final Map<String, NodeCache> cacheMap = new HashMap<>(8);

        private static String watcherHashKey(String zkServer, String zkAuth, String zkNode, EventPublisher eventPublisher) {
            return zkServer + "-" + zkAuth + "-" + zkNode + "-" + eventPublisher.getClass().getCanonicalName();
        }

        private static NodeCache startPropertiesNodeCache(String zkServer, String zkAuth, String zkNode, EventPublisher eventPublisher) throws Exception {
            String cacheKey = watcherHashKey(zkServer, zkAuth, zkNode, eventPublisher);

            synchronized (cacheMap) {
                NodeCache nodeCache = cacheMap.get(cacheKey);
                if (null == nodeCache) {
                    CuratorFramework client = ZooKeeperClientHolder.getClient(zkServer, zkAuth);

                    NodeCache newCache = new NodeCache(client, zkNode);
                    newCache.getListenable().addListener(() -> {
                        ChildData data = newCache.getCurrentData();

                        log.debug("START");
                        if (null != data && null != data.getData()) {
                            Reader inputReader = new InputStreamReader(new ByteArrayInputStream(data.getData()), DEFAULT_CHARSET);
                            Properties p = new Properties();
                            p.load(inputReader);

                            logNewEvent(zkNode, zkServer);
                            eventPublisher.onPropertyChanged(p);
                        } else {
                            log.error("zookeeper properties is blank: " + zkNode);
                            // do nothing, and wait for next event
                        }
                        log.debug("END");
                    });
                    newCache.start();
                    cacheMap.put(cacheKey, newCache);
                    log.info("Watching zookeeper server [{}], node [{}]", zkServer, zkNode);
                } else {
                    log.info("Duplicated for watching zookeeper server [{}], node [{}]", zkServer, zkNode);
                }
                return nodeCache;
            }
        }

        private static void logNewEvent(final String zkNode, final String zkServer) {
            log.debug("Watched znode changed, modified node [{}]", zkNode);
            log.debug("  Zookeeper Server [{}]", zkServer);
        }

        private static NodeCache stopPropertiesNodeCache(String zkServer, String zkAuth, String zkNode, EventPublisher eventPublisher) throws Exception {
            String cacheKey = watcherHashKey(zkServer, zkAuth, zkNode, eventPublisher);

            synchronized (cacheMap) {
                NodeCache nodeCache = cacheMap.remove(cacheKey);
                if (null != nodeCache) {
                    nodeCache.close();
                }
                return nodeCache;
            }
        }
    }


    ///////////////////////////////
    ///    zookeeper client    ////
    ///////////////////////////////
    public static class ZooKeeperClientHolder {
        private static final Map<String, CuratorFramework> clientMap = new HashMap<>(8);

        private static final int SESSION_TIMEOUT = 30000;
        private static final int CONNECTION_TIMEOUT = 30000;
        private static final RetryPolicy DEFAULT_RETRY_POLICY = new ExponentialBackoffRetry(1000, 5);

        private static String clientHashKey(String zkServer, String zkAuth) {
            return zkServer + "-" + zkAuth;
        }

        public static CuratorFramework getClient(String server, String auth) {
            String clientKey = clientHashKey(server, auth);
            return clientMap.computeIfAbsent(clientKey, key
                    -> newClient(server, auth, SESSION_TIMEOUT, CONNECTION_TIMEOUT));
        }

        private static CuratorFramework newClient(String server, String auth, int sessionTimeout, int connectionTimeout) {
            CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder().connectString(server)
                    .sessionTimeoutMs(sessionTimeout)
                    .connectionTimeoutMs(connectionTimeout)
                    .retryPolicy(DEFAULT_RETRY_POLICY);
            if (null != auth) {
                builder.authorization("digest", auth.getBytes());
            }

            CuratorFramework client = builder.build();
            client.getConnectionStateListenable().addListener((client1, newState) -> {
                switch (newState) {
                    case CONNECTED:
                        log.info("connected to zookeeper: " + server);
                        break;
                    case SUSPENDED:
                        log.warn("suspended to zookeeper: " + server);
                        break;
                    case RECONNECTED:
                        log.info("reconnected to zookeeper: " + server);
                        break;
                    case LOST:
                        log.error("lose connection to zookeeper: " + server);
                        break;
                    case READ_ONLY:
                        log.info("read only model to zookeeper: " + server);
                        break;
                }
            });
            client.start();
            log.info("ZooKeeper client started: server [{}]", server);
            return client;
        }
    }

}
