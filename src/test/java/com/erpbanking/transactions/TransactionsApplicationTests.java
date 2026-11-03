package com.erpbanking.transactions;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.erpbanking.transactions.config.BearerTokenRelayInterceptor;
import com.erpbanking.transactions.service.client.CreditScoreClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class TransactionsApplicationTests {

    @Autowired ApplicationContext ctx;
    @Autowired RestTemplate creditScoreRestTemplate;
    @MockBean CreditScoreClient creditScoreClient;

    @Test
    void contextLoads() {
        assertThat(ctx).isNotNull();
    }

    @Test
    void rabbitListenersRetryWithBackoffInsteadOfRequeueingForever() {
        SimpleRabbitListenerContainerFactory factory = ctx.getBean(SimpleRabbitListenerContainerFactory.class);
        assertThat(factory.getAdviceChain()).hasSize(1);
        assertThat(factory.getAdviceChain()[0]).isInstanceOf(RetryOperationsInterceptor.class);
    }

    @Test
    void creditScoreRestTemplateRelaysTheUserToken() {
        assertThat(creditScoreRestTemplate.getInterceptors())
                .hasAtLeastOneElementOfType(BearerTokenRelayInterceptor.class);
    }
}
