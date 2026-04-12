import requests

CLIENT_ID = "XSveac6TziI2nErUbc6Z"
CLIENT_SECRET = "NzJBZAI96V"


def search_shopping(keyword):
    url = "https://openapi.naver.com/v1/search/shop.json"  # 네이버 쇼핑에서 .json형식으로 받아오는 방식
    headers = {"X-Naver-Client-Id": CLIENT_ID, "X-Naver-Client-Secret": CLIENT_SECRET}
    # query: 무엇을 검색할지
    # display: 몇 개만 보여줄지 (기본 10개인데 5개만 요청)
    # sort: 어떤 순서로 정렬할지 (유사도 순)
    params = {"query": keyword, "display": 5, "sort": "sim"}

    # url, headers, params를 네이버 서버에 보내고 .GET 방식으로 요청을 함. 그냥 조회하는 API임.
    # 받아온 응답을 res 변수에 넣음.
    res = requests.get(url, headers=headers, params=params)

    if res.status_code == 200:
        # 네이버 서버에서 보내준 핵심 데이터들임. res.json은 무조건 리스트로 저장하는 것이 아니라 서버가 보내준 JSON 데이터의 원래 생김새에 맞춰서 파이썬이 알아들을 수 있는 자료형으로 자동 변환해주는 함수.
        # 서버에서 온 텍스트가 어떤 자료형으로 묶여 있느냐에 따라 자료형이 결정됨.
        data = res.json()
        for item in data["items"]:
            # b 태그 제거 후 출력
            clean_title = item["title"].replace("<b>", "").replace("</b>", "")

            # 가격, 쇼핑몰, 링크 변수에 담기
            price = item["lprice"]
            mall_name = item["mallName"]
            link = item["link"]

            print(f"[{clean_title}]")
            print(f" - 가격: {price}원")
            print(f" - 쇼핑몰: {mall_name}")
            print(f" - 쇼핑몰 링크: {link}\n")

    else:
        print(f"Error: {res.status_code}")


if __name__ == "__main__":
    search_shopping("네이버 쇼핑몰 유그린 보조베터리 10000mAh")
