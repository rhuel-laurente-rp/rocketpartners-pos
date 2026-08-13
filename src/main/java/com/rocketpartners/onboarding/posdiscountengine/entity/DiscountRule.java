package com.rocketpartners.onboarding.posdiscountengine.entity;

import com.rocketpartners.onboarding.commons.model.DiscountType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * One discount rule, stored in the engine's database. This is the <em>rule</em>, not the applied
 * {@link com.rocketpartners.onboarding.commons.model.Discount} the POS ultimately puts on a
 * receipt — evaluation (turning a rule plus a transaction into a concrete dollar reduction) is a
 * later branch.
 *
 * <p>The schema is deliberately a set of explicit, nullable columns rather than a JSON parameter
 * blob. This is a learning project: a reviewer should be able to read the table and see what every
 * rule does. The tradeoff is that some columns only apply to some {@link #discountType}s
 * (e.g. {@link #buyQuantity}/{@link #getQuantity} are for {@link DiscountType#PROMO} only, and are
 * null otherwise).</p>
 */
@Entity
@Table(name = "DISCOUNT_RULES")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class DiscountRule {

    /** Surrogate primary key, database-generated. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable business identifier the POS references a rule by (e.g. {@code SENIOR_20}). Unique. */
    @Column(nullable = false, unique = true)
    private String code;

    /** Human-readable text for the receipt and the cashier dialog (e.g. "Senior Citizen Discount 20%"). */
    @Column(nullable = false)
    private String description;

    /** Whether this is an eligibility discount or a promotion. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DiscountCategory category;

    /** Whether the rule targets the whole transaction or a single UPC. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TargetType targetType;

    /** The UPC when {@link #targetType} is {@link TargetType#UPC}; null for transaction-level rules. */
    @Column
    private String targetValue;

    /** The kind of reduction — determines how {@link #amount} is interpreted. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DiscountType discountType;

    /**
     * The rule's monetary parameter: the percent for {@link DiscountType#PERCENT_OFF}, the flat
     * dollar amount for {@link DiscountType#FIXED_AMOUNT_OFF}, and null for {@link DiscountType#PROMO}
     * (whose reduction is computed from {@link #buyQuantity}/{@link #getQuantity}). Money is always
     * {@link BigDecimal}, never {@code double}.
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    /** For {@link DiscountType#PROMO} (buy-N-get-M): the "buy" quantity. Null otherwise. */
    @Column
    private Integer buyQuantity;

    /** For {@link DiscountType#PROMO} (buy-N-get-M): the "get" (free) quantity. Null otherwise. */
    @Column
    private Integer getQuantity;

    /** Application order; lower priorities apply first. */
    @Column(nullable = false)
    private int priority;

    /**
     * Optional grouping tag. Rules sharing a group are mutually exclusive — at most one applies
     * (e.g. all customer-eligibility discounts share {@code CUSTOMER_ELIGIBILITY}). Null means the
     * rule stacks freely.
     */
    @Column
    private String exclusivityGroup;

    /** Whether the rule is currently in effect. Inactive rules are stored but never offered. */
    @Column(nullable = false)
    private boolean active;

    /**
     * Whether this rule's {@link #discountType}/{@link #targetType} pairing is one the engine can
     * actually evaluate. Exactly three combinations are supported today:
     * <ul>
     *   <li>{@link DiscountType#PROMO} on {@link TargetType#UPC} (buy-N-get-M),</li>
     *   <li>{@link DiscountType#PERCENT_OFF} on {@link TargetType#TRANSACTION},</li>
     *   <li>{@link DiscountType#FIXED_AMOUNT_OFF} on {@link TargetType#TRANSACTION}.</li>
     * </ul>
     * Anything else (e.g. {@code PROMO/TRANSACTION}, {@code PERCENT_OFF/UPC}) has no evaluation path
     * and would silently apply no discount, so the seed loader rejects it at startup. Colocated here
     * so the loader and {@code DiscountService} share one definition of "supported".
     */
    public boolean isSupportedCombination() {
        if (discountType == null || targetType == null) {
            return false;
        }
        return switch (discountType) {
            case PROMO -> targetType == TargetType.UPC;
            case PERCENT_OFF, FIXED_AMOUNT_OFF -> targetType == TargetType.TRANSACTION;
        };
    }
}
