import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdtempSync, unlinkSync, rmdirSync, existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, delimiter } from 'node:path';
import { fileURLToPath } from 'node:url';
import { generateKeyPairSync } from 'node:crypto';
import { credentialJson, validateEnvironment, secretParameter, environmentForUpdate, apiImageUri, maintenanceFlag, REPOSITORY, TAGS } from './deploy-api.mjs';

const credential = credentialJson(JSON.stringify({
  type: 'service_account', project_id: 'avistai-tcc', client_email: 'synthetic@example.test',
  private_key: generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey.export({ type: 'pkcs8', format: 'pem' }),
}));
const options = {
  jwt: 'synthetic-jwt-key-not-production-12345678', credential,
  runner: '00000000-0000-4000-8000-000000000001', enabled: 'false',
};

test('compact credential rejects wrong project, endpoint and malformed values without disclosing contents', () => {
  assert.equal(JSON.parse(credential).project_id, 'avistai-tcc');
  for (const source of ['null', '{sensitive-test-data', credential.replace('avistai-tcc', 'wrong-project'),
    credential.replace('service_account', 'invalid')]) {
    assert.throws(() => credentialJson(source), /content omitted/);
  }
  assert.throws(() => credentialJson(JSON.stringify({ ...JSON.parse(credential), token_uri: 'https://example.test/token' })));
});

test('complete environment includes JWT, credential, runner and disabled processing and enforces UTF-8 limit', () => {
  const variables = environmentForUpdate({ JWT_SECRET: options.jwt }, options);
  assert.equal(variables.JWT_SECRET, options.jwt);
  assert.equal(variables.NOTIFICATION_PROCESSING_ENABLED, 'false');
  assert.equal(variables.FCM_SERVICE_ACCOUNT_JSON, credential);
  assert.ok(validateEnvironment(variables) < 4096);
  assert.throws(() => validateEnvironment({ ...variables, EXTRA: 'x'.repeat(4096) }), /exceeds/);
  assert.equal(validateEnvironment({ X: 'é' }), 3);
  assert.throws(() => environmentForUpdate({ UNMANAGED: 'value' }, options), /Unmanaged/);
  assert.throws(() => environmentForUpdate({}, { ...options, runner: 'invalid' }), /UUID/);
});

test('both secrets preserve previous values except initial setup or explicit rotation', () => {
  for (const key of ['JwtSecret', 'FcmServiceAccountJson']) {
    assert.deepEqual(secretParameter(key, { previous: true, rotate: false, value: 'unused' }),
      { ParameterKey: key, UsePreviousValue: true });
    assert.deepEqual(secretParameter(key, { previous: true, rotate: true, value: 'synthetic' }),
      { ParameterKey: key, ParameterValue: 'synthetic' });
    assert.throws(() => secretParameter(key, { previous: false }), /requires/);
  }
});

test('maintenance is explicit and an existing pause survives unrelated deployment', () => {
  assert.equal(maintenanceFlag({}, false, false), 'false');
  assert.equal(maintenanceFlag({}, true, false), 'true');
  assert.equal(maintenanceFlag({ SIGHTINGS_WRITES_PAUSED: 'true' }, false, false), 'true');
  assert.equal(maintenanceFlag({ SIGHTINGS_WRITES_PAUSED: 'true' }, false, true), 'false');
  assert.throws(() => maintenanceFlag({}, true, true), /not both/);
  assert.equal(environmentForUpdate({ SIGHTINGS_WRITES_PAUSED: 'true' }, options).SIGHTINGS_WRITES_PAUSED, 'true');
  assert.throws(() => environmentForUpdate({}, { ...options, writesPaused: 'invalid' }), /maintenance flag/);
});

test('immutable API digest cannot select another repository or mutable tag', () => {
  assert.equal(apiImageUri(REPOSITORY, `sha256:${'a'.repeat(64)}`), `${REPOSITORY}@sha256:${'a'.repeat(64)}`);
  assert.throws(() => apiImageUri(REPOSITORY, 'latest'));
  assert.throws(() => apiImageUri('other-repository', `sha256:${'a'.repeat(64)}`));
});

test('template keeps NoEcho secrets, disabled processing and only the existing API infrastructure', () => {
  const template = readFileSync(new URL('../infra/aves-lambda.yaml', import.meta.url), 'utf8');
  for (const key of ['JwtSecret', 'FcmServiceAccountJson']) {
    assert.match(template, new RegExp(`${key}:\\n    Type: String\\n    NoEcho: true`));
  }
  assert.match(template, /NotificationProcessingEnabled:[\s\S]*?Default: 'false'/);
  assert.match(template, /SIGHTINGS_WRITES_PAUSED: !Ref SightingsWritesPaused/);
  assert.doesNotMatch(template, /AWS::IAM|AWS::DynamoDB|AWS::S3|EventSourceMapping|NotificationsTableName/);
  assert.doesNotMatch(template.split('Outputs:')[1], /FcmServiceAccountJson|JwtSecret/);
});

test('actual deployment command keeps initial FCM in a protected file and preserves JWT without secret argv/logs', () => {
  const directory = mkdtempSync(join(tmpdir(), 'aves-api-deploy-test-'));
  const files = ['aws', 'fixture.json', 'fcm.json', 'calls.jsonl', 'request-capture.json'];
  try {
    const role = 'arn:aws:iam::640168426886:role/aluno_23.01182-3_lambda_aves_api';
    const fixture = {
      role, jwt: options.jwt, repository: REPOSITORY, tags: TAGS,
      calls: join(directory, 'calls.jsonl'), capture: join(directory, 'request-capture.json'),
    };
    writeFileSync(join(directory, 'fixture.json'), JSON.stringify(fixture), { mode: 0o600 });
    writeFileSync(join(directory, 'fcm.json'), credential, { mode: 0o600 });
    // AWS is the unsafe external boundary; execute the real deploy script, not a replacement.
    writeFileSync(join(directory, 'aws'), `#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
const f = JSON.parse(fs.readFileSync(process.env.SYNTHETIC_AWS_FIXTURE, 'utf8'));
const args = process.argv.slice(2);
fs.appendFileSync(f.calls, JSON.stringify(args) + '\\n');
const tags = Object.entries(f.tags).map(([Key, Value]) => ({Key, Value}));
const responses = {
  'sts get-caller-identity': {Account: '640168426886'},
  'iam get-role': {Role: {Arn: f.role, AssumeRolePolicyDocument: {Statement: [{Effect: 'Allow', Principal: {Service: 'lambda.amazonaws.com'}}]}}},
  'iam list-attached-role-policies': {AttachedPolicies: [
    {PolicyArn: 'arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole'},
    {PolicyArn: 'arn:aws:iam::640168426886:policy/AvesDynamoDbAccess'}]},
  'iam list-role-tags': {Tags: tags},
  'ecr describe-repositories': {repositories: [{repositoryUri: f.repository, repositoryArn: 'synthetic-ecr-arn'}]},
  'ecr list-tags-for-resource': {tags},
  'ecr describe-images': {imageDetails: [{imageDigest: 'sha256:' + 'a'.repeat(64)}]},
  'lambda get-function-configuration': {PackageType: 'Image', Role: f.role, Environment: {Variables: {JWT_SECRET: f.jwt}}},
  'lambda get-function': {Code: {ResolvedImageUri: f.repository + '@sha256:' + 'b'.repeat(64)}},
  'cloudformation describe-stacks': {Stacks: [{
    Parameters: [{ParameterKey: 'JwtSecret', ParameterValue: '****'}],
    Outputs: [{OutputKey: 'HttpApiId', OutputValue: 'synthetic-api'},
      {OutputKey: 'HttpApiEndpoint', OutputValue: 'https://example.test'}]}]},
  'cloudformation describe-change-set': {Changes: [{ResourceChange: {Action: 'Modify', Replacement: 'False'}}]},
};
const command = args.slice(0, 2).join(' ');
if (command === 'cloudformation create-change-set') {
  const file = args[args.indexOf('--cli-input-json') + 1].slice(7);
  fs.writeFileSync(f.capture, JSON.stringify({
    request: JSON.parse(fs.readFileSync(file, 'utf8')), file,
    mode: fs.statSync(file).mode & 0o777, parentMode: fs.statSync(path.dirname(file)).mode & 0o777,
  }));
}
const empty = new Set(['cloudformation validate-template', 'cloudformation create-change-set',
  'cloudformation wait', 'cloudformation execute-change-set', 'apigatewayv2 tag-resource']);
if (!(command in responses) && !empty.has(command)) throw new Error('Unexpected AWS boundary command');
process.stdout.write(JSON.stringify(responses[command] || {}));
`, { mode: 0o700 });
    const stdout = execFileSync(process.execPath, [fileURLToPath(new URL('./deploy-api.mjs', import.meta.url)), '--apply'], {
      encoding: 'utf8', env: {
        PATH: directory + delimiter + process.env.PATH, AWS_PROFILE: 'synthetic-profile', AWS_REGION: 'us-east-1',
        SYNTHETIC_AWS_FIXTURE: join(directory, 'fixture.json'),
        FCM_SERVICE_ACCOUNT_FILE: join(directory, 'fcm.json'), NOTIFICATION_RUNNER_USER_ID: options.runner,
        API_IMAGE_URI: apiImageUri(REPOSITORY, `sha256:${'a'.repeat(64)}`),
      },
    });
    const captured = JSON.parse(readFileSync(fixture.capture, 'utf8'));
    assert.equal(captured.mode, 0o600);
    assert.equal(captured.parentMode, 0o700);
    assert.equal(existsSync(captured.file), false);
    assert.deepEqual(captured.request.Parameters.find(parameter => parameter.ParameterKey === 'JwtSecret'),
      { ParameterKey: 'JwtSecret', UsePreviousValue: true });
    assert.equal(captured.request.Parameters.find(parameter => parameter.ParameterKey === 'FcmServiceAccountJson').ParameterValue, credential);
    assert.equal(captured.request.Parameters.find(parameter => parameter.ParameterKey === 'NotificationProcessingEnabled').ParameterValue, 'false');
    assert.equal(captured.request.Parameters.find(parameter => parameter.ParameterKey === 'SightingsWritesPaused').ParameterValue, 'false');
    const calls = readFileSync(fixture.calls, 'utf8');
    assert.equal(calls.includes(options.jwt), false);
    assert.equal(calls.includes('BEGIN PRIVATE KEY'), false);
    assert.equal(stdout.includes(options.jwt), false);
    assert.equal(stdout.includes('BEGIN PRIVATE KEY'), false);
    assert.match(stdout, /API deployed; processing=false/);
  } finally {
    for (const file of files) if (existsSync(join(directory, file))) unlinkSync(join(directory, file));
    rmdirSync(directory);
  }
});
