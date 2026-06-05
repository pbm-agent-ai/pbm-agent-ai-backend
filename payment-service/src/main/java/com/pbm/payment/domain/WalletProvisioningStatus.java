package com.pbm.payment.domain;

/**
 * PBM 스마트 지갑 생성 진행상태 열거형.
 * <p>
 * WalletService.deployAndSaveWallet()의 각 단계를 추적하기 위한 상태값.
 * NOT_STARTED(0)부터 SAVED(6)까지 7단계로 구성되며, FAILED는 예외 발생 시 사용된다.
 * 단계 순서: 키쌍 생성 → EOA ETH 지원 → 스마트 지갑 배포 → DB 저장
 */
public enum WalletProvisioningStatus {

    NOT_STARTED("지갑 생성 준비 중", 0),
    KEYPAIR_CREATED("사용자 지갑 주소 생성 완료", 1),
    FUNDING_USER_EOA("마스터 지갑에서 ETH 지급 중", 2),
    USER_EOA_FUNDED("마스터 지갑에서 ETH 지급 완료", 3),
    CREATING_SMART_WALLET("스마트 지갑 배포 중", 4),
    SMART_WALLET_CREATED("스마트 지갑 배포 완료", 5),
    SAVED("지갑 생성 완료", 6),
    FAILED("실패", -1);

    private final String label;
    private final int step;

    WalletProvisioningStatus(String label, int step) {
        this.label = label;
        this.step = step;
    }

    /** 한글 레이블 */
    public String getLabel() {
        return label;
    }

    /** 단계 번호 (0부터 시작, FAILED는 -1) */
    public int getStep() {
        return step;
    }
}
