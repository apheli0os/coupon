package com.mall.coupon.config;

import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.io.InputStream;

/** profile=sharding 时用 ShardingSphere 数据源替换默认单库数据源 */
@Configuration
@Profile("sharding")
public class ShardingDataSourceConfig {

    @Bean
    public DataSource dataSource() throws Exception {
        try (InputStream in = new ClassPathResource("sharding.yaml").getInputStream()) {
            return YamlShardingSphereDataSourceFactory.createDataSource(in.readAllBytes());
        }
    }
}
