package com.actors.examples

import com.actors.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * ═══════════════════════════════════════════════════════════════════
 * EXAMPLE 2: Actor-Based HTTP Request Handler (Web Framework)
 * ═══════════════════════════════════════════════════════════════════
 *
 * Demonstrates how to build a web framework's request processing
 * pipeline using actors, showing advantages over Netty/thread-per-request:
 *
 *   - **No thread pool exhaustion**: each handler is a lightweight coroutine
 *   - **Backpressure**: bounded mailboxes prevent overload
 *   - **Fault isolation**: one handler crash doesn't affect others
 *   - **Middleware pipeline**: actors compose naturally as pipelines
 *   - **Stateful handlers**: actors maintain state safely (sessions, caches)
 *
 * Architecture:
 * ```
 *  HTTP Request
 *       │
 *       ▼
 *  ┌─────────────┐     ┌─────────────┐     ┌──────────────┐
 *  │  Rate Limiter│────▶│  Auth       │────▶│   Router     │
 *  │  (actor)     │     │  Middleware │     │  (actor)     │
 *  └─────────────┘     └─────────────┘     └─────┬────────┘
 *                                                 │
 *                                    ┌────────────┼────────────┐
 *                                    ▼            ▼            ▼
 *                              ┌─────────┐  ┌─────────┐  ┌─────────┐
 *                              │ GET /api │  │POST /api│  │ GET /   │
 *                              │ handler  │  │ handler │  │ handler │
 *                              └─────────┘  └─────────┘  └─────────┘
 * ```
 *
 * Why better than Netty (for certain workloads):
 *   1. Actors have built-in fault tolerance (supervisor restarts)
 *   2. No thread pool tuning needed (coroutines auto-scale)
 *   3. Natural backpressure (bounded mailboxes reject excess load)
 *   4. Stateful handlers without synchronization
 *   5. Easy to add middleware by composing actor pipelines
 *
 * Future (Distributed):
 *   - Route requests to handler actors on different nodes
 *   - Sticky sessions via ConsistentHash routing
 *   - Distributed rate limiting across cluster
 */

// ─── Message Protocols ──────────────────────────────────────────

/** Incoming HTTP-like request */
data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val requestId: String = "req-${System.nanoTime()}"
)

/** Outgoing HTTP-like response */
data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    val requestId: String = ""
)

/** Internal message wrapping request + reply channel */
data class HttpExchange(
    val request: HttpRequest,
    override val replyTo: ActorRef<HttpResponse>
) : Request<HttpResponse>

// ─── Rate Limiter Middleware (Stateful Actor) ───────────────────

/**
 * Actor-based rate limiter using a sliding window.
 * Maintains state (request count) without any locks.
 *
 * Equivalent to a middleware in Express.js or Spring WebFlux,
 * but with guaranteed thread safety via actor model.
 */
sealed class RateLimiterMsg {
    data class Check(val exchange: HttpExchange) : RateLimiterMsg()
    data object WindowReset : RateLimiterMsg()
}

fun rateLimiterBehavior(
    maxRequests: Int,
    downstream: ActorRef<HttpExchange>
): Behavior<RateLimiterMsg> {
    var currentCount = 0

    return behavior { msg ->
        when (msg) {
            is RateLimiterMsg.Check -> {
                if (currentCount >= maxRequests) {
                    // 429 Too Many Requests
                    msg.exchange.replyTo.tell(HttpResponse(
                        statusCode = 429,
                        body = """{"error": "Rate limit exceeded. Max $maxRequests requests per window."}""",
                        requestId = msg.exchange.request.requestId
                    ))
                    println("  [RateLimiter] BLOCKED ${msg.exchange.request.requestId} ($currentCount/$maxRequests)")
                } else {
                    currentCount++
                    println("  [RateLimiter] PASSED ${msg.exchange.request.requestId} ($currentCount/$maxRequests)")
                    downstream.tell(msg.exchange)
                }
                Behavior.same()
            }
            is RateLimiterMsg.WindowReset -> {
                println("  [RateLimiter] Window reset (was $currentCount)")
                currentCount = 0
                Behavior.same()
            }
        }
    }
}

// ─── Request Router (Pattern Matching) ──────────────────────────

/**
 * Routes requests to handler actors based on path matching.
 * This is the core of a web framework's routing system.
 */
fun routerBehavior(
    routes: Map<String, ActorRef<HttpExchange>>
): Behavior<HttpExchange> = statelessBehavior { exchange ->
    val path = exchange.request.path
    val method = exchange.request.method

    val handler = routes.entries
        .firstOrNull { (pattern, _) -> path.startsWith(pattern) }
        ?.value

    if (handler != null) {
        println("  [Router] ${method} ${path} → ${handler.name}")
        handler.tell(exchange)
    } else {
        // 404 Not Found
        exchange.replyTo.tell(HttpResponse(
            statusCode = 404,
            body = """{"error": "No handler for $method $path"}""",
            requestId = exchange.request.requestId
        ))
        println("  [Router] ${method} ${path} → 404 NOT FOUND")
    }
}

// ─── Handlers ───────────────────────────────────────────────────

/** Handler that maintains a stateful in-memory store (like a session store). */
fun crudHandlerBehavior(resourceName: String): Behavior<HttpExchange> {
    val store = mutableMapOf<String, String>()

    return behavior { exchange ->
        val req = exchange.request
        val response = when (req.method) {
            "GET" -> {
                val data = store.entries.joinToString(", ") { "${it.key}=${it.value}" }
                HttpResponse(200, """{"$resourceName": {$data}, "count": ${store.size}}""",
                    requestId = req.requestId)
            }
            "POST" -> {
                val key = "item-${store.size + 1}"
                store[key] = req.body
                HttpResponse(201, """{"created": "$key", "value": "${req.body}"}""",
                    requestId = req.requestId)
            }
            "DELETE" -> {
                store.clear()
                HttpResponse(200, """{"deleted": "all $resourceName"}""",
                    requestId = req.requestId)
            }
            else -> HttpResponse(405, """{"error": "Method ${req.method} not allowed"}""",
                requestId = req.requestId)
        }
        exchange.replyTo.tell(response)
        println("  [Handler:$resourceName] ${req.method} → ${response.statusCode}")
        Behavior.same()
    }
}

/** Simple health check handler */
fun healthHandlerBehavior(): Behavior<HttpExchange> = statelessBehavior { exchange ->
    exchange.replyTo.tell(HttpResponse(
        statusCode = 200,
        body = """{"status": "healthy", "uptime": "${System.currentTimeMillis()}"}""",
        requestId = exchange.request.requestId
    ))
    println("  [Handler:health] 200 OK")
}

// ─── Main: Run the Web Server ───────────────────────────────────

fun main() = runBlocking {
    println("═══════════════════════════════════════════════════════════")
    println(" Actor-Based Web Framework")
    println("═══════════════════════════════════════════════════════════")
    println()

    val system = ActorSystem.create("web-server")

    // 1. Create handler actors (stateful, fault-tolerant)
    val usersHandler = system.spawn("handler-users",
        crudHandlerBehavior("users"),
        supervisorStrategy = SupervisorStrategy.restart(maxRestarts = 5))

    val productsHandler = system.spawn("handler-products",
        crudHandlerBehavior("products"),
        supervisorStrategy = SupervisorStrategy.restart(maxRestarts = 5))

    val healthHandler = system.spawn("handler-health",
        healthHandlerBehavior())

    // 2. Create request router
    val routes = mapOf(
        "/api/users" to usersHandler,
        "/api/products" to productsHandler,
        "/health" to healthHandler
    )
    val router = system.spawn("request-router",
        routerBehavior(routes))

    // 3. Create rate limiter middleware (max 10 requests per window)
    val rateLimiter = system.spawn("rate-limiter",
        rateLimiterBehavior(maxRequests = 10, downstream = router))

    println("Server started with routes:")
    routes.keys.forEach { println("  ${it}") }
    println()

    // 4. Simulate incoming HTTP requests
    println("─── Incoming Requests ──────────────────────────────────")

    val requests = listOf(
        HttpRequest("GET", "/health"),
        HttpRequest("POST", "/api/users", body = "Alice"),
        HttpRequest("POST", "/api/users", body = "Bob"),
        HttpRequest("GET", "/api/users"),
        HttpRequest("POST", "/api/products", body = "Widget"),
        HttpRequest("GET", "/api/products"),
        HttpRequest("GET", "/api/unknown"),  // → 404
        HttpRequest("DELETE", "/api/users"),
    )

    for (request in requests) {
        println()
        println(">>> ${request.method} ${request.path}")

        // Use ask pattern: send request, await response (like HTTP)
        val response: HttpResponse = rateLimiter.ask(timeout = 5.seconds) { replyTo ->
            RateLimiterMsg.Check(HttpExchange(request, replyTo))
        }

        println("<<< ${response.statusCode}: ${response.body}")
        delay(20.milliseconds)
    }

    println()
    println("─── Server Statistics ──────────────────────────────────")
    println("  Active actors: ${system.actorCount}")
    println("  Actor names: ${system.actorNames}")

    system.terminate()
    println()
    println("Server shut down.")
}
