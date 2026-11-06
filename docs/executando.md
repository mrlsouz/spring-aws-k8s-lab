# Executando a aplicação

Todos os comandos partem da raiz do repositório.

## Requisitos

| Ferramenta | Para quê |
|------------|----------|
| Docker | Compose, build da imagem e testes (Testcontainers) |
| Java 25 | Rodar testes ou a API fora do container |
| `kind` e `kubectl` | Só para o ambiente Kubernetes local |
| `curl` e `jq` | Smoke test da API no `kind` |
| AWS CLI | Smoke test do S3Mock no `kind` |

O Maven não precisa ser instalado: o projeto usa o wrapper `app/mvnw`.

## Docker Compose (API + DynamoDB Local)

```bash
docker compose up --build      # sobe tudo; API em http://localhost:8080
docker compose logs -f api     # logs da API
docker compose down            # para e remove os containers
```

O DynamoDB Local roda em memória: os dados somem ao parar.

## API fora do container

Útil para debugar pela IDE. Só o DynamoDB fica no Docker:

```bash
docker compose up -d --wait dynamodb  # espera o healthcheck indicar que o DynamoDB está pronto
cd app
APP_DYNAMODB_ENDPOINT=http://localhost:8000 APP_DYNAMODB_CREATE_TABLE=true ./mvnw spring-boot:run
```

## Testes e formatação

```bash
cd app
./mvnw verify             # testes de integração (precisa do Docker rodando)
./mvnw spotless:apply     # formata o código
./mvnw spotless:check     # só verifica, como o CI faz
```

## Kubernetes local (kind)

```bash
kind create cluster --name lab
docker build -t notes-api:0.1.0 ./app
kind load docker-image notes-api:0.1.0 --name lab
kubectl create namespace lab
kubectl apply -f k8s/
kubectl rollout status deployment/notes-api -n lab
kubectl rollout status deployment/s3mock -n lab
kubectl get pods -n lab
./scripts/smoke-test-kind.sh
./scripts/smoke-test-s3mock.sh
```

O smoke test da API confere health checks e o ciclo de criar, listar, consultar e excluir uma nota. O S3Mock fica em `http://s3mock:9090` dentro do cluster, com bucket inicial `notes-local`; o smoke test usa credenciais fictícias para enviar e baixar um objeto. S3Mock implementa um subconjunto da API S3 e não acessa a AWS. Os dados são locais e podem sumir ao recriar o Pod.

Depois de reconstruir a imagem com a mesma tag ou alterar o ConfigMap, atualize o cluster:

```bash
kind load docker-image notes-api:0.1.0 --name lab
kubectl apply -f k8s/
kubectl rollout restart deployment/notes-api -n lab
kubectl rollout status deployment/notes-api -n lab
```

Para apagar o cluster: `kind delete cluster --name lab`.

## Configuração

Todas as propriedades de `app/src/main/resources/application.properties` podem ser sobrescritas por variável de ambiente.

| Variável | Padrão | Descrição |
|----------|--------|-----------|
| `APP_DYNAMODB_TABLE` | `notes` | Nome da tabela |
| `APP_DYNAMODB_REGION` | `us-east-1` | Região AWS |
| `APP_DYNAMODB_ENDPOINT` | vazio (AWS real) | Endpoint alternativo, como o DynamoDB Local |
| `APP_DYNAMODB_CREATE_TABLE` | `false` | Cria a tabela ao subir; só para ambiente local |

Na AWS, as credenciais vêm da cadeia padrão do SDK (variáveis de ambiente, `~/.aws` ou Pod Identity no EKS). Nunca coloque chaves no código ou na imagem.

## Problemas comuns

| Sintoma | Causa provável |
|---------|----------------|
| Testes falham com erro de Docker | Docker parado; o Testcontainers precisa dele |
| `release version 25 not supported` | JDK instalado é anterior ao 25 |
| `ResourceNotFoundException` ao chamar a API | Tabela não existe: use `APP_DYNAMODB_CREATE_TABLE=true` no ambiente local |
| Pod em `ErrImagePull` no kind | Imagem não carregada: rode o `kind load docker-image` |
