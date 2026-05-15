package com.pbm.price.repository;

import com.pbm.price.domain.Product;
import com.pbm.price.domain.Platform;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Product 엔티티 조회/저장 레포지토리.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByPlatformAndExternalProductId(Platform platform, String externalProductId);
}
