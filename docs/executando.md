# Executando a aplicação

Todos os comandos partem da raiz do repositório.

Este guia usa Docker, kind e serviços locais. Não exige credenciais AWS nem cria recursos cobrados. O job do Terraform no CI faz apenas validação estática; não execute `terraform apply` neste lab.

## Requisitos

| Ferramenta | Para quê |
|------------|----------|
| Docker | Compose, build da imagem e testes (Testcontainers) |
| Java 25 | Rodar testes ou a API fora do container |
| `kind` e `kubectl` | Só para o ambiente Kubernetes local |
| `curl` e `jq` | Smoke test da API no `kind` |
| AWS CLI | Smoke test do S3Mock no `kind` |

O Maven não precisa ser instalado: o projeto usa o wrapper `app/mvnw`.

## Docker Compose (API + serviços locais)

```bash
docker compose up --build      # API em :8080, DynamoDB Local em :8000, S3Mock em :9090
docker compose logs -f api     # logs da API
docker compose down            # para os containers; preserva o volume do S3Mock
```

O DynamoDB Local roda em memória: as notas ativas somem ao parar. O S3Mock guarda snapshots no volume local `s3mock-data`, que sobrevive a `docker compose down`. Para apagar também o arquivo: `docker compose down -v`.

Crie, liste e baixe um snapshot, depois importe-o:

```bash
export_id="$(curl --silent --fail --request POST http://localhost:8080/exports | jq -r '.exportId')"
curl --fail --remote-header-name --remote-name "http://localhost:8080/exports/${export_id}"
curl --fail --request POST "http://localhost:8080/imports/${export_id}"
```

Cada objeto usa a chave `exports/{exportId}.json` e contém `schemaVersion: 1`, `exportId`, `generatedAt` e a lista completa de notas. A importação preserva os IDs, ignora notas idênticas, informa conflitos sem sobrescrever e retorna IDs com falha; ao falhar uma gravação, interrompe o lote para permitir nova tentativa.

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
./scripts/smoke-test-note-export.sh
```

O smoke test da API confere health checks e o ciclo de criar, listar, consultar e excluir uma nota. O teste de exportação cria um snapshot, baixa o JSON, apaga a nota, restaura-a e confirma que repetir a importação não duplica registros. Ele remove os dados temporários ao terminar.

O S3Mock fica em `http://s3mock:9090` dentro do cluster, com bucket `notes-local`; a API aponta para esse endereço. O manifesto usa armazenamento persistente no nó kind, então os objetos sobrevivem à substituição do Pod. Apagar o cluster também apaga os dados locais. O smoke test S3 usa credenciais fictícias e verifica persistência. S3Mock implementa um subconjunto da API S3 e não acessa a AWS.

Depois de reconstruir a imagem com a mesma tag ou alterar o ConfigMap, atualize o cluster:

```bash
kind load docker-image notes-api:0.1.0 --name lab
kubectl apply -f k8s/
kubectl rollout restart deployment/notes-api -n lab
kubectl rollout status deployment/notes-api -n lab
```

Para apagar o cluster: `kind delete cluster --name lab`.

## Observabilidade local

Esta atividade é opcional. Com o cluster `lab`, a API e o DynamoDB Local em execução, aplique o stack no namespace isolado `observability`:

```bash
kubectl apply -k k8s/observability/
kubectl rollout status deployment/kube-state-metrics -n observability
kubectl rollout status deployment/prometheus -n observability
kubectl rollout status deployment/grafana -n observability
kubectl get pods -n observability
```

Em outro terminal, abra o Grafana:

```bash
kubectl port-forward -n observability service/grafana 3000:3000
```

Acesse <http://localhost:3000> e entre com `admin` / `admin`; no primeiro acesso, defina uma nova senha. Se a porta 3000 estiver ocupada, use `3001:3000` e abra <http://localhost:3001>. O dashboard **Spring AWS K8s Lab** já vem provisionado.

Prometheus coleta `/actuator/prometheus` da API e `/metrics` do kube-state-metrics. Para conferir os alvos, abra outro terminal e rode:

```bash
kubectl port-forward -n observability service/prometheus 9090:9090
```

Depois acesse <http://localhost:9090/targets>. Ambos os alvos devem aparecer como `UP`. Gere tráfego para ver séries nos painéis, por exemplo, usando o port-forward da API e o comando `curl` descrito acima. A atualização do painel pode levar até 15 segundos.

Os painéis de DynamoDB medem contagem, duração e falhas das operações feitas pela API. O DynamoDB Local não expõe métricas internas. O stack também não coleta logs, traces ou métricas detalhadas de CPU/memória dos nós. As séries ficam em armazenamento temporário e podem sumir ao recriar os Pods ou o cluster.

O stack roda dentro do `kind`, não usa credenciais AWS e não cria recursos AWS. Ele consome CPU e memória locais. Para removê-lo sem apagar a API ou o DynamoDB Local:

```bash
kubectl delete -k k8s/observability/
```

## Configuração

Todas as propriedades de `app/src/main/resources/application.properties` podem ser sobrescritas por variável de ambiente.

| Variável | Padrão | Descrição |
|----------|--------|-----------|
| `APP_DYNAMODB_TABLE` | `notes` | Nome da tabela |
| `APP_DYNAMODB_REGION` | `us-east-1` | Região AWS |
| `APP_DYNAMODB_ENDPOINT` | `http://localhost:8000` | Endpoint local do DynamoDB Local |
| `APP_DYNAMODB_CREATE_TABLE` | `false` | Cria a tabela ao subir; só para ambiente local |
| `APP_S3_ENDPOINT` | `http://localhost:9090` | Endpoint local do S3Mock |
| `APP_S3_BUCKET` | `notes-local` | Bucket local para snapshots |
| `APP_S3_REGION` | `us-east-1` | Região fictícia exigida pelo SDK |

Este lab não usa credenciais AWS. Mantenha o endpoint local padrão; não configure credenciais AWS nem deixe o endpoint vazio.

## Problemas comuns

| Sintoma | Causa provável |
|---------|----------------|
| Testes falham com erro de Docker | Docker parado; o Testcontainers precisa dele |
| `release version 25 not supported` | JDK instalado é anterior ao 25 |
| `ResourceNotFoundException` ao chamar a API | Tabela não existe: use `APP_DYNAMODB_CREATE_TABLE=true` no ambiente local |
| Pod em `ErrImagePull` no kind | Imagem não carregada: rode o `kind load docker-image` |
