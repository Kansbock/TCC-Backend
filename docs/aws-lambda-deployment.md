# Deploy da API Aves no AWS Lambda

A API mantém sua imagem Spring Boot/Java 25 com Lambda Web Adapter, ECR
`aves-api`, stack `aves-api-stack`, Lambda `aves-api` e HTTP API existente.
Não criar IAM, tabela, índice, Streams, bucket ou função de notificações.
As tabelas `aves-users`, `aves-species` e `aves-sightings` continuam existentes;
notificações usam partições reservadas na última.

A imagem habilita `AWS_LWA_ASYNC_INIT=true`: se o Spring ultrapassar a janela
inicial de 10 segundos, o Web Adapter continua verificando `/health` durante
a primeira invocação, sem provocar o reinício da aplicação por timeout de
inicialização. Isso não torna o cold start instantâneo nem aumenta os
30 segundos disponíveis no API Gateway.

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
