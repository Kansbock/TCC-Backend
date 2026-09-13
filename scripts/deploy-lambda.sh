#!/usr/bin/env bash

set -euo pipefail

readonly AWS_REGION="${AWS_REGION:-us-east-1}"
readonly EXPECTED_ACCOUNT_ID="640168426886"
readonly ECR_REPOSITORY="aves-api"
readonly STACK_NAME="aves-api-stack"
readonly FUNCTION_NAME="aves-api"
readonly EXPECTED_ROLE_NAME="aluno_23.01182-3_lambda_aves_api"
readonly REQUIRED_POLICY_ARNS=(
  "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
  "arn:aws:iam::640168426886:policy/AvesDynamoDbAccess"
)
readonly STACK_TAGS=(
  "environment=GRADUACAO"
  "project=TCC"
  "group=CIC12"
  "creator=MATHEUSKANSBOCK_23011823"
  "owner=BOSSINI"
)
readonly API_GATEWAY_TAGS='environment=GRADUACAO,project=TCC,group=CIC12,creator=MATHEUSKANSBOCK_23011823,owner=BOSSINI'
readonly ECR_TAGS=(
  "Key=environment,Value=GRADUACAO"
  "Key=project,Value=TCC"
  "Key=group,Value=CIC12"
  "Key=creator,Value=MATHEUSKANSBOCK_23011823"
  "Key=owner,Value=BOSSINI"
)

export AWS_REGION

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

require_role_policy() {
  local policy_arn="$1"
  local count

  count="$(aws iam list-attached-role-policies \
    --role-name "$EXPECTED_ROLE_NAME" \
    --query "length(AttachedPolicies[?PolicyArn=='${policy_arn}'])" \
    --output text)"
  [[ "$count" == "1" ]] || die "Execution role is missing required policy: $policy_arn"
}

require_role_tag() {
  local tag="$1"
  local key="${tag%%=*}"
  local value="${tag#*=}"
  local count

  count="$(aws iam list-role-tags \
    --role-name "$EXPECTED_ROLE_NAME" \
    --query "length(Tags[?Key=='${key}' && Value=='${value}'])" \
    --output text)"
  [[ "$count" == "1" ]] || die "Execution role is missing required tag: $tag"
}

require_command aws
require_command docker

: "${EXECUTION_ROLE_ARN:?Set EXECUTION_ROLE_ARN to the dedicated Lambda execution role ARN.}"

account_id="$(aws sts get-caller-identity --query Account --output text)"
[[ "$account_id" == "$EXPECTED_ACCOUNT_ID" ]] ||
  die "Connected account $account_id does not match expected account $EXPECTED_ACCOUNT_ID."

expected_role_arn="arn:aws:iam::${account_id}:role/${EXPECTED_ROLE_NAME}"
[[ "$EXECUTION_ROLE_ARN" == "$expected_role_arn" ]] ||
  die "EXECUTION_ROLE_ARN must be $expected_role_arn."

aws iam get-role --role-name "$EXPECTED_ROLE_NAME" --query 'Role.Arn' --output text >/dev/null
for policy_arn in "${REQUIRED_POLICY_ARNS[@]}"; do
  require_role_policy "$policy_arn"
done
for tag in "${STACK_TAGS[@]}"; do
  require_role_tag "$tag"
done

ecr_uri="$(aws ecr describe-repositories \
  --repository-names "$ECR_REPOSITORY" \
  --query 'repositories[0].repositoryUri' \
  --output text)"
[[ "$ecr_uri" == "${account_id}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPOSITORY}" ]] ||
  die "Unexpected ECR repository URI: $ecr_uri"

partition="$(aws sts get-caller-identity --query Arn --output text | cut -d: -f2)"
ecr_arn="arn:${partition}:ecr:${AWS_REGION}:${account_id}:repository/${ECR_REPOSITORY}"
aws ecr tag-resource --resource-arn "$ecr_arn" --tags "${ECR_TAGS[@]}"

image_tag="${IMAGE_TAG:-$(git rev-parse --short=12 HEAD)}"
local_image="${ECR_REPOSITORY}:${image_tag}"
remote_image="${ecr_uri}:${image_tag}"

docker build --platform linux/amd64 --tag "$local_image" .
aws ecr get-login-password --region "$AWS_REGION" |
  docker login --username AWS --password-stdin "${account_id}.dkr.ecr.${AWS_REGION}.amazonaws.com"
docker tag "$local_image" "$remote_image"
docker push "$remote_image"

image_digest="$(aws ecr describe-images \
  --repository-name "$ECR_REPOSITORY" \
  --image-ids imageTag="$image_tag" \
  --query 'imageDetails[0].imageDigest' \
  --output text)"
[[ "$image_digest" == sha256:* ]] || die "Could not resolve an image digest for $remote_image."
image_uri="${ecr_uri}@${image_digest}"

aws cloudformation validate-template --template-body file://infra/aves-lambda.yaml >/dev/null
parameter_overrides=(
  "ImageUri=${image_uri}"
  "ExecutionRoleArn=${EXECUTION_ROLE_ARN}"
  "FunctionName=${FUNCTION_NAME}"
)
if [[ -n "${JWT_SECRET:-}" ]]; then
  parameter_overrides+=("JwtSecret=${JWT_SECRET}")
else
  aws cloudformation describe-stacks \
    --stack-name "$STACK_NAME" \
    --query 'Stacks[0].StackStatus' \
    --output text >/dev/null ||
    die "JWT_SECRET must be set when creating the stack for the first time."
fi

aws cloudformation deploy \
  --stack-name "$STACK_NAME" \
  --template-file infra/aves-lambda.yaml \
  --parameter-overrides "${parameter_overrides[@]}" \
  --tags "${STACK_TAGS[@]}" \
  --no-fail-on-empty-changeset

api_id="$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" \
  --query "Stacks[0].Outputs[?OutputKey=='HttpApiId'].OutputValue | [0]" \
  --output text)"
stage_arn="arn:${partition}:apigateway:${AWS_REGION}::/apis/${api_id}/stages/\$default"
aws apigatewayv2 tag-resource --resource-arn "$stage_arn" --tags "$API_GATEWAY_TAGS"

endpoint="$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" \
  --query "Stacks[0].Outputs[?OutputKey=='HttpApiEndpoint'].OutputValue | [0]" \
  --output text)"
printf 'Deployment complete. HTTP API endpoint: %s\n' "$endpoint"
