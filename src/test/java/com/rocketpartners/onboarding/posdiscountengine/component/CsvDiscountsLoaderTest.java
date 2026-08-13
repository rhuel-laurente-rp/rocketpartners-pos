package com.rocketpartners.onboarding.posdiscountengine.component;

import com.rocketpartners.onboarding.commons.model.DiscountType;
import com.rocketpartners.onboarding.posdiscountengine.entity.DiscountCategory;
import com.rocketpartners.onboarding.posdiscountengine.entity.DiscountRule;
import com.rocketpartners.onboarding.posdiscountengine.entity.TargetType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvDiscountsLoaderTest {

    private static final String HEADER =
            "code,description,category,targetType,targetValue,discountType,amount,buyQuantity,getQuantity,priority,exclusivityGroup,active";

    private List<DiscountRule> parse(String csv) throws IOException {
        return CsvDiscountsLoader.parse(new StringReader(csv));
    }

    @Test
    void parsesAWellFormedTransactionPercentRow() throws IOException {
        String csv = HEADER + "\n" +
                "SENIOR_20,Senior Citizen Discount 20%,ELIGIBILITY,TRANSACTION,,PERCENT_OFF,20,,,2,CUSTOMER_ELIGIBILITY,true\n";

        List<DiscountRule> rules = parse(csv);

        assertEquals(1, rules.size());
        DiscountRule rule = rules.get(0);
        assertEquals("SENIOR_20", rule.getCode());
        assertEquals("Senior Citizen Discount 20%", rule.getDescription());
        assertEquals(DiscountCategory.ELIGIBILITY, rule.getCategory());
        assertEquals(TargetType.TRANSACTION, rule.getTargetType());
        assertNull(rule.getTargetValue());
        assertEquals(DiscountType.PERCENT_OFF, rule.getDiscountType());
        assertEquals(0, new BigDecimal("20").compareTo(rule.getAmount()));
        assertNull(rule.getBuyQuantity());
        assertNull(rule.getGetQuantity());
        assertEquals(2, rule.getPriority());
        assertEquals("CUSTOMER_ELIGIBILITY", rule.getExclusivityGroup());
        assertTrue(rule.isActive());
    }

    @Test
    void parsesAPromoRowWithBuyGetQuantities() throws IOException {
        String csv = HEADER + "\n" +
                "BOGO_MONSTER,Buy 2 Get 1 Free Monster Energy,PROMOTIONAL,UPC,070847811169,PROMO,,2,1,1,,true\n";

        DiscountRule rule = parse(csv).get(0);

        assertEquals(DiscountCategory.PROMOTIONAL, rule.getCategory());
        assertEquals(TargetType.UPC, rule.getTargetType());
        assertEquals("070847811169", rule.getTargetValue());
        assertEquals(DiscountType.PROMO, rule.getDiscountType());
        assertNull(rule.getAmount());
        assertEquals(2, rule.getBuyQuantity());
        assertEquals(1, rule.getGetQuantity());
        assertNull(rule.getExclusivityGroup());
    }

    @Test
    void skipsMalformedRowsButKeepsTheValidOnesAroundThem() throws IOException {
        String csv = HEADER + "\n" +
                // valid
                "SENIOR_20,Senior Citizen Discount 20%,ELIGIBILITY,TRANSACTION,,PERCENT_OFF,20,,,2,CUSTOMER_ELIGIBILITY,true\n" +
                // malformed: too few columns
                "BROKEN_ROW,Missing most columns,ELIGIBILITY\n" +
                // malformed: unknown enum value for discountType
                "BAD_ENUM,Bad Discount Type,ELIGIBILITY,TRANSACTION,,NOT_A_TYPE,5,,,2,CUSTOMER_ELIGIBILITY,true\n" +
                // malformed: unparseable amount
                "BAD_AMOUNT,Bad Amount,ELIGIBILITY,TRANSACTION,,FIXED_AMOUNT_OFF,not-a-number,,,2,CUSTOMER_ELIGIBILITY,true\n" +
                // valid
                "VETERAN_15,Veteran Discount 15%,ELIGIBILITY,TRANSACTION,,PERCENT_OFF,15,,,2,CUSTOMER_ELIGIBILITY,true\n";

        List<DiscountRule> rules = parse(csv);

        Map<String, DiscountRule> byCode = rules.stream()
                .collect(Collectors.toMap(DiscountRule::getCode, Function.identity()));
        assertEquals(2, rules.size(), "only the two valid rows should survive");
        assertTrue(byCode.containsKey("SENIOR_20"));
        assertTrue(byCode.containsKey("VETERAN_15"));
    }

    @Test
    void treatsFirstNonBlankLineAsHeaderAndSkipsBlankLines() throws IOException {
        String csv = "\n" + HEADER + "\n\n" +
                "EMPLOYEE_5,Employee Discount $5 Off,ELIGIBILITY,TRANSACTION,,FIXED_AMOUNT_OFF,5.00,,,2,CUSTOMER_ELIGIBILITY,true\n\n";

        List<DiscountRule> rules = parse(csv);

        assertEquals(1, rules.size());
        assertEquals("EMPLOYEE_5", rules.get(0).getCode());
        assertEquals(0, new BigDecimal("5.00").compareTo(rules.get(0).getAmount()));
    }
}
