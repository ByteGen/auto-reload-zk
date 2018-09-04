# Auto-Reload
Dynamic property support for spring, highly inspired by [ReloadablePropertiesAnnotation](https://github.com/jamesmorgan/ReloadablePropertiesAnnotation). 

## Principle
1. 动态配置功能与 spring 相关，通过实现 InstantiationAwareBeanPostProcessorAdapter 的 postProcessAfterInstantiation 方法记录 Property
2. 实现机制：
    - 通过 spring bean 定义上 @ReloadZnode 指定配置文件目录，并使用 NodeCacheListener 监听文件更新
    - 在解析 place holder 时，将带有 @ReloadZnode 标签的 property 及其对应的 bean 记录下来
    - 当文件更新后，解析变更的 property 并向 EventBus 发布一个事件
    - 当变更事件触发后，根据解析时记录的 bean properties map 进行更新
3. 默认的一些配置参考 ReloadZnodePropertySupport
4. 注意：从文件更新到 bean property 更新，会有秒级别的延时
5. 如果与其他配置中心集成，如 file watcher，可以使用 zookeeper -- local file -- jvm 的方式。好处是：有个基础的文件配置，即使 zk 失效也不会影响使用

## Usage
1. 添加 pom 依赖
```
<dependency>
    <groupId>com.bytegen.common</groupId>
    <artifactId>auto-reload-zk</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```
2. 使用 annotation
```java
@Configuration
@ReloadZnode(zookeeperServer = "127.0.0.1:2181", zookeeperAuth = "",
        zookeeperPath = "/test", ignoreResourceNotFound = true)
public class SampleBean {

    @ReloadValue("the_key")
    private String keyed;
}
```
3. 自定义解析方式

默认支持的是 spring 已有的 converter (DefaultConversionService.getSharedInstance()).

如果需要支持其他类型的转换, 可指定 PropertyConversion, 参考如下示例:
```java
@Bean
public class ConfigSample {

    public static class ListPropertyConversion implements PropertyConversion {

        @Override
        public Object convertPropertyForField(final Field field, final Object property) {
            try {
                return new ListConverter(field.getType()).apply(property);
            } catch (final Throwable e) {
                throw new BeanInitializationException(
                        String.format("Unable to convert property for field [%s].  Value [%s] cannot be converted to [%s]",
                                field.getName(), property, field.getType()), e);
            }
        }
    
        private static class ListConverter implements Function<Object, List> {
            private final Class<?> type;
    
            public ListConverter(final Class<?> type) {
                this.type = type;
            }
    
            @Override
            public List apply(final Object input) {
                if (null == input) {
                    return null;
                }
                return Arrays.asList(input.toString().split(";"));
            }
        }
    }
    
    @ReloadValue(value = "the_key", conversion = ListPropertyConversion.class)
    private List<String> keyed;
}

```
