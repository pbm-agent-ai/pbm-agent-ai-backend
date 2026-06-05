package com.pbm.payment.repository;

import com.pbm.payment.domain.UserWallet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * UserWallet JPA 레포지토리.
 * <p>
 * userId → walletAddress 매핑을 조회/저장한다.
 */
public interface UserWalletRepository extends JpaRepository<UserWallet, Long> {

    /**
     * userId로 지갑 정보를 조회한다.
     *
     * @param userId 사용자 식별자
     * @return UserWallet Optional (지갑이 없으면 empty)
     */
    Optional<UserWallet> findByUserId(Long userId);

    /**
     * userId에 해당하는 지갑이 존재하는지 확인한다.
     *
     * @param userId 사용자 식별자
     * @return 지갑 존재 여부
     */
    boolean existsByUserId(Long userId);
}
