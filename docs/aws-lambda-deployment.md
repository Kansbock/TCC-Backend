# Deploy da API Aves no AWS Lambda

## Recursos

O deploy cria uma Lambda por imagem, HTTP API com rota `ANY /{proxy+}`, grupo
CloudWatch Logs e o stack CloudFormation. O repositório ECR `aves-api` é
reutilizado. As tabelas DynamoDB `aves-users`, `aves-species` e
`aves-sightings` já existem e não são alteradas.

Todos os recursos criados e o ECR recebem estas tags:

```text
environment=GRADUACAO
project=TCC
group=CIC12
creator=MATHEUSKANSBOCK_23011823
owner=BOSSINI
```

O template declara as tags na Lambda, HTTP API e Log Group. Após criar o
stack, o script aplica as mesmas tags ao estágio `$default` pela API do API
Gateway, porque a propriedade de tags do estágio não é aceita pela validação
CloudFormation desta conta.

## Pré-requisitos

- AWS CLI v2 autenticado na conta `640168426886`, região `us-east-1`;
- Docker em execução;
- role IAM `aluno_23.01182-3_lambda_aves_api`, com trust policy para
  `lambda.amazonaws.com` e as policies:
  - `arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole`;
  - `arn:aws:iam::640168426886:policy/AvesDynamoDbAccess`.
- permissão para usar `iam:PassRole` somente sobre a role acima, além das
  permissões de ECR, CloudFormation, Lambda, API Gateway e CloudWatch Logs
  exigidas pelo deploy.
- `JWT_SECRET` aleatório de ao menos 32 bytes, guardado fora do repositório.

## Implantar

No primeiro deploy, defina o ARN da role e informe o segredo sem exibi-lo no
terminal. O valor digitado por `read` não entra no histórico de comandos:

```bash
export EXECUTION_ROLE_ARN='arn:aws:iam::640168426886:role/aluno_23.01182-3_lambda_aves_api'
read -r -s -p 'JWT_SECRET: ' JWT_SECRET
printf '\n'
export JWT_SECRET
./scripts/deploy-lambda.sh
unset JWT_SECRET
```

O script constrói uma imagem `linux/amd64`, a envia para o ECR e atualiza o
stack `aves-api-stack` com uma referência imutável pelo digest da imagem. Ao
final, ele exibe a URL HTTPS da HTTP API a ser configurada no aplicativo mobile.
Use sempre a mesma chave ao atualizar ou reverter a imagem, pois sua troca
invalida os JWTs emitidos anteriormente.

Em atualizações normais, informe apenas `EXECUTION_ROLE_ARN` e execute o
script. Quando `JWT_SECRET` não é definida, o CloudFormation preserva o valor
`NoEcho` já existente no stack. Para rotacionar a chave, execute novamente o
bloco com `read` e `export JWT_SECRET`.

Para publicar uma tag diferente da derivada do commit Git, defina
`IMAGE_TAG` antes de executar o script. A Lambda sempre recebe o digest
resolvido, e não a tag mutável.

## Configuração do segredo JWT

O template recebe `JwtSecret` como parâmetro `NoEcho` e o aplica à variável
`JWT_SECRET` da Lambda em cada deploy. Isso evita que uma atualização do
CloudFormation apague a configuração. `NoEcho` mascara o valor em outputs e
eventos do stack, mas não substitui um serviço de segredos: restrinja acesso à
configuração CloudFormation/Lambda e mantenha a chave fora do Git e de
terminais compartilhados.

## Validar e reverter

Depois de um deploy com `JWT_SECRET`, valide `GET /health` pela URL impressa
pelo script. Em seguida, valide o fluxo de cadastro, login e uma rota
protegida com dados de teste exclusivos, removendo o usuário criado ao final.

Para reverter, execute novamente o deploy definindo `IMAGE_TAG` para uma tag
anterior que ainda exista no ECR. O script resolve e aplica o digest daquela
imagem, preservando a infraestrutura e reaplicando o `JWT_SECRET` fornecido
na sessão.

## Frontend mobile

Não há configuração CORS porque o consumidor é um aplicativo mobile nativo.
Se um cliente web for adicionado, defina origens explícitas na HTTP API em uma
alteração separada; não use `*` para uma API autenticada.
