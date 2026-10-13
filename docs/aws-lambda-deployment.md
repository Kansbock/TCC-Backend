# Deploy da API Aves no AWS Lambda

A API mantém sua imagem Spring Boot/Java 25 com Lambda Web Adapter, ECR
`aves-api`, stack `aves-api-stack`, Lambda `aves-api` e HTTP API existente.
Não criar IAM, tabela, índice, Streams, bucket ou função de notificações.
As tabelas `aves-users`, `aves-species` e `aves-sightings` continuam existentes;
notificações usam partições reservadas na última.

## Índice para ordenação de avistamentos

A página mobile de avistamentos usa a GSI `primary-species-index` da tabela
`aves-sightings` para ordenar globalmente pela primeira espécie do registro.
O índice tem `primarySpeciesPk` como chave de partição e
`primarySpeciesSk` como chave de ordenação; ambos os atributos são gravados
em novos registros e em edições pela API.

As tabelas e índices são externos a este repositório. Antes de publicar uma
imagem com esse recurso, a operação responsável deve criar essa GSI com
projeção `ALL`, aguardar o status `ACTIVE` e executar um backfill idempotente
dos dois atributos nos avistamentos existentes. A Lambda não cria índice nem
altera itens históricos automaticamente. Sem backfill, a ordenação cronológica
continua disponível, mas a ordenação por espécie não mostrará registros
legados.

A imagem habilita `AWS_LWA_ASYNC_INIT=true`: se o Spring ultrapassar a janela
inicial de 10 segundos, o Web Adapter continua verificando `/health` durante
a primeira invocação, sem provocar o reinício da aplicação por timeout de
inicialização. Isso não torna o cold start instantâneo nem aumenta os
30 segundos disponíveis no API Gateway.

## Agrupamento por minuto e migração histórica

A API aceita uma foto por `POST /api/sightings` e retorna o avistamento ao
qual ela foi associada. Mesmo conjunto de espécies (sem considerar ordem),
dia e `HH:mm` usam a mesma combinação; segundos e milissegundos não participam
da chave. Cada grupo tem até quatro fotos; a quinta abre outro UUID. Anexos
não alteram referências/contadores nem geram outra outbox.

Respostas acrescentam `imageUrls`; `imageUrl` continua sendo a primeira foto
ou `null`. Registros sem a lista usam a foto legada. `PUT` com `imageUrls`
substitui explicitamente a galeria, até quatro URLs distintas. Em galerias
maiores, reenviar apenas a capa antiga preserva as outras fotos; alterar ou
remover fotos usando só `imageUrl` retorna 409. Corrigir espécies/data/minuto
para uma combinação ocupada retorna 409, sem mesclar. Contagens passam a
representar grupos, não imagens.

Reservas `SIGHTING_GROUP#v1#...`, membros e recibos ficam na tabela existente,
sem atributos das GSIs. Não criar recursos ou concessões IAM. IDs absorvidos
resolvem por `SIGHTING_ALIAS#...` em consultas/replay; edição/exclusão de um
ID absorvido retorna 409 indicando o ID canônico. Fingerprints de requisições
antigas mantêm os segundos originais. Cursores antigos são rejeitados;
reiniciar a primeira página após migrar.

**Publicação e migração exigem autorização operacional separada.** Não
executar os exemplos contra AWS sem inventário, janela e manifesto aprovados.

1. Pausar worker e executor de notificações. Publicar imagem compatível com
   `./scripts/deploy-lambda.sh --apply --pause-sightings`, usando digest
   imutável e parâmetros anteriores de JWT/FCM. Sem `--apply` continua somente
   preparação. `SIGHTINGS_WRITES_PAUSED=true` faz POST/PUT/DELETE de
   avistamentos retornarem 503 e mantém leituras. Esperar requisições/leases.
2. Fazer backup restaurável e exportar snapshot paginado com perfil, região,
   conta e tabelas explícitos. O export da ferramenta exclui dispositivos,
   tokens e perfis de notificação; não substitui o backup completo.
3. Gerar manifesto local (dry-run padrão), revisar grupos 4/4/restante, aliases,
   contadores e anomalias. Eventos pendentes já iniciados bloqueiam aplicação
   para decisão operacional; não apagar ledgers ou simular entrega.
   Quando houver autorização explícita para encerrar todos os envios
   históricos pendentes, usar `--pending-policy cancel-historical` no dry-run.
   Essa política integra o hash aprovado: remove somente a fila dos registros
   migrados, registra cancelamento no evento e mantém progresso, tentativas,
   caixa e leitura. Não marca envios como entregues e não reenvia o passado.
   Estado ausente ou lease ainda ativo continua bloqueando a operação.
4. Aplicar somente o hash aprovado, ainda em manutenção. A ferramenta usa
   transações pequenas, condições sobre before-images e journal local atômico
   para retomar resposta/checkpoint perdido. Nenhuma operação move/apaga S3
   nem modifica o SQLite ou caixas de usuário.
5. Verificar dados da tabela base, referências e contadores; aguardar GSIs.
   Publicar app, reiniciar cursores e liberar com `--resume-sightings`.
   Um deploy sem nenhuma dessas flags preserva uma pausa existente.
   Não habilitar processamento FCM implicitamente.

```bash
# Export remoto: executar apenas depois da autorização e da pausa.
node scripts/migrate-sighting-groups.mjs --export --snapshot ./backup-grouping.json \
  --profile '<perfil-autorizado>' --region us-east-1 --account '<conta>' \
  --sightings-table aves-sightings --species-table aves-species

# Dry-run local: não chama AWS.
node scripts/migrate-sighting-groups.mjs --snapshot ./backup-grouping.json \
  --manifest ./manifest-grouping.json

# Alternativa somente com autorização para cancelar os envios históricos:
# acrescentar --pending-policy cancel-historical ao dry-run acima.
# Apply/rollback usam a política persistida no manifesto, sem essa flag.

# Apply autorizado: mesmas opções de alvo utilizadas no export.
node scripts/migrate-sighting-groups.mjs --apply --manifest ./manifest-grouping.json \
  --journal ./journal-grouping.json --approve-sha '<hash-revisado>' --confirm-maintenance \
  --profile '<perfil-autorizado>' --region us-east-1 --account '<conta>' \
  --sightings-table aves-sightings --species-table aves-species
```

Snapshots/manifestos/journals são locais, com permissão 0600; guardá-los fora
do Git, revisar referências e manter backup. Anomalia, versão divergente,
permissão negada ou alvo/hash diferente interrompe explicitamente.
Fotos iguais por URL não consomem vagas. Registros sem foto se associam ao
primeiro grupo; uma combinação sem fotos mantém um grupo vazio.

Para rollback sob pausa, usar o mesmo manifesto/journal e opções, substituindo
`--apply` por `--rollback`. As before-images recompõem detalhes, referências,
contagens e metadados. Não voltar apenas para a API de uma foto: ela não
entende galerias/reservas nem gerações da caixa limpa. Após novas escritas,
reconciliar antes de reverter. Limpezas de notificações confirmadas e ledgers
não são apagados por esta ferramenta.

```bash
# Validação local, somente dados sintéticos / DynamoDB Local.
node --test scripts/migrate-sighting-groups.test.mjs scripts/deploy-api.test.mjs
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./gradlew test \
  --tests 'tcc.aves.repository.dynamodb.SightingRepositoryDynamoDbAdapterTest' \
  --tests 'tcc.aves.e2e.ApiEndToEndTest' --no-daemon
```

## Pré-requisitos e tags

AWS CLI v2 com perfil válido na conta `640168426886`, região `us-east-1`;
Node.js e Docker para construir/publicar imagem; role preconfigurada
`aluno_23.01182-3_lambda_aves_api`, trust Lambda e policies existentes
`AWSLambdaBasicExecutionRole` e `AvesDynamoDbAccess`. A identidade de deploy
precisa das ações ECR/CloudFormation/Lambda/API Gateway/Logs e PassRole sobre
a role específica. Uma negativa interrompe; não criar concessões alternativas.

Cinco tags permanecem nos recursos etiquetáveis:
`environment=GRADUACAO`, `project=TCC`, `group=CIC12`,
`creator=MATHEUSKANSBOCK_23011823`, `owner=BOSSINI`.
O script verifica role/ECR e reaplica tags no estágio `$default`, que não
aceita essa propriedade pelo template desta conta. Não altera policies IAM.

## Preparação somente leitura

Para o primeiro update que adiciona FCM, indicar o arquivo **externo** da
conta de serviço e UUID persistido da conta executora do worker, não e-mail:

```bash
export AWS_PROFILE='640168426886_FullAccess-AlunosGraduacao'
export AWS_REGION='us-east-1'
export FCM_SERVICE_ACCOUNT_FILE='/caminho/externo/da-chave-fcm.json'
export NOTIFICATION_RUNNER_USER_ID='<uuid-da-conta-de-servico>'
./scripts/deploy-lambda.sh
```

O wrapper executa `deploy-api.mjs`. **Sem `--apply`, não publica nem altera
recursos**, diferentemente do script antigo. Confere identidade, role/tags,
tipo Image, ambiente completo <=4096 bytes, credencial/projeto, template e,
quando fornecido, digest existente. Exibe somente metadados e digest de
rollback. Ignora credenciais exportadas antigas e usa o perfil indicado.
Exportar em outro terminal não muda o ambiente de um processo aberto.

Publicação exige autorização explícita própria. Após recebê-la:

```bash
./scripts/deploy-lambda.sh --apply
```

Gera tag exclusiva `api-<timestamp>`, build `linux/amd64` com
`--provenance=false`, login ECR por stdin/config temporária e referência
imutável SHA-256. Cria change set com requisição protegida e não executa
update que adicione/remova/substitua recursos inesperadamente.
Um change set vazio/falho não é declarado sucesso: inspecionar antes de seguir.
Imagem publicada pode permanecer sem uso após falha; limpeza específica,
nunca apagar digest ativo ou rollback.

Processamento fica **false em cada deploy**, salvo `--enable-processing`
explicitamente autorizado. Habilitar também o flag do worker depois de
validar FCM e APK; veja [notifications.md](notifications.md).
O script não executa worker nem envia push diretamente.

## Segredos e preservação

`JwtSecret` e `FcmServiceAccountJson` são `NoEcho`, sem default, Outputs ou
Metadata. Chave FCM fica somente no ambiente da Lambda HTTP.
`NoEcho` não protege Outputs/Metadata nem restringe consultas da configuração;
controle IAM de leitura e mantenha os arquivos fora do Git/imagem/app/worker.
Não truncar/dividir/comprimir segredo para contornar os 4 KB.

Primeiro update preserva JWT existente e inicializa apenas FCM. Updates
seguintes preservam ambos por `UsePreviousValue`; ID executor anterior é
reutilizado se não informado. Variáveis desconhecidas existentes bloqueiam
update até serem incorporadas ao template, evitando apagamento silencioso.
Requests são arquivos 0600 em diretórios 0700, removidos ao finalizar; nenhum
segredo vai em argv ou log.

Rotação FCM: fornecer `FCM_SERVICE_ACCOUNT_FILE` novo e `--rotate-key`.
Rotação JWT: fornecer `JWT_SECRET_FILE` externo (ou `JWT_SECRET` por entrada
oculta) e `--rotate-jwt`. JWT precisa de ao menos 32 caracteres; trocar
invalida sessões existentes. Entrada JWT em update sem o flag é recusada.
Após validar a nova FCM, revogar a anterior; configuração publicada antiga
pode restaurar chave antiga se revertida.

Somente ao preparar uma implantação inicial **autorizada** da própria API,
usar `--create`, JWT externo, arquivo FCM e UUID executor. Na conta atual o
stack já existe: **não usar `--create`**. Nenhuma função separada é criada.

## Validar e reverter

Depois de um deploy autorizado, conferir `GET /health`, URL existente, tags,
login e rotas com dados sintéticos aprovados. Testar duas contas em APK
Android próprio; não declarar entrega pelo HTTP 200 do FCM.

Para rollback sem rebuild/publicação, usar o digest da **API** registrado:

```bash
export API_IMAGE_URI='640168426886.dkr.ecr.us-east-1.amazonaws.com/aves-api@sha256:<digest-anterior>'
./scripts/deploy-lambda.sh --apply
```

Sem flags de rotação, JWT/FCM ficam preservados e processamento desabilitado.
Desligar também o opt-in do worker. Não apagar fila, caixas, contadores ou
tabelas. Versão anterior à outbox não cria trabalhos para novos avistamentos;
trabalhos anteriores permanecem para retomada da versão compatível.

## Acesso mobile e CORS

Não há configuração CORS porque o consumidor é aplicativo nativo.
Cliente web requer origens explícitas em alteração separada, nunca `*`
para esta API autenticada.
