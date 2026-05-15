"""Kafka 토픽 감시 모듈 (데모용)

- 로컬 Kafka 브로커에서 지정한 토픽의 최근 메시지를 가져옵니다.
- 프로덕션 코드가 아니므로 단순/경량 구현을 지향합니다.
- 연결 실패 시 예외를 던지지 않고 빈 결과를 반환합니다.
"""

from __future__ import annotations

import os
import time
import uuid
from typing import Any

KAFKA_BOOTSTRAP_SERVERS_DEFAULT = "localhost:9092"


def get_bootstrap_servers() -> str:
    """환경변수 KAFKA_BOOTSTRAP_SERVERS 또는 기본값 반환"""
    return os.getenv("KAFKA_BOOTSTRAP_SERVERS", KAFKA_BOOTSTRAP_SERVERS_DEFAULT)


def reset_topics(topics: list[str]) -> None:
    """지정한 토픽을 삭제 후 재생성하여 메시지를 초기화한다."""
    from kafka.admin import KafkaAdminClient, NewTopic  # type: ignore[import-untyped]
    from kafka.errors import TopicAlreadyExistsError  # type: ignore[import-untyped]

    admin = KafkaAdminClient(bootstrap_servers=get_bootstrap_servers())
    try:
        existing = set(admin.list_topics())
        targets = [t for t in topics if t in existing]
        if targets:
            admin.delete_topics(targets)
            # 삭제 완료 대기 (최대 10초)
            for _ in range(20):
                time.sleep(0.5)
                if not any(t in admin.list_topics() for t in targets):
                    break

        new_topics = [NewTopic(name=t, num_partitions=1, replication_factor=1) for t in topics]
        try:
            admin.create_topics(new_topics)
        except TopicAlreadyExistsError:
            for nt in new_topics:
                try:
                    admin.create_topics([nt])
                except TopicAlreadyExistsError:
                    pass
    finally:
        admin.close()


def _make_consumer(group_id: str, timeout_ms: int):
    """KafkaConsumer 인스턴스 생성. NoBrokersAvailable 시 최대 2회 재시도."""
    from kafka import KafkaConsumer  # type: ignore[import-untyped]
    from kafka.errors import NoBrokersAvailable  # type: ignore[import-untyped]

    for attempt in range(3):
        try:
            return KafkaConsumer(
                bootstrap_servers=get_bootstrap_servers(),
                group_id=group_id,
                auto_offset_reset="latest",
                enable_auto_commit=False,
                consumer_timeout_ms=timeout_ms,
                value_deserializer=lambda v: v.decode("utf-8", errors="replace") if v else None,
                key_deserializer=lambda k: k.decode("utf-8", errors="replace") if k else None,
            )
        except NoBrokersAvailable:
            if attempt == 2:
                raise
            time.sleep(0.3)


def fetch_topics_batch(
    topics: list[str],
    max_messages: int = 5,
    timeout_ms: int = 2000,
) -> dict[str, list[dict[str, Any]]]:
    """여러 토픽의 최근 메시지를 컨슈머 1개로 한 번에 가져옵니다.

    Returns:
        topic -> 메시지 리스트 dict. 연결 실패 또는 토픽 없으면 빈 리스트.
    """
    result: dict[str, list[dict[str, Any]]] = {t: [] for t in topics}

    try:
        from kafka import TopicPartition  # type: ignore[import-untyped]
        from kafka.errors import KafkaError  # type: ignore[import-untyped]
    except ImportError:
        return result

    consumer = None
    try:
        group_id = f"streamlit-demo-{uuid.uuid4().hex[:8]}"
        consumer = _make_consumer(group_id, timeout_ms)

        # 메타데이터 강제 로드: 짧은 poll로 브로커로부터 클러스터 정보를 받아온다.
        # assign() 전에 partitions_for_topic()을 호출하면 None이 나오는 경우가 있어서 필요함.
        consumer.poll(timeout_ms=300)

        # 모든 토픽의 파티션을 한 번에 assign
        all_tps: list[TopicPartition] = []
        topic_tps: dict[str, list[TopicPartition]] = {}
        for topic in topics:
            partitions_meta = consumer.partitions_for_topic(topic)
            if not partitions_meta:
                continue
            tps = [TopicPartition(topic, p) for p in sorted(partitions_meta)]
            topic_tps[topic] = tps
            all_tps.extend(tps)

        if not all_tps:
            return result

        consumer.assign(all_tps)

        # 각 파티션을 end-N 위치로 seek
        for tps in topic_tps.values():
            for tp in tps:
                end_offset = consumer.end_offsets([tp]).get(tp, 0)
                begin_offset = consumer.beginning_offsets([tp]).get(tp, 0)
                consumer.seek(tp, max(end_offset - max_messages, begin_offset))

        # 남은 타임아웃 안에서 메시지 수집 (메타데이터 poll에 300ms 소모했으므로 차감)
        remaining_ms = max(timeout_ms - 300, 200)
        start = time.monotonic()
        while True:
            elapsed_ms = (time.monotonic() - start) * 1000
            poll_ms = max(int(remaining_ms - elapsed_ms), 100)
            if poll_ms <= 0:
                break

            records = consumer.poll(timeout_ms=poll_ms, max_records=max_messages * len(topic_tps))
            if not records:
                break

            for tp, batch in records.items():
                msgs = result[tp.topic]
                for record in batch:
                    if len(msgs) < max_messages:
                        msgs.append({
                            "topic": record.topic,
                            "partition": record.partition,
                            "offset": record.offset,
                            "key": record.key,
                            "value": record.value,
                            "timestamp": record.timestamp,
                        })

            # 모든 토픽이 max_messages 채우면 조기 종료
            if all(len(result[t]) >= max_messages for t in topic_tps):
                break

        return result

    except Exception:
        return result
    finally:
        if consumer is not None:
            try:
                consumer.close(autocommit=False)
            except Exception:
                pass


def fetch_recent_messages(
    topic: str,
    max_messages: int = 5,
    timeout_ms: int = 2000,
) -> list[dict[str, Any]]:
    """단일 토픽 조회. fetch_topics_batch의 래퍼."""
    batch = fetch_topics_batch([topic], max_messages=max_messages, timeout_ms=timeout_ms)
    return batch.get(topic, [])
