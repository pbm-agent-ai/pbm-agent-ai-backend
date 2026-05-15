package com.pbm.price.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@Entity
@Table(
        name = "category_nodes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_category_nodes_platform_source_category_id",
                columnNames = {"platform", "source_category_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CategoryNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 카테고리 노드가 어느 플랫폼에 속하는지 구분
    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 30)
    private Platform platform;

    // 외부 플랫폼이 내려준 원본 카테고리 ID
    @Column(name = "source_category_id", nullable = false, length = 120)
    private String sourceCategoryId;

    // 외부 플랫폼이 내려준 원본 카테고리명
    @Column(name = "source_category_name", nullable = false, length = 255)
    private String sourceCategoryName;

    // 부모 카테고리의 원본 ID
    // 최상위 카테고리면 null or "0"을 저장
    @Column(name = "parent_source_category_id", length = 120)
    private String parentSourceCategoryId;

    // 루트 기준 현재 카테고리 깊이
    @Column(name = "depth", nullable = false)
    private Integer depth;

    // 부모를 따라 올라가 계산한 전체 경로
    @Column(name = "category_path", nullable = false, length = 500)
    private String categoryPath;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private CategoryNode(Platform platform,
                         String sourceCategoryId,
                         String sourceCategoryName,
                         String parentSourceCategoryId,
                         Integer depth,
                         String categoryPath){
        this.platform = platform;
        this.sourceCategoryId = sourceCategoryId;
        this.sourceCategoryName = sourceCategoryName;
        this.parentSourceCategoryId = parentSourceCategoryId;
        this.depth = depth;
        this.categoryPath = categoryPath;
    }

    /**
     * 새 카테고리 노드를 생성한다.
     *
     * @param platform               플랫폼 구분
     * @param sourceCategoryId       외부 플랫폼 카테고리 ID
     * @param sourceCategoryName     외부 플랫폼 카테고리명
     * @param parentSourceCategoryId 부모 카테고리 ID
     * @param depth                  카테고리 깊이
     * @param categoryPath           계산된 전체 경로
     * @return 생성된 CategoryNode 엔티티
     */
    public static CategoryNode create(Platform platform,
                                      String sourceCategoryId,
                                      String sourceCategoryName,
                                      String parentSourceCategoryId,
                                      Integer depth,
                                      String categoryPath){
        return new CategoryNode(
                platform,
                sourceCategoryId,
                sourceCategoryName,
                parentSourceCategoryId,
                depth,
                categoryPath
        );
    }
    /**
     * 같은 카테고리 노드가 다시 동기화되었을 때 최신 정보를 반영한다.
     *
     * @param sourceCategoryName     외부 플랫폼 카테고리명
     * @param parentSourceCategoryId 부모 카테고리 ID
     * @param depth                  카테고리 깊이
     * @param categoryPath           계산된 전체 경로
     */
    // DB를 바로 수정하는게 아니라 현재 메모리에 올라와 있는 객체 값만 변경
    // 이후 repository.save()가 호출될 때 DB UPDATE로 반영됨
    public void updateSnapshot(String sourceCategoryName,
                               String parentSourceCategoryId,
                               Integer depth,
                               String categoryPath){
        this.sourceCategoryName = sourceCategoryName;
        this.parentSourceCategoryId = parentSourceCategoryId;
        this.depth = depth;
        this.categoryPath = categoryPath;
    }

    // JPA 라이프 사이클 콜백 어노테이션으로 INSERT 직전에 새 엔티티를 처음 저장할 때 자동 호출된다.
    @PrePersist
    void prePersist(){
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    // UPDATE 직전 즉, 이미 있는 엔티티를 수정 저장할 때 자동 호출
    @PreUpdate
    void preUpdate(){
        this.updatedAt = Instant.now();
    }
}
