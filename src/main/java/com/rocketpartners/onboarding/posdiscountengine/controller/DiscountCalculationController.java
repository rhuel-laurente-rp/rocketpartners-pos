package com.rocketpartners.onboarding.posdiscountengine.controller;

import com.rocketpartners.onboarding.commons.dto.DiscountResponseDto;
import com.rocketpartners.onboarding.commons.dto.TransactionDto;
import com.rocketpartners.onboarding.posdiscountengine.service.DiscountService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The discount-calculation endpoint. Takes a {@link TransactionDto} (line items plus the cashier's
 * selected eligibility codes) and returns the discounts that apply and their total. All computation
 * lives in {@link DiscountService}; this class is just the HTTP seam.
 */
@RestController
public class DiscountCalculationController {

    private final DiscountService discountService;

    public DiscountCalculationController(DiscountService discountService) {
        this.discountService = discountService;
    }

    @PostMapping("/discounts/calculate")
    public DiscountResponseDto calculate(@RequestBody TransactionDto request) {
        return discountService.calculate(request);
    }
}
