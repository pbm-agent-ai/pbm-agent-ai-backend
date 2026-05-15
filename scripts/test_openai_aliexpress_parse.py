"""OpenAI GPT 모델로 AliExpress 자연어 파싱을 빠르게 검증하는 테스트 스크립트.

역할:
- .env 에서 OPENAI_API_KEY 를 읽어 OpenAI Chat Completions API를 호출한다.
- command-service의 기존 GPT system/user prompt를 그대로 재사용한다.
- gpt-5.4-mini, gpt-5.1 같은 모델이 동일 조건에서 어떤 JSON을 반환하는지 확인한다.

사용 예시:
    python3 scripts/test_openai_aliexpress_parse.py
    python3 scripts/test_openai_aliexpress_parse.py --model gpt-5.4-mini
    python3 scripts/test_openai_aliexpress_parse.py --model gpt-5.1
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path
from urllib import error, request


DEFAULT_MODEL = "gpt-5.4-mini"
DEFAULT_TEXT = "알리익스프레스에서 QCY T13 PRO 블랙 5만원 이하로 가격 확인해줘"
DEFAULT_BASE_URL = "https://api.openai.com/v1"

INTENTS = ["AUTO_PURCHASE", "PRICE_TRACK", "PRICE_CHECK"]
CATEGORIES = ["SHOES", "ELECTRONICS", "APPAREL", "UNKNOWN"]
PLATFORMS = ["NAVER", "ALIEXPRESS"]
PARSED_COMMAND_FIELDS = [
    "productCategory",
    "productName",
    "brand",
    "line",
    "model",
    "color",
    "size",
    "platform",
    "maxPrice",
    "minPrice",
    "currency",
]

COMMAND_PARSE_SCHEMA = {
    "type": "object",
    "properties": {
        "intent": {
            "type": ["string", "null"],
            "enum": ["AUTO_PURCHASE", "PRICE_TRACK", "PRICE_CHECK", None],
        },
        "parsedCommand": {
            "type": "object",
            "properties": {
                "productCategory": {
                    "type": ["string", "null"],
                    "enum": ["SHOES", "ELECTRONICS", "APPAREL", "UNKNOWN", None],
                },
                "productName": {"type": ["string", "null"]},
                "brand": {"type": ["string", "null"]},
                "line": {"type": ["string", "null"]},
                "model": {"type": ["string", "null"]},
                "color": {"type": ["string", "null"]},
                "size": {"type": ["string", "null"]},
                "platform": {
                    "type": ["string", "null"],
                    "enum": ["NAVER", "ALIEXPRESS", None],
                },
                "maxPrice": {"type": ["integer", "null"]},
                "minPrice": {"type": ["integer", "null"]},
                "currency": {"type": ["string", "null"]},
            },
            "required": [
                "productCategory",
                "productName",
                "brand",
                "line",
                "model",
                "color",
                "size",
                "platform",
                "maxPrice",
                "minPrice",
                "currency",
            ],
            "additionalProperties": False,
        },
        "confidence": {"type": "number"},
    },
    "required": ["intent", "parsedCommand", "confidence"],
    "additionalProperties": False,
}


def load_dotenv(dotenv_path: Path) -> None:
    """루트 .env 파일을 읽어 환경변수로 적재한다."""
    if not dotenv_path.exists():
        return

    for raw_line in dotenv_path.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        os.environ.setdefault(key, value)


def format_bullets(values: list[str]) -> str:
    """Java PromptBuilder와 동일하게 불릿 문자열을 만든다."""
    return "\n".join(f"   - {value}" for value in values)


def build_system_prompt(project_root: Path) -> str:
    """command-service의 기존 GPT system prompt 템플릿을 그대로 조합한다."""
    template_path = project_root / "command-service/src/main/resources/prompts/command-parser-system.txt"
    template = template_path.read_text()
    return (
        template
        .replace("{{intents}}", format_bullets(INTENTS))
        .replace("{{categories}}", format_bullets(CATEGORIES))
        .replace("{{platforms}}", format_bullets(PLATFORMS + ["null"]))
        .replace("{{parsedCommandFields}}", format_bullets(PARSED_COMMAND_FIELDS))
    )


def build_user_prompt(command_text: str, user_id: int = 0) -> str:
    """CommandParsePromptBuilder.buildUserPrompt()와 동일한 형식으로 user prompt를 만든다."""
    normalized_text = "" if command_text is None else command_text.strip()
    return f"""
                아래 사용자 명령을 파싱하라.

                userId: {user_id}
                commandText: {normalized_text}

                다시 한 번 강조한다.
                - JSON만 반환하라.
                - 추측하지 마라.
                - 애매하면 null을 사용하라.
                - intent/productCategory/platform은 허용 목록 밖의 값을 쓰지 마라.
                """.strip()


def extract_json_text(raw_text: str) -> str:
    """응답이 코드 펜스로 감싸졌을 때 JSON 본문만 추출한다."""
    stripped = raw_text.strip()
    if stripped.startswith("```"):
        match = re.search(r"```(?:json)?\s*(.*?)\s*```", stripped, re.DOTALL)
        if match:
            return match.group(1).strip()
    return stripped


def call_openai(base_url: str, api_key: str, model: str, system_prompt: str, user_prompt: str) -> str:
    """OpenAI Chat Completions API를 Structured Outputs 방식으로 호출한다."""
    endpoint = base_url.rstrip("/") + "/chat/completions"
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "temperature": 0.0,
        "response_format": {
            "type": "json_schema",
            "json_schema": {
                "name": "command_parse_response",
                "strict": True,
                "schema": COMMAND_PARSE_SCHEMA,
            },
        },
    }

    req = request.Request(
        endpoint,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )

    try:
        with request.urlopen(req, timeout=120) as response:
            response_body = response.read().decode("utf-8")
    except error.HTTPError as exc:
        error_body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"OpenAI HTTP {exc.code}: {error_body}") from exc
    except error.URLError as exc:
        raise RuntimeError(f"OpenAI 호출 실패: {exc}") from exc

    parsed_response = json.loads(response_body)
    choices = parsed_response.get("choices", [])
    if not choices:
        raise RuntimeError(f"choices 가 비어 있습니다: {response_body}")

    message = choices[0].get("message", {})
    content = message.get("content")
    if not content:
        raise RuntimeError(f"message.content 가 비어 있습니다: {response_body}")
    return content


def main() -> int:
    parser = argparse.ArgumentParser(description="OpenAI AliExpress 파싱 테스트")
    parser.add_argument("--model", default=DEFAULT_MODEL, help="호출할 OpenAI 모델명")
    parser.add_argument("--text", default=DEFAULT_TEXT, help="테스트할 자연어 명령")
    parser.add_argument("--user-id", type=int, default=0, help="GPT user prompt와 동일한 userId")
    args = parser.parse_args()

    project_root = Path(__file__).resolve().parents[1]
    load_dotenv(project_root / ".env")

    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        print("OPENAI_API_KEY 가 설정되지 않았습니다.", file=sys.stderr)
        return 1

    base_url = os.environ.get("OPENAI_BASE_URL", DEFAULT_BASE_URL)
    system_prompt = build_system_prompt(project_root)
    user_prompt = build_user_prompt(args.text, args.user_id)

    try:
        text = call_openai(base_url, api_key, args.model, system_prompt, user_prompt)
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        return 2

    print("=== Raw Response ===")
    print(text)

    json_text = extract_json_text(text)

    print("\n=== Parsed JSON ===")
    try:
        parsed = json.loads(json_text)
    except json.JSONDecodeError as exc:
        print(f"JSON 파싱 실패: {exc}", file=sys.stderr)
        return 3

    print(json.dumps(parsed, ensure_ascii=False, indent=2))

    parsed_command = parsed.get("parsedCommand", {})
    print("\n=== Key Fields ===")
    print(f"intent: {parsed.get('intent')}")
    print(f"productCategory: {parsed_command.get('productCategory')}")
    print(f"brand: {parsed_command.get('brand')}")
    print(f"model: {parsed_command.get('model')}")
    print(f"platform: {parsed_command.get('platform')}")
    print(f"confidence: {parsed.get('confidence')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
