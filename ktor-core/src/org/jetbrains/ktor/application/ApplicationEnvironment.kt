package org.jetbrains.ktor.application

import com.jdiazcano.cfg4k.providers.*
import org.slf4j.*

/**
 * Represents an environment in which [Application] runs
 */
interface ApplicationEnvironment {
    /**
     * [ClassLoader] used to load application.
     * Useful for various reflection-based services, like dependency injection.
     */
    val classLoader: ClassLoader

    /**
     * Instance of [Logger] to be used for logging.
     */
    val log: Logger

    /**
     * Binding config provider
     */
    val configProvider: ConfigProvider

    /**
     * Provides events on Application lifecycle
     */
    val monitor: ApplicationMonitor
}

