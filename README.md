# Product

Microsserviço de **catálogo de produtos** da loja online do grupo `pato-nego`. Ele cadastra, lista, busca e remove produtos, guarda os dados no PostgreSQL (AWS RDS) e usa Redis como cache das buscas por id.

## Lugar na arquitetura

```
internet ──► gateway ──► product ──► PostgreSQL (RDS, banco "product")
                           ▲   └───► Redis (cache, mesma máquina)
                order ─────┘
```

- O cliente **nunca** acessa o Product diretamente: as chamadas públicas passam pelo gateway, que encaminha `/products` e `/products/**` para este serviço.
- O serviço **Order** chama o Product por dentro da rede privada para consultar o preço dos produtos (`GET /products/{id}`).

## Endpoints

| Método | Rota | Corpo | Resposta |
|---|---|---|---|
| `POST` | `/products` | `{ "name", "price", "unit" }` | `201 Created` com o produto criado |
| `GET` | `/products` | — | `200 OK` com a lista de produtos |
| `GET` | `/products/{id}` | — | `200 OK` com o produto, ou `404` |
| `DELETE` | `/products/{id}` | — | `204 No Content`, ou `404` |

O `id` é um **UUID v7**, gerado pelo próprio serviço.

### Exemplos

Criar um produto:

```bash
curl -X POST http://localhost:8080/products \
  -H "Content-Type: application/json" \
  -d '{"name": "Café 500g", "price": 24.90, "unit": "pacote"}'
```

```json
{
  "id": "019a8f3c-6b1e-7c2a-9d4e-3f5a1b2c3d4e",
  "name": "Café 500g",
  "price": 24.90,
  "unit": "pacote"
}
```

Buscar pelo id:

```bash
curl http://localhost:8080/products/019a8f3c-6b1e-7c2a-9d4e-3f5a1b2c3d4e
```

### Validação

| Campo | Regra |
|---|---|
| `name` | obrigatório, não pode ser vazio |
| `price` | obrigatório, maior que zero |
| `unit` | obrigatório, não pode ser vazio |

### Erros

Produto inexistente (`404`):

```json
{ "status": 404, "error": "Product not found: 019a8f3c-6b1e-7c2a-9d4e-3f5a1b2c3d4e" }
```

Corpo inválido (`400`), com o motivo de cada campo:

```json
{
  "status": 400,
  "error": "Validation failed",
  "fields": { "name": "must not be blank", "price": "must be greater than 0" }
}
```

Um `id` que não é UUID também retorna `400`.

## Modelo de dados

Tabela `products`, criada e atualizada pelo Hibernate (`ddl-auto=update`):

| Coluna | Tipo | Observação |
|---|---|---|
| `id` | `uuid` | chave primária, UUID v7 |
| `name` | `varchar(255)` | não nulo |
| `price` | `numeric(38,2)` | não nulo, `BigDecimal` no Java |
| `unit` | `varchar(255)` | não nulo |

## Tecnologias

- Java 25 e Spring Boot 4.1
- Spring Web MVC, Spring Data JPA (Hibernate) e Bean Validation
- Spring Cache com Redis
- PostgreSQL em produção e H2 em memória para rodar localmente
- Lombok
- Maven, pelo wrapper `./mvnw` (não é preciso instalar o Maven)
- Docker, Docker Compose e GitHub Actions

## Estrutura do código

```
src/main/java/pato/nego/product/
├── ProductApplication.java       ponto de entrada
├── ProductController.java        rotas HTTP
├── ProductService.java           regras de negócio e cache
├── ProductRepository.java        acesso ao banco (Spring Data JPA)
├── Product.java                  entidade JPA
├── dto/
│   ├── ProductRequest.java       corpo do POST, com validação
│   └── ProductResponse.java      resposta da API
├── exceptions/
│   ├── ProductNotFoundException.java
│   ├── ErrorResponse.java        formato padrão de erro
│   └── GlobalExceptionHandler.java
└── config/
    └── CacheConfig.java          liga o cache e trata falhas do Redis
```

O fluxo de uma requisição é sempre `Controller → Service → Repository`. A entidade `Product` nunca sai da API: a entrada é convertida de `ProductRequest` e a saída para `ProductResponse`.

## Rodar localmente

O perfil `local` usa **H2 em memória** e **cache em memória**. Não precisa de PostgreSQL, Redis nem Docker.

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

A API sobe em `http://localhost:8080`. Os dados são apagados quando a aplicação para.

## Testes

```bash
./mvnw test
```

Os testes rodam com o perfil `local`, então também não dependem de banco nem de Redis.

## Configuração

O arquivo [`application.properties`](src/main/resources/application.properties) é a configuração de produção. Os valores sensíveis vêm de variáveis de ambiente:

| Variável | Padrão | Descrição |
|---|---|---|
| `DATABASE_HOST` | — | endpoint do RDS |
| `DATABASE_PORT` | `5432` | porta do PostgreSQL |
| `DATABASE_NAME` | `product` | nome do banco |
| `DATABASE_USERNAME` | — | usuário do banco (`product_app`) |
| `DATABASE_PASSWORD` | — | senha do usuário |
| `REDIS_HOST` | `localhost` | endereço do Redis (`redis` no Compose) |
| `REDIS_PORT` | `6379` | porta do Redis |

O modelo do arquivo `.env` está em [`.env.example`](.env.example). O `.env` real **nunca** vai para o Git.

A conexão com o banco usa TLS com verificação do certificado (`sslmode=verify-full`). Para isso, o arquivo `rds-ca.pem`, o pacote de certificados do RDS da região `sa-east-1`, precisa estar ao lado do `docker-compose.yml`:

```bash
curl -o rds-ca.pem https://truststore.pki.rds.amazonaws.com/sa-east-1/sa-east-1-bundle.pem
```

[`application-local.properties`](src/main/resources/application-local.properties) substitui o banco e o cache quando o perfil `local` está ativo.

## Cache

| Operação | Cache |
|---|---|
| `GET /products/{id}` | guarda o resultado por **10 minutos** |
| `DELETE /products/{id}` | remove o produto do cache |
| `GET /products` e `POST /products` | não usam cache |

Se o Redis cair, a API **continua respondendo**: o `CacheConfig` registra o erro no log e a busca vai direto ao banco. O cache acelera, mas não é uma dependência obrigatória.

## Deploy

### Imagem Docker

O [`Dockerfile`](Dockerfile) tem duas etapas: compila com o JDK e roda só com o JRE, com um usuário sem privilégios de root.

```bash
docker build -t product:latest .
```

### Docker Compose

O [`docker-compose.yml`](docker-compose.yml) sobe dois containers: a API (porta `8080`) e o Redis, que fica acessível só pela rede interna do Compose.

```bash
docker compose up -d
docker compose logs -f product
```

A explicação linha por linha dos dois arquivos está em [`tutorial_docker.md`](tutorial_docker.md).

### Produção (AWS)

- **Máquina:** EC2 `pato-nego-product` (Amazon Linux 2023, t3.micro), IP privado `10.42.0.15`, na VPC do grupo.
- **Banco:** a instância RDS PostgreSQL compartilhada pelo grupo, com o banco `product` e o usuário `product_app`.
- **Arquivos na máquina** (em `~/product`): `docker-compose.yml`, `.env` (permissão `600`) e `rds-ca.pem`.
- **Firewall:** a porta `8080` aceita só o gateway e o serviço Order. A porta `22` aceita SSH apenas por chave.

### CI/CD (GitHub Actions)

O workflow [`ci-cd.yml`](.github/workflows/ci-cd.yml) roda a cada push e pull request na `main`:

```
pull request ──► test
push na main ──► test ──► deploy
```

| Job | O que faz |
|---|---|
| `test` | `./mvnw -B verify` com o perfil `local` |
| `deploy` | constrói a imagem, envia para a EC2 por SSH e roda `docker compose up -d` |

O deploy só acontece se os testes passarem e nunca roda em pull requests. Ele precisa de três segredos no repositório (**Settings → Secrets and variables → Actions**):

| Segredo | Conteúdo |
|---|---|
| `EC2_HOST` | endereço público da EC2 |
| `EC2_SSH_KEY` | chave privada SSH usada só pelo CI |
| `EC2_KNOWN_HOSTS` | chave pública do host, para verificar a identidade da máquina |

O `.env` e o `rds-ca.pem` não passam pelo GitHub: eles são criados uma vez, direto na EC2.

## Decisões de projeto

- **UUID v7 como id:** ordenado pelo tempo de criação, o que mantém o índice do banco eficiente, e impossível de adivinhar, ao contrário de um id sequencial.
- **`BigDecimal` para o preço:** evita os erros de arredondamento de `double` com valores em dinheiro.
- **DTOs como `record`:** a entidade JPA fica isolada da API, e a entrada é validada antes de chegar ao serviço.
- **Tratamento de erros centralizado:** um único `@RestControllerAdvice` garante o mesmo formato de erro em todas as rotas.
- **Redis na mesma máquina, em container:** sem custo extra e sem configuração de rede adicional. Como a API funciona sem ele, a simplicidade compensa a falta de alta disponibilidade.
