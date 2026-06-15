package com.pbm.notification.service;

import com.pbm.notification.dto.event.PaymentResultEventPayload;
import com.pbm.notification.dto.event.PriceAlertEventPayload;
import org.springframework.stereotype.Component;

/**
 * 알림 메시지 포맷팅 전담 컴포넌트.
 *
 * 역할: 다양한 이벤트 타입별로 사용자에게 보여줄 알림 메시지 문자열을 생성한다.
 *       텔레그램/이메일/콘솔 등 모든 채널에 공통으로 사용할 수 있는 plain text 메시지를 반환한다.
 * 동작:
 *   - 가격 알림(조건 충족, 자동결제 시작) 메시지 포맷팅
 *   - 결제 알림(완료, 실패) 메시지 포맷팅
 *   - 이메일 제목 포맷팅
 * 설계 의도: 메시지 포맷팅 로직을 NotificationService로부터 분리하여 단일 책임을 준수한다.
 *           새로운 알림 타입이 추가될 때 이 컴포넌트만 확장하면 된다.
 * 연관: NotificationService(호출자), PriceAlertEventPayload, PaymentResultEventPayload.
 */
@Component
public class NotificationMessageFormatter {

    /**
     * 모니터링 조건 충족 알림 메시지를 포맷팅한다.
     * 사용자가 설정한 목표 가격 이하로 상품 가격이 내려갔을 때 전송된다.
     *
     * @param payload 가격 알림 페이로드 (상품명, 현재가, 목표가, URL)
     * @return 포맷팅된 알림 메시지 문자열
     */
    public String formatConditionMet(PriceAlertEventPayload payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 조건 충족 알림\n\n");
        sb.append("상품명: ").append(payload.productName()).append("\n");
        sb.append("현재 가격: ").append(formatPrice(payload.currentPrice())).append("원\n");
        sb.append("목표 가격: ").append(formatPrice(payload.targetPrice())).append("원 이하\n");
        if (payload.productUrl() != null && !payload.productUrl().isBlank()) {
            sb.append("\n🔗 상품 바로가기: ").append(payload.productUrl());
        }
        return sb.toString();
    }

    /**
     * 자동결제 시작 알림 메시지를 포맷팅한다.
     * 조건 충족 후 시스템이 자동으로 결제를 진행하기 시작할 때 전송된다.
     *
     * @param payload 가격 알림 페이로드 (상품명, 현재가, 목표가)
     * @return 포맷팅된 알림 메시지 문자열
     */
    public String formatAutoPaymentStart(PriceAlertEventPayload payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("🛒 자동결제 시작 알림\n\n");
        sb.append("상품명: ").append(payload.productName()).append("\n");
        sb.append("현재 가격: ").append(formatPrice(payload.currentPrice())).append("원\n");
        sb.append("목표 가격: ").append(formatPrice(payload.targetPrice())).append("원 이하 감지\n");
        sb.append("\n✅ 조건이 충족되어 자동결제가 진행 중입니다.");
        return sb.toString();
    }

    /**
     * 결제 실패 알림 메시지를 포맷팅한다.
     * 자동결제 시도 중 오류가 발생하여 결제가 완료되지 못했을 때 전송된다.
     *
     * @param payload 결제 결과 페이로드 (금액, 실패 사유)
     * @return 포맷팅된 알림 메시지 문자열
     */
    public String formatPaymentFailed(PaymentResultEventPayload payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("❌ 결제 실패 알림\n\n");
        sb.append("결제금액: ").append(formatPrice(payload.amount())).append("원\n");
        sb.append("실패 사유: ").append(payload.message()).append("\n");
        sb.append("\n⚠️ 결제를 완료하지 못했습니다. 다시 시도해주세요.");
        return sb.toString();
    }

    /**
     * 결제 완료 알림 메시지를 포맷팅한다.
     * 자동결제가 성공적으로 완료되었을 때 전송된다.
     *
     * @param payload 결제 결과 페이로드 (금액, 수수료, 잔액)
     * @return 포맷팅된 알림 메시지 문자열
     */
    public String formatPaymentCompleted(PaymentResultEventPayload payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("✅ 결제 완료 알림\n\n");
        sb.append("결제금액: ").append(formatPrice(payload.amount())).append("원\n");
        if (payload.fee() != null) {
            sb.append("수수료: ").append(formatPrice(payload.fee())).append("원\n");
        }
        if (payload.remainingBalance() != null) {
            sb.append("잔액: ").append(formatPrice(payload.remainingBalance())).append("원\n");
        }
        sb.append("\n🎉 결제가 성공적으로 완료되었습니다!");
        return sb.toString();
    }

    /**
     * 이메일 제목을 생성한다.
     * 알림 타입에 따라 적절한 접두사와 상품명을 조합한 제목을 반환한다.
     *
     * @param type        알림 타입 (PRICE_CONDITION_MET, AUTO_PAYMENT_START, PAYMENT_FAILED, PAYMENT_COMPLETED)
     * @param productName 상품명 (결제 알림의 경우 null 전달 가능)
     * @return 이메일 제목 문자열
     */
    public String formatSubject(String type, String productName) {
        return switch (type) {
            case "PRICE_CONDITION_MET" -> "[Custos] " + productName + " 조건 충족!";
            case "AUTO_PAYMENT_START" -> "[Custos] " + productName + " 자동결제 시작";
            case "PAYMENT_FAILED" -> "[Custos] 결제 실패";
            case "PAYMENT_COMPLETED" -> "[Custos] 결제 완료";
            default -> "[Custos] 알림";
        };
    }

    /**
     * 가격(정수)을 읽기 쉬운 포맷으로 변환한다.
     * 예: 1200000 → "1,200,000"
     *
     * @param price 포맷팅할 가격 (null 허용, null이면 "0" 반환)
     * @return 천 단위 콤마가 포함된 가격 문자열
     */
    private String formatPrice(Integer price) {
        if (price == null) return "0";
        return String.format("%,d", price);
    }
}
