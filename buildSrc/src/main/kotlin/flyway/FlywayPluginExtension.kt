package flyway

/**
 * The configuring object for the Flyway plugin.
 */
abstract class FlywayPluginExtension : FlywayConfig() {
    init {
        applyConventions()
    }
}
