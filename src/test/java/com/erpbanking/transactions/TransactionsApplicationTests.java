package com.erpbanking.transactions;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.erpbanking.transactions.service.client.CreditScoreClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class TransactionsApplicationTests {

    @Autowired ApplicationContext ctx;
    @MockBean CreditScoreClient creditScoreClient;

    @Test
    void contextLoads() {
        assertThat(ctx).isNotNull();
    }
}
