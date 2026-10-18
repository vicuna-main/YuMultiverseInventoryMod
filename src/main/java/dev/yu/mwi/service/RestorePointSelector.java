package dev.yu.mwi.service;

import dev.yu.mwi.storage.RestorePoint;
import java.time.*;
import java.time.format.*;
import java.util.*;

/** Exact wall-clock selection; ambiguous seconds, dates and DST overlaps are never guessed. */
public final class RestorePointSelector {
    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter MILLISECOND = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss.SSS").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private RestorePointSelector() {}
    public static String label(RestorePoint point, ZoneId zone, List<RestorePoint> all) {
        String second = SECOND.format(point.committedAt().atZone(zone));
        boolean collision = all.stream().filter(p -> SECOND.format(p.committedAt().atZone(zone)).equals(second)).count() > 1;
        return (collision ? MILLISECOND : SECOND).format(point.committedAt().atZone(zone));
    }
    public static RestorePoint select(String token, List<RestorePoint> points, ZoneId zone) {
        try {
            List<RestorePoint> matching;
            if (token.matches("[0-9]{8}")) {
                LocalDate date = LocalDate.parse(token, DATE);
                matching = points.stream().filter(p -> p.committedAt().atZone(zone).toLocalDate().equals(date)).toList();
            } else {
                DateTimeFormatter format = token.matches("[0-9]{8}-[0-9]{6}\\.[0-9]{3}") ? MILLISECOND : SECOND;
                LocalDateTime.parse(token, format);
                matching = points.stream().filter(p -> format.format(p.committedAt().atZone(zone)).equals(token)).toList();
            }
            if (matching.size() == 1) return matching.getFirst();
            if (matching.isEmpty()) throw new IllegalArgumentException("该时间没有已提交恢复点");
            throw new IllegalArgumentException("时间对应多份记录，请从 list 选择完整时间：" + matching.stream()
                    .map(p -> label(p, zone, points) + " [" + p.revision() + "]").collect(java.util.stream.Collectors.joining(", ")));
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("时间格式应为 YYYYMMDD-HHmmss（同秒记录加 .SSS）或 YYYYMMDD", invalid);
        }
    }
}
