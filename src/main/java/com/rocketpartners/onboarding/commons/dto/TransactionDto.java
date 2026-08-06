package com.rocketpartners.onboarding.commons.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Wire form of a POS transaction, sent to the discount engine on {@code POST /discounts/calculate}.
 *
 * <p>The engine's contract is: given these items and this subtotal, what discounts apply?
 * Internal POS state (lifecycle state, tender type, cash tendered, previously-applied discounts,
 * tax rate) is deliberately omitted from the wire form — the engine does not need it, and
 * sending less keeps the contract narrow.</p>
 *
 * <p>Kept separate from the domain {@code Transaction} so the wire contract can evolve
 * independently of the aggregate. Mapping between {@code Transaction} and this DTO belongs
 * in the POS-side client that calls the engine, not in {@code commons}.</p>
 *
 * <p>This class carries no framework annotations. Jackson serializes and deserializes it via
 * property names on the Lombok-generated accessors.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionDto {

    /** Stable identifier for the transaction. */
    private String transactionId;

    /** When the transaction was opened on the POS. */
    private Instant createdAt;

    /** Non-voided line items on this transaction. */
    private List<LineItemDto> lineItems;

    /** Pre-tax, pre-discount total. */
    private BigDecimal subtotal;
}
