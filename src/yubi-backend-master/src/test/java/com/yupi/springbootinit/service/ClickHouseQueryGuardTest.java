package com.yupi.springbootinit.service;

import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.service.impl.ClickHouseQueryServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClickHouseQueryGuardTest {

    @Test
    void validateReadonlySelect_rejectsMutationSql() {
        ClickHouseQueryServiceImpl service = new ClickHouseQueryServiceImpl();

        assertThrows(BusinessException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "validateReadonlySelect", "drop table retail_sales"));
    }

    @Test
    void validateReadonlySelect_acceptsSelectSql() {
        ClickHouseQueryServiceImpl service = new ClickHouseQueryServiceImpl();

        assertDoesNotThrow(
                () -> ReflectionTestUtils.invokeMethod(service, "validateReadonlySelect", "select * from retail_sales limit 100"));
    }

    @Test
    void appendDefaultLimit_addsLimitWhenMissing() {
        ClickHouseQueryServiceImpl service = new ClickHouseQueryServiceImpl();

        String sql = ReflectionTestUtils.invokeMethod(service, "appendDefaultLimit", "select * from retail_sales");

        assertEquals("select * from retail_sales LIMIT 1000", sql);
    }
}
