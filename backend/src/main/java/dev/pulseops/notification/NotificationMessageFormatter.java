package dev.pulseops.notification;

import dev.pulseops.incident.IncidentEntity;
import dev.pulseops.monitor.MonitorEntity;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class NotificationMessageFormatter {

    static final int MESSAGE_MAX_LENGTH = 4096;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    public String format(NotificationEventType eventType, IncidentEntity incident,
                         MonitorEntity monitor) {
        String message = switch (eventType) {
            case INCIDENT_OPENED -> "【PulseOps】故障告警"
                    + "\n监控项：" + monitor.getName()
                    + "\n故障时间：" + TIME_FORMAT.format(incident.getStartedAt())
                    + "\n失败类型：" + incident.getFailureType().label()
                    + "\n详情：" + incident.getSummary();
            case INCIDENT_RESOLVED -> "【PulseOps】故障恢复"
                    + "\n监控项：" + monitor.getName()
                    + "\n故障时间：" + TIME_FORMAT.format(incident.getStartedAt())
                    + "\n恢复时间：" + TIME_FORMAT.format(incident.getResolvedAt());
        };
        if (message.length() <= MESSAGE_MAX_LENGTH) {
            return message;
        }
        int end = MESSAGE_MAX_LENGTH;
        if (Character.isHighSurrogate(message.charAt(end - 1))) {
            end--;
        }
        return message.substring(0, end);
    }
}
