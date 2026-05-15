# PBM Agent AI Streamlit Demo

멘토링용으로 백엔드 이벤트 흐름을 시각적으로 보여주기 위한 간단한 Streamlit 데모입니다.

## 4단계 현재 상태

- Streamlit 기본 화면 뼈대 구현
- command-service parse API 호출 연결
- parse 응답 JSON 표시
- Kafka 토픽 감시 (price-topic, payment-topic, payment-result)
- 토픽 메시지 기반 단계 상태 추론
- payment-service 조회 API 연결
- 최신 결제 상세(paymentId, status, transactionHash) 확인 가능
- clarification 모달에서 missing/ambiguous 필드 모두 재입력 가능

## 실행 방법

```bash
pip install -r demo/streamlit/requirements.txt
streamlit run demo/streamlit/app.py
```

필요하면 service 주소와 Kafka 브로커를 환경변수로 지정할 수 있습니다.

```bash
export DEMO_COMMAND_SERVICE_URL=http://localhost:8082
export DEMO_PAYMENT_SERVICE_URL=http://localhost:8080
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
streamlit run demo/streamlit/app.py
```

기본값은 command/payment 직접 포트를 사용하며,
개별 서비스 URL을 환경변수로 별도 덮어쓸 수 있습니다.

현재 기본 데모값은 아래와 같습니다.

- command-service: `http://localhost:8082`
- payment-service: `http://localhost:8084`
- external-api-service: `http://localhost:8090`

## Kafka 토픽 감시

- 왼쪽 하단 "Kafka 토픽 새로고침" 버튼으로 각 토픽의 최근 메시지를 조회합니다.
- `price-topic`: command-service가 price-service로 보내는 가격 요청 토픽
- `payment-topic`: price-service가 payment-service로 보내는 결제 요청 토픽
- `payment-result`: 결제 결과 토픽
- 각 토픽당 최대 5건의 최근 메시지를 가져옵니다.
- Kafka 연결 실패 시 오류가 UI에 표시되며 앱이 중단되지 않습니다.
- 토픽 초기화 후에는 consumer 재구독 시간 때문에 5~10초 뒤 재실행하는 것을 권장합니다.

## 다음 단계 예정

- Kafka 메시지와 paymentId를 더 정확히 매칭하는 trace 개선
- 필요 시 demo trace topic 추가
