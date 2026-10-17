import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildManifest, applyManifest, groupKey, hash, awsStore } from './migrate-sighting-groups.mjs';

const s = value => ({ S: value });
const n = value => ({ N: String(value) });
const id = (table, key) => `${table}|${key.pk.S}|${key.sk?.S || ''}`;
const key = item => ({ pk: item.pk, ...(item.sk ? { sk: item.sk } : {}) });

function snapshot(count, speciesCount = 1) {
  const ids = Array.from({ length: speciesCount }, (_, i) => `sp-${i}`);
  const species = ids.map(speciesId => ({ pk: s(`SPECIES#${speciesId}`), id: s(speciesId),
    itemType: s('SPECIES'), commonName: s('Synthetic bird'), referenceCount: n(count), version: n(1) }));
  const sightings = [];
  for (let i = 0; i < count; i++) {
    const sightingId = `old-${i}`;
    const at = `2026-09-10T10:15:${String(i).padStart(2, '0')}Z`;
    sightings.push({ pk: s(`SIGHTING#${sightingId}`), sk: s('DETAILS'), id: s(sightingId), itemType: s('SIGHTING_DETAILS'),
      speciesIds: { L: ids.map(s) }, date: s('2026-09-10'), time: s(`10:15:${String(i).padStart(2, '0')}`),
      observedAt: s(at), imageUrl: s(`https://synthetic.test/${i}`), createdAt: s(at), updatedAt: s(at), version: n(1) });
    for (const speciesId of ids) sightings.push({ pk: s(`SPECIES#${speciesId}`),
      sk: s(`${new Date(at).toISOString()}#SIGHTING#${sightingId}`), itemType: s('SPECIES_SIGHTING_REF'),
      speciesId: s(speciesId), sightingId: s(sightingId), observedAt: s(at) });
  }
  return { target: { account: 'LOCAL', region: 'us-east-1', sightingsTable: 'synthetic-sightings', speciesTable: 'synthetic-species' },
    capturedAt: '2026-10-01T00:00:00Z', sightings, species };
}

function storage(input) {
  const items = new Map();
  for (const [table, values] of [[input.target.sightingsTable, input.sightings], [input.target.speciesTable, input.species]]) {
    for (const item of values) items.set(id(table, key(item)), structuredClone(item));
  }
  let writes = 0;
  return {
    items,
    get: (table, itemKey) => structuredClone(items.get(id(table, itemKey)) || null),
    writes: () => writes,
    transact: entries => {
      assert.ok(entries.length <= 100);
      assert.equal(new Set(entries.map(entry => id((entry.Put || entry.Delete).TableName,
        entry.Put ? key(entry.Put.Item) : entry.Delete.Key))).size, entries.length);
      writes++;
      for (const entry of entries) {
        if (entry.Put) items.set(id(entry.Put.TableName, key(entry.Put.Item)), structuredClone(entry.Put.Item));
        else items.delete(id(entry.Delete.TableName, entry.Delete.Key));
      }
    },
  };
}

test('canonical group identity matches Java fixture and ignores seconds/order', () => {
  const expected = `SIGHTING_GROUP#v1#${hash('v1\n2026-09-10\n10:15\n1:a\n1:b\n')}`;
  assert.equal(groupKey(['b', 'a'], '2026-09-10', '10:15:59.900'), expected);
  assert.equal(groupKey(['a', 'b'], '2026-09-10', '10:15:01'), expected);
  assert.notEqual(groupKey(['a'], '2026-09-10', '10:15'), expected);
});

test('nine historical photos become 4+4+1 with deterministic aliases and counts', () => {
  const input = snapshot(9);
  const manifest = buildManifest(input);
  assert.equal(manifest.groups, 3);
  assert.deepEqual(manifest.anomalies, []);
  assert.deepEqual(manifest.mappings.map(row => row.canonicalId), [
    'old-0', 'old-0', 'old-0', 'old-0', 'old-4', 'old-4', 'old-4', 'old-4', 'old-8',
  ]);
  const store = storage(input);
  const journal = { approvalHash: manifest.approvalHash, completed: [], reverted: [] };
  applyManifest(manifest, store, journal, () => {});
  const details = [...store.items.values()].filter(item => item.itemType?.S === 'SIGHTING_DETAILS');
  assert.deepEqual(details.map(item => item.imageUrls.L.length), [4, 4, 1]);
  assert.equal(store.get(input.target.speciesTable, { pk: s('SPECIES#sp-0') }).referenceCount.N, '3');
  const written = store.writes();
  applyManifest(manifest, store, journal, () => {});
  assert.equal(store.writes(), written);
  applyManifest(manifest, store, journal, () => {}, true);
  const order = (a, b) => `${a.pk.S}|${a.sk?.S || ''}`.localeCompare(`${b.pk.S}|${b.sk?.S || ''}`);
  assert.deepEqual([...store.items.values()].sort(order), [...input.sightings, ...input.species].sort(order));
});

test('twenty-species merges stay below transaction limit and retain all photos', () => {
  const input = snapshot(9, 20);
  const manifest = buildManifest(input);
  assert.ok(manifest.operations.every(operation => operation.changes.length <= 100));
  const store = storage(input);
  applyManifest(manifest, store, { approvalHash: manifest.approvalHash, completed: [], reverted: [] }, () => {});
  assert.equal([...store.items.values()].filter(item => item.itemType?.S === 'SIGHTING_DETAILS')
    .flatMap(item => item.imageUrls.L).length, 9);
});

test('lost checkpoint after committed transaction resumes without double decrements', () => {
  const input = snapshot(5);
  const manifest = buildManifest(input);
  const store = storage(input);
  const journal = { approvalHash: manifest.approvalHash, completed: [], reverted: [] };
  assert.throws(() => applyManifest(manifest, store, journal, () => { throw new Error('synthetic interruption'); }), /interruption/);
  journal.completed = [];
  applyManifest(manifest, store, journal, () => {});
  assert.equal(store.get(input.target.speciesTable, { pk: s('SPECIES#sp-0') }).referenceCount.N, '2');
});

test('diverged inputs and modified manifests stop explicitly', () => {
  const input = snapshot(2);
  const manifest = buildManifest(input);
  const store = storage(input);
  store.items.get(id(input.target.sightingsTable, { pk: s('SIGHTING#old-0'), sk: s('DETAILS') })).version = n(9);
  const journal = { approvalHash: manifest.approvalHash, completed: [], reverted: [] };
  assert.throws(() => applyManifest(manifest, store, journal, () => {}), /diverged/);
  assert.equal(store.writes(), 0);
  assert.throws(() => applyManifest({ ...manifest, groups: 999 }, store, journal, () => {}), /modified/);
});

test('duplicate URLs and empty records do not consume photo capacity', () => {
  const input = snapshot(3);
  input.sightings.find(item => item.id?.S === 'old-1').imageUrl = s('https://synthetic.test/0');
  delete input.sightings.find(item => item.id?.S === 'old-2').imageUrl;
  const manifest = buildManifest(input);
  assert.equal(manifest.groups, 1);
  const store = storage(input);
  applyManifest(manifest, store, { approvalHash: manifest.approvalHash, completed: [], reverted: [] }, () => {});
  assert.equal(store.get(input.target.sightingsTable, { pk: s('SIGHTING#old-0'), sk: s('DETAILS') }).imageUrls.L.length, 1);
  assert.equal(store.get(input.target.speciesTable, { pk: s('SPECIES#sp-0') }).referenceCount.N, '1');
});

test('started pending events block apply; pristine duplicates are cancelled without ledger deletion', () => {
  const input = snapshot(2);
  for (let i = 0; i < 2; i++) {
    input.sightings.push({ pk: s('NOTIF#PENDING'), sk: s(`pending-${i}`), itemType: s('NOTIF_PENDING'),
      sightingId: s(`old-${i}`), createdAt: s('2026-09-10T11:00:00Z') });
    input.sightings.push({ pk: s(`NOTIF#EVENT#old-${i}`), sk: s('STATE'), itemType: s('NOTIF_EVENT'),
      sightingId: s(`old-${i}`), createdAt: s('2026-09-10T11:00:00Z') });
  }
  let manifest = buildManifest(input);
  assert.deepEqual(manifest.anomalies, []);
  const store = storage(input);
  applyManifest(manifest, store, { approvalHash: manifest.approvalHash, completed: [], reverted: [] }, () => {});
  assert.equal(store.get(input.target.sightingsTable, { pk: s('NOTIF#EVENT#old-1'), sk: s('STATE') }).jobStatus.S, 'CANCELLED');
  assert.ok(store.get(input.target.sightingsTable, { pk: s('NOTIF#EVENT#old-0'), sk: s('STATE') }));
  input.sightings.find(item => item.pk.S === 'NOTIF#EVENT#old-1').historyDone = { BOOL: true };
  manifest = buildManifest(input);
  assert.equal(manifest.anomalies.length, 1);
  assert.throws(() => applyManifest(manifest, storage(input),
    { approvalHash: manifest.approvalHash, completed: [], reverted: [] }, () => {}), /blocked/);
});

test('counter mismatch and mixed already-grouped history require reconciliation', () => {
  const input = snapshot(2);
  input.species[0].referenceCount = n(8);
  assert.ok(buildManifest(input).anomalies.some(message => message.includes('reference count')));
  input.species[0].referenceCount = n(2);
  input.sightings[0].groupKey = s(groupKey(['sp-0'], '2026-09-10', '10:15'));
  assert.ok(buildManifest(input).anomalies.some(message => message.includes('Mixed')));
  assert.throws(() => awsStore({ endpoint: 'https://remote.test', region: 'us-east-1' }), /loopback/);
});

test('authorized historical cancellation preserves progress, delivered inbox, ledgers and rollback', () => {
  const input = snapshot(2);
  for (let i = 0; i < 2; i++) {
    input.sightings.push({ pk: s('NOTIF#PENDING'), sk: s(`pending-${i}`), itemType: s('NOTIF_PENDING'),
      sightingId: s(`old-${i}`), createdAt: s('2026-09-10T11:00:00Z') });
    input.sightings.push({ pk: s(`NOTIF#EVENT#old-${i}`), sk: s('STATE'), itemType: s('NOTIF_EVENT'),
      sightingId: s(`old-${i}`), historyDone: { BOOL: i === 0 }, complete: { BOOL: false },
      jobStatus: s('WAITING'), lastError: s('HTTP_403') });
  }
  input.sightings.push({ pk: s('NOTIF#EVENT#old-0'), sk: s('PUSH#synthetic#accepted'),
    status: s('ACCEPTED'), attempts: n(1), messageId: s('synthetic-message') });
  input.sightings.push({ pk: s('NOTIF#EVENT#old-0'), sk: s('PUSH#synthetic#waiting'),
    status: s('WAITING'), attempts: n(1), errorCode: s('HTTP_403') });
  input.sightings.push({ pk: s('NOTIF#USER#synthetic'), sk: s('NOTICE#synthetic'),
    itemType: s('NOTIF_NOTICE'), read: { BOOL: true }, sightingId: s('old-0') });
  input.sightings.push({ pk: s('NOTIF#USER#synthetic'), sk: s('PROFILE'),
    unreadCount: n(0), inboxGeneration: n(0) });

  const blocked = buildManifest(input);
  assert.ok(blocked.anomalies.length);
  const manifest = buildManifest(input, { pendingPolicy: 'cancel-historical' });
  assert.deepEqual(manifest.anomalies, []);
  assert.equal(manifest.cancelledPending, 2);
  assert.notEqual(manifest.approvalHash, blocked.approvalHash);
  assert.throws(() => buildManifest(input, { pendingPolicy: 'ignore' }), /policy/);
  const store = storage(input);
  const journal = { approvalHash: manifest.approvalHash, completed: [], reverted: [] };
  applyManifest(manifest, store, journal, () => {});
  assert.equal([...store.items.values()].filter(item => item.itemType?.S === 'NOTIF_PENDING').length, 0);
  for (const original of input.sightings.filter(item => item.itemType?.S === 'NOTIF_EVENT')) {
    const event = store.get(input.target.sightingsTable, key(original));
    assert.deepEqual(event, { ...original, jobStatus: s('CANCELLED'),
      cancellationReason: s('GROUPING_HISTORICAL_CANCELLED'), cancelledAt: s(input.capturedAt), supersededBy: s('old-0') });
  }
  for (const original of input.sightings.filter(item => item.sk.S.startsWith('PUSH#') || item.pk.S.startsWith('NOTIF#USER#'))) {
    assert.deepEqual(store.get(input.target.sightingsTable, key(original)), original);
  }
  const written = store.writes();
  applyManifest(manifest, store, journal, () => {});
  assert.equal(store.writes(), written);
  applyManifest(manifest, store, journal, () => {}, true);
  assert.equal(store.items.size, input.sightings.length + input.species.length);
  for (const original of input.sightings) assert.deepEqual(store.get(input.target.sightingsTable, key(original)), original);
  for (const original of input.species) assert.deepEqual(store.get(input.target.speciesTable, key(original)), original);
});

test('historical cancellation blocks missing state and live event or delivery leases', () => {
  const input = snapshot(1);
  input.sightings.push({ pk: s('NOTIF#PENDING'), sk: s('pending-0'), itemType: s('NOTIF_PENDING'), sightingId: s('old-0') });
  const options = { pendingPolicy: 'cancel-historical' };
  assert.ok(buildManifest(input, options).anomalies.length);
  const event = { pk: s('NOTIF#EVENT#old-0'), sk: s('STATE'), itemType: s('NOTIF_EVENT'),
    sightingId: s('old-0'), leaseOwner: s('synthetic-owner') };
  input.sightings.push(event);
  assert.ok(buildManifest(input, options).anomalies.length);
  event.leaseUntil = n(Date.parse(input.capturedAt) + 1000);
  assert.ok(buildManifest(input, options).anomalies.length);
  event.leaseUntil = n(Date.parse(input.capturedAt) - 1000);
  assert.deepEqual(buildManifest(input, options).anomalies, []);
  const attempt = { pk: s('NOTIF#EVENT#old-0'), sk: s('PUSH#synthetic'), status: s('SENDING'),
    leaseUntil: n(Date.parse(input.capturedAt) + 1000) };
  input.sightings.push(attempt);
  const manifest = buildManifest(input, options);
  const store = storage(input);
  assert.throws(() => applyManifest(manifest, store,
    { approvalHash: manifest.approvalHash, completed: [], reverted: [] }, () => {}), /blocked/);
  assert.equal(store.writes(), 0);
  attempt.leaseUntil = n(Date.parse(input.capturedAt) - 1000);
  assert.deepEqual(buildManifest(input, options).anomalies, []);
});
