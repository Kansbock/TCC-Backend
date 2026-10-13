# Notificações Android do AvistAI

## Arquitetura sem alteração IAM

O cadastro de um avistamento grava, na mesma transação, seus dados, as
referências de espécies e dois itens de outbox na tabela **existente**
`aves-sightings`: fila pendente e estado do evento. O caminho idempotente
também inclui a reserva original; replay/conflito não deixa trabalho órfão.
São `4 + 2 × espécies` ações no caminho idempotente, limitadas a 100.
Edição, exclusão e replay não criam outra outbox. Não importar dados anteriores.

O worker chama `POST /api/internal/notification-jobs/process` antes e depois
de cada ciclo, inclusive sem fotos. A **Lambda HTTP existente** executa o lote
e envia FCM dentro dessa requisição; não há threads fire-and-forget.
Não criar Lambda, tabela, índice, bucket, Streams, SNS ou concessões IAM.
O Firebase é somente transporte Android: dados e autenticação continuam AWS.

Worker parado, falha no bootstrap/modelo ou sessão inválida atrasam histórico
e push. A fila fica no DynamoDB, independentemente do SQLite, e retoma quando
ele voltar. O intervalo padrão de 60 s não é SLA: classificação, rede e
backoff também consomem tempo.

## Armazenamento

Todos os itens novos usam namespace `NOTIF#`, sem `timelinePk`/`timelineSk`.
O índice de timeline permanece esparso e não contém notificações.

| Item | Partição / sort key | Garantia |
|---|---|---|
| Perfil | `NOTIF#USER#id` / `PROFILE` | Início original, preferência, geração da caixa, marco de limpeza e contador |
| Participante | `NOTIF#PARTICIPANTS` / `epochMillis(19 dígitos)#userId` | Query paginada |
| Dispositivo | `NOTIF#USER#id` / `DEVICE#installationId` | Token nativo e geração UUID |
| Reserva | `NOTIF#TOKEN#sha256(token)` / `OWNER` | Dono único, transferência atômica |
| Caixa | `NOTIF#USER#id` / `INBOX#epochMillis#sightingId` (geração 0) ou `INBOXG#geração#epochMillis#sightingId` | Query consistente somente da geração ativa |
| Localizador | `NOTIF#USER#id` / `NOTIFICATION#sightingId` | Resolve aviso por ID estável |
| Recibo de limpeza | `NOTIF#USER#id` / `CLEAR#sha256(Idempotency-Key)` | Resultado persistente da confirmação, sem TTL |
| Evento | `NOTIF#EVENT#sightingId` / `STATE` | Snapshot, lease de 120 s, checkpoints |
| Tentativa | `NOTIF#EVENT#sightingId` / `PUSH#generation` | Lease de 30 s e resultado |
| Pendente | `NOTIF#PENDING` / `epochMillis(próximo prazo)#sightingId` | Query de vencidos, sem Scan |

A caixa não usa `inbox-index`. Aviso, localizador e incremento são atômicos;
leitura e decremento também. Replay não reabre leitura. Não há TTL de
histórico, fila ou falhas. A conta real continua em `aves-users`, `USER#id`.
Atualizações condicionais legítimas de `notificationActivityAt`/`version`
preservam seus demais campos e impedem recriação após exclusão. As condições
de preferência, geração e dono acompanham atualizações de atividade de envio.
Não usar `ConditionCheckItem`; somente operações já autorizadas nas tabelas.

Participação começa no primeiro acesso autenticado da versão nova, mesmo sem
push. Desligar push ou sair não apaga a caixa. Conta que entra depois do
evento não recebe esse passado, mesmo que o worker processe com atraso.

## API

Todas as rotas exigem JWT e existência da conta. Destinatário vem da sessão,
nunca do corpo. Erros seguem `ApiError`; tokens não aparecem em respostas.

| Método/rota | Corpo ou resposta |
|---|---|
| POST `/api/notification-subscriptions` | `{startedAt,pushEnabled,unreadCount}` |
| GET `/api/notification-preferences` | Perfil; 404 sem participação |
| PUT `/api/notification-preferences` | `{pushEnabled:boolean}` |
| PUT `/api/notification-devices/{installationId}` | `{token:string}`; 204 |
| DELETE `/api/notification-devices/{installationId}` | Desativação idempotente; 204 |
| GET `/api/notifications?limit=20&cursor=...` | `{items,nextCursor}`; limite 1–50 |
| DELETE `/api/notifications` | `Idempotency-Key` obrigatória (1–128 letras, dígitos, `_` ou `-`); 204 |
| GET `/api/notifications/unread-count` | `{unreadCount}` |
| PATCH `/api/notifications/{id}/read` | Aviso com leitura confirmada |
| POST `/api/internal/notification-jobs/process` | Sem corpo; `{completed,deferred,failed}` |

O executor autoriza **somente** o UUID de `NOTIFICATION_RUNNER_USER_ID`,
configurado para a conta de serviço já existente. JWT ausente/inválido/de
conta excluída: 401; outra conta: 403; executor sem configuração/desabilitado:
503. Não aceitar parâmetros de envio; vazio ou `{}` são permitidos.
O worker não recebe token de dispositivo nem chave FCM.

### Limpar a caixa

`DELETE /api/notifications` avança `inboxGeneration`, zera `unreadCount` e
persiste `clearedThrough` e o recibo da chave na mesma transação que verifica
a existência da conta. O marco usa `Instant` com precisão de nanossegundos
da **criação do evento**, não o horário da foto. Eventos com criação menor
ou igual ao marco não entram; um evento posterior no mesmo milissegundo
continua elegível. Perfis e localizadores legados sem geração significam 0.

Somente a caixa visível é removida. Permanecem início da participação,
preferência, dados da conta, dispositivos, localizadores, histórico físico,
checkpoints e ledger FCM. Repetir a mesma chave devolve 204 sem limpar avisos
novos; outra confirmação deve usar outra chave. Não há TTL dos recibos.
Criação e leitura condicionam o contador à geração lida e reavaliam conflitos:
leitura de aviso limpo retorna 404; nenhum decremento atinge a geração nova.
Cursores incluem conta e geração; um cursor anterior à limpeza retorna 400,
exigindo recarregar a primeira página.

O dispatcher e a reserva transacional de tentativa FCM verificam marco e
geração do localizador. Um aviso de geração antiga é inelegível mesmo que
tenha timestamp futuro; eventos atrasados não ressurgem. A conta B não é
afetada por uma limpeza na conta A. Não é possível recolher um envio já
em andamento/aceito pelo FCM, nem limpar a central nativa do Android.

No app a ação pede confirmação, permite cancelar sem requisição e mostra
carregamento/erro. Falha conserva a caixa e a mesma chave para retry; sucesso
invalida respostas de listagem/leitura/contador/sincronização anteriores,
zera o estado local e reconcilia eventos realmente novos. Troca de conta
invalida respostas da sessão antiga. Outra instalação observa a limpeza
persistida na próxima consulta.

Até dez trabalhos vencidos por chamada. Orçamento monotônico de 20 s começa
antes da consulta de autenticação; margem de 15 s impede assumir mais trabalho
perto do limite HTTP de 30 s. Notificações usam cliente DynamoDB com timeout
total de 2 s/tentativa de 1 s; o restante da API mantém seu cliente original.
OAuth usa Google Auth Library com transporte limitado a 3 s e sem retries
internos; FCM limita conexão a 2 s/requisição a 5 s. Falhas podem deixar lease
temporário até expirar, mas nunca removem trabalho incompleto.

## FCM e recuperação

Histórico de todos os elegíveis precede push. Google Auth mantém OAuth em
memória com escopo `https://www.googleapis.com/auth/firebase.messaging`.
Destino fixo: `https://fcm.googleapis.com/v1/projects/avistai-tcc/messages:send`.
Payload usa token FCM nativo, IDs string, canal `sightings` e tag estável.
Sem dados pessoais, URLs privadas ou fotos. A foto pode ainda estar sendo
movida; um avistamento removido usa o estado de indisponibilidade do detalhe.

ID FCM confirma **aceitação**, não recebimento ou leitura. Falha entre envio
aceito e gravação do ledger pode duplicar push; tag mitiga, não garante
exactly-once. Logout não cancela mensagem aceita. Tentativas confirmadas,
inboxes e contadores não são repetidos na retomada.

UNREGISTERED retira somente a geração correspondente. Payload inválido,
401/403 e SENDER_ID_MISMATCH não desativam todos os dispositivos. Rede/429/5xx
persistem próximo prazo com backoff/jitter e Retry-After; 429 respeita mínimo
de 60 s. Não dormir longamente nem enviar antes do prazo. Até cinco tentativas
por geração; falha permanente/esgotada retira o evento da fila pendente,
mantendo `jobStatus=FAILED`, erro categorizado, snapshot, progresso e ledger.
Logs não incluem chave, token ou resposta privada do provedor.

`completed` representa trabalho concluído e retirado; `deferred` inclui
backoff, orçamento e concorrência; `failed` representa falha terminal
persistida. Erro SDK inesperado pode devolver erro HTTP: a fila continua
persistida. Dois executores usam lease e condições para não remover/reagendar
o trabalho do outro. Resposta perdida após checkpoint não perde o evento.

Revisão de falhas é operacional e autorizada: consultar a partição
`NOTIF#EVENT#id`, corrigir a causa e definir quais gerações podem tentar de
novo antes de qualquer alteração transacional do ledger/estado/fila. Não
limpar histórico, zerar contadores ou apagar tentativas aceitas. Não há
endpoint público de reprocessamento terminal nem destino S3 novo.

## Configuração e implantação

Somente a **API** recebe `FCM_SERVICE_ACCOUNT_JSON` e `FCM_PROJECT_ID`.
`FcmServiceAccountJson` e `JwtSecret` são parâmetros CloudFormation `NoEcho`,
sem valor default, Outputs ou Metadata. Não copiar chave para app, worker,
Git, imagem ou argumentos. `NoEcho`/criptografia não impedem leitura da
configuração por identidades autorizadas; restrinja acesso.

Ver [aws-lambda-deployment.md](aws-lambda-deployment.md): script lê arquivos
externos em memória, valida chave RSA/projeto, compacta JSON e soma todo o
ambiente UTF-8, incluindo JWT/ID/flags, até 4096 bytes. Requisição temporária
0600 em diretório 0700 é removida ao finalizar. Novos parâmetros precisam da
chave no primeiro update; depois `UsePreviousValue` preserva ambos os segredos.
Rotação exige `--rotate-key` ou `--rotate-jwt`; revogar chave anterior somente
após validação. Rollback de configuração antiga pode restaurar chave antiga.

Rollout **não executado**: publicação, gravações compartilhadas e push precisam
de autorização separada. Publicar primeiro API com processamento desligado,
instalar APK próprio e registrar participação/dispositivos; após validar
conta executora/FCM e autorização, habilitar API com `--enable-processing`
e worker com `NOTIFICATION_PROCESSING_ENABLED=true`. O default é false em ambos.
Validar duas contas, recebimento/navegação, recusa/logout, replay e retomada.
Expo Go Android SDK 54 não comprova push.

Para rollback, desabilitar processamento/worker e reaplicar **digest da API**
sem rotação. Preservar JWT, chave válida, histórico, pendentes e tabelas.
Uma API anterior às notificações não enfileira eventos novos; os já persistidos
continuam disponíveis quando retornar a versão compatível. Limpeza somente de
contas/instalações/objetos sintéticos explicitamente identificados.

## Validação local

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./gradlew test \
  --tests 'tcc.aves.notification.*Test' \
  --tests 'tcc.aves.controller.ControllerApiTest' \
  --tests 'tcc.aves.service.SightingServiceTest' bootJar --no-daemon
node --test scripts/deploy-api.test.mjs
```

HTTP Spring real e DynamoDB Local/Testcontainers usam dados/credenciais
sintéticos. OAuth/FCM usam servidor local e RSA efêmera, não a chave externa.
Testes do worker exercitam loop ocioso/dry-run, refresh JWT e isolamento do
estado de fotos; Jest/TypeScript preservam contrato e UX do app.
