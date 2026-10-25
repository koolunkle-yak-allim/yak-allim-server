package com.example.yakallim

import com.example.yakallim.ocr.repository.RedisTestSupport
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 블루-그린 전환처럼 서버 두 대가 같은 Redis를 쓸 때, 한 서버가 받은 작업을 다른 서버가 이어서 처리하는지 확인한다.
 * n8n은 요청을 받기만 하는 가짜 서버로 대신하고, 콜백은 테스트가 직접 보낸다. Docker가 필요하다.
 */
class BlueGreenHandoverTest {

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = jacksonObjectMapper()

    private fun url(context: ConfigurableApplicationContext, path: String): URI {
        val port = (context as ServletWebServerApplicationContext).webServer.port
        return URI.create("http://127.0.0.1:$port/api/v1/ocr$path")
    }

    private fun enqueue(context: ConfigurableApplicationContext): String {
        val boundary = "----yakallim-test"
        val body = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"rx.jpg\"\r\n" +
            "Content-Type: image/jpeg\r\n\r\n" +
            "synthetic\r\n" +
            "--$boundary--\r\n"
        val response = http.send(
            HttpRequest.newBuilder(url(context, "/enqueue"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(202, response.statusCode(), response.body())
        return json.readTree(response.body())["jobId"].asText()
    }

    private fun jobStatus(context: ConfigurableApplicationContext, jobId: String): Map<*, *> {
        val response = http.send(
            HttpRequest.newBuilder(url(context, "/jobs/$jobId")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(200, response.statusCode(), response.body())
        return json.readValue(response.body(), Map::class.java)
    }

    private fun awaitStatus(context: ConfigurableApplicationContext, jobId: String, status: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (jobStatus(context, jobId)["status"] != status && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(status, jobStatus(context, jobId)["status"])
    }

    /** SSE 줄을 받는 대로 큐에 넣는다. */
    private fun subscribe(context: ConfigurableApplicationContext, jobId: String): LinkedBlockingQueue<String> {
        val lines = LinkedBlockingQueue<String>()
        http.sendAsync(
            HttpRequest.newBuilder(url(context, "/jobs/$jobId/progress")).GET().build(),
            HttpResponse.BodyHandlers.ofLines()
        ).thenAccept { response -> response.body().forEach(lines::add) }
        return lines
    }

    private val seenLines = mutableListOf<String>()

    private fun awaitLine(lines: LinkedBlockingQueue<String>, contains: String): String? {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val line = lines.poll(100, TimeUnit.MILLISECONDS) ?: continue
            seenLines += line
            if (contains in line) return line
        }
        println("SSE lines so far: $seenLines")
        return null
    }

    @Test
    @DisplayName("blue가 받은 작업의 콜백이 green에 와도, blue에 연결된 SSE와 조회 모두 완료를 받는다")
    fun callbackOnOtherInstanceReachesSubscriber() {
        val jobId = enqueue(blue)
        // green도 blue가 받은 작업을 보고 있다.
        awaitStatus(green, jobId, "PROCESSING")
        assertEquals(1, n8nRequests.get(), "이미지는 가짜 n8n으로만 간다")

        val sse = subscribe(blue, jobId)
        assertNotNull(awaitLine(sse, "\"step\":\"ANALYZING\""), "구독 직후 현재 단계(ANALYZING)")

        val callback = """
            {"jobId":"$jobId","data":{"medicines":[
              {"medicineName":"타이레놀정500mg","dosagePerTake":"1정","dailyFrequency":3,"durationDays":5}
            ]}}
        """.trimIndent()
        val callbackResponse = http.send(
            HttpRequest.newBuilder(url(green, "/n8n/callback/$jobId"))
                .header("Content-Type", "application/json")
                .header("X-N8N-Secret", "test-secret")
                .POST(HttpRequest.BodyPublishers.ofString(callback))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(200, callbackResponse.statusCode(), callbackResponse.body())

        assertNotNull(awaitLine(sse, "\"step\":\"COMPLETED\""), "blue SSE가 green이 받은 완료를 전달")
        val job = jobStatus(blue, jobId)
        assertEquals("COMPLETED", job["status"])
        val medicines = (job["result"] as Map<*, *>)["medicines"] as List<*>
        assertEquals("타이레놀정500mg", (medicines.single() as Map<*, *>)["medicineName"])
    }

    companion object {
        private lateinit var n8n: HttpServer
        private val n8nRequests = AtomicInteger()
        private lateinit var blue: ConfigurableApplicationContext
        private lateinit var green: ConfigurableApplicationContext

        // properties()는 가장 낮은 우선순위라 테스트 application.properties의 n8n 주소에 덮인다.
        // 실제 n8n으로 보내지 않도록 우선순위가 가장 높은 명령줄 인자로 넘긴다.
        private fun startServer(): ConfigurableApplicationContext =
            SpringApplicationBuilder(YakallimApplication::class.java).run(
                "--server.port=0",
                "--ocr.job-store=redis",
                "--spring.data.redis.host=${RedisTestSupport.host}",
                "--spring.data.redis.port=${RedisTestSupport.port}",
                "--ocr.n8n.webhook-url=http://127.0.0.1:${n8n.address.port}/webhook/ocr",
                "--ocr.upload-dir=${Files.createTempDirectory("yakallim-upload")}"
            )

        @JvmStatic
        @BeforeAll
        fun startServers() {
            RedisTestSupport.template().connectionFactory!!.connection.use { it.serverCommands().flushAll() }
            // 이미지를 받기만 하는 가짜 n8n. 받은 요청 수를 세어, 실제 n8n이 아니라 여기로 왔는지 확인한다.
            n8n = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/webhook/ocr") { exchange ->
                    n8nRequests.incrementAndGet()
                    exchange.requestBody.readAllBytes()
                    exchange.sendResponseHeaders(200, -1)
                    exchange.close()
                }
                start()
            }
            blue = startServer()
            green = startServer()
        }

        @JvmStatic
        @AfterAll
        fun stopServers() {
            blue.close()
            green.close()
            n8n.stop(0)
        }
    }
}
