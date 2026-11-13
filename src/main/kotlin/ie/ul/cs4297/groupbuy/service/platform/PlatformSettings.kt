package ie.ul.cs4297.groupbuy.service.platform

import org.springframework.boot.context.properties.ConfigurationProperties

/** The `groupbuy.*` settings from application.yml, each set from an environment variable. */
@ConfigurationProperties("groupbuy")
data class PlatformSettings(
    /** SERVICE_NAME: deals, orders, ledger, payments, restaurant-ops, support, experience or platform. */
    val serviceName: String,
    /** Which instance this is: INSTANCE_ID, else the container's HOSTNAME. */
    val instanceId: String,
    /** CLOCK_URL: the platform clock. Required: empty stops the service at start-up, except in tests (ClockUrlCheck). */
    val clockUrl: String = "",
    val hold: Hold = Hold(),
    val outbox: Outbox = Outbox(),
    val timers: Timers = Timers(),
) {
    data class Hold(
        /** HOLD_CLIENT: stub or http. Empty (the default): http when LEDGER_URL is set, stub when not. */
        val client: String = "",
        /** LEDGER_URL, for http. */
        val ledgerUrl: String = "",
        /** HOLD_STUB_MODE: placed, insufficient, slow or down. */
        val stubMode: String = "placed",
    )

    data class Outbox(val pollMillis: Long = 200)
    data class Timers(val pollMillis: Long = 500)

    init {
        require(serviceName in SERVICE_NAMES) {
            "SERVICE_NAME is '$serviceName'; it must be one of ${SERVICE_NAMES.joinToString(", ")}"
        }
    }

    companion object {
        val SERVICE_NAMES = setOf("deals", "orders", "ledger", "payments", "restaurant-ops", "support", "experience", "platform")
    }
}
