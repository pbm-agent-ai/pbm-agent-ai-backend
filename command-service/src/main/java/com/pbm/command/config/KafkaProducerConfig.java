package com.pbm.command.config;

import com.pbm.command.dto.event.PriceRequestEvent;
import com.pbm.command.dto.event.ProductCandidateDto;
import com.pbm.command.dto.event.ProductSelectionEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Producer 설정.
 *
 * 역할: command-service가 price-topic, product-selection-topic으로 이벤트를 발행할 때
 *       사용할 KafkaTemplate들을 명시적으로 제공한다.
 *       price-service가 consumer에서 자체 DTO 클래스로 역직렬화할 수 있도록
 *       JsonSerializer의 타입 헤더를 비활성화한다.
 * 동작: application.yml의 spring.kafka.bootstrap-servers를 읽어 ProducerFactory를 구성한다.
 *       producer properties의 spring.json.add.type.headers: false 설정과 동일하게
 *       ADD_TYPE_INFO_HEADERS를 false로 명시하여 타입 헤더를 제거한다.
 * 연관: ProductSelectionEventPublisher.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * 공통 Kafka Producer 기본 설정을 반환한다.
     * String 키 + JSON 직렬화를 사용한다.
     */
    private Map<String, Object> baseProducerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return props;
    }

    /**
     * 기본 KafkaTemplate (Primary).
     *
     * Spring Boot auto-configuration이 @ConditionalOnMissingBean(KafkaTemplate.class) 조건으로
     * 기본 KafkaTemplate을 생성하지 않게 되므로, 이 클래스에서 직접 정의한다.
     * @Primary를 지정하여 PriceRequestService처럼 @Qualifier 없이 주입받는 곳에서
     * 자동으로 선택된다.
     *
     * application.yml의 spring.kafka.producer.properties.spring.json.add.type.headers: false를
     * 반영하여 타입 헤더를 비활성화한다. ProductSelectionEvent 전용 템플릿과 동일한 직렬화 설정을 사용한다.
     */
    @Bean
    @Primary
    public KafkaTemplate<String, Object> kafkaTemplate() {
        Map<String, Object> props = baseProducerProps();
        // 타입 헤더 비활성화 — consumer가 자체 DTO로 역직렬화 가능
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        DefaultKafkaProducerFactory<String, Object> factory =
                new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(factory);
    }

    /**
     * price-topic(PriceRequestEvent) 전용 KafkaTemplate.
     *
     * PriceRequestService는 KafkaTemplate<String, PriceRequestEvent>를 직접 주입받으므로
     * 런타임에서 타입이 명확한 Bean을 별도로 제공한다.
     */
    @Bean
    public KafkaTemplate<String, PriceRequestEvent> priceRequestKafkaTemplate() {
        Map<String, Object> props = baseProducerProps();
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        DefaultKafkaProducerFactory<String, PriceRequestEvent> factory =
                new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(factory);
    }

    /**
     * product-selection 토픽 전용 KafkaTemplate.
     * JsonSerializer.ADD_TYPE_INFO_HEADERS를 false로 설정하여
     * Kafka 메시지 헤더에 FQCN 타입 정보를 포함하지 않는다.
     * 이를 통해 consumer(price-service)가 패키지 구조 차이와 무관하게
     * 자체 DTO로 역직렬화할 수 있다.
     */
    @Bean
    public KafkaTemplate<String, ProductSelectionEvent> productSelectionKafkaTemplate() {
        Map<String, Object> props = baseProducerProps();
        // 타입 헤더를 비활성화하여 consumer에서 자체 DTO로 역직렬화 가능하게 함
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        DefaultKafkaProducerFactory<String, ProductSelectionEvent> factory =
                new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(factory);
    }
}
