# spring-aws-k8s-lab

![CI](https://github.com/mrlsouz/spring-aws-k8s-lab/actions/workflows/ci.yml/badge.svg)
![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4-6DB33F?logo=springboot&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-multi--stage-2496ED?logo=docker&logoColor=white)
![Kubernetes](https://img.shields.io/badge/Kubernetes-kind-326CE5?logo=kubernetes&logoColor=white)
Lab local de estudo: uma API de notas em **Spring Boot**, **Docker** e **Kubernetes** com **kind**, sem provisionar recursos na AWS.

O projeto é construído em fases pequenas, uma tecnologia por vez. Como rodar a aplicação: [`docs/executando.md`](docs/executando.md).

## Arquitetura local

```mermaid
flowchart LR
    dev[Desenvolvimento local] --> compose[Docker Compose]
    compose --> api[Notes API]
    api --> ddb[(DynamoDB Local)]
    api -->|snapshots JSON| s3mock[S3Mock]
    dev --> kind[kind]
    kind --> kapi[Notes API]
    kapi --> kddb[(DynamoDB Local)]
    kapi -->|snapshots JSON| ks3[S3Mock]
    kind --> prom[Prometheus]
    kind --> ksm[Kube State Metrics]
    kind --> grafana[Grafana]
    prom -->|scrape| kapi
    prom -->|scrape| ksm
    grafana -->|queries| prom
    dev -->|port-forward| grafana
    gha[GitHub Actions] --> ci[Testes, build e smoke tests]
    ci --> kind
```

O S3Mock guarda snapshots JSON de exportação da API, usando a API compatível com S3. DynamoDB Local continua guardando as notas ativas. Tudo roda localmente; o CI valida os arquivos Terraform sem planejar ou criar recursos AWS.

A observabilidade local é opcional e documentada em [`docs/executando.md`](docs/executando.md#observabilidade-local). Prometheus coleta métricas da API e estado dos workloads; o Grafana apresenta um dashboard local.

## Roteiro

| Etapa | Conteúdo | Estado |
|-------|----------|--------|
| 1 | API Spring Boot, testes, Docker Compose, DynamoDB Local e exportação/importação S3 | ✅ concluída |
| 2 | kind, Deployments, Services, ConfigMap, probes, S3Mock persistente e smoke tests no CI | ✅ concluída |
| Observabilidade local | Prometheus, Grafana, estado de Pods e métricas das operações DynamoDB | Opcional |
| Próximos estudos | Operar o cluster local: logs, eventos, probes, rollout e troubleshooting | Opcional |

Provisionamento AWS, incluindo S3, EC2, ECR, DynamoDB gerenciado e EKS, foi removido do roteiro para evitar cobranças. Os manifests e arquivos Terraform existentes são material de estudo; não rode `terraform apply` nem crie recursos na AWS neste lab.

## Estrutura

```
.
├── app/            # API Spring Boot (Maven wrapper, Dockerfile, testes)
├── k8s/            # manifests Kubernetes local (kind)
├── terraform/      # exemplos validados estaticamente; sem apply
├── docs/           # documentação de execução e configuração
└── compose.yaml    # ambiente local: API + DynamoDB Local + S3Mock
```

## Rodando localmente

Requisitos: Docker. Para a fase Kubernetes, também `kind`, `kubectl`, `curl` e `jq`. O smoke test do S3Mock requer AWS CLI. Para rodar os testes ou a API fora do container, Java 25. Maven não é necessário, porque o projeto usa o wrapper `./mvnw`.

```bash
docker compose up --build

# em outro terminal
curl -X POST localhost:8080/notes -H 'Content-Type: application/json' \
  -d '{"title":"primeira nota","content":"oi"}'
curl localhost:8080/notes
curl -X POST localhost:8080/exports
```

O Compose inicia a API, DynamoDB Local e S3Mock. O volume `s3mock-data` preserva os objetos ao parar os containers. `docker compose down -v` também apaga os dados arquivados.

Testes de integração (sobem o DynamoDB Local via Testcontainers, então precisam do Docker rodando):

```bash
cd app && ./mvnw test
```

### Kubernetes local (fase 2, concluída)

```bash
kind create cluster --name lab
docker build -t notes-api:0.1.0 ./app
kind load docker-image notes-api:0.1.0 --name lab
kubectl create namespace lab
kubectl apply -f k8s/
kubectl rollout status deployment/dynamodb -n lab
kubectl rollout status deployment/s3mock -n lab
kubectl rollout status deployment/notes-api -n lab
./scripts/smoke-test-kind.sh
./scripts/smoke-test-s3mock.sh
./scripts/smoke-test-note-export.sh
```

O S3Mock fica disponível dentro do cluster em `http://s3mock:9090`, com o bucket `notes-local`. Os snapshots ficam sob `exports/` e persistem no hostPath do nó kind quando o Pod reinicia. Apagar o cluster kind também apaga esse armazenamento local. S3Mock implementa apenas parte da API S3 e não cria nem acessa recursos AWS.

Este lab não precisa de credenciais AWS. O CI não executa `terraform plan` nem `terraform apply`.

Depois de reconstruir a imagem com a mesma tag ou alterar o ConfigMap, atualize o cluster:

```bash
kind load docker-image notes-api:0.1.0 --name lab
kubectl apply -f k8s/
kubectl rollout restart deployment/notes-api -n lab
kubectl rollout status deployment/notes-api -n lab
```

## API

| Método | Rota | Descrição |
|--------|------|-----------|
| `POST` | `/notes` | cria nota (`title` obrigatório, até 200 caracteres) |
| `GET` | `/notes` | lista notas |
| `GET` | `/notes/{id}` | busca nota; `404` se não existir |
| `DELETE` | `/notes/{id}` | remove nota |
| `POST` | `/exports` | cria snapshot JSON e retorna `exportId` |
| `GET` | `/exports` | lista snapshots armazenados |
| `GET` | `/exports/{exportId}` | baixa snapshot como arquivo JSON |
| `POST` | `/imports/{exportId}` | restaura notas e informa importadas, ignoradas, conflitos e falhas |
| `GET` | `/actuator/health/liveness` | liveness probe |
| `GET` | `/actuator/health/readiness` | readiness probe |
| `GET` | `/actuator/prometheus` | métricas no formato Prometheus |

## Decisões de projeto

- **DynamoDB Local:** permite praticar a integração com o AWS SDK sem criar uma conta ou tabela AWS.
- **Sem credenciais AWS:** Compose e Kind apontam para emuladores locais; não configure chaves AWS neste lab.
- **Configuração por variável de ambiente:** a mesma imagem roda no Compose e no `kind`.
- **Imagem multi-stage, usuário não-root** e heap da JVM dimensionado pelo limite de memória do container.
- **Testes contra DynamoDB Local** com Testcontainers, em vez de mocks.
- **Snapshots versionados:** JSON com `schemaVersion`, `exportId`, `generatedAt` e as notas completas.
- **Importação retomável:** preserva IDs, ignora registros idênticos, informa conflitos sem sobrescrever e interrompe na primeira falha de gravação.

## Stack

Java 25 · Spring Boot 4 · AWS SDK v2 (DynamoDB Enhanced Client e S3) · S3Mock · JUnit 5 · Testcontainers · Docker · Kubernetes (`kind`)
