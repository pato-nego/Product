# Tutorial: Dockerfile e Docker Compose do microsserviço Product

Este tutorial explica, linha por linha, como o `Dockerfile` e o `docker-compose.yml` deste projeto foram escritos e por que cada decisão foi tomada.

Ele tem três partes:

1. O raciocínio antes de escrever
2. O `Dockerfile`
3. O `docker-compose.yml`

---

## 1. O raciocínio antes de escrever

Antes de escrever qualquer linha, vale responder quatro perguntas:

| Pergunta | Resposta neste projeto | Onde isso entra |
|---|---|---|
| **Do que o projeto precisa para ser compilado?** | Java 25 **completo** (JDK), o Maven (`mvnw`), o `pom.xml` e o código | 1ª etapa do Dockerfile |
| **Do que precisa para rodar?** | só o Java de **execução** (JRE) e o `.jar` | 2ª etapa do Dockerfile |
| **O que muda entre ambientes?** | endereço e senha do banco, endereço do Redis | variáveis de ambiente, lidas do `.env` no Compose |
| **De quais outros serviços depende?** | Redis | um segundo serviço no Compose |

A separação entre **compilar** e **rodar** é a ideia central do Dockerfile. A separação entre **imagem** e **configuração** é a ideia central do Compose.

---

## 2. O Dockerfile

Um Dockerfile é uma **receita**: cada linha é um passo, executado de cima para baixo, e o resultado é uma **imagem**, um pacote com tudo o que a aplicação precisa.

O arquivo completo:

```dockerfile
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src
RUN ./mvnw package -DskipTests

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/target/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

### Etapa 1: compilar

```dockerfile
FROM eclipse-temurin:25-jdk-alpine AS build
```

- **`FROM`** escolhe a imagem de partida. Todo Dockerfile começa com ela.
- **`eclipse-temurin`** é uma distribuição gratuita e oficial do Java. **`25`** é a versão, a mesma do `pom.xml` (`<java.version>25</java.version>`).
- **`jdk`** é o Java completo, **com compilador**, necessário para gerar o `.jar`.
- **`alpine`** indica um Linux mínimo, de poucos MB, o que deixa a imagem bem menor.
- **`AS build`** dá um nome a essa etapa, para buscar arquivos nela depois.

```dockerfile
WORKDIR /app
```

Cria a pasta `/app` dentro da imagem e entra nela, como um `mkdir` seguido de `cd`. Os próximos comandos rodam a partir dali.

```dockerfile
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src
```

Copia do computador para dentro da imagem **só o que a compilação precisa**: o wrapper (`mvnw` e `.mvn/`), o `pom.xml` e o código (`src/`). O `.dockerignore` garante que `target/`, `.git/` e o `.env` nunca entrem, mesmo por acidente.

```dockerfile
RUN ./mvnw package -DskipTests
```

- **`RUN`** executa um comando durante a construção da imagem.
- **`./mvnw package`** baixa as dependências, compila o projeto e gera o `.jar` em `target/`.
- **`-DskipTests`** pula os testes durante o build. Eles rodam à parte, com `./mvnw test`. Se rodassem aqui, toda construção de imagem ficaria mais lenta.

Como a imagem é construída do zero, cada build baixa as dependências do Maven de novo e leva alguns minutos. Existe um recurso do Docker que guarda esse download entre builds (`RUN --mount=type=cache,target=/root/.m2`). Ele não foi usado para manter o arquivo simples: só vale a pena se os builds forem frequentes.

### Etapa 2: rodar

```dockerfile
FROM eclipse-temurin:25-jre-alpine
```

Um **segundo `FROM`** começa uma imagem **nova e vazia**. Tudo o que foi feito na etapa 1 (JDK, código-fonte, dependências do Maven) é **descartado**, a menos que seja copiado explicitamente. Agora a base é o **`jre`**, só o Java de execução, sem compilador.

Esse é o **multi-stage build**, e ele traz duas vantagens:

- **Imagem menor:** fica só o Java de execução e o `.jar`, sem a ferramenta de build nem as centenas de MB de dependências do Maven. Fica mais rápida de enviar para a EC2.
- **Mais segura:** o código-fonte e o compilador não vão para produção.

```dockerfile
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
```

Cria um usuário chamado `app`, sem senha e sem privilégios. O `-S` indica um usuário "de sistema". A sintaxe é a do Alpine.

```dockerfile
COPY --from=build /app/target/*.jar app.jar
```

**`--from=build`** busca o arquivo na etapa 1, a que foi chamada de `build`. O `.jar` gerado é copiado e renomeado para `app.jar`, um nome fixo que não depende da versão do projeto.

```dockerfile
USER app
```

Daqui para frente, tudo roda como o usuário `app`, e não como `root`. Se alguém explorar uma falha na aplicação, ganha só os poderes desse usuário, que são mínimos. Essa linha fica **depois** do `COPY`, porque copiar arquivos para dentro da imagem exige permissão de root.

```dockerfile
EXPOSE 8080
```

**Só documenta** em qual porta a aplicação escuta. **Não abre porta nenhuma.** Quem publica a porta é o Compose, com o `ports:`.

```dockerfile
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

É o comando que roda quando o container inicia.

- **`-jar app.jar`** roda a aplicação Spring Boot.
- **`-XX:MaxRAMPercentage=75`** diz ao Java para usar no máximo **75% da memória do container**. Com o limite de 600 MB do Compose, isso dá cerca de 450 MB para os dados da aplicação. O restante fica para o próprio Java, que também consome memória por conta própria.
- **O formato com colchetes** (JSON) é importante. Nele, o Java recebe direto o sinal de parada quando alguém roda `docker compose down`, e o Spring Boot pode encerrar com calma, terminando as requisições em andamento. No formato sem colchetes (`ENTRYPOINT java -jar app.jar`), o sinal vai para um shell no meio do caminho, e o Java acaba sendo encerrado à força.

---

## 3. O docker-compose.yml

Se o Dockerfile diz **como construir uma imagem**, o Compose diz **como rodar os containers**: quais são, como se conectam e com qual configuração.

O arquivo completo:

```yaml
services:

  product:
    image: product:latest
    env_file: .env

    environment:
      REDIS_HOST: redis

    ports:
      - "8080:8080"

    depends_on:
      - redis

    restart: unless-stopped
    mem_limit: 600m

  redis:
    image: redis:7-alpine
    command: redis-server --maxmemory 100mb --maxmemory-policy allkeys-lru --save ""
    restart: unless-stopped
```

O YAML ignora linhas em branco, então elas podem ser usadas à vontade para organizar o arquivo. O que importa é a **indentação com espaços**: nunca use tab.

### Os serviços

```yaml
services:
  product:
    ...
  redis:
    ...
```

Cada item dentro de **`services`** é um container. O Compose cria uma **rede interna** onde **o nome do serviço vira o endereço**: de dentro do container `product`, o Redis responde pelo nome `redis`.

### Serviço `product`

```yaml
    image: product:latest
```

Usa a imagem `product:latest`, a que é construída com `docker build -t product:latest .` e enviada para a EC2. Não foi usado **`build: .`**, que faria o Compose construir a imagem, porque o build **não deve rodar na EC2**: compilar consome muita memória para a `t3.micro`.

```yaml
    env_file: .env
```

Lê o arquivo `.env` e passa cada linha como **variável de ambiente** para o container. Essa é a ligação com o `application.properties`:

```
.env:                    DATABASE_HOST=meu-banco.rds.amazonaws.com
application.properties:  spring.datasource.url=jdbc:postgresql://${DATABASE_HOST}:...
```

As senhas ficam no `.env`, que existe só na EC2 e nunca vai para o Git. O `.env.example` mostra quais variáveis são necessárias, sem os valores.

```yaml
    environment:
      REDIS_HOST: redis
```

Uma variável escrita direto no Compose. Ela fica aqui, e não no `.env`, porque **não é segredo** e é **sempre igual**: o Redis vai sempre se chamar `redis` nesta rede interna. Ela substitui o padrão `localhost` do `application.properties`.

```yaml
    ports:
      - "8080:8080"
```

O formato é **`porta-da-EC2:porta-do-container`**. Uma requisição que chega na porta 8080 da EC2 é encaminhada para a porta 8080 do container. É essa porta que a regra do grupo de segurança da EC2 libera.

```yaml
    depends_on:
      - redis
```

Inicia o Redis **antes** da API. Ele **não espera** o Redis estar pronto, só controla a ordem de inicialização. Aqui isso basta, porque a API só conecta no Redis quando precisa, e a `CacheConfig` segura o caso de o Redis ainda não estar disponível.

```yaml
    restart: unless-stopped
```

Reinicia o container se ele cair ou se a EC2 reiniciar. A exceção é quando alguém o parou de propósito.

| Opção | Comportamento |
|---|---|
| `no` (padrão) | nunca reinicia |
| `on-failure` | reinicia só se o container terminar com erro |
| `always` | reinicia sempre, até se tiver sido parado à mão (volta quando o Docker reinicia) |
| **`unless-stopped`** | como `always`, mas respeita quando alguém parou |

```yaml
    mem_limit: 600m
```

Teto de memória do container. Se a API passar disso, o Docker encerra o container, e o `restart` o sobe de novo. Ele funciona em conjunto com o `-XX:MaxRAMPercentage=75` do Dockerfile, que faz o Java se ajustar a esse teto. A conta para a `t3.micro`, que tem 1 GiB:

```
600 MB API + ~100 MB Redis + ~250 MB sistema e Docker ≈ 1 GiB  (o swap dá a folga)
```

O Redis não precisa de `mem_limit`, porque o `--maxmemory 100mb`, explicado abaixo, já limita o quanto ele cresce.

### Serviço `redis`

```yaml
    image: redis:7-alpine
```

A imagem oficial do Redis, na versão 7. Fixar a versão evita que um `docker compose pull` traga, sem aviso, uma versão nova com mudanças incompatíveis.

```yaml
    command: redis-server --maxmemory 100mb --maxmemory-policy allkeys-lru --save ""
```

Substitui o comando padrão da imagem para configurar o Redis como cache:

- **`--maxmemory 100mb`**: o Redis guarda no máximo 100 MB de dados.
- **`--maxmemory-policy allkeys-lru`**: quando encher, descarta os itens **usados há mais tempo**. Sem isso, o Redis passaria a recusar gravações.
- **`--save ""`**: desliga a gravação em disco. Como é só cache, perder os dados num reinício não é problema. Mas o motivo principal é evitar uma falha: para gravar em disco, o Redis duplica o próprio processo. Numa máquina com pouca memória, essa duplicação pode falhar, e aí o Redis passa a **recusar todas as gravações** até conseguir salvar.

**O Redis não tem `ports:`.** Por isso ele só pode ser alcançado pela rede interna do Compose. Nem a internet nem a própria EC2 conseguem acessá-lo de fora do Docker. Isso é importante, porque o Redis não pede senha por padrão.

---

## Como verificar na prática

Com o Docker ligado, na raiz do projeto, estes comandos mostram o que foi explicado acima:

```bash
docker build -t product:latest .   # roda as duas etapas do Dockerfile
docker images product              # mostra o tamanho da imagem final, que é bem menor que o JDK
docker history product:latest      # lista as camadas: cada linha do Dockerfile vira uma camada
docker compose config              # mostra o compose como o Docker entendeu, com o .env já aplicado
```

O `docker compose config` precisa de um arquivo `.env` na mesma pasta. Para testar sem os dados reais, copie o `.env.example` para `.env`.

## Para escrever os seus próprios arquivos

A página de cada imagem no Docker Hub mostra as versões disponíveis e exemplos de uso. É o melhor ponto de partida:

- Java: <https://hub.docker.com/_/eclipse-temurin>
- Redis: <https://hub.docker.com/_/redis>
