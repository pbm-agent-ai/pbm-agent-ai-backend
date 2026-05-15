package com.pbm.price.config;

import com.pbm.price.dto.event.PaymentRequestEvent;
import com.pbm.price.dto.event.PriceValidationResultEvent;
import com.pbm.price.dto.event.PriceAlertEvent;
import com.pbm.price.dto.event.ProductSelectionEvent;
import com.pbm.price.dto.event.ProductSelectionRequiredEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 관련 설정 (product-selection-required, product-selection, price-alert, payment-topic).
 *
 * 역할: price-service가 사용하는 모든 KafkaTemplate과 ListenerContainerFactory를 제공한다.
 *       producer 측은 JsonSerializer 타입 헤더를 제어하여 consumer가 자체 DTO로
 *       역직렬화할 수 있도록 한다.
 *       consumer 측은 타입 헤더를 무시하고 VALUE_DEFAULT_TYPE을 지정하여
 *       command-service의 DTO와 패키지 차이를 극복한다.
 * 동작: application.yml의 spring.kafka.bootstrap-servers를 읽어 설정을 구성한다.
 * 연관: ProductSelectionRequiredEventPublisher, ProductSelectionConsumer.
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    // =========================================================================
    // Producer 설정
    // =========================================================================

    /**
     * 공통 Kafka Producer 기본 설정을 반환한다.
     * String 키 + JSON 직렬화를 사용하며, 타입 헤더 포함 여부는 각 템플릿에서 별도 지정한다.
     */
    private Map<String, Object> baseProducerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return props;
    }

    /**
     * product-selection-required 토픽 전용 KafkaTemplate.
     * JsonSerializer.ADD_TYPE_INFO_HEADERS를 false로 설정하여
     * Kafka 메시지 헤더에 FQCN 타입 정보를 포함하지 않는다.
     * 이를 통해 consumer(command-service)가 패키지 구조 차이와 무관하게
     * 자체 DTO로 역직렬화할 수 있다.
     */
    @Bean
    public KafkaTemplate<String, ProductSelectionRequiredEvent> productSelectionRequiredKafkaTemplate() {
        Map<String, Object> props = baseProducerProps();
        // 타입 헤더를 비활성화하여 consumer에서 자체 DTO로 역직렬화 가능하게 함
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        DefaultKafkaProducerFactory<String, ProductSelectionRequiredEvent> factory =
                new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(factory);
    }

    /**
     * price-alert 토픽 전용 KafkaTemplate.
     * 기본 JsonSerializer 설정(type headers 활성화)을 사용한다.
     * PriceAlertEventPublisher에서 주입받아 사용한다.
     */
    @Bean
    public KafkaTemplate<String, PriceAlertEvent> priceAlertKafkaTemplate() {
        Map<String, Object> props = baseProducerProps();
        DefaultKafkaProducerFactory<String, PriceAlertEvent> factory =
                new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(factory);
    }

    /**
     * payment-topic 전용 KafkaTemplate.
     * 기본 JsonSerializer 설정(type headers 활성화)을 사용한다.
     * PaymentRequestEventPublisher에서 주입받아 사용한다.
     */
    @Bean
    public KafkaTemplate<String, PaymentRequestEvent> paymentRequestKafkaTemplate() {
        Map<String, Object> props = baseProducerProps();
        DefaultKafkaProducerFactory<String, PaymentRequestEvent> factory =
                new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(factory);
    }

    /**
     * price-validation-result 토픽 전용 KafkaTemplate.
     * 타입 헤더를 비활성화하여 command-service가 자체 DTO로 역직렬화할 수 있게 한다.
     */
    @Bean
    public KafkaTemplate<String, PriceValidationResultEvent> priceValidationResultKafkaTemplate() {
        Map<String, Object> props = baseProducerProps();
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        DefaultKafkaProducerFactory<String, PriceValidationResultEvent> factory =
                new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(factory);
    }

    // =========================================================================
    // Consumer 설정
    // =========================================================================

    /**
     * product-selection 토픽 전용 KafkaListenerContainerFactory.
     * USE_TYPE_INFO_HEADERS = false로 설정하여 메시지 헤더의 타입 정보를 무시하고,
     * VALUE_DEFAULT_TYPE으로 price-service의 ProductSelectionEvent를 사용한다.
     * command-service가 발행한 ProductSelectionEvent를 패키지 차이 없이 역직렬화한다.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ProductSelectionEvent>
            productSelectionContainerFactory() {

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        // 타입 헤더를 무시하고 VALUE_DEFAULT_TYPE으로 역직렬화
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE,
                ProductSelectionEvent.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");

        DefaultKafkaConsumerFactory<String, ProductSelectionEvent> consumerFactory =
                new DefaultKafkaConsumerFactory<>(props);

        ConcurrentKafkaListenerContainerFactory<String, ProductSelectionEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }
}
