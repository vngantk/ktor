package io.ktor.client.engine.cio

import ch.qos.logback.classic.*
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.client.tests.utils.*
import io.ktor.http.*
import io.ktor.network.tls.certificates.*
import io.ktor.network.tls.extensions.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.*
import io.ktor.util.*
import kotlinx.coroutines.*
import org.junit.*
import org.slf4j.*
import org.slf4j.Logger
import java.io.*
import java.security.*
import javax.net.ssl.*
import kotlin.test.*
import kotlin.test.Test

class CIOHttpsTest : TestWithKtor() {
    override val server: ApplicationEngine = embeddedServer(Jetty, applicationEngineEnvironment {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        root.level = Level.DEBUG

        sslConnector(keyStore, "sha256ecdsa", { "changeit".toCharArray() }, { "changeit".toCharArray() }) {
            port = serverPort
            keyStorePath = keyStoreFile.absoluteFile

            module {
                routing {
                    get("/") {
                        call.respondText("Hello, world")
                    }
                }
            }
        }
    })

    companion object {
        val keyStoreFile = File("build/temp.jks")
        lateinit var keyStore: KeyStore
        private lateinit var sslContext: SSLContext
        lateinit var x509TrustManager: X509TrustManager

        @BeforeClass
        @JvmStatic
        fun setupAll() {
            keyStore = buildKeyStore {
                certificate("sha384ecdsa") {
                    hash = HashAlgorithm.SHA384
                    sign = SignatureAlgorithm.ECDSA
                    keySizeInBits = 384
                    password = "changeit"
                }
                certificate("sha256ecdsa") {
                    hash = HashAlgorithm.SHA256
                    sign = SignatureAlgorithm.ECDSA
                    keySizeInBits = 256
                    password = "changeit"
                }
                certificate("sha384rsa") {
                    hash = HashAlgorithm.SHA384
                    sign = SignatureAlgorithm.RSA
                    keySizeInBits = 1024
                    password = "changeit"
                }
                certificate("sha1rsa") {
                    hash = HashAlgorithm.SHA1
                    sign = SignatureAlgorithm.RSA
                    keySizeInBits = 1024
                    password = "changeit"
                }
            }

            keyStore.saveToFile(keyStoreFile, "changeit")
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)
            sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, tmf.trustManagers, null)
            x509TrustManager = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
        }

    }

    @Test
    fun hello(): Unit = runBlocking {
        HttpClient(CIO.config {
            https.apply {
                trustManager = x509TrustManager
            }
        }).use { client ->
            val actual = client.get<String>("https://127.0.0.1:$serverPort/")
            assertEquals("Hello, world", actual)
        }
    }

    @Test
    fun external(): Unit = clientTest(CIO) {
        test { client ->
            val response = client.get<HttpResponse>("https://kotlinlang.org")
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun customDomainsTest() = clientTest(CIO) {
        val domains = listOf(
            "https://google.com",
            "https://facebook.com",
//            "https://elster.de"
            "https://freenode.net"
        )

        test { client ->
            domains.forEach { url ->
                client.get<String>(url)
            }
        }
    }

    @Test
    fun repeatRequestTest() = clientTest(CIO) {
        config {
            followRedirects = false

            engine {
                maxConnectionsCount = 1_000_000
                pipelining = true
                endpoint.apply {
                    connectRetryAttempts = 1
                    maxConnectionsPerRoute = 10_000
                }
            }
        }

        test { client ->
            val testSize = 10
            var received = 0
            client.async {
                repeat(testSize) {
                    client.get<HttpResponse>("https://www.facebook.com").use { response ->
                        assertTrue(response.status.isSuccess())
                        received++
                    }
                }
            }.await()

            assertEquals(testSize, received)
        }
    }
}
