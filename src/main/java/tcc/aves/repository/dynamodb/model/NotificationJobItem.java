package tcc.aves.repository.dynamodb.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.time.Instant;
import java.util.Locale;

@DynamoDbBean
public class NotificationJobItem {
    public static final String PENDING = "NOTIF#PENDING";
    private String pk;
    private String sk;
    private String itemType;
    private String sightingId;
    private Instant createdAt;

    public static String order(Instant at, String id) {
        return String.format(Locale.ROOT, "%019d#%s", at.toEpochMilli(), id);
    }

    public static NotificationJobItem create(String id, Instant at, boolean pending) {
        var item = new NotificationJobItem();
        item.pk = pending ? PENDING : "NOTIF#EVENT#" + id;
        item.sk = pending ? order(at, id) : "STATE";
        item.itemType = pending ? "NOTIF_PENDING" : "NOTIF_EVENT";
        item.sightingId = id;
        item.createdAt = at;
        return item;
    }

    @DynamoDbPartitionKey public String getPk() { return pk; }
    public void setPk(String value) { pk = value; }
    @DynamoDbSortKey public String getSk() { return sk; }
    public void setSk(String value) { sk = value; }
    public String getItemType() { return itemType; }
    public void setItemType(String value) { itemType = value; }
    public String getSightingId() { return sightingId; }
    public void setSightingId(String value) { sightingId = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
}
