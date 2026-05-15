import streamlit as st

from api_client import (
    fetch_payment_detail,
    fetch_payments,
    get_command_service_base_url,
    get_external_api_base_url,
    get_payment_service_base_url,
    parse_command,
    search_aliexpress,
    search_naver,
)
from kafka_watcher import fetch_topics_batch, get_bootstrap_servers, reset_topics


DEMO_TOPICS = ["price-topic", "payment-topic", "payment-result"]

FIELD_LABELS = [
    ("productName", "제품명"),
    ("platform", "플랫폼"),
    ("maxPrice", "최대 가격"),
    ("minPrice", "최소 가격"),
    ("currency", "통화"),
    ("color", "색상"),
    ("size", "사이즈"),
    ("brand", "브랜드"),
    ("model", "모델"),
    ("productCategory", "상품 카테고리"),
]
FIELD_LABEL_MAP = dict(FIELD_LABELS)

st.set_page_config(page_title="PBM Agent AI Demo", page_icon="🤖", layout="wide")


# ── 누락 정보 보완 모달 ───────────────────────────────────────────────────
@st.dialog("⚠️ 추가 정보 입력이 필요합니다")
def clarification_modal() -> None:
    parsed_data = (st.session_state.parse_response or {}).get("data") or {}
    parsed_cmd = parsed_data.get("parsedCommand") or {}
    missing = parsed_data.get("missingRequiredFields") or []
    ambiguous = parsed_data.get("ambiguousFields") or []
    all_incomplete = set(missing + ambiguous)

    for field_key, label in FIELD_LABELS:
        value = parsed_cmd.get(field_key)
        if field_key in all_incomplete:
            col_a, col_b = st.columns([1, 2])
            with col_a:
                st.write(f"**{label}**")
            with col_b:
                st.markdown("(빈 칸)")
                st.caption(f"⚠️ {label} 누락됨.")
        elif value is not None:
            st.write(f"**{label}:** {value}")

    if all_incomplete:
        st.divider()
        with st.form("clarification_form"):
            clarifications: dict[str, str] = {}
            for field_key in sorted(all_incomplete):
                label = FIELD_LABEL_MAP.get(field_key, field_key)
                clarifications[field_key] = st.text_input(
                    f"{label} 입력",
                    placeholder=f"{label}을 입력하세요",
                    key=f"clarification_{field_key}",
                )
            submitted = st.form_submit_button("보완 후 재실행", use_container_width=True)

        if submitted:
            additions = [
                f"{FIELD_LABEL_MAP.get(fk, fk)}: {val.strip()}"
                for fk, val in clarifications.items()
                if val.strip()
            ]
            new_cmd = st.session_state.last_command_text
            if additions:
                new_cmd += f", {', '.join(additions)}"
            try:
                resp = parse_command(st.session_state.last_user_id, new_cmd)
                st.session_state.parse_response = resp
                st.session_state.demo_steps["command"] = "done"
                new_data = resp.get("data") or {}
                if not new_data.get("needsClarification"):
                    st.session_state.demo_steps["price"] = "running"
                    st.session_state.show_clarification = False
                    _trigger_search(new_data)
                else:
                    st.session_state.show_clarification = True
                st.rerun()
            except Exception as exc:
                st.error(f"재실행 실패: {exc}")


def _trigger_search(parsed_data: dict) -> None:
    """파싱 성공 시 플랫폼에 맞는 외부 API 검색을 실행하고 세션에 저장한다."""
    cmd = parsed_data.get("parsedCommand") or {}
    keyword_parts = [
        cmd.get("productName") or "",
        cmd.get("color") or "",
        cmd.get("size") or "",
    ]
    keyword = " ".join(p for p in keyword_parts if p).strip()
    platform = cmd.get("platform")
    if not keyword or not platform:
        return
    try:
        if platform == "NAVER":
            st.session_state.search_result = search_naver(keyword)
            st.session_state.search_platform = "NAVER"
        elif platform == "ALIEXPRESS":
            st.session_state.search_result = search_aliexpress(keyword)
            st.session_state.search_platform = "ALIEXPRESS"
        st.session_state.search_keyword = keyword
    except Exception as exc:
        st.session_state.search_error = str(exc)


def init_demo_state() -> None:
    defaults = {
        "demo_steps": {
            "input": "pending",
            "command": "pending",
            "price": "pending",
            "payment_topic": "pending",
            "payment": "pending",
        },
        "parse_response": None,
        "parse_error": None,
        "payment_list_response": None,
        "payment_detail_response": None,
        "payment_lookup_error": None,
        "kafka_messages": {t: [] for t in DEMO_TOPICS},
        "kafka_error": None,
        "last_command_text": "",
        "last_user_id": 1,
        "show_clarification": False,
        "search_result": None,
        "search_platform": None,
        "search_keyword": None,
        "search_error": None,
    }
    for key, val in defaults.items():
        if key not in st.session_state:
            st.session_state[key] = val


def render_step(label: str, state: str) -> None:
    icons = {"pending": "⚪ 대기", "running": "🟡 진행중", "done": "🟢 완료", "error": "🔴 실패"}
    st.write(f"- **{label}**: {icons.get(state, state)}")


def refresh_payment_data(user_id: int) -> None:
    st.session_state.payment_lookup_error = None
    st.session_state.payment_list_response = None
    st.session_state.payment_detail_response = None
    try:
        resp = fetch_payments(user_id)
        st.session_state.payment_list_response = resp
        payments = resp.get("data") or []
        if payments:
            pid = payments[0].get("paymentId")
            if pid:
                st.session_state.payment_detail_response = fetch_payment_detail(pid)
    except Exception as exc:
        st.session_state.payment_lookup_error = str(exc)


def infer_step_states(topic_messages: dict) -> dict:
    # input/command는 parse 버튼 클릭 시 직접 설정하므로 Kafka에서 추론하지 않음
    steps: dict = {"price": "pending", "payment_topic": "pending", "payment": "pending"}
    if topic_messages.get("price-topic"):
        steps["price"] = "done"
    if topic_messages.get("payment-topic"):
        steps["payment_topic"] = "done"
    if topic_messages.get("payment-result"):
        steps["payment"] = "done"
        steps["payment_topic"] = "done"
    return steps


def merge_step_states(inferred: dict[str, str]) -> None:
    """단계 상태를 병합한다.

    원칙:
    - done 상태는 pending/running으로 되돌리지 않는다.
    - pending -> running/done, running -> done 은 허용한다.
    """
    for key, new_state in inferred.items():
        current_state = st.session_state.demo_steps.get(key, "pending")

        # 이미 완료된 단계는 더 낮은 상태로 되돌리지 않는다.
        if current_state == "done" and new_state in {"pending", "running"}:
            continue

        st.session_state.demo_steps[key] = new_state


# ── 초기화 ──────────────────────────────────────────────────────────────
init_demo_state()

# 모달 자동 오픈
if st.session_state.show_clarification and st.session_state.parse_response:
    clarification_modal()

st.title("PBM Agent AI 멘토링 데모")

with st.sidebar:
    st.subheader("데모 설명")
    st.write("- 자연어 명령 → command-service 파싱")
    st.write("- price-service 외부 API 조회")
    st.write("- payment-service 결제 처리")
    st.divider()
    st.caption(f"command: {get_command_service_base_url()}")
    st.caption(f"payment/gateway: {get_payment_service_base_url()}")
    st.caption(f"external-api: {get_external_api_base_url()}")
    st.caption(f"Kafka: {get_bootstrap_servers()}")

col1, col2 = st.columns([2, 3])

# ── 왼쪽 컬럼 ──────────────────────────────────────────────────────────
with col1:
    st.subheader("1. 사용자 입력")
    user_id = st.number_input("User ID", min_value=1, value=1, step=1)
    command_text = st.text_area(
        "자연어 명령",
        placeholder="예: QCY T13 PRO 블랙 3만원 이하 알리에서 사줘",
        height=120,
    )

    if st.button("데모 실행", use_container_width=True):
        st.session_state.update({
            "demo_steps": {
                "input": "done",
                "command": "running",
                "price": "pending",
                "payment_topic": "pending",
                "payment": "pending",
            },
            "parse_response": None,
            "parse_error": None,
            "payment_list_response": None,
            "payment_detail_response": None,
            "payment_lookup_error": None,
            "last_command_text": command_text,
            "last_user_id": int(user_id),
            "show_clarification": False,
            "search_result": None,
            "search_error": None,
        })
        try:
            resp = parse_command(int(user_id), command_text)
            st.session_state.parse_response = resp
            st.session_state.demo_steps["command"] = "done"
            parsed_data = resp.get("data") or {}
            if parsed_data.get("needsClarification"):
                st.session_state.show_clarification = True
            else:
                st.session_state.demo_steps["price"] = "running"
                _trigger_search(parsed_data)
        except Exception as exc:
            st.session_state.demo_steps["command"] = "error"
            st.session_state.parse_error = str(exc)
        st.rerun()

    st.write("### Parse 응답")
    if st.session_state.parse_response is not None:
        st.json(st.session_state.parse_response)
    elif st.session_state.parse_error is not None:
        st.error(st.session_state.parse_error)
    else:
        st.caption("아직 command-service 호출 전입니다.")

    # ── Kafka 토픽 ──────────────────────────────────────────────────────
    st.divider()
    st.subheader("3. Kafka 토픽")

    btn_col1, btn_col2 = st.columns(2)
    with btn_col1:
        if st.button("🔄 새로고침", use_container_width=True):
            st.session_state.kafka_error = None
            with st.spinner("조회 중..."):
                try:
                    all_msgs = fetch_topics_batch(DEMO_TOPICS, max_messages=5, timeout_ms=3000)
                except Exception as exc:
                    st.session_state.kafka_error = str(exc)
                    all_msgs = {t: [] for t in DEMO_TOPICS}
            st.session_state.kafka_messages = all_msgs
            inferred = infer_step_states(all_msgs)
            merge_step_states(inferred)
            if all_msgs.get("payment-result"):
                refresh_payment_data(int(user_id))

    with btn_col2:
        if st.button("🗑️ 토픽 초기화", use_container_width=True):
            with st.spinner("토픽 초기화 중..."):
                try:
                    reset_topics(DEMO_TOPICS)
                    st.session_state.kafka_messages = {t: [] for t in DEMO_TOPICS}
                    st.session_state.kafka_error = None
                    st.success("초기화 완료 (consumer 재구독을 위해 5~10초 후 다시 실행 권장)")
                except Exception as exc:
                    st.error(f"초기화 실패: {exc}")

    if st.session_state.kafka_error:
        st.warning(f"⚠️ {st.session_state.kafka_error}")

    for topic in DEMO_TOPICS:
        msgs = st.session_state.kafka_messages.get(topic, [])
        with st.expander(f"📨 {topic} ({len(msgs)}건)"):
            if msgs:
                for i, msg in enumerate(msgs):
                    st.caption(f"#{i+1} offset={msg.get('offset', '?')}")
                    st.json(msg)
            else:
                st.caption("메시지 없음")

    # ── payment-service ──────────────────────────────────────────────────
    st.divider()
    st.subheader("4. payment-service 조회")
    if st.button("💳 결제 결과 조회", use_container_width=True):
        refresh_payment_data(int(user_id))
    if st.session_state.payment_lookup_error:
        st.warning(f"결제 조회 실패: {st.session_state.payment_lookup_error}")
    st.write("#### 결제 목록")
    if st.session_state.payment_list_response is not None:
        st.json(st.session_state.payment_list_response)
    else:
        st.caption("아직 조회 전입니다.")
    st.write("#### 최신 결제 상세")
    if st.session_state.payment_detail_response is not None:
        st.json(st.session_state.payment_detail_response)
    else:
        st.caption("아직 조회 전입니다.")


# ── 오른쪽 컬럼 ────────────────────────────────────────────────────────
with col2:
    @st.fragment(run_every=4)
    def step_status_panel() -> None:
        st.subheader("2. 단계별 진행 상태")
        render_step("사용자 명령 입력", st.session_state.demo_steps["input"])
        render_step("command-service 파싱 및 토픽 발행", st.session_state.demo_steps["command"])
        render_step("price-service 외부 API 조회", st.session_state.demo_steps["price"])
        render_step("payment-topic 발행", st.session_state.demo_steps["payment_topic"])
        render_step("payment-service 처리", st.session_state.demo_steps["payment"])

        import time as _time
        st.caption(f"마지막 갱신: {_time.strftime('%H:%M:%S')}")

        # 단계 패널은 즉시 렌더링하고, 그 다음에 백그라운드성 폴링을 수행한다.
        # 이렇게 해야 Streamlit 첫 화면 진입 시 패널이 늦게 나타나는 현상을 줄일 수 있다.
        try:
            all_msgs = fetch_topics_batch(DEMO_TOPICS, max_messages=5, timeout_ms=700)
            changed = any(
                all_msgs.get(t) != st.session_state.kafka_messages.get(t)
                for t in DEMO_TOPICS
            )
            if changed:
                st.session_state.kafka_messages = all_msgs
                inferred = infer_step_states(all_msgs)
                merge_step_states(inferred)
                if all_msgs.get("payment-result"):
                    refresh_payment_data(st.session_state.last_user_id)
                    st.rerun(scope="app")
        except Exception:
            pass

    step_status_panel()

    # ── 외부 API 검색 결과 ───────────────────────────────────────────────
    st.divider()
    st.subheader("5. 외부 API 검색 결과")

    if st.session_state.search_error:
        st.error(f"검색 실패: {st.session_state.search_error}")
    elif st.session_state.search_result is not None:
        result = st.session_state.search_result
        platform = st.session_state.search_platform
        keyword = st.session_state.search_keyword
        items = result.get("items") or []
        total = result.get("total", 0)

        st.caption(f"🔍 **{platform}** 검색어: `{keyword}` — 총 {total}건 중 {len(items)}건 표시")

        if platform == "NAVER":
            for item in items:
                with st.container(border=True):
                    c1, c2 = st.columns([3, 1])
                    with c1:
                        title = item.get("title", "").replace("<b>", "**").replace("</b>", "**")
                        st.markdown(f"**{title}**")
                        st.caption(f"🏪 {item.get('mallName', '')}  |  브랜드: {item.get('brand', '-')}")
                    with c2:
                        lprice = item.get("lprice", "")
                        if lprice:
                            st.metric("최저가", f"₩{int(lprice):,}")
                    link = item.get("link", "")
                    if link:
                        st.markdown(f"[상품 링크]({link})")

        elif platform == "ALIEXPRESS":
            for item in items:
                with st.container(border=True):
                    c1, c2 = st.columns([3, 1])
                    with c1:
                        st.markdown(f"**{item.get('product_title', '')}**")
                        st.caption(f"🏪 {item.get('shop_name', '')}  |  평점: {item.get('evaluate_rate', '-')}  |  판매량: {item.get('lastest_volume', '-')}")
                    with c2:
                        price = item.get("target_sale_price") or item.get("sale_price", "")
                        if price:
                            st.metric("판매가", f"₩{float(price):,.0f}")
                    url = item.get("product_detail_url", "")
                    if url:
                        st.markdown(f"[상품 링크]({url})")
    else:
        st.caption("파싱 성공 후 자동으로 검색 결과가 표시됩니다.")

    st.divider()
    st.info("Kafka 토픽 새로고침 후 payment-result가 보이면 결제 조회 응답으로 paymentId / status / transactionHash까지 확인할 수 있습니다.")
