package ie.ul.cs4297.groupbuy.service

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import java.util.TimeZone

// Your service. The plumbing is in `platform` (leave it as it is); the two examples are in
// `example` (copy their patterns, then delete them). Your own code goes in packages of your own.

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
class ServiceApplication

fun main(args: Array<String>) {
    // Every time in this system is UTC (spec B, rule 4). Irish time is for screens only.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    runApplication<ServiceApplication>(*args)
}
