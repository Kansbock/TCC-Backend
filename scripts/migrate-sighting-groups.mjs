import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, existsSync, openSync, closeSync, fsyncSync, renameSync, unlinkSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

const s = value => ({ S: String(value) });
const n = value => ({ N: String(value) });
const strings = values => ({ L: values.map(s) });
const text = (item, key) => item?.[key]?.S;
const number = (item, key) => Number(item?.[key]?.N || 0);
const list = (item, key) => (item?.[key]?.L || []).map(value => value.S);
export const hash = value => createHash('sha256').update(value).digest('hex');
const keyOf = item => Object.fromEntries(['pk', 'sk'].filter(key => item[key]).map(key => [key, item[key]]));
const identity = (table, key) => `${table}|${text(key, 'pk')}|${text(key, 'sk') || ''}`;
const canonical = value => JSON.stringify(normalize(value));
function normalize(value) {
  if (Array.isArray(value)) return value.map(normalize);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, normalize(value[key])]));
  return value;
}
const equal = (a, b) => canonical(a) === canonical(b);
const member = sequence => `MEMBER#${String(sequence).padStart(19, '0')}`;
const photo = url => `PHOTO#${hash(url)}`;
const meta = (pk, sk, type, id) => ({
  pk: s(pk), sk: s(sk), itemType: s(type), version: n(1),
  ...(id ? { sightingId: s(id) } : {}),
});
const urls = item => item.imageUrls ? list(item, 'imageUrls') : text(item, 'imageUrl') ? [text(item, 'imageUrl')] : [];

export function groupKey(speciesIds, date, time) {
  const ids = [...new Set(speciesIds)].sort();
  return `SIGHTING_GROUP#v1#${hash(`v1\n${date}\n${time.slice(0, 5)}\n${ids.map(id => `${id.length}:${id}\n`).join('')}`)}`;
}

function reference(speciesId, item, at) {
  return {
    pk: s(`SPECIES#${speciesId}`), sk: s(`${new Date(at).toISOString()}#SIGHTING#${text(item, 'id')}`),
    itemType: s('SPECIES_SIGHTING_REF'), speciesId: s(speciesId),
    sightingId: item.id, observedAt: s(at),
  };
}

function stamp(time) {
  const [whole, fraction = ''] = time.split('.');
  return `${whole.length === 5 ? `${whole}:00` : whole}.${fraction.padEnd(9, '0')}`;
}

export function buildManifest(snapshot, { pendingPolicy = 'coalesce-unstarted' } = {}) {
  if (!['coalesce-unstarted', 'cancel-historical'].includes(pendingPolicy)) {
    throw new Error('Invalid pending notification policy.');
  }
  const { target, sightings, species, capturedAt } = snapshot;
  if (!target?.sightingsTable || !target?.speciesTable || !target?.region || !target?.account
      || !Array.isArray(sightings) || !Array.isArray(species) || !capturedAt || Number.isNaN(Date.parse(capturedAt))) {
    throw new Error('Snapshot requires target, capturedAt, sightings and species arrays.');
  }
  const tables = new Map();
  for (const [table, items] of [[target.sightingsTable, sightings], [target.speciesTable, species]]) {
    for (const item of items) {
      if (!text(item, 'pk')) throw new Error('Snapshot item has no partition key.');
      const id = identity(table, keyOf(item));
      if (tables.has(id)) throw new Error(`Duplicate snapshot key: ${id}`);
      tables.set(id, structuredClone(item));
    }
  }
  const operations = [];
  const buckets = new Map();
  const mappings = [];
  const anomalies = [];
  const details = sightings.filter(item => text(item, 'itemType') === 'SIGHTING_DETAILS');
  const references = new Map();
  for (const item of details) for (const id of new Set(list(item, 'speciesIds'))) references.set(id, (references.get(id) || 0) + 1);
  for (const item of species) {
    if (number(item, 'referenceCount') !== (references.get(text(item, 'id')) || 0)) {
      anomalies.push(`Species reference count differs from snapshot: ${text(item, 'id')}`);
    }
  }
  const groupedKeys = new Set(details.filter(item => item.groupKey).map(item => text(item, 'groupKey')));
  for (const item of details.filter(item => !item.groupKey)) {
    const ids = list(item, 'speciesIds');
    const date = text(item, 'date');
    const time = text(item, 'time');
    if (!text(item, 'id') || !ids.length || ids.length > 20 || new Set(ids).size !== ids.length
        || !/^\d{4}-\d{2}-\d{2}$/.test(date || '') || !/^\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?$/.test(time || '')
        || urls(item).length > 1 || urls(item).some(url => !url?.trim() || url.length > 2048)) {
      anomalies.push(`Invalid or non-single-photo legacy record: ${text(item, 'id')}`);
      continue;
    }
    const key = groupKey(ids, date, time);
    if (groupedKeys.has(key) || tables.has(identity(target.sightingsTable, { pk: s(key), sk: s('STATE') }))) {
      anomalies.push(`Mixed legacy/grouped combination: ${key}`);
      continue;
    }
    if (ids.some(id => !tables.has(identity(target.speciesTable, { pk: s(`SPECIES#${id}`) })))) {
      anomalies.push(`Missing species for ${text(item, 'id')}`);
      continue;
    }
    if (!buckets.has(key)) buckets.set(key, []);
    buckets.get(key).push(item);
  }
  function changes(label, updates) {
    const seen = new Set();
    const entries = updates.map(({ table, key, after }) => {
      const id = identity(table, key);
      if (seen.has(id)) throw new Error(`Two operations on one item: ${id}`);
      seen.add(id);
      const before = tables.get(id) || null;
      return { table, key, before: structuredClone(before), after: structuredClone(after) };
    }).filter(entry => !equal(entry.before, entry.after));
    if (entries.length > 100 || Buffer.byteLength(canonical(entries)) > 4 * 1024 * 1024
        || entries.some(entry => entry.after && Buffer.byteLength(canonical(entry.after)) > 400 * 1024)) {
      throw new Error(`Transaction capacity exceeded: ${label}`);
    }
    for (const entry of entries) {
      const id = identity(entry.table, entry.key);
      if (entry.after) tables.set(id, entry.after); else tables.delete(id);
    }
    if (entries.length) operations.push({ id: hash(canonical({ label, entries })), label, changes: entries });
  }
  const put = (table, item) => ({ table, key: keyOf(item), after: item });
  const del = (table, key) => ({ table, key, after: null });
  for (const [pk, records] of [...buckets].sort(([a], [b]) => a.localeCompare(b))) {
    records.sort((a, b) => `${text(a, 'date')}T${stamp(text(a, 'time'))}`.localeCompare(`${text(b, 'date')}T${stamp(text(b, 'time'))}`)
      || (text(a, 'createdAt') || '').localeCompare(text(b, 'createdAt') || '')
      || text(a, 'id').localeCompare(text(b, 'id')));
    const unique = [...new Set(records.flatMap(urls))];
    const chunks = unique.length ? Array.from({ length: Math.ceil(unique.length / 4) }, (_, i) => unique.slice(i * 4, i * 4 + 4)) : [[]];
    for (let i = 0; i < chunks.length; i++) {
      const images = chunks[i];
      const sources = records.filter(item => urls(item).length ? images.includes(urls(item)[0]) : i === 0);
      const original = sources.find(item => urls(item).length) || sources[0];
      const id = text(original, 'id');
      const minute = text(original, 'time').slice(0, 5);
      const at = `${text(original, 'date')}T${minute}:00Z`;
      const primary = tables.get(identity(target.speciesTable, { pk: s(`SPECIES#${list(original, 'speciesIds')[0]}`) }));
      const primaryName = (text(primary, 'commonName') || '').normalize('NFD').replace(/\p{M}/gu, '').trim().toLowerCase();
      let current = {
        ...structuredClone(original), time: s(minute), observedAt: s(at),
        imageUrls: strings(urls(original)), groupKey: s(pk), groupSequence: n(i + 1),
        timelinePk: s(`SIGHTINGS#${text(original, 'date').slice(0, 7)}`),
        timelineSk: s(`${new Date(at).toISOString()}#${id}`),
        primarySpeciesPk: s('SIGHTINGS#PRIMARY_SPECIES'),
        primarySpeciesSk: s(`${primaryName}#${new Date(at).toISOString()}#${id}`),
        updatedAt: s(capturedAt), version: n(number(original, 'version') + 1),
      };
      if (urls(current).length) current.imageUrl = s(urls(current)[0]); else delete current.imageUrl;
      const stateKey = { pk: s(pk), sk: s('STATE') };
      const previousState = tables.get(identity(target.sightingsTable, stateKey));
      const state = {
        ...meta(pk, 'STATE', 'SIGHTING_GROUP', id), liveCount: n(i + 1),
        nextSequence: n(i + 2), version: n(number(previousState, 'version') + 1),
      };
      const updates = [put(target.sightingsTable, current), put(target.sightingsTable, state),
        put(target.sightingsTable, meta(pk, member(i + 1), 'SIGHTING_GROUP_MEMBER', id))];
      for (const url of urls(current)) updates.push(put(target.sightingsTable, meta(pk, photo(url), 'SIGHTING_PHOTO', id)));
      for (const speciesId of list(original, 'speciesIds')) {
        const oldRef = reference(speciesId, original, text(original, 'observedAt'));
        if (!tables.has(identity(target.sightingsTable, keyOf(oldRef)))) throw new Error(`Missing historical reference for ${id}`);
        const newRef = reference(speciesId, current, at);
        if (!equal(keyOf(oldRef), keyOf(newRef))) updates.push(del(target.sightingsTable, keyOf(oldRef)));
        updates.push(put(target.sightingsTable, newRef));
      }
      changes(`normalize:${id}`, updates);
      for (const source of sources) {
        const oldId = text(source, 'id');
        mappings.push({ oldId, canonicalId: id });
        if (oldId === id) continue;
        const photos = [...new Set([...urls(current), ...urls(source)])];
        const added = photos.filter(url => !urls(current).includes(url));
        current = { ...current, imageUrls: strings(photos), version: n(number(current, 'version') + 1) };
        if (photos.length) current.imageUrl = s(photos[0]); else delete current.imageUrl;
        const absorb = [put(target.sightingsTable, current), del(target.sightingsTable, keyOf(source)),
          put(target.sightingsTable, meta(`SIGHTING_ALIAS#${oldId}`, 'TARGET', 'SIGHTING_ALIAS', id))];
        for (const url of added) absorb.push(put(target.sightingsTable, meta(pk, photo(url), 'SIGHTING_PHOTO', id)));
        for (const speciesId of list(source, 'speciesIds')) {
          const oldRef = reference(speciesId, source, text(source, 'observedAt'));
          if (!tables.has(identity(target.sightingsTable, keyOf(oldRef)))) throw new Error(`Missing historical reference for ${oldId}`);
          absorb.push(del(target.sightingsTable, keyOf(oldRef)));
          const speciesKey = { pk: s(`SPECIES#${speciesId}`) };
          const value = tables.get(identity(target.speciesTable, speciesKey));
          if (number(value, 'referenceCount') < 1) throw new Error(`Invalid species count: ${speciesId}`);
          absorb.push(put(target.speciesTable, { ...value, referenceCount: n(number(value, 'referenceCount') - 1),
            updatedAt: s(capturedAt), version: n(number(value, 'version') + 1) }));
        }
        changes(`absorb:${oldId}:${id}`, absorb);
      }
      const sourceIds = new Set(sources.map(item => text(item, 'id')));
      const pending = sightings.filter(item => text(item, 'itemType') === 'NOTIF_PENDING' && sourceIds.has(text(item, 'sightingId')));
      const started = event => event && (event.historyCursor?.S || event.historyDone?.BOOL
        || event.pushCursor?.S || event.deviceCursor?.S || event.complete?.BOOL
        || event.leaseOwner?.S || (event.leaseUntil && number(event, 'leaseUntil') > 0));
      const eventFor = oldId => tables.get(identity(target.sightingsTable, { pk: s(`NOTIF#EVENT#${oldId}`), sk: s('STATE') }));
      const attemptsFor = oldId => sightings.filter(item => text(item, 'pk') === `NOTIF#EVENT#${oldId}`
        && text(item, 'sk')?.startsWith('PUSH#'));
      const activeLease = item => (item?.leaseOwner?.S && !item.leaseUntil)
        || (item?.leaseUntil && (!Number.isFinite(number(item, 'leaseUntil'))
          || number(item, 'leaseUntil') > Date.parse(capturedAt)));
      if (pendingPolicy === 'cancel-historical') {
        if (pending.some(item => !eventFor(text(item, 'sightingId'))
            || activeLease(eventFor(text(item, 'sightingId'))) || attemptsFor(text(item, 'sightingId')).some(activeLease))) {
          anomalies.push(`Pending notification missing state or has an active lease in group ${id}`);
          continue;
        }
        for (const item of pending) {
          const event = eventFor(text(item, 'sightingId'));
          changes(`cancel-pending:${text(item, 'sightingId')}`, [
            del(target.sightingsTable, keyOf(item)),
            put(target.sightingsTable, { ...event, jobStatus: s('CANCELLED'),
              cancellationReason: s('GROUPING_HISTORICAL_CANCELLED'), cancelledAt: s(capturedAt), supersededBy: s(id) }),
          ]);
        }
        continue;
      }
      if (pending.some(item => !eventFor(text(item, 'sightingId')) || started(eventFor(text(item, 'sightingId')))
          || attemptsFor(text(item, 'sightingId')).length)) {
        anomalies.push(`Pending notification already started or missing state in group ${id}`);
        continue;
      }
      const delivered = sources.some(item => started(eventFor(text(item, 'id'))));
      pending.sort((a, b) => (text(a, 'sightingId') === id ? -1 : text(b, 'sightingId') === id ? 1
        : (text(a, 'createdAt') || '').localeCompare(text(b, 'createdAt') || '') || text(a, 'sightingId').localeCompare(text(b, 'sightingId'))));
      for (const item of delivered ? pending : pending.slice(1)) {
        const event = eventFor(text(item, 'sightingId'));
        changes(`cancel-pending:${text(item, 'sightingId')}`, [
          del(target.sightingsTable, keyOf(item)),
          put(target.sightingsTable, { ...event, jobStatus: s('CANCELLED'), lastError: s('GROUPING_SUPERSEDED'), supersededBy: s(id) }),
        ]);
      }
    }
  }
  const expected = new Map();
  for (const operation of operations) for (const entry of operation.changes) expected.set(identity(entry.table, entry.key), entry);
  const manifest = { schema: 1, target, capturedAt, pendingPolicy, inputHash: hash(canonical(snapshot)),
    cancelledPending: operations.filter(operation => operation.label.startsWith('cancel-pending:')).length,
    legacyCount: [...buckets.values()].flat().length,
    groups: mappings.filter(mapping => mapping.oldId === mapping.canonicalId).length, mappings, anomalies, operations,
    final: [...expected.values()].map(({ table, key, after }) => ({ table, key, after })) };
  return { ...manifest, approvalHash: hash(canonical(manifest)) };
}

function condition(before) {
  if (!before) return { ConditionExpression: 'attribute_not_exists(pk)' };
  const names = {};
  const values = {};
  const predicates = ['attribute_exists(pk)'];
  for (const [key, value] of Object.entries(before).filter(([key]) => !['pk', 'sk'].includes(key))) {
    const index = Object.keys(names).length;
    names[`#a${index}`] = key;
    values[`:v${index}`] = value;
    predicates.push(`#a${index} = :v${index}`);
  }
  return { ConditionExpression: predicates.join(' AND '),
    ...(Object.keys(names).length ? { ExpressionAttributeNames: names, ExpressionAttributeValues: values } : {}) };
}

export function applyManifest(manifest, store, journal, save, rollback = false) {
  const { approvalHash, ...content } = manifest;
  if (manifest.schema !== 1 || hash(canonical(content)) !== approvalHash || manifest.anomalies.length) {
    throw new Error('Invalid, modified, or blocked manifest.');
  }
  if (journal.approvalHash !== approvalHash) throw new Error('Journal belongs to a different manifest.');
  const operations = rollback ? [...manifest.operations].reverse().filter(operation => journal.completed.includes(operation.id)) : manifest.operations;
  for (const operation of operations) {
    if (!rollback && journal.completed.includes(operation.id)) continue;
    if (rollback && journal.reverted.includes(operation.id)) continue;
    const entries = operation.changes.map(entry => rollback ? { ...entry, before: entry.after, after: entry.before } : entry);
    const actual = entries.map(entry => store.get(entry.table, entry.key));
    if (!actual.every((item, i) => equal(item, entries[i].after))) {
      if (!actual.every((item, i) => equal(item, entries[i].before))) throw new Error(`State diverged at ${operation.label}; stop and reconcile.`);
      store.transact(entries.map(entry => entry.after
        ? { Put: { TableName: entry.table, Item: entry.after, ...condition(entry.before) } }
        : { Delete: { TableName: entry.table, Key: entry.key, ...condition(entry.before) } }));
    }
    (rollback ? journal.reverted : journal.completed).push(operation.id);
    save(journal);
  }
  if (!rollback) for (const entry of manifest.final) {
    if (!equal(store.get(entry.table, entry.key), entry.after)) throw new Error('Final state differs from approved manifest.');
  }
  return journal;
}

export function awsStore(flags) {
  const endpoint = flags.endpoint;
  if (endpoint && !/^http:\/\/(?:127\.0\.0\.1|localhost):\d+$/.test(endpoint)) throw new Error('Only loopback endpoints allowed for local tests.');
  const env = { ...process.env, AWS_PAGER: '', AWS_REGION: flags.region };
  for (const key of ['AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN', 'AWS_SECURITY_TOKEN']) delete env[key];
  if (endpoint) Object.assign(env, { AWS_ACCESS_KEY_ID: 'test', AWS_SECRET_ACCESS_KEY: 'test' });
  else {
    if (!flags.profile) throw new Error('An explicit AWS profile is required.');
    env.AWS_PROFILE = flags.profile;
  }
  const call = (service, method, input) => {
    const output = execFileSync('aws', [service, method, '--cli-input-json', JSON.stringify(input),
      '--region', flags.region, '--output', 'json', ...(endpoint && service === 'dynamodb' ? ['--endpoint-url', endpoint] : [])],
    { env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 32 * 1024 * 1024, timeout: 30000 });
    return output.trim() ? JSON.parse(output) : {};
  };
  return {
    account: () => endpoint ? 'LOCAL' : call('sts', 'get-caller-identity', {}).Account,
    get: (table, key) => call('dynamodb', 'get-item', { TableName: table, Key: key, ConsistentRead: true }).Item || null,
    transact: TransactItems => call('dynamodb', 'transact-write-items', { TransactItems }),
    scan: table => {
      const items = [];
      let cursor;
      do {
        const page = call('dynamodb', 'scan', { TableName: table, ConsistentRead: true, ...(cursor ? { ExclusiveStartKey: cursor } : {}) });
        items.push(...page.Items);
        cursor = page.LastEvaluatedKey;
      } while (cursor && Object.keys(cursor).length);
      return items;
    },
  };
}

function main() {
  const flags = {};
  const booleans = new Set(['apply', 'rollback', 'confirm-maintenance', 'export']);
  const valid = new Set([...booleans, 'snapshot', 'manifest', 'journal', 'approve-sha', 'profile', 'region', 'account',
    'sightings-table', 'species-table', 'endpoint', 'pending-policy']);
  for (let i = 2; i < process.argv.length; i++) {
    const key = process.argv[i].replace(/^--/, '');
    if (!process.argv[i].startsWith('--') || !valid.has(key) || flags[key] !== undefined) throw new Error('Invalid or duplicate flag.');
    flags[key] = booleans.has(key) ? true : process.argv[++i];
    if (flags[key] === undefined || String(flags[key]).startsWith('--')) throw new Error(`Missing value: ${key}`);
  }
  if (flags['pending-policy'] && (flags.export || flags.apply || flags.rollback)) {
    throw new Error('Pending policy is selected during dry-run and approved in the manifest.');
  }
  const read = path => JSON.parse(readFileSync(path, 'utf8'));
  const write = (path, value) => {
    const temporary = `${path}.${randomUUID()}.tmp`;
    try {
      writeFileSync(temporary, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600, flag: 'wx' });
      const fd = openSync(temporary, 'r');
      try { fsyncSync(fd); } finally { closeSync(fd); }
      renameSync(temporary, path);
    } finally {
      if (existsSync(temporary)) unlinkSync(temporary);
    }
  };
  if (flags.export) {
    if (!flags.snapshot || existsSync(flags.snapshot) || !flags.region || !flags.account || !flags['sightings-table'] || !flags['species-table']) {
      throw new Error('Export requires a new snapshot path and explicit target.');
    }
    const store = awsStore(flags);
    if (store.account() !== flags.account) throw new Error('Account mismatch.');
    const sightings = store.scan(flags['sightings-table']).filter(item =>
      ['SIGHTING_DETAILS', 'SPECIES_SIGHTING_REF', 'NOTIF_PENDING', 'NOTIF_EVENT'].includes(text(item, 'itemType'))
      || text(item, 'pk')?.startsWith('NOTIF#EVENT#') || item.groupKey || text(item, 'pk')?.startsWith('SIGHTING_GROUP#'));
    write(flags.snapshot, { target: { account: flags.account, region: flags.region,
      sightingsTable: flags['sightings-table'], speciesTable: flags['species-table'] },
    capturedAt: new Date().toISOString(), sightings, species: store.scan(flags['species-table']).filter(item => text(item, 'itemType') === 'SPECIES') });
    return;
  }
  if (!flags.apply && !flags.rollback) {
    if (!flags.snapshot || !flags.manifest || existsSync(flags.manifest)) throw new Error('Dry-run requires --snapshot and a new --manifest path.');
    const manifest = buildManifest(read(flags.snapshot), { pendingPolicy: flags['pending-policy'] });
    write(flags.manifest, manifest);
    console.log(JSON.stringify({ legacyCount: manifest.legacyCount, groups: manifest.groups,
      operations: manifest.operations.length, pendingPolicy: manifest.pendingPolicy, cancelledPending: manifest.cancelledPending,
      anomalies: manifest.anomalies, approvalHash: manifest.approvalHash }));
    if (manifest.anomalies.length) process.exitCode = 1;
    return;
  }
  if (flags.apply && flags.rollback) throw new Error('Choose apply or rollback, not both.');
  const manifest = read(flags.manifest);
  if (!flags['confirm-maintenance'] || !flags.journal || flags['approve-sha'] !== manifest.approvalHash
      || flags.account !== manifest.target.account || flags.region !== manifest.target.region
      || flags['sightings-table'] !== manifest.target.sightingsTable || flags['species-table'] !== manifest.target.speciesTable) {
    throw new Error('Apply/rollback requires approved hash, explicit matching target, journal and confirmed maintenance.');
  }
  const store = awsStore(flags);
  if (store.account() !== flags.account) throw new Error('Account mismatch.');
  const journal = existsSync(flags.journal) ? read(flags.journal) : { approvalHash: manifest.approvalHash, completed: [], reverted: [] };
  if (flags.rollback && !existsSync(flags.journal)) throw new Error('Rollback requires the original journal.');
  if (flags.apply && journal.reverted.length) throw new Error('Cannot apply a rolled-back journal; make a new snapshot/manifest.');
  applyManifest(manifest, store, journal, value => write(flags.journal, value), Boolean(flags.rollback));
  console.log(flags.rollback ? 'Rollback verified.' : 'Migration verified.');
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main();
