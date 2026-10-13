package tcc.aves.repository.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.enhanced.dynamodb.document.EnhancedDocument;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import tcc.aves.exception.ConcurrentUpdateException;
import tcc.aves.exception.ResourceNotFoundException;
import tcc.aves.repository.NotificationRepository;
import tcc.aves.repository.dynamodb.model.NotificationJobItem;
import tcc.aves.repository.dynamodb.support.DynamoDbSupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

public class NotificationRepositoryDynamoDbAdapter implements NotificationRepository {
    private final DynamoDbClient client;
    private final DynamoDbTable<EnhancedDocument> documents;
    private final String table;
    private final String usersTable;

    public NotificationRepositoryDynamoDbAdapter(DynamoDbClient client, DynamoDbEnhancedClient enhanced,
                                                 String table, String usersTable) {
        this.client = client;
        this.table = table;
        this.usersTable = usersTable;
        this.documents = enhanced.table(table, TableSchema.documentSchemaBuilder()
                .addIndexPartitionKey(TableMetadata.primaryIndexName(), "pk", AttributeValueType.S)
                .addIndexSortKey(TableMetadata.primaryIndexName(), "sk", AttributeValueType.S)
                .attributeConverterProviders(AttributeConverterProvider.defaultProvider()).build());
    }

    @Override
    public Preferences subscribe(String userId, Instant now) {
        Map<String, AttributeValue> existing = get(user(userId), "PROFILE");
        if (!existing.isEmpty()) return preferences(existing);
        Map<String, AttributeValue> profile = item(user(userId), "PROFILE",
                "startedAt", s(now.toString()), "pushEnabled", bool(true), "unreadCount", n(0));
        Map<String, AttributeValue> participant = item("NOTIF#PARTICIPANTS", order(now, userId),
                "userId", s(userId), "startedAt", s(now.toString()));
        try {
            transact(put(profile, "attribute_not_exists(pk)", Map.of()),
                    put(participant, "attribute_not_exists(pk)", Map.of()), userExists(userId));
        } catch (TransactionCanceledException e) {
            if (conditional(e, 2)) throw new ResourceNotFoundException("Conta não encontrada");
            if (!conditional(e, 0)) throw e;
            return preferences(userId);
        }
        return preferences(profile);
    }

    @Override
    public Preferences preferences(String userId) {
        Map<String, AttributeValue> profile = get(user(userId), "PROFILE");
        if (profile.isEmpty()) throw new ResourceNotFoundException("Participação não encontrada");
        return preferences(profile);
    }

    @Override
    public Preferences setPreference(String userId, boolean enabled) {
        try {
            Map<String, AttributeValue> result = client.updateItem(r -> r.tableName(table)
                    .key(key(user(userId), "PROFILE")).updateExpression("SET pushEnabled = :enabled")
                    .conditionExpression("attribute_exists(pk)")
                    .expressionAttributeValues(Map.of(":enabled", bool(enabled)))
                    .returnValues(ReturnValue.ALL_NEW)).attributes();
            return preferences(result);
        } catch (ConditionalCheckFailedException e) {
            throw new ResourceNotFoundException("Participação não encontrada");
        }
    }

    @Override
    public void registerDevice(String userId, String installationId, String token) {
        preferences(userId);
        String hash = hash(token);
        for (int retry = 0; retry < 3; retry++) {
            Map<String, AttributeValue> previous = get(user(userId), "DEVICE#" + installationId);
            Map<String, AttributeValue> owner = get("NOTIF#TOKEN#" + hash, "OWNER");
            if (active(previous) && token.equals(text(previous, "token"))
                    && userId.equals(text(owner, "userId")) && installationId.equals(text(owner, "installationId"))
                    && text(previous, "generation").equals(text(owner, "generation"))) return;
            String generation = UUID.randomUUID().toString();
            Map<String, AttributeValue> device = item(user(userId), "DEVICE#" + installationId,
                    "userId", s(userId), "installationId", s(installationId), "token", s(token),
                    "tokenHash", s(hash), "active", bool(true), "generation", s(generation),
                    "updatedAt", s(Instant.now().toString()));
            Map<String, AttributeValue> reservation = item("NOTIF#TOKEN#" + hash, "OWNER",
                    "userId", s(userId), "installationId", s(installationId), "generation", s(generation));
            List<TransactWriteItem> writes = new ArrayList<>();
            writes.add(put(device, generationCondition(previous), generationValues(previous)));
            writes.add(put(reservation, generationCondition(owner), generationValues(owner)));
            writes.add(userExists(userId));
            if (active(previous) && !hash.equals(text(previous, "tokenHash"))) {
                writes.add(delete(key("NOTIF#TOKEN#" + text(previous, "tokenHash"), "OWNER"),
                        "generation = :g", Map.of(":g", s(text(previous, "generation")))));
            }
            if (!owner.isEmpty() && !(userId.equals(text(owner, "userId"))
                    && installationId.equals(text(owner, "installationId")))) {
                writes.add(update(key(user(text(owner, "userId")), "DEVICE#" + text(owner, "installationId")),
                        "SET active = :false", "generation = :g",
                        Map.of(":false", bool(false), ":g", s(text(owner, "generation")))));
            }
            try {
                transact(writes.toArray(TransactWriteItem[]::new));
                return;
            } catch (TransactionCanceledException e) {
                if (conditional(e, 2)) throw new ResourceNotFoundException("Conta não encontrada");
                if (!hasConditional(e)) throw e;
            }
        }
        throw new ConcurrentUpdateException("Cadastro mudou durante a atualização; tente novamente");
    }

    @Override
    public void deactivateDevice(String userId, String installationId) {
        for (int retry = 0; retry < 3; retry++) {
            Map<String, AttributeValue> device = get(user(userId), "DEVICE#" + installationId);
            if (!active(device)) return;
            if (deactivate(device)) return;
        }
        throw new ConcurrentUpdateException("Dispositivo mudou durante a saída; tente novamente");
    }

    @Override
    public void deactivateGeneration(Device device) {
        Map<String, AttributeValue> current = get(user(device.userId()), "DEVICE#" + device.installationId());
        if (active(current) && device.generation().equals(text(current, "generation"))) deactivate(current);
    }

    private boolean deactivate(Map<String, AttributeValue> device) {
        String generation = text(device, "generation");
        try {
            transact(update(key(text(device, "pk"), text(device, "sk")), "SET active = :false",
                            "generation = :g", Map.of(":false", bool(false), ":g", s(generation))),
                    delete(key("NOTIF#TOKEN#" + text(device, "tokenHash"), "OWNER"),
                            "generation = :g", Map.of(":g", s(generation))));
            return true;
        } catch (TransactionCanceledException e) {
            if (!hasConditional(e)) throw e;
            return false;
        }
    }

    @Override
    public Page<Notice> inbox(String userId, int limit, String cursor) {
        long generation = number(requireProfile(userId), "inboxGeneration");
        String prefix = inboxPrefix(generation);
        QueryEnhancedRequest.Builder request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.sortBetween(
                        Key.builder().partitionValue(user(userId)).sortValue(prefix).build(),
                        Key.builder().partitionValue(user(userId)).sortValue(prefix + "~").build()))
                .scanIndexForward(false).consistentRead(true).limit(limit);
        if (cursor != null && !cursor.isBlank()) {
            String decoded;
            try {
                if (cursor.length() > 1024) throw new IllegalArgumentException();
                decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                String[] parts = decoded.split("\\|", -1);
                if (parts.length != 3 || !parts[0].equals(userId)
                        || !parts[1].equals(Long.toString(generation))
                        || !parts[2].matches("[0-9]{19}#[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException();
                request.exclusiveStartKey(key(user(userId), prefix + parts[2]));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Cursor de notificações inválido");
            }
        }
        software.amazon.awssdk.enhanced.dynamodb.model.Page<EnhancedDocument> page =
                documents.query(request.build()).iterator().next();
        String next = page.lastEvaluatedKey() == null || page.lastEvaluatedKey().isEmpty() ? null
                : Base64.getUrlEncoder().withoutPadding().encodeToString(
                (userId + "|" + generation + "|" + page.lastEvaluatedKey().get("sk").s().substring(prefix.length()))
                        .getBytes(StandardCharsets.UTF_8));
        if (number(requireProfile(userId), "inboxGeneration") != generation) {
            throw new IllegalArgumentException("Caixa de notificações mudou; recarregue a primeira página");
        }
        return new Page<>(page.items().stream().map(d -> notice(d.toMap())).toList(), next);
    }

    @Override
    public void clearInbox(String userId, String idempotencyKey, Instant now) {
        String receiptKey = "CLEAR#" + hash(idempotencyKey);
        for (int retry = 0; retry < 5; retry++) {
            Map<String, AttributeValue> profile = requireProfile(userId);
            if (!get(user(userId), receiptKey).isEmpty()) {
                try {
                    transact(userExists(userId));
                    return;
                } catch (TransactionCanceledException e) {
                    if (conditional(e, 0)) throw new ResourceNotFoundException("Conta não encontrada");
                    throw e;
                }
            }
            long generation = number(profile, "inboxGeneration");
            Instant previous = profile.containsKey("clearedThrough")
                    ? Instant.parse(text(profile, "clearedThrough")) : Instant.MIN;
            Instant cutoff = now.isAfter(previous) ? now : previous;
            Map<String, AttributeValue> values = inboxGenerationValues(generation);
            values.put(":next", n(generation + 1));
            values.put(":zero", n(0));
            values.put(":cutoff", s(cutoff.toString()));
            try {
                transact(update(key(user(userId), "PROFILE"),
                                "SET inboxGeneration = :next, unreadCount = :zero, clearedThrough = :cutoff",
                                "attribute_exists(pk) AND " + inboxGenerationCondition(generation), values),
                        put(item(user(userId), receiptKey, "inboxGeneration", n(generation + 1),
                                        "clearedThrough", s(cutoff.toString())),
                                "attribute_not_exists(pk)", Map.of()),
                        userExists(userId));
                return;
            } catch (TransactionCanceledException e) {
                if (conditional(e, 2)) throw new ResourceNotFoundException("Conta não encontrada");
                if (!hasConditional(e)) throw e;
            }
        }
        throw new ConcurrentUpdateException("Caixa mudou durante a limpeza; tente novamente com a mesma Idempotency-Key");
    }

    @Override
    public Notice markRead(String userId, String id, Instant now) {
        for (int retry = 0; retry < 3; retry++) {
            long generation = number(requireProfile(userId), "inboxGeneration");
            Map<String, AttributeValue> locator = get(user(userId), "NOTIFICATION#" + id);
            if (locator.isEmpty() || number(locator, "inboxGeneration") != generation) {
                throw new ResourceNotFoundException("Notificação não encontrada");
            }
            String inboxKey = text(locator, "inboxKey");
            Map<String, AttributeValue> current = get(user(userId), inboxKey);
            if (current.isEmpty()) throw new ResourceNotFoundException("Notificação não encontrada");
            if (current.containsKey("readAt")) {
                if (number(requireProfile(userId), "inboxGeneration") != generation) continue;
                return notice(current);
            }
            Map<String, AttributeValue> values = inboxGenerationValues(generation);
            values.put(":minus", n(-1));
            values.put(":one", n(1));
            try {
                transact(update(key(user(userId), inboxKey), "SET readAt = :at",
                                "attribute_exists(pk) AND attribute_not_exists(readAt)", Map.of(":at", s(now.toString()))),
                        update(key(user(userId), "PROFILE"), "ADD unreadCount :minus",
                                "attribute_exists(pk) AND unreadCount >= :one AND " + inboxGenerationCondition(generation), values),
                        userExists(userId));
                return notice(get(user(userId), inboxKey));
            } catch (TransactionCanceledException e) {
                if (conditional(e, 2)) throw new ResourceNotFoundException("Conta não encontrada");
                if (!hasConditional(e)) throw e;
            }
        }
        throw new ConcurrentUpdateException("Leitura mudou durante a atualização; tente novamente");
    }

    @Override
    public Page<Participant> participants(Event event, String cursor) {
        String end = order(event.createdAt(), "~");
        if (cursor != null && cursor.compareTo(end) >= 0) return new Page<>(List.of(), null);
        Key upper = Key.builder().partitionValue("NOTIF#PARTICIPANTS").sortValue(end).build();
        QueryConditional conditional = cursor == null ? QueryConditional.sortLessThanOrEqualTo(upper)
                : QueryConditional.sortBetween(Key.builder().partitionValue("NOTIF#PARTICIPANTS")
                .sortValue(cursor + "\u0000").build(), upper);
        var page = documents.query(QueryEnhancedRequest.builder().queryConditional(conditional)
                .consistentRead(true).limit(20).build()).iterator().next();
        List<Participant> items = page.items().stream().map(d -> new Participant(d.getString("sk"),
                d.getString("userId"), Instant.parse(d.getString("startedAt")))).toList();
        return new Page<>(items, lastSortKey(page));
    }

    @Override
    public Page<Device> devices(String userId, String cursor) {
        QueryConditional conditional = QueryConditional.sortBetween(
                Key.builder().partitionValue(user(userId)).sortValue(cursor == null ? "DEVICE#" : cursor + "\u0000").build(),
                Key.builder().partitionValue(user(userId)).sortValue("DEVICE#~").build());
        var page = documents.query(QueryEnhancedRequest.builder().queryConditional(conditional)
                .consistentRead(true).limit(20).build()).iterator().next();
        return new Page<>(page.items().stream().filter(d -> Boolean.TRUE.equals(d.getBoolean("active")))
                .map(d -> new Device(userId, d.getString("installationId"), d.getString("token"), d.getString("generation")))
                .toList(), lastSortKey(page));
    }

    @Override
    public boolean createNotice(String userId, Event event) {
        for (int retry = 0; retry < 5; retry++) {
            Map<String, AttributeValue> profile = get(user(userId), "PROFILE");
            if (!eventEligible(profile, event)) return false;
            long generation = number(profile, "inboxGeneration");
            String inboxKey = inboxPrefix(generation) + order(event.createdAt(), event.sightingId());
            Map<String, AttributeValue> notification = item(user(userId), inboxKey,
                    "sightingId", s(event.sightingId()), "createdAt", s(event.createdAt().toString()),
                    "inboxGeneration", n(generation), "title", s(event.title()), "body", s(event.body()));
            Map<String, AttributeValue> values = inboxGenerationValues(generation);
            values.put(":one", n(1));
            try {
                transact(put(notification, "attribute_not_exists(pk)", Map.of()),
                        update(key(user(userId), "PROFILE"), "ADD unreadCount :one",
                                "attribute_exists(pk) AND " + inboxGenerationCondition(generation), values),
                        userExists(userId),
                        put(item(user(userId), "NOTIFICATION#" + event.sightingId(),
                                        "inboxKey", s(inboxKey), "inboxGeneration", n(generation)),
                                "attribute_not_exists(pk)", Map.of()));
                return true;
            } catch (TransactionCanceledException e) {
                if (conditional(e, 0) || conditional(e, 2) || conditional(e, 3)) return false;
                if (!hasConditional(e)) throw e;
            }
        }
        throw new ConcurrentUpdateException("Caixa mudou durante a criação; tente novamente");
    }

    @Override
    public boolean eligible(Device device) {
        Map<String, AttributeValue> profile = get(user(device.userId()), "PROFILE");
        Map<String, AttributeValue> current = get(user(device.userId()), "DEVICE#" + device.installationId());
        Map<String, AttributeValue> reservation = get("NOTIF#TOKEN#" + hash(device.token()), "OWNER");
        return Boolean.TRUE.equals(profile.getOrDefault("pushEnabled", bool(false)).bool())
                && active(current) && device.generation().equals(text(current, "generation"))
                && device.generation().equals(text(reservation, "generation"))
                && !client.getItem(r -> r.tableName(usersTable).consistentRead(true)
                .key(Map.of("pk", s("USER#" + device.userId())))).item().isEmpty();
    }

    @Override
    public boolean eligible(Device device, Event event) {
        Map<String, AttributeValue> profile = get(user(device.userId()), "PROFILE");
        Map<String, AttributeValue> locator = get(user(device.userId()), "NOTIFICATION#" + event.sightingId());
        return eventEligible(profile, event) && !locator.isEmpty()
                && number(locator, "inboxGeneration") == number(profile, "inboxGeneration") && eligible(device);
    }

    @Override
    public Progress acquireEvent(Event event, String owner, Instant now) {
        try {
            var result = client.updateItem(r -> r.tableName(table).key(key("NOTIF#EVENT#" + event.sightingId(), "STATE"))
                    .updateExpression("SET leaseOwner = :owner, leaseUntil = :until")
                    .conditionExpression("attribute_not_exists(leaseUntil) OR leaseUntil <= :now")
                    .expressionAttributeValues(Map.of(":owner", s(owner), ":until", n(now.plusSeconds(120).toEpochMilli()),
                            ":now", n(now.toEpochMilli()))).returnValues(ReturnValue.ALL_NEW)).attributes();
            return new Progress(nullableText(result, "historyCursor"), truth(result, "historyDone"),
                    nullableText(result, "pushCursor"), nullableText(result, "deviceCursor"), truth(result, "complete"));
        } catch (ConditionalCheckFailedException e) {
            throw new ConcurrentUpdateException("Evento já está em processamento");
        }
    }

    @Override
    public void checkpoint(Event event, String owner, Progress progress) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":owner", s(owner));
        values.put(":h", s(empty(progress.historyCursor())));
        values.put(":hd", bool(progress.historyDone()));
        values.put(":p", s(empty(progress.pushCursor())));
        values.put(":d", s(empty(progress.deviceCursor())));
        values.put(":c", bool(progress.complete()));
        client.updateItem(r -> r.tableName(table).key(key("NOTIF#EVENT#" + event.sightingId(), "STATE"))
                .updateExpression("SET historyCursor = :h, historyDone = :hd, pushCursor = :p, deviceCursor = :d, complete = :c")
                .conditionExpression("leaseOwner = :owner").expressionAttributeValues(values));
    }

    @Override
    public void releaseEvent(Event event, String owner) {
        try {
            client.updateItem(r -> r.tableName(table).key(key("NOTIF#EVENT#" + event.sightingId(), "STATE"))
                    .updateExpression("REMOVE leaseOwner, leaseUntil").conditionExpression("leaseOwner = :owner")
                    .expressionAttributeValues(Map.of(":owner", s(owner))));
        } catch (ConditionalCheckFailedException e) {
            throw new ConcurrentUpdateException("Lease do evento mudou durante o processamento");
        }
    }

    @Override
    public Attempt attempt(Event event, Device device) {
        Map<String, AttributeValue> item = get("NOTIF#EVENT#" + event.sightingId(), attemptKey(device));
        return new Attempt(text(item, "status"), (int) number(item, "attempts"),
                item.containsKey("nextAttemptAt") ? Instant.ofEpochMilli(number(item, "nextAttemptAt")) : Instant.EPOCH,
                item.containsKey("leaseUntil") ? Instant.ofEpochMilli(number(item, "leaseUntil")) : Instant.EPOCH);
    }

    @Override
    public boolean claimAttempt(Event event, Device device, Instant now) {
        Map<String, AttributeValue> profile = get(user(device.userId()), "PROFILE");
        long generation = number(profile, "inboxGeneration");
        Map<String, AttributeValue> locator = get(user(device.userId()), "NOTIFICATION#" + event.sightingId());
        if (!eventEligible(profile, event) || locator.isEmpty()
                || number(locator, "inboxGeneration") != generation) return false;
        Map<String, AttributeValue> profileValues = inboxGenerationValues(generation);
        profileValues.put(":at", s(now.toString()));
        profileValues.put(":true", bool(true));
        try {
            transact(update(key("NOTIF#EVENT#" + event.sightingId(), attemptKey(device)),
                            "SET #status = :sending, leaseUntil = :until ADD attempts :one",
                            "(attribute_not_exists(#status) OR (#status <> :accepted AND #status <> :skipped)) "
                                    + "AND (attribute_not_exists(nextAttemptAt) OR nextAttemptAt <= :now) "
                                    + "AND (attribute_not_exists(leaseUntil) OR leaseUntil <= :now)",
                            Map.of(":sending", s("SENDING"), ":accepted", s("ACCEPTED"), ":skipped", s("SKIPPED"),
                                    ":until", n(now.plusSeconds(30).toEpochMilli()), ":now", n(now.toEpochMilli()), ":one", n(1)),
                            Map.of("#status", "status")),
                    update(key(user(device.userId()), "DEVICE#" + device.installationId()), "SET lastPushAttemptAt = :at",
                            "active = :true AND generation = :g",
                            Map.of(":at", s(now.toString()), ":true", bool(true), ":g", s(device.generation()))),
                    update(key(user(device.userId()), "PROFILE"), "SET lastPushAttemptAt = :at",
                            "pushEnabled = :true AND " + inboxGenerationCondition(generation), profileValues),
                    update(key("NOTIF#TOKEN#" + hash(device.token()), "OWNER"), "SET lastPushAttemptAt = :at",
                            "generation = :g", Map.of(":at", s(now.toString()), ":g", s(device.generation()))),
                    userExists(device.userId()));
            return true;
        } catch (TransactionCanceledException e) {
            if (!hasConditional(e)) throw e;
            return false;
        }
    }

    @Override
    public void finishAttempt(Event event, Device device, String status, String messageId, String errorCode, Instant nextAt) {
        client.updateItem(r -> r.tableName(table).key(key("NOTIF#EVENT#" + event.sightingId(), attemptKey(device)))
                .updateExpression("SET #status = :status, messageId = :message, errorCode = :error, nextAttemptAt = :next REMOVE leaseUntil")
                .conditionExpression("#status = :sending").expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":status", s(status), ":sending", s("SENDING"),
                        ":message", s(empty(messageId)), ":error", s(empty(errorCode)), ":next", n(nextAt.toEpochMilli()))));
    }

    @Override
    public List<Job> dueJobs(Instant now, int limit) {
        var page = documents.query(QueryEnhancedRequest.builder().consistentRead(true).limit(limit)
                .queryConditional(QueryConditional.sortLessThanOrEqualTo(Key.builder()
                        .partitionValue(NotificationJobItem.PENDING).sortValue(order(now, "~")).build()))
                .build()).iterator().next();
        return page.items().stream().map(d -> new Job(
                new Event(d.getString("sightingId"), Instant.parse(d.getString("createdAt"))), d.getString("sk"))).toList();
    }

    @Override
    public void completeJob(Job job) {
        finishJob(job, null, null, false);
    }

    @Override
    public void deferJob(Job job, Instant nextAt, String errorCode, boolean terminal) {
        finishJob(job, nextAt, errorCode, terminal);
    }

    private void finishJob(Job job, Instant nextAt, String errorCode, boolean terminal) {
        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(delete(key(NotificationJobItem.PENDING, job.key()), "attribute_exists(pk)", Map.of()));
        writes.add(update(key("NOTIF#EVENT#" + job.event().sightingId(), "STATE"),
                "SET jobStatus = :status, lastError = :error",
                "attribute_exists(pk) AND attribute_not_exists(leaseOwner)" + (nextAt == null ? " AND complete = :true" : ""),
                nextAt == null ? Map.of(":status", s("COMPLETE"), ":error", s(""), ":true", bool(true))
                        : Map.of(":status", s(terminal ? "FAILED" : "WAITING"), ":error", s(errorCode))));
        if (nextAt != null && !terminal) {
            writes.add(put(item(NotificationJobItem.PENDING, order(nextAt, job.event().sightingId()),
                            "itemType", s("NOTIF_PENDING"), "sightingId", s(job.event().sightingId()),
                            "createdAt", s(job.event().createdAt().toString())), "attribute_not_exists(pk)", Map.of()));
        }
        try {
            transact(writes.toArray(TransactWriteItem[]::new));
        } catch (TransactionCanceledException e) {
            if (!hasConditional(e)) throw e;
            throw new ConcurrentUpdateException("Trabalho de notificação mudou durante a atualização");
        }
    }

    private Map<String, AttributeValue> get(String pk, String sk) {
        EnhancedDocument document = documents.getItem(GetItemEnhancedRequest.builder()
                .key(Key.builder().partitionValue(pk).sortValue(sk).build()).consistentRead(true).build());
        return document == null ? Map.of() : document.toMap();
    }

    private Map<String, AttributeValue> requireProfile(String userId) {
        Map<String, AttributeValue> profile = get(user(userId), "PROFILE");
        if (profile.isEmpty()) throw new ResourceNotFoundException("Participação não encontrada");
        return profile;
    }

    private static boolean eventEligible(Map<String, AttributeValue> profile, Event event) {
        return !profile.isEmpty() && !event.createdAt().isBefore(Instant.parse(text(profile, "startedAt")))
                && (!profile.containsKey("clearedThrough")
                || event.createdAt().isAfter(Instant.parse(text(profile, "clearedThrough"))));
    }

    private static String inboxPrefix(long generation) {
        return generation == 0 ? "INBOX#" : "INBOXG#" + generation + "#";
    }

    private static String inboxGenerationCondition(long generation) {
        return generation == 0 ? "(attribute_not_exists(inboxGeneration) OR inboxGeneration = :generation)"
                : "inboxGeneration = :generation";
    }

    private static Map<String, AttributeValue> inboxGenerationValues(long generation) {
        return new HashMap<>(Map.of(":generation", n(generation)));
    }

    private void transact(TransactWriteItem... writes) {
        for (int attempt = 0; ; attempt++) {
            try {
                client.transactWriteItems(r -> r.transactItems(writes));
                return;
            } catch (TransactionCanceledException e) {
                if (hasConditional(e) || e.cancellationReasons().stream()
                        .noneMatch(reason -> "TransactionConflict".equals(reason.code()))) throw e;
                if (attempt >= 2) throw new ConcurrentUpdateException("Estado de notificação alterado simultaneamente; tente novamente");
            }
        }
    }

    private TransactWriteItem userExists(String userId) {
        return TransactWriteItem.builder().update(Update.builder().tableName(usersTable)
                .key(Map.of("pk", s("USER#" + userId)))
                .updateExpression("SET notificationActivityAt = :at ADD #version :one")
                .conditionExpression("attribute_exists(pk) AND itemType = :type")
                .expressionAttributeNames(Map.of("#version", "version"))
                .expressionAttributeValues(Map.of(":at", s(Instant.now().toString()),
                        ":one", n(1), ":type", s("USER"))).build()).build();
    }

    private TransactWriteItem put(Map<String, AttributeValue> item, String condition, Map<String, AttributeValue> values) {
        return TransactWriteItem.builder().put(Put.builder().tableName(table).item(item)
                .conditionExpression(condition).expressionAttributeValues(values.isEmpty() ? null : values).build()).build();
    }

    private TransactWriteItem delete(Map<String, AttributeValue> key, String condition, Map<String, AttributeValue> values) {
        return TransactWriteItem.builder().delete(Delete.builder().tableName(table).key(key)
                .conditionExpression(condition).expressionAttributeValues(values.isEmpty() ? null : values).build()).build();
    }

    private TransactWriteItem update(Map<String, AttributeValue> key, String expression, String condition,
                                    Map<String, AttributeValue> values) {
        return update(key, expression, condition, values, Map.of());
    }

    private TransactWriteItem update(Map<String, AttributeValue> key, String expression, String condition,
                                    Map<String, AttributeValue> values, Map<String, String> names) {
        return TransactWriteItem.builder().update(Update.builder().tableName(table).key(key).updateExpression(expression)
                .conditionExpression(condition).expressionAttributeValues(values.isEmpty() ? null : values)
                .expressionAttributeNames(names.isEmpty() ? null : names).build()).build();
    }

    private static Map<String, AttributeValue> item(String pk, String sk, Object... entries) {
        Map<String, AttributeValue> result = new HashMap<>(key(pk, sk));
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], (AttributeValue) entries[i + 1]);
        return result;
    }

    private static Preferences preferences(Map<String, AttributeValue> profile) {
        return new Preferences(Instant.parse(text(profile, "startedAt")), truth(profile, "pushEnabled"), number(profile, "unreadCount"));
    }

    private static Notice notice(Map<String, AttributeValue> item) {
        String read = nullableText(item, "readAt");
        return new Notice(text(item, "sightingId"), text(item, "sightingId"), Instant.parse(text(item, "createdAt")),
                read == null ? null : Instant.parse(read), text(item, "title"), text(item, "body"));
    }

    private static String lastSortKey(software.amazon.awssdk.enhanced.dynamodb.model.Page<EnhancedDocument> page) {
        return page.lastEvaluatedKey() == null || page.lastEvaluatedKey().isEmpty() ? null : page.lastEvaluatedKey().get("sk").s();
    }

    private static String generationCondition(Map<String, AttributeValue> item) {
        return item.isEmpty() ? "attribute_not_exists(pk)" : "generation = :g";
    }

    private static Map<String, AttributeValue> generationValues(Map<String, AttributeValue> item) {
        return item.isEmpty() ? Map.of() : Map.of(":g", s(text(item, "generation")));
    }

    private static boolean conditional(TransactionCanceledException e, int index) {
        return e.cancellationReasons().size() > index && "ConditionalCheckFailed".equals(e.cancellationReasons().get(index).code());
    }
    private static boolean hasConditional(TransactionCanceledException e) {
        return DynamoDbSupport.hasConditionFailure(e.cancellationReasons());
    }
    private static Map<String, AttributeValue> key(String pk, String sk) { return Map.of("pk", s(pk), "sk", s(sk)); }
    private static String user(String id) { return "NOTIF#USER#" + id; }
    private static String order(Instant at, String id) { return String.format(Locale.ROOT, "%019d#%s", at.toEpochMilli(), id); }
    private static String attemptKey(Device d) { return "PUSH#" + d.generation(); }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue n(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
    private static AttributeValue bool(boolean value) { return AttributeValue.builder().bool(value).build(); }
    private static String empty(String value) { return value == null ? "" : value; }
    private static String text(Map<String, AttributeValue> item, String name) { return item.containsKey(name) ? item.get(name).s() : ""; }
    private static String nullableText(Map<String, AttributeValue> item, String name) {
        String value = text(item, name); return value.isEmpty() ? null : value;
    }
    private static long number(Map<String, AttributeValue> item, String name) { return item.containsKey(name) ? Long.parseLong(item.get(name).n()) : 0; }
    private static boolean truth(Map<String, AttributeValue> item, String name) { return item.containsKey(name) && Boolean.TRUE.equals(item.get(name).bool()); }
    private static boolean active(Map<String, AttributeValue> item) { return truth(item, "active"); }
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
