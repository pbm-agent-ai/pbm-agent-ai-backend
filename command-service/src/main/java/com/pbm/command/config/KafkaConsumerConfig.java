package com.pbm.command.config;

import com.pbm.command.dto.event.PriceValidationResultEvent;
import com.pbm.command.dto.event.ProductSelectionRequiredEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Consumer 설정 (product-selection-required 전용).
 *
 * 역할: product-selection-required 토픽을 구독하는 consumer를 위한
 *       전용 KafkaListenerContainerFactory를 제공한다.
 *       price-service가 발행한 이벤트에는 타입 헤더에 price-service 패키지의
 *       FQCN이 포함되어 있으므로, JsonDeserializer.USE_TYPE_INFO_HEADERS를 false로
 *       설정하고 VALUE_DEFAULT_TYPE을 command-service의 DTO로 지정하여
 *       패키지 차이에 무관하게 역직렬화할 수 있도록 한다.
 * 동작: application.yml의 spring.kafka.bootstrap-servers와
 *       spring.kafka.consumer.group-id를 읽어 ConsumerFactory를 구성한다.
 * 연관: ProductSelectionRequiredConsumer.
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    /**
     * product-selection-required 토픽 전용 KafkaListenerContainerFactory.
     * USE_TYPE_INFO_HEADERS = false로 설정하여 메시지 헤더의 타입 정보를 무시하고,
     * VALUE_DEFAULT_TYPE으로 command-service의 ProductSelectionRequiredEvent를 사용한다.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ProductSelectionRequiredEvent>
            productSelectionRequiredContainerFactory() {

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        // 타입 헤더를 무시하고 VALUE_DEFAULT_TYPE으로 역직렬화
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE,
                ProductSelectionRequiredEvent.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");

        DefaultKafkaConsumerFactory<String, ProductSelectionRequiredEvent> consumerFactory =
                new DefaultKafkaConsumerFactory<>(props);

        ConcurrentKafkaListenerContainerFactory<String, ProductSelectionRequiredEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }

    /**
     * price-validation-result 토픽 전용 KafkaListenerContainerFactory.
     * 타입 헤더를 무시하고 command-service의 DTO로 역직렬화한다.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PriceValidationResultEvent>
            priceValidationResultContainerFactory() {

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, PriceValidationResultEvent.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");

        DefaultKafkaConsumerFactory<String, PriceValidationResultEvent> consumerFactory =
                new DefaultKafkaConsumerFactory<>(props);

        ConcurrentKafkaListenerContainerFactory<String, PriceValidationResultEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }
}
