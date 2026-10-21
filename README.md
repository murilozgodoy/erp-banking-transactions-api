# ERP Banking - Transactions API

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

Health: `GET http://localhost:8080/health`

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
| `DATABASE_DRIVER` | `org.h2.Driver` | driver |

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

## Padrões de projeto aplicados

- **Singleton**: `RestClientConfig` provê o `RestTemplate` como bean singleton do Spring (`@Bean`)
- **Repository (Spring Data JPA)**: `AccountRepository`, `TransactionRepository`, `CreditRequestRepository`, `ContractRepository`
- Preparado para **Strategy** de política de crédito nas próximas etapas

## Autenticação e autorização (Auth0)

Resource server do Spring Security (`SecurityConfig`): valida a assinatura RS256 via JWKS, o
issuer (`https://<AUTH0_DOMAIN>/`), a audience, a expiração e o `sub`.

- Sem token ou com token inválido: **401** (`WWW-Authenticate: Bearer`).
- Token sem a permissão da rota: **403**. As permissões da claim `permissions` (RBAC do Auth0) e
  de `scope` viram authorities do Spring.
- `/health` é público; os endpoints do Actuator exigem token. Preflight CORS não exige token.
- Sem `AUTH0_DOMAIN` ou `AUTH0_AUDIENCE`, a inicialização falha explicitamente.

**Token relay:** o `RestTemplate` usado pelo `CreditScoreClient` tem um interceptor
(`BearerTokenRelayInterceptor`) que repassa o token do usuário à Credit Score API. Solicitar
crédito exige `write:credits` aqui **e** `read:scores` no Score; se faltar a segunda, a
resposta é 403 com o motivo.

Nos testes, `spring-security-test` (`jwt()` com authorities) e um JWKS local; o perfil `test`
fornece configuração fictícia e não deve ser usado em produção.
