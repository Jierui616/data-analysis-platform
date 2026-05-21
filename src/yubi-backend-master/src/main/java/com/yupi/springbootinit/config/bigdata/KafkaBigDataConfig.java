package com.yupi.springbootinit.config.bigdata;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
@ConditionalOnProperty(prefix = "bigdata.kafka", name = "enabled", havingValue = "true")
public class KafkaBigDataConfig {

    @Bean
    public ProducerFactory<String, String> bigDataProducerFactory(BigDataProperties bigDataProperties) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bigDataProperties.getKafka().getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.RETRIES_CONFIG, 1);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean(name = "bigDataKafkaTemplate")
    public KafkaTemplate<String, String> bigDataKafkaTemplate(ProducerFactory<String, String> bigDataProducerFactory) {
        return new KafkaTemplate<>(bigDataProducerFactory);
    }
}
