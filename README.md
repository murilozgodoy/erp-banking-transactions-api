# ErpIa - Transactions API

Serviço responsável por contas, transações e contratos de crédito. Consome a Credit Score API antes de aprovar operações de crédito.
Documentação central do projeto em [erp-banking-front/docs](https://github.com/murilozgodoy/erp-banking-front/tree/main/docs).

## Requisitos

- JDK 21+ (funciona em Java 25)
- Maven 3.9+

## Executar

```bash
cp .env.example .env    # lido automaticamente em dev (spring.config.import)
mvn spring-boot:run
```

Porta padrão: 8080. H2 in-memory por padrão. Console H2 desativado.

Health: `GET http://localhost:8080/health` (e `GET /actuator/health`, usado pelo `HEALTHCHECK` da imagem).

## Docker

```bash
docker build -t transactions-api .
docker run --rm -p 8080:8080 --env-file .env transactions-api
```

Imagem `eclipse-temurin:21-jre-alpine`, usuário não-root (`app`, uid 10001), `HEALTHCHECK` em `/actuator/health` e
`JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"` (pode ser sobrescrito).
No compose do infra (container com `mem_limit` de 512 MB na EC2) ele é sobrescrito por
`-XX:MaxRAMPercentage=60 -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -Xss512k -XX:+UseSerialGC`, para
heap + metaspace + code cache caberem no limite sem OOM kill.
O CI publica `ghcr.io/murilozgodoy/erp-banking-transactions-api:latest` e `:<sha>` a cada push na `main` (job `publish`).
O stack completo (gateway, Postgres, RabbitMQ, Prometheus, Grafana) fica no repo
[`GabrielRosa1/erp-banking-infra`](https://github.com/GabrielRosa1/erp-banking-infra).

## Produção (atrás do API Gateway)

Uma EC2 (`3.235.6.136`) roda o `docker compose` do infra. Portas públicas: gateway nginx **8080**, Grafana 8081 e
Prometheus 8082. O serviço **não** é publicado diretamente: escuta na 8080 interna e é acessado pelo prefixo
`/transactions` do gateway (prefixo removido; o nginx envia `X-Forwarded-Prefix`). Ex.:
`http://3.235.6.136:8080/transactions/health`. Outras rotas do gateway: `/onboarding`, `/score` e `/gateway/health`.
O front (https://erp-banking-front.vercel.app) reescreve `/api/transactions/*` para
`http://3.235.6.136:8080/transactions/*`.

## Testes com cobertura

```bash
mvn verify
```

- Roda testes unit (Mockito) + integração (`@SpringBootTest` + MockMvc + H2)
- Gera relatório JaCoCo em `target/site/jacoco/index.html`
- **Falha o build se cobertura < 80%** (regra em `pom.xml`)

## Configuração

Em dev, as variáveis vêm do `.env` na raiz do projeto (opcional; ver `.env.example`).
Em produção, defina-as no ambiente do processo/container (têm precedência sobre o `.env`).

| Var | Padrão | Descrição |
|-----|--------|-----------|
| `SERVER_PORT` | 8080 | Porta HTTP |
| `AUTH0_DOMAIN` | — (obrigatória) | Domínio do tenant, sem `https://` |
| `AUTH0_AUDIENCE` | — (obrigatória) | Identifier da API no Auth0: `https://erp-banking-api` |
| `CORS_ORIGINS` | `http://localhost:5173` | Origens do frontend, separadas por vírgula |
| `CREDIT_SCORE_API_URL` | `http://localhost:8082` | URL do Score |
| `DATABASE_URL` | `jdbc:h2:mem:transactions;...` | JDBC URL |
| `DATABASE_USER` | sa | usuário |
| `DATABASE_PASSWORD` | (vazio) | senha |
| `DATABASE_DRIVER` | `org.h2.Driver` | driver (`org.postgresql.Driver` em produção) |
| `DDL_AUTO` | `update` | `spring.jpa.hibernate.ddl-auto`; as tabelas são criadas no startup |
| `RABBITMQ_URL` | (vazio) | Ex.: `amqp://erp:erp@rabbitmq:5672/`. Vazio = mensageria desligada (no-op) |
| `MESSAGING_ENABLED` | (vazio) | `true`/`false` força ligar/desligar; vazio = segue o `RABBITMQ_URL` |
| `AUTO_OPEN_ACCOUNT` | `false` | Consome `customer.created` e abre conta corrente automaticamente |

Em produção (compose do infra): `DATABASE_URL=jdbc:postgresql://postgres:5432/erp?currentSchema=transactions`. O schema
`transactions` é criado pelo init do Postgres e o Hibernate cria/atualiza as tabelas dentro dele.

Resiliência da chamada ao Score (`credit-score.*` no `application.yml`): timeout de conexão de 2 s e de leitura de 5 s,
2 novas tentativas com backoff exponencial em falhas transitórias, e circuit breaker que abre após 5 falhas seguidas e
fica 30 s aberto. Com o circuito aberto ou o Score fora do ar, `POST /credits` responde **503** com mensagem clara.
Propriedades (`CreditScoreProperties`, defaults em código): `connect-timeout-ms` (2000), `read-timeout-ms`
(usa `timeout-ms`, 5000), `max-retries` (2), `retry-backoff-ms` (200), `circuit-failure-threshold` (5) e
`circuit-open-duration-ms` (30000).

## Endpoints

| Método | Rota | Permissão | Descrição |
|--------|------|-----------|-----------|
| GET | `/health` | pública | Health check |
| GET | `/accounts?customerId=` | `read:accounts` | Lista contas (todas ou de um cliente) |
| POST | `/accounts` | `write:accounts` | Cria conta |
| GET | `/accounts/{id}` | `read:accounts` | Consulta conta |
| GET | `/accounts/{id}/transactions` | `read:accounts` | Extrato, com o saldo após cada lançamento |
| POST | `/transactions` | `write:accounts` | Registra CREDIT / DEBIT |
| GET | `/credits?customerId=` | `read:credits` | Lista pedidos de crédito |
| POST | `/credits` | `write:credits` | Solicita crédito (consulta o Score) |
| GET | `/credits/{id}` | `read:credits` | Consulta situação do crédito |
| GET | `/actuator/health`, `/actuator/info`, `/actuator/prometheus` | pública | Actuator (não exposto pelo gateway) |
| GET | `/swagger-ui/index.html`, `/v3/api-docs` | pública | Swagger / OpenAPI |

### Swagger / OpenAPI

Documentação interativa (springdoc) em `/swagger-ui/index.html` e JSON em `/v3/api-docs` — públicos; as rotas
continuam exigindo o Bearer token (botão **Authorize**). Atrás do API Gateway use
`http://3.235.6.136:8080/transactions/swagger-ui/index.html` (ou `localhost:8080` com o compose local): o `X-Forwarded-Prefix` enviado pelo nginx é aplicado pelo
`ForwardedHeaderFilter`, então as URLs geradas já saem com o prefixo `/transactions`.

## Observabilidade (Prometheus)

- `GET /actuator/prometheus` e `GET /actuator/health` são públicos. O gateway **não** os expõe; o Prometheus coleta
  pela rede interna.
- Todas as séries levam a tag `application="transactions-api"`. `http_server_requests_seconds` tem histograma (p95/p99).
- Métricas de negócio (nomes de contrato com o Grafana):

| Métrica | Labels | Quando |
|---------|--------|--------|
| `credit_decisions_total` | `status` = APROVADO, REVISAO, NEGADO | decisão de crédito confirmada (após o commit) |
| `transactions_created_total` | `tipo` = CREDIT, DEBIT | transação registrada (após o commit) |
| `events_published_total` | `routing_key`, `result` = ok, error | publicação no RabbitMQ |
| `events_consumed_total` | `routing_key`, `result` = ok, duplicate, invalid, error | consumo de `customer.created` e `customer.onboarding_submitted` |
| `seeder_runs_total` | `result` = ok, skipped, error | seeder do portal (skipped = cliente já tinha dados) |

Usa `micrometer-registry-prometheus-simpleclient`: o cliente Prometheus 1.x remove o sufixo `_created` dos nomes e
`transactions_created_total` viraria `transactions_total`.

## Mensageria (RabbitMQ)

Exchange `erp.events` (topic, durável). As mensagens são JSON persistentes com `event_id` (UUID, também no
`message_id`), `event_type` e `occurred_at` (ISO-8601 UTC). Os eventos são publicados **depois do commit**
(`@TransactionalEventListener`): o que sofreu rollback não sai. Falha no broker nunca quebra a requisição HTTP;
gera log e `events_published_total{result="error"}`.

| Routing key | Direção | Payload |
|-------------|---------|---------|
| `credit.decided` | publica | `credit_request_id, customer_id, valor_solicitado, prazo_meses, score, faixa_risco, status, motivo, taxa_juros` |
| `transaction.created` | publica | `transaction_id, account_id, customer_id, tipo, valor, saldo_apos` |
| `customer.created` | consome (publicado pelo Onboarding) (fila `transactions.customer-created`, com `AUTO_OPEN_ACCOUNT=true`) | `customer_id, nome, cpf, email` |
| `customer.onboarding_submitted` | consome (publicado pelo Onboarding) (fila `transactions.onboarding-submitted`, sempre que a mensageria está ligada) | `customer_id, auth0_sub, segmento, empresa, respostas, observacoes` |

O consumo de `customer.created` abre uma conta corrente com saldo zero e é idempotente: se o cliente já tem conta, a
mensagem é ignorada. Payload sem `customer_id` válido é descartado sem requeue. Fila e binding são declarados pelo
próprio serviço.

O consumo de `customer.onboarding_submitted` (`portal/seed/PortalSeeder`) popula o portal ErpIa do cliente PJ numa
transação: abre a conta corrente se não existir (PJ não recebe `customer.created`) e gera dados determinísticos
(semente = `customer_id`) a partir das `respostas`: 120 dias de vendas, recebíveis de cartão (de 30 dias atrás até
90 dias à frente), contas a pagar (30 dias pagos, 60 à frente e 2 ou 3 atrasadas), estoque (quando `possui_estoque`)
e o extrato dos últimos 30 dias, que fecha no saldo da conta. Idempotente: cliente que já tem vendas é ignorado.

Classes: `events/` (eventos de domínio e `DomainEventListener`), `messaging/` (`EventPublisher` com implementações
Rabbit e no-op escolhidas por `MessagingEnabledCondition`, e `CustomerCreatedListener`), `metrics/BusinessMetrics`.

## Padrões de projeto aplicados

- **Singleton**: `RestClientConfig` provê o `RestTemplate` como bean singleton do Spring (`@Bean`)
- **Repository (Spring Data JPA)**: `AccountRepository`, `TransactionRepository`, `CreditRequestRepository`, `ContractRepository`
- **Observer**: eventos de domínio do Spring (`ApplicationEventPublisher` + `@TransactionalEventListener`) separam a
  regra de negócio das métricas e da mensageria
- **Strategy**: `EventPublisher` (RabbitMQ ou no-op), escolhido por configuração
- **Circuit Breaker + Retry** na chamada ao Credit Score

## Autenticação e autorização (Auth0)

Resource server do Spring Security (`SecurityConfig`): valida a assinatura RS256 via JWKS, o
issuer (`https://<AUTH0_DOMAIN>/`), a audience, a expiração e o `sub`.

- Sem token ou com token inválido: **401** (`WWW-Authenticate: Bearer`).
- Token sem a permissão da rota: **403**. As permissões da claim `permissions` (RBAC do Auth0) e
  de `scope` viram authorities do Spring.
- `/health`, `/actuator/**` (health, info, prometheus) e o Swagger são públicos. Preflight CORS não exige token.
- Sem `AUTH0_DOMAIN` ou `AUTH0_AUDIENCE`, a inicialização falha explicitamente.

**Token relay:** o `RestTemplate` usado pelo `CreditScoreClient` tem um interceptor
(`BearerTokenRelayInterceptor`) que repassa o token do usuário à Credit Score API. Solicitar
crédito exige `write:credits` aqui **e** `read:scores` no Score; se faltar a segunda, a
resposta é 403 com o motivo.

Nos testes, `spring-security-test` (`jwt()` com authorities) e um JWKS local; o perfil `test`
fornece configuração fictícia e não deve ser usado em produção.
