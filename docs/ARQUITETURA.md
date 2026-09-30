# Fluxo — arquitetura do projeto

Versão 1.0 · 24/09/2026

## 1. Propósito

Aplicação de portfólio para gerenciar clientes, cobranças em BRL e pagamentos simulados. Mostra Java, Spring Boot, modelagem relacional, regras de negócio, API REST, interface web, testes, CI e operação. Nenhum dinheiro é movimentado e nenhuma integração financeira real é realizada.

### Critério de sucesso da demonstração

Uma pessoa inicia o sistema com Docker Compose, cadastra um cliente, cria uma cobrança, consulta o painel, simula seu pagamento, consulta o histórico e verifica que repetir o pagamento ou cancelar uma cobrança paga produz respostas corretas. O pipeline valida o código automaticamente.

## 2. Escopo

### MVP (versão publicável)

- Cadastro e listagem de clientes fictícios: nome e e-mail; identificação por UUID.
- Criar, listar, filtrar, detalhar e cancelar cobranças.
- Pagamento integral simulado e histórico dos eventos da cobrança.
- Painel com quantidade e soma por condição: pendente no prazo, atrasada, paga, cancelada.
- Interface responsiva em HTML, CSS e JavaScript puro, consumindo a API.
- Migrations, testes automatizados, Docker Compose, healthcheck e CI.

### Fora do MVP

Autenticação, multiempresa, CPF/CNPJ, cartão/Pix/boleto real, pagamentos parciais, parcelamento, webhooks, notificações, mensageria e deploy público. Só incluir por necessidade demonstrada após o MVP. O aplicativo deve usar dados fictícios.

## 3. Stack e arquitetura

- Java 21; Spring Boot 4.1.1; Maven.
- Spring MVC, Spring Data JPA, Bean Validation, Actuator; PostgreSQL; Flyway.
- Testes com JUnit, Spring Test e Testcontainers/PostgreSQL nas integrações; Playwright e axe no fluxo de navegador desktop/mobile; Docker Compose no ambiente local.
- Frontend estático servido pelo próprio Spring Boot em `/`, com `fetch` para `/api/v1`; sem build de SPA.
- GitHub Actions executa `mvn verify` e o fluxo Playwright/axe em push, pull request ou disparo manual; README documenta execução e decisões.
- Monólito modular, um deploy e um banco. Camadas por domínio: `client`, `charge`, `payment`, `dashboard`, `shared`. Em cada domínio, controller/DTO, service, repository/entity. Controllers não acessam repositories; entidades JPA não são respostas HTTP.

```
Browser (HTML/CSS/JS) -> Controller REST -> Service (regras/transação) -> Repository -> PostgreSQL
                                              |                       |
                                              +-- Eventos de cobrança -+
```

Spring Boot Actuator fornece `/actuator/health`; logs estruturados contêm identificadores e erros, sem dados pessoais ou segredos. Métricas e disponibilidade podem ser acrescentadas sem introduzir infraestrutura de observabilidade no MVP.

## 4. Modelo de domínio

### Cliente

`id: UUID`, `name: VARCHAR(120)`, `email: VARCHAR(254)`, `created_at: TIMESTAMPTZ`. E-mail normalizado e único nesta instância de demonstração. Sem exclusão no MVP.

### Cobrança

`id: UUID`, `client_id: UUID FK`, `description: VARCHAR(200)`, `amount: NUMERIC(15,2)`, `currency: BRL`, `due_date: DATE`, `status: PENDING | PAID | CANCELED`, `created_at`, `updated_at`, `version` (controle de concorrência). `amount` é imutável após criação. `client_id`, `status` e `due_date` têm índices úteis às consultas.

### Pagamento simulado

`id: UUID`, `charge_id: UUID UNIQUE FK`, `amount: NUMERIC(15,2)`, `idempotency_key: VARCHAR(100) UNIQUE`, `paid_at: TIMESTAMPTZ`. Um pagamento integral por cobrança. Valor e moeda são copiados da cobrança pelo servidor; cliente não informa o valor a pagar.

### Evento de cobrança

`id: UUID`, `charge_id: UUID FK`, `type: CREATED | PAID | CANCELED`, `occurred_at: TIMESTAMPTZ`, `details: JSONB` opcional. Inserido na mesma transação da operação. Nenhum evento de atraso é gravado: atraso é uma condição derivada.

### Condição exibida

Se `status = PENDING` e `due_date < hoje` na zona `America/Sao_Paulo`, a API exibe `OVERDUE`; nos demais casos, exibe o status persistido. O banco armazena instantes em UTC; a data de vencimento é uma data civil brasileira. A aplicação injeta `Clock` para testes determinísticos.

## 5. Regras de negócio

1. Valor estritamente positivo, até R$ 1.000.000,00 e com no máximo duas casas decimais; entrada decimal em JSON como número, armazenamento `NUMERIC`, cálculo em `BigDecimal`.
2. Cliente precisa existir; descrição obrigatória; vencimento pode ser hoje ou futuro na criação.
3. Cobrança pendente pode ser paga no prazo ou após vencimento. Não há juros ou multa no MVP.
4. Cobrança pendente pode ser cancelada, inclusive se vencida. Cobrança paga ou cancelada não pode ser paga ou cancelada novamente com uma nova operação.
5. Repetição de `POST /payments` com a mesma `Idempotency-Key` e mesma cobrança devolve o pagamento anterior; reutilizar a chave para outra cobrança devolve 409. Chave obrigatória e gerada pelo cliente. O MVP garante repetição de pagamentos concluídos; não promete replay de erros anteriores.
6. Uma restrição UNIQUE em `payments.charge_id`, outra em `payments.idempotency_key` e controle de versão na cobrança protegem concorrência. Pagamento/cancelamento e evento devem ocorrer atomicamente. Disputa entre duas requisições resulta em uma vencedora; a outra recebe 409 ou resposta idempotente quando aplicável. Testar essas situações.
7. Dashboard e filtros não devem somar cobranças canceladas como recebíveis. Os totais em BRL são formatados na interface; a API retorna números decimais.

## 6. Contrato HTTP `/api/v1`

| Método e rota | Função | Sucesso |
| --- | --- | --- |
| `POST /clients` | Criar cliente | 201 + `Location` |
| `GET /clients?search=&page=&size=` | Buscar clientes | 200 paginado |
| `POST /charges` | Criar cobrança | 201 + `Location` |
| `GET /charges?condition=&clientId=&page=&size=` | Listar e filtrar | 200 paginado |
| `GET /charges/{id}` | Detalhar | 200 |
| `POST /charges/{id}/payments` | Pagar; exige `Idempotency-Key` | 201; replay 200 |
| `POST /charges/{id}/cancellation` | Cancelar | 200 |
| `GET /charges/{id}/events` | Consultar histórico ordenado | 200 |
| `GET /dashboard/summary` | Totais do painel | 200 |
| `GET /actuator/health` | Saúde da aplicação | 200/503 |

Exemplo de criação: `POST /charges` com `{ "clientId": "<uuid>", "description": "Assinatura de setembro", "amount": 149.90, "dueDate": "2026-10-10" }`. Resposta inclui `id`, `status`, `condition`, `amount`, `dueDate`, `createdAt` e resumo do cliente. `GET /charges` usa `condition=PENDING|OVERDUE|PAID|CANCELED`; paginação tem máximo de 100 itens.

`GET /dashboard/summary` retorna `referenceDate` e os objetos `pending`, `overdue`, `paid`, `canceled` e `receivable`, cada um com `count` e `amount` numéricos. `receivable` soma as cobranças persistidas como `PENDING`, incluindo as vencidas e excluindo pagas e canceladas. A data de referência usa `America/Sao_Paulo`.

Erros: `400` para payload, formato, limite ou chave ausente; `404` para identificador inexistente; `409` para transição inválida, chave reaproveitada com outra cobrança ou conflito concorrente. Resposta uniforme `application/problem+json` com `type`, `title`, `status`, `detail`, `instance`, `code`, `traceId`; validações acrescentam `fieldErrors`. Nunca exibir stack trace ao usuário. Documentar contrato no README e adicionar OpenAPI somente se contribuir para a demonstração.

## 7. Interface

Três telas: painel de indicadores e cobranças recentes; lista com filtro/busca e formulário de criação; detalhe da cobrança com cliente, valor, vencimento, eventos e ações `Simular pagamento`/`Cancelar`. Formulário de cliente em modal ou página simples. Estados de carregamento, vazio, sucesso e erro; confirmação antes do cancelamento. Ações indisponíveis para cobrança paga ou cancelada. Campo monetário com máscara apenas visual; validação repetida no servidor. HTML semântico, navegação por teclado, labels e mensagens de erro acessíveis.

## 8. Qualidade e testes

- Unitários: limite monetário, datas e `Clock`, cálculo `OVERDUE`, transições válidas/inválidas.
- Integração com PostgreSQL/Testcontainers: migração Flyway, persistência, filtros, restrições UNIQUE, rollback de evento em falha, pagamento duplicado e disputa pagar/cancelar.
- Web/API: validação e formatos de erro, status HTTP, idempotência, paginação e DTOs.
- Teste manual de ponta a ponta do fluxo principal na interface.
- CI: Maven `verify`; falhas barram merge. README registra execução, screenshots reais e decisões de arquitetura. Não afirmar cobertura ou disponibilidade sem medição.

## 9. Configuração, execução e estrutura

```
fluxo/
  pom.xml
  Dockerfile
  compose.yaml
  .github/workflows/ci.yml
  src/main/java/com/enzoguimaraes/fluxo/
    FluxoApplication.java
    client/ charge/ payment/ dashboard/ shared/
  src/main/resources/
    application.yml
    db/migration/V1__initial_schema.sql
    static/index.html
    static/css/styles.css
    static/js/app.js
  src/test/java/com/enzoguimaraes/fluxo/
  README.md
  docs/architecture.md
```

Variáveis `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`; configuração local em `.env.example` sem credenciais reais. `spring.jpa.hibernate.ddl-auto=validate`, Flyway altera o esquema. `docker compose up --build` inicia banco e aplicação e espera o banco estar saudável. Não publicar variáveis, segredos ou informações pessoais. Deploy público só após autenticação/proteção adequada ou em ambiente de demonstração restrito.

## 10. Plano de implementação e critérios de aceite

### Entrega 1 — Fundação e domínio

Criar repositório, app Spring, Compose, migration, entidades, DTOs e clientes. Aceite: app e banco iniciam, migration executa, cliente é criado e consultado; CI compila.

### Entrega 2 — Cobranças e regras

Criar/listar/detalhar cobranças, condição derivada, pagamento e cancelamento transacionais, histórico e erros padronizados. Aceite: casos válidos e inválidos cobertos por testes; repetição com mesma chave não cria outro pagamento; concorrência não gera dois pagamentos.

### Entrega 3 — Painel e interface

Resumo SQL/serviço e três telas integradas. Aceite: fluxo completo funciona sem ferramentas externas; filtros, feedback e acessibilidade básica verificados.

### Entrega 4 — Preparação do portfólio

Healthcheck, logs, pipeline, Dockerfile, README com trade-offs e prints, revisão de código por PRs pequenos. Aceite: outra pessoa consegue clonar e executar apenas com Docker Compose; CI verde; sem dados ou segredos reais.

## 11. Decisões e evoluções possíveis

Escolhemos monólito para mostrar limites claros de domínio com operação simples. Sem eventos externos nem microsserviços: evento é trilha transacional local. Pagamento simulado permite testar comportamento sem dependência financeira. `OVERDUE` derivado evita job diário e inconsistência com o calendário. Depois do MVP, se houver tempo, adicionar autenticação, webhook simulado com assinatura e retries, OpenAPI e métricas expostas para dashboard operacional. Cada evolução deve ser uma issue/PR com cenário e teste correspondente.
