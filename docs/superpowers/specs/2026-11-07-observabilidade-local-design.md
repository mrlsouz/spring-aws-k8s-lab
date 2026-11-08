# Observabilidade local — desenho

## Intenção

Consultar, em painéis locais, métricas da Notes API, do workload no Kubernetes e das operações da API contra DynamoDB Local. O lab continuará sem criar ou acessar recursos AWS.

## Objetivos

- Coletar métricas da API pelo endpoint Actuator `/actuator/prometheus` já existente.
- Acompanhar estado dos Deployments e Pods do namespace `lab`, incluindo disponibilidade e reinícios.
- Medir contagem, duração e falhas das operações DynamoDB executadas pela API, sem alegar métricas internas que o DynamoDB Local não fornece.
- Exibir os dados em painéis Grafana acessíveis por `kubectl port-forward`.
- Documentar instalação, uso e remoção do stack local.

## Fora de escopo

- Logs centralizados, traces distribuídos, alertas externos e retenção durável.
- Métricas internas do DynamoDB Local, que não expõe uma interface Prometheus.
- AWS, Grafana Cloud, infraestrutura remota ou qualquer recurso com cobrança.
- Métricas detalhadas de CPU/memória do nó; a primeira versão cobre estado dos workloads Kubernetes.

## Abordagens consideradas

1. **Prometheus sozinho:** menos componentes, coleta e armazena métricas, com interface básica para consultas; não oferece os painéis Grafana escolhidos.
2. **Prometheus + Grafana:** Prometheus coleta e armazena; Grafana consulta e apresenta painéis. É a abordagem escolhida por equilibrar simplicidade e visualização.
3. **Plataforma all-in-one:** reduz ferramentas visíveis, mas acrescenta uma plataforma diferente ao lab e não elimina a necessidade de instrumentar fontes sem métricas nativas.

## Desenho escolhido

- Executar Prometheus e Grafana dentro do cluster `kind`, com manifests locais e imagens fixadas por versão.
- Configurar Prometheus para descobrir e coletar as métricas da Notes API.
- Usar `kube-state-metrics` para expor o estado dos objetos Kubernetes ao Prometheus. É um coletor auxiliar, não um backend de observabilidade.
- Adicionar métricas Micrometer de baixa cardinalidade ao redor das operações DynamoDB da aplicação: operação, duração e resultado. Não registrar IDs de notas, conteúdo ou outros dados por requisição.
- Provisionar no Grafana a fonte Prometheus e um dashboard inicial para API, estado dos workloads e operações DynamoDB.
- Acessar a interface por port-forward; sem Ingress, LoadBalancer ou exposição pública.

## Restrições e decisões para implementação

- O stack deve ser opcional e removível sem afetar API, DynamoDB Local ou S3Mock.
- Usar recursos modestos, compatíveis com o cluster `kind` já usado pelo lab.
- Proteger o endpoint Actuator Prometheus para uso interno no cluster; não expô-lo via Service público.
- Os dados de métricas podem ser efêmeros ao recriar o cluster.
- Validar manifests, instalação local, coleta dos targets e conteúdo básico do dashboard; executar os checks locais aplicáveis do repositório.

## Resultado esperado

Com os serviços do lab rodando e o stack aplicado, o usuário abre o Grafana por port-forward e consulta um dashboard único. API e estado de Pods vêm de endpoints Prometheus; métricas DynamoDB representam chamadas feitas pela API, não a saúde interna ou estatísticas do servidor DynamoDB Local.
