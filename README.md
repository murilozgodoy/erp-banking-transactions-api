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
