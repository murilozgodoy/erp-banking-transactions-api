# ERP Banking - Transactions API

Serviço responsável por contas, transações e contratos de crédito. Consome a Credit Score API antes de aprovar operações de crédito.

## Requisitos

- JDK 21+ (funciona em Java 25)
- Maven 3.9+ (ou usar `mvnw` gerado localmente)

## Executar

```bash
mvn spring-boot:run
```

Porta padrão: 8080. H2 in-memory por padrão. Docs H2 desativado.

Health: `GET http://localhost:8080/health`

## Testes com cobertura

```bash
mvn verify
```

- Roda testes unit (Mockito) + integração (`@SpringBootTest` + MockMvc + H2)
- Gera relatório JaCoCo em `target/site/jacoco/index.html`
- **Falha o build se cobertura < 80%** (regra em `pom.xml`)

## Configuração

Perfil default: H2 in-memory. Perfil `prod`: PostgreSQL via env vars.

| Var | Padrão | Descrição |
|-----|--------|-----------|
| `SERVER_PORT` | 8080 | Porta HTTP |
| `DATABASE_URL` | `jdbc:h2:mem:transactions;...` | JDBC URL |
| `DATABASE_USER` | sa | usuário |
| `DATABASE_PASSWORD` | (vazio) | senha |
| `DATABASE_DRIVER` | `org.h2.Driver` | driver |
| `CREDIT_SCORE_API_URL` | `http://localhost:8002` | URL do Score |

## Endpoints

| Método | Rota | Descrição |
|--------|------|-----------|
| GET | `/health` | Health check |
| POST | `/accounts` | Cria conta |
| GET | `/accounts/{id}` | Consulta conta |
| POST | `/transactions` | Registra CREDIT / DEBIT |
| POST | `/credits` | Solicita crédito (consulta Score) |
| GET | `/credits/{id}` | Consulta situação do crédito |

## Padrões de projeto aplicados

- **Singleton**: `RestClientConfig` provê o `RestTemplate` como bean singleton do Spring (`@Bean`)
- **Repository (Spring Data JPA)**: `AccountRepository`, `TransactionRepository`, `CreditRequestRepository`, `ContractRepository`
- Preparado para **Strategy** de política de crédito nas próximas etapas

## Autenticação Auth0 (Etapa 3)

Envie o **access token** em `Authorization: Bearer <token>`.
A API valida assinatura RS256 via JWKS, issuer HTTPS com barra final,
audience e expiração. `/health` permanece público; preflight CORS não exige token.
Sem token ou com token inválido: **401**, com `WWW-Authenticate: Bearer`.
Roles/permissões por rota ainda não são exigidas; não há uma regra de 403 por escopo nesta etapa.

Configure `AUTH0_DOMAIN` (somente hostname, sem https:// nem barra final) e
`AUTH0_AUDIENCE` (Identifier da API no Auth0). O valor `https://erp-banking-api`
no exemplo precisa ser confirmado no painel e deve coincidir com `VITE_AUTH0_AUDIENCE`.
Não use ID token ou token opaco. Nenhuma credencial de client secret é necessária para validar JWT.

CORS permite `http://localhost:5173`, os métodos utilizados e os headers
`Authorization` e `Content-Type`. Configure `CORS_ORIGINS` para produção.

Os testes usam chaves RSA e tokens locais ou autenticação simulada, sem chamadas ao Auth0.

**Pendente de decisão:** as chamadas Score → Onboarding e Transactions → Score
precisam de propagação do token do usuário ou Machine-to-Machine. Os clients atuais
ainda não enviam credenciais: esses fluxos entre serviços retornarão erro até a escolha ser implementada.

Spring Boot exige as variáveis no ambiente do processo; não lê `.env` automaticamente.
Sem `AUTH0_DOMAIN` ou `AUTH0_AUDIENCE`, a inicialização falha explicitamente.
`CORS_ORIGINS` usa origens separadas por vírgula.
O perfil `test` fornece configuração fictícia e não deve ser usado em produção.
