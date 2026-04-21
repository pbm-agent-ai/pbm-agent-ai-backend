from youtube_transcript_api import YouTubeTranscriptApi
from youtube_transcript_api.formatters import TextFormatter


def get_transcript(video_url: str, lang: str = "ko"):
    # URL에서 video_id 추출
    if "v=" in video_url:
        video_id = video_url.split("v=")[1].split("&")[0]
    elif "youtu.be/" in video_url:
        video_id = video_url.split("youtu.be/")[1].split("?")[0]
    else:
        video_id = video_url

    print(f"Video ID: {video_id}")

    try:
        ytt_api = YouTubeTranscriptApi()

        # 사용 가능한 자막 목록 확인
        transcript_list = ytt_api.list(video_id)

        # 한국어 자막 시도 → 영어 → 자동생성 순서
        try:
            transcript = transcript_list.find_transcript([lang])
            print(f"자막 언어: {lang}")
        except:
            try:
                transcript = transcript_list.find_transcript(["en"])
                print("한국어 자막 없음 → 영어 자막 사용")
            except:
                transcript = transcript_list.find_generated_transcript(["ko", "en"])
                print("자동 생성 자막 사용")

        # 자막 데이터 가져오기
        data = transcript.fetch()

        # 전체 텍스트 합치기
        formatter = TextFormatter()
        full_text = formatter.format_transcript(data)

        print("\n===== 자막 전체 텍스트 =====")
        print(full_text[:10000])
        print(f"\n총 자막 길이: {len(full_text)}자")
        print(f"총 자막 세그먼트 수: {len(data)}개")

        # 타임스탬프 포함 미리보기 (앞 5개)
        print("\n===== 타임스탬프 미리보기 =====")
        for item in data[:5]:
            minutes = int(item.start // 60)
            seconds = int(item.start % 60)
            print(f"[{minutes:02d}:{seconds:02d}] {item.text}")

        return full_text

    except Exception as e:
        print(f"오류 발생: {e}")
        return None


if __name__ == "__main__":
    url = "https://www.youtube.com/watch?v=UfOcIFZvWRY"
    result = get_transcript(url, lang="ko")
