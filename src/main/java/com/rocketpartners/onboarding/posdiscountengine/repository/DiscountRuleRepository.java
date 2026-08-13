package com.rocketpartners.onboarding.posdiscountengine.repository;

import com.rocketpartners.onboarding.posdiscountengine.entity.DiscountCategory;
import com.rocketpartners.onboarding.posdiscountengine.entity.DiscountRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository over {@link DiscountRule}. The derived-query finders below are what
 * keep the POS dialog data-driven: it asks the engine "which active eligibility rules exist?"
 * rather than hard-coding "Senior" and "Veteran".
 */
@Repository
public interface DiscountRuleRepository extends JpaRepository<DiscountRule, Long> {

    /** Active rules of a given category, in application order (lower {@code priority} first). */
    List<DiscountRule> findByCategoryAndActiveTrueOrderByPriorityAsc(DiscountCategory category);

    /** Active rules targeting a specific UPC (there may be more than one). */
    List<DiscountRule> findByTargetValueAndActiveTrue(String targetValue);

    /** Lookup by the business code, used by the seed loader to stay idempotent across restarts. */
    Optional<DiscountRule> findByCode(String code);
}
