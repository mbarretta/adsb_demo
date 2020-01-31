import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import co.elastic.logging.logback.EcsEncoder

appender("FILE", RollingFileAppender) {
    file = "log.log"
    rollingPolicy(SizeAndTimeBasedRollingPolicy) {
        fileNamePattern = "log.log.%d{yyyy-MM-dd}.%i"
        maxHistory = 10
        maxFileSize = "100MB"
        totalSizeCap = "1GB"
    }
    encoder(EcsEncoder) {

        serviceName = "adsb_demo"
    }
}
appender("STDOUT", ConsoleAppender) {
    encoder(PatternLayoutEncoder) {
        pattern = "%date{MM.dd.yyyy HH:mm:ss.SSS} [%thread] %-5level %logger{35} - %msg%n"
    }
}
root(TRACE, ["FILE", "STDOUT"])