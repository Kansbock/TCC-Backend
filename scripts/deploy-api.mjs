import { execFileSync } from 'node:child_process';
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmdirSync, unlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createPrivateKey } from 'node:crypto';

export const TAGS = {
  environment: 'GRADUACAO', project: 'TCC', group: 'CIC12',
  creator: 'MATHEUSKANSBOCK_23011823', owner: 'BOSSINI',
};
export const REPOSITORY = '640168426886.dkr.ecr.us-east-1.amazonaws.com/aves-api';

export function credentialJson(source) {
  let credential;
  try { credential = JSON.parse(source); } catch { throw new Error('Invalid FCM JSON; content omitted.'); }
  if (!credential || credential.type !== 'service_account' || credential.project_id !== 'avistai-tcc'
      || typeof credential.client_email !== 'string' || typeof credential.private_key !== 'string'
      || (credential.token_uri !== undefined && credential.token_uri !== 'https://oauth2.googleapis.com/token')
      || !credential.private_key.startsWith('-----BEGIN PRIVATE KEY-----')) {
    throw new Error('Invalid FCM account/project; content omitted.');
  }
  try {
    if (createPrivateKey(credential.private_key).asymmetricKeyType !== 'rsa') throw new Error('RSA required');
  } catch {
    throw new Error('Invalid FCM private key; content omitted.');
  }
  return JSON.stringify(credential);
}

export function validateEnvironment(variables) {
  if (Object.values(variables).some(value => typeof value !== 'string')) {
    throw new Error('Environment values must be strings; content omitted.');
  }
  const bytes = Object.entries(variables).reduce((sum, [key, value]) =>
    sum + Buffer.byteLength(key, 'utf8') + Buffer.byteLength(value, 'utf8'), 0);
  if (bytes > 4096) throw new Error(`Lambda environment exceeds 4096 bytes (${bytes}).`);
  return bytes;
}

export function secretParameter(key, { previous, rotate, value }) {
  if (previous && !rotate) return { ParameterKey: key, UsePreviousValue: true };
  if (!value) throw new Error(`${key} requires an external value for initialization or explicit rotation.`);
  return { ParameterKey: key, ParameterValue: value };
}

export function apiImageUri(repository, digest) {
  if (repository !== REPOSITORY || !/^sha256:[a-f0-9]{64}$/.test(digest)) {
    throw new Error('Expected an immutable API digest in the approved aves-api repository.');
  }
  return `${repository}@${digest}`;
}

export function environmentForUpdate(current, { jwt, credential, runner, enabled, writesPaused = current.SIGHTINGS_WRITES_PAUSED || 'false' }) {
  const known = ['JWT_SECRET', 'FCM_SERVICE_ACCOUNT_JSON', 'FCM_PROJECT_ID',
    'NOTIFICATION_RUNNER_USER_ID', 'NOTIFICATION_PROCESSING_ENABLED', 'SIGHTINGS_WRITES_PAUSED'];
  if (Object.keys(current).some(key => !known.includes(key))) {
    throw new Error('Unmanaged existing environment variables: incorporate them into the template before updating.');
  }
  if (!jwt || jwt.length < 32) throw new Error('JWT must contain at least 32 characters; content omitted.');
  if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(runner)) {
    throw new Error('Set NOTIFICATION_RUNNER_USER_ID to the existing service account UUID.');
  }
  if (!['true', 'false'].includes(enabled)) throw new Error('Processing flag must be true or false.');
  if (!['true', 'false'].includes(writesPaused)) throw new Error('Sightings maintenance flag must be true or false.');
  return {
    JWT_SECRET: jwt, FCM_SERVICE_ACCOUNT_JSON: credential, FCM_PROJECT_ID: 'avistai-tcc',
    NOTIFICATION_RUNNER_USER_ID: runner, NOTIFICATION_PROCESSING_ENABLED: enabled,
    SIGHTINGS_WRITES_PAUSED: writesPaused,
  };
}

export function maintenanceFlag(current, pause, resume) {
  if (pause && resume) throw new Error('Choose --pause-sightings or --resume-sightings, not both.');
  return pause ? 'true' : resume ? 'false' : current.SIGHTINGS_WRITES_PAUSED || 'false';
}

function main() {
  const flags = new Set(process.argv.slice(2));
  for (const flag of flags) {
    if (!['--apply', '--create', '--rotate-key', '--rotate-jwt', '--enable-processing',
      '--pause-sightings', '--resume-sightings'].includes(flag)) {
      throw new Error('Unknown deployment flag.');
    }
  }
  const create = flags.has('--create');
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const region = process.env.AWS_REGION || 'us-east-1';
  if (region !== 'us-east-1') throw new Error('Expected region us-east-1.');
  const env = { ...process.env, AWS_PROFILE: process.env.AWS_PROFILE || '640168426886_FullAccess-AlunosGraduacao',
    AWS_REGION: region, AWS_PAGER: '' };
  for (const name of ['AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN', 'AWS_SECURITY_TOKEN']) delete env[name];
  const output = args => {
    try {
      return execFileSync('aws', [...args, '--output', 'json'], {
        env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
      }).trim();
    } catch (error) {
      const code = String(error.stderr || '').match(/\(([A-Za-z0-9]+)\)/)?.[1] || 'AWS_CLI_ERROR';
      throw new Error(`AWS ${args.slice(0, 2).join(' ')} failed: ${code}. Output omitted.`);
    }
  };
  const aws = args => { const value = output(args); return value ? JSON.parse(value) : null; };
  const account = aws(['sts', 'get-caller-identity']).Account;
  if (account !== '640168426886') throw new Error('Unexpected AWS account.');
  const stack = 'aves-api-stack';
  const functionName = 'aves-api';
  const roleName = 'aluno_23.01182-3_lambda_aves_api';
  const role = `arn:aws:iam::${account}:role/${roleName}`;
  if (process.env.EXECUTION_ROLE_ARN && process.env.EXECUTION_ROLE_ARN !== role) throw new Error('Unexpected execution role.');
  const roleData = aws(['iam', 'get-role', '--role-name', roleName]).Role;
  if (!roleData.AssumeRolePolicyDocument.Statement.some(statement =>
    statement.Effect === 'Allow' && [statement.Principal?.Service].flat().includes('lambda.amazonaws.com'))) {
    throw new Error('Existing role must trust Lambda.');
  }
  const policies = aws(['iam', 'list-attached-role-policies', '--role-name', roleName]).AttachedPolicies;
  for (const arn of ['arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole',
    `arn:aws:iam::${account}:policy/AvesDynamoDbAccess`]) {
    if (!policies.some(policy => policy.PolicyArn === arn)) throw new Error('Required existing role policy unavailable.');
  }
  const repository = aws(['ecr', 'describe-repositories', '--repository-names', 'aves-api']).repositories[0];
  apiImageUri(repository.repositoryUri, `sha256:${'0'.repeat(64)}`);
  const roleTags = Object.fromEntries(aws(['iam', 'list-role-tags', '--role-name', roleName]).Tags.map(tag => [tag.Key, tag.Value]));
  const ecrTags = Object.fromEntries(aws(['ecr', 'list-tags-for-resource',
    '--resource-arn', repository.repositoryArn]).tags.map(tag => [tag.Key, tag.Value]));
  for (const [key, value] of Object.entries(TAGS)) {
    if (roleTags[key] !== value || ecrTags[key] !== value) throw new Error(`Missing required role/ECR tag ${key}.`);
  }
  const previous = create ? [] : aws(['cloudformation', 'describe-stacks', '--stack-name', stack]).Stacks[0].Parameters;
  const configuration = create ? null : aws(['lambda', 'get-function-configuration', '--function-name', functionName]);
  if (configuration && (configuration.PackageType !== 'Image' || configuration.Role !== role)) {
    throw new Error('Unexpected existing API function type/role.');
  }
  const current = configuration?.Environment?.Variables || {};
  const hadFcm = previous.some(parameter => parameter.ParameterKey === 'FcmServiceAccountJson');
  const rotateFcm = flags.has('--rotate-key');
  const rotateJwt = flags.has('--rotate-jwt');
  if (!create && !rotateJwt && (process.env.JWT_SECRET || process.env.JWT_SECRET_FILE)) {
    throw new Error('JWT input requires explicit --rotate-jwt on updates.');
  }
  let credential = current.FCM_SERVICE_ACCOUNT_JSON;
  if (!hadFcm || rotateFcm) {
    if (!process.env.FCM_SERVICE_ACCOUNT_FILE) throw new Error('Set FCM_SERVICE_ACCOUNT_FILE to the external private JSON file.');
    credential = readFileSync(process.env.FCM_SERVICE_ACCOUNT_FILE, 'utf8');
  }
  credential = credentialJson(credential);
  const jwt = create || rotateJwt
    ? (process.env.JWT_SECRET_FILE ? readFileSync(process.env.JWT_SECRET_FILE, 'utf8').trim() : process.env.JWT_SECRET)
    : current.JWT_SECRET;
  const runner = process.env.NOTIFICATION_RUNNER_USER_ID || current.NOTIFICATION_RUNNER_USER_ID;
  const enabled = flags.has('--enable-processing') ? 'true' : 'false';
  const writesPaused = maintenanceFlag(current, flags.has('--pause-sightings'), flags.has('--resume-sightings'));
  const variables = environmentForUpdate(current, { jwt, credential, runner, enabled, writesPaused });
  const bytes = validateEnvironment(variables);
  const templatePath = resolve(root, 'infra/aves-lambda.yaml');
  const template = readFileSync(templatePath, 'utf8');
  aws(['cloudformation', 'validate-template', '--template-body', `file://${templatePath}`]);
  let imageUri = process.env.API_IMAGE_URI;
  if (imageUri) {
    const [repositoryUri, digest] = imageUri.split('@');
    if (apiImageUri(repositoryUri, digest) !== imageUri) throw new Error('Invalid API_IMAGE_URI.');
    aws(['ecr', 'describe-images', '--repository-name', 'aves-api', '--image-ids', `imageDigest=${digest}`]);
  }
  console.log(`API preflight: account ${account}, ${bytes}/4096 bytes, processing=${enabled}, sightingsPaused=${writesPaused}. JWT/key content omitted.`);
  if (configuration) {
    const old = aws(['lambda', 'get-function', '--function-name', functionName]).Code.ResolvedImageUri;
    console.log(`Rollback API digest: ${old}`);
  }
  if (!flags.has('--apply')) {
    console.log('Read-only preparation complete; no image upload, resource update or push.');
    return;
  }
  const directory = mkdtempSync(resolve(tmpdir(), 'aves-api-deploy-'));
  chmodSync(directory, 0o700);
  const file = resolve(directory, 'request.json');
  const dockerConfig = resolve(directory, 'config.json');
  const docker = (args, options = {}) => {
    try { return execFileSync('docker', args, { env, cwd: root, stdio: 'inherit', ...options }); }
    catch { throw new Error(`Docker ${args[0]} failed; deployment stopped.`); }
  };
  try {
    if (!imageUri) {
      const tag = `api-${Date.now()}`;
      const remote = `${repository.repositoryUri}:${tag}`;
      docker(['build', '--platform', 'linux/amd64', '--provenance=false', '--tag', remote, '.']);
      docker(['login', '--username', 'AWS', '--password-stdin', repository.repositoryUri.split('/')[0]], {
        input: output(['ecr', 'get-login-password', '--region', region]),
        env: { ...env, DOCKER_CONFIG: directory }, stdio: ['pipe', 'pipe', 'pipe'],
      });
      if (existsSync(dockerConfig)) chmodSync(dockerConfig, 0o600);
      docker(['push', remote], { env: { ...env, DOCKER_CONFIG: directory } });
      imageUri = apiImageUri(repository.repositoryUri, aws(['ecr', 'describe-images',
        '--repository-name', 'aves-api', '--image-ids', `imageTag=${tag}`]).imageDetails[0].imageDigest);
    }
    const changeSet = `api-${Date.now()}`;
    const request = {
      StackName: stack, ChangeSetName: changeSet, ChangeSetType: create ? 'CREATE' : 'UPDATE', TemplateBody: template,
      Parameters: [
        ...Object.entries({ ImageUri: imageUri, ExecutionRoleArn: role, FunctionName: functionName,
          NotificationRunnerUserId: runner, NotificationProcessingEnabled: enabled, SightingsWritesPaused: writesPaused })
          .map(([ParameterKey, ParameterValue]) => ({ ParameterKey, ParameterValue })),
        secretParameter('JwtSecret', { previous: !create, rotate: rotateJwt, value: jwt }),
        secretParameter('FcmServiceAccountJson', { previous: hadFcm, rotate: rotateFcm, value: credential }),
      ],
      Tags: Object.entries(TAGS).map(([Key, Value]) => ({ Key, Value })),
    };
    writeFileSync(file, JSON.stringify(request), { mode: 0o600, flag: 'wx' });
    aws(['cloudformation', 'create-change-set', '--cli-input-json', `file://${file}`]);
    aws(['cloudformation', 'wait', 'change-set-create-complete', '--stack-name', stack, '--change-set-name', changeSet]);
    const changes = aws(['cloudformation', 'describe-change-set', '--stack-name', stack, '--change-set-name', changeSet]).Changes;
    if (!create && changes.some(change => change.ResourceChange?.Action !== 'Modify'
      || change.ResourceChange?.Replacement !== 'False')) {
      throw new Error('Unexpected resource addition/removal/replacement; inspect change set before execution.');
    }
    aws(['cloudformation', 'execute-change-set', '--stack-name', stack, '--change-set-name', changeSet]);
    aws(['cloudformation', 'wait', create ? 'stack-create-complete' : 'stack-update-complete', '--stack-name', stack]);
    const outputs = aws(['cloudformation', 'describe-stacks', '--stack-name', stack]).Stacks[0].Outputs;
    const apiId = outputs.find(item => item.OutputKey === 'HttpApiId').OutputValue;
    aws(['apigatewayv2', 'tag-resource', '--resource-arn',
      `arn:aws:apigateway:${region}::/apis/${apiId}/stages/$default`,
      '--tags', JSON.stringify(TAGS)]);
    console.log(`API deployed; processing=${enabled}; endpoint=${outputs.find(item => item.OutputKey === 'HttpApiEndpoint').OutputValue}`);
  } finally {
    if (existsSync(file)) unlinkSync(file);
    if (existsSync(dockerConfig)) unlinkSync(dockerConfig);
    rmdirSync(directory);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
