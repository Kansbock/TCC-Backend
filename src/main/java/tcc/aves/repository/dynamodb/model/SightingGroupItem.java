package tcc.aves.repository.dynamodb.model;

import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class SightingGroupItem {
    private String pk;
    private String sk;
    private String itemType;
    private String sightingId;
    private Long nextSequence;
    private Long liveCount;
    private Long version;

    @DynamoDbPartitionKey public String getPk() { return pk; }
    public void setPk(String pk) { this.pk = pk; }
    @DynamoDbSortKey public String getSk() { return sk; }
    public void setSk(String sk) { this.sk = sk; }
    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; }
    public String getSightingId() { return sightingId; }
    public void setSightingId(String sightingId) { this.sightingId = sightingId; }
    public Long getNextSequence() { return nextSequence; }
    public void setNextSequence(Long nextSequence) { this.nextSequence = nextSequence; }
    public Long getLiveCount() { return liveCount; }
    public void setLiveCount(Long liveCount) { this.liveCount = liveCount; }
    @DynamoDbVersionAttribute public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
