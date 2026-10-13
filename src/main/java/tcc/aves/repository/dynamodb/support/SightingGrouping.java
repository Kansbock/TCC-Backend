package tcc.aves.repository.dynamodb.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class SightingGrouping {
    public static final int MAX_PHOTOS = 4;

    private SightingGrouping() {}

    public static LocalTime minute(LocalTime time) { return time.truncatedTo(ChronoUnit.MINUTES); }

    public static String key(List<String> speciesIds, LocalDate date, LocalTime time) {
        StringBuilder canonical = new StringBuilder("v1\n").append(date).append('\n')
                .append(String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute())).append('\n');
        speciesIds.stream().distinct().sorted().forEach(id ->
                canonical.append(id.length()).append(':').append(id).append('\n'));
        return "SIGHTING_GROUP#v1#" + hash(canonical.toString());
    }

    public static String photo(String url) { return "PHOTO#" + hash(url); }

    public static String member(long sequence) { return String.format(Locale.ROOT, "MEMBER#%019d", sequence); }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
