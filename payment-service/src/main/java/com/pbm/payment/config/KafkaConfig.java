package com.pbm.payment.config;

import com.pbm.payment.dto.event.SessionKeyRegistrationEvent;
import com.pbm.payment.dto.event.SubscriptionTerminationEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 컨슈머 설정 클래스.
 * <p>
 * payment-topic 기본 컨슈머(PaymentRequestEvent)는 application.yml에서 자동 구성되며,
 * session-key-registration 토픽은 SessionKeyRegistrationEvent 타입으로 별도 팩토리를 등록한다.
 */
@EnableKafka
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    /**
     * SessionKeyRegistrationEvent 전용 Kafka 컨슈머 팩토리.
     * application.yml의 기본 역직렬화 타입과 충돌하지 않도록 별도 팩토리로 분리한다.
     */
    @Bean
    public ConsumerFactory<String, SessionKeyRegistrationEvent> sessionKeyRegistrationConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE,
                "com.pbm.payment.dto.event.SessionKeyRegistrationEvent");

        return new DefaultKafkaConsumerFactory<>(
                props,
                new StringDeserializer(),
                new JsonDeserializer<>(SessionKeyRegistrationEvent.class, false)
        );
    }

    /**
     * SessionKeyRegistrationEvent 전용 KafkaListenerContainerFactory.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, SessionKeyRegistrationEvent>
    sessionKeyRegistrationKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, SessionKeyRegistrationEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(sessionKeyRegistrationConsumerFactory());
        return factory;
    }

    /**
     * SubscriptionTerminationEvent 전용 Kafka 컨슈머 팩토리.
     */
    @Bean
    public ConsumerFactory<String, SubscriptionTerminationEvent> subscriptionTerminationConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE,
                "com.pbm.payment.dto.event.SubscriptionTerminationEvent");

        return new DefaultKafkaConsumerFactory<>(
                props,
                new StringDeserializer(),
                new JsonDeserializer<>(SubscriptionTerminationEvent.class, false)
        );
    }

    /**
     * SubscriptionTerminationEvent 전용 KafkaListenerContainerFactory.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, SubscriptionTerminationEvent>
    subscriptionTerminationKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, SubscriptionTerminationEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(subscriptionTerminationConsumerFactory());
        return factory;
    }
}
