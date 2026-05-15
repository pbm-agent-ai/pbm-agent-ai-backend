"""Gemini Flash로 AliExpress 자연어 파싱을 빠르게 검증하는 테스트 스크립트.

역할:
- .env 에서 GEMINI_API_KEY 를 읽어 Gemini 모델을 호출한다.
- command-service의 기존 GPT system/user prompt를 그대로 읽어 동일 조건으로 Gemini를 호출한다.
- 특정 문장에서 productCategory 를 ELECTRONICS 로 추론하는지 빠르게 확인한다.

사용 예시:
    python3 scripts/test_gemini_aliexpress_parse.py
    python3 scripts/test_gemini_aliexpress_parse.py --model gemini-2.5-flash
    python3 scripts/test_gemini_aliexpress_parse.py --text "알리익스프레스에서 QCY T13 PRO 블랙 5만원 이하로 가격 확인해줘"
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path


DEFAULT_MODEL = "gemini-2.5-flash"
DEFAULT_TEXT = "알리익스프레스에서 QCY T13 PRO 블랙 5만원 이하로 가격 확인해줘"

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
    """Gemini 응답이 코드 펜스로 감싸졌을 때 JSON 본문만 추출한다."""
    stripped = raw_text.strip()
    if stripped.startswith("```"):
        match = re.search(r"```(?:json)?\s*(.*?)\s*```", stripped, re.DOTALL)
        if match:
            return match.group(1).strip()
    return stripped


def main() -> int:
    parser = argparse.ArgumentParser(description="Gemini Flash AliExpress 파싱 테스트")
    parser.add_argument("--model", default=DEFAULT_MODEL, help="호출할 Gemini 모델명")
    parser.add_argument("--text", default=DEFAULT_TEXT, help="테스트할 자연어 명령")
    parser.add_argument("--user-id", type=int, default=0, help="GPT user prompt와 동일한 userId")
    args = parser.parse_args()

    project_root = Path(__file__).resolve().parents[1]
    load_dotenv(project_root / ".env")

    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        print("GEMINI_API_KEY 가 설정되지 않았습니다.", file=sys.stderr)
        return 1

    try:
        from google import genai
        from google.genai import types
    except ImportError:
        print(
            "google-genai 패키지가 없습니다. 먼저 `pip3 install google-genai` 를 실행하세요.",
            file=sys.stderr,
        )
        return 2

    client = genai.Client(api_key=api_key)
    system_prompt = build_system_prompt(project_root)
    user_prompt = build_user_prompt(args.text, args.user_id)

    response = client.models.generate_content(
        model=args.model,
        contents=user_prompt,
        config=types.GenerateContentConfig(system_instruction=system_prompt),
    )

    text = getattr(response, "text", None)
    if not text:
        print("Gemini 응답에 text 가 없습니다.", file=sys.stderr)
        print(response)
        return 3

    print("=== Raw Response ===")
    print(text)

    json_text = extract_json_text(text)

    print("\n=== Parsed JSON ===")
    try:
        parsed = json.loads(json_text)
    except json.JSONDecodeError as exc:
        print(f"JSON 파싱 실패: {exc}", file=sys.stderr)
        return 4

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
