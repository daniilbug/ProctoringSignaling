package main.kotlin

import com.google.gson.Gson
import io.ktor.application.Application
import io.ktor.application.install
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.features.DefaultHeaders
import io.ktor.gson.gson
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import io.ktor.routing.routing
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.WebSocketServerSession
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Duration
import java.util.*

enum class MessageAction {
    ICE_CANDIDATE,
    JOIN,
    SESSION_DESCRIPTION,
    CREATE_OFFER,
    CREATE_ANSWER,
    REMOVE
}

data class Message(
    val action: MessageAction,
    val from: String? = null,
    val to: String? = null,
    val text: String = ""
)

@ExperimentalCoroutinesApi
fun main() {
    embeddedServer(Netty, applicationEngineEnvironment {
        connector {
            port = 8080
            host = "0.0.0.0"
        }
        module { modules() }
    }).start(true)
}

@ExperimentalCoroutinesApi
fun Application.modules() {
    install(DefaultHeaders) {
        header("X-Engine", "Ktor") // will send this header with each response
    }

    install(CallLogging)

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(ContentNegotiation) {
        gson {
        }
    }

    val connections = Collections.synchronizedMap(mutableMapOf<String, WebSocketServerSession>())
    val gson = Gson()

    routing {
        webSocket(path = "/connect") {
            val id = UUID.randomUUID().toString()
            connections[id] = this
            println("Connected clients = ${connections.size}")
            try {
                for (data in incoming) {
                    if (data is Frame.Text) {
                        val message = gson.fromJson(data.readText(), Message::class.java) ?: continue
                        println("got message: $message")
                        when(message.action) {
                            MessageAction.JOIN -> if (connections.size > 1) onJoin(id, connections)
                            MessageAction.SESSION_DESCRIPTION -> onSessionDescription(id, connections, message)
                            MessageAction.ICE_CANDIDATE -> onIceCandidate(id, connections, message)
                            else -> println("Unknown action for server: ${message.action}")
                        }
                    }
                }
            } finally {
                connections.remove(id)
                onExit(id, connections)
            }
        }
    }
}

suspend fun onIceCandidate(fromId: String, connections: Map<String, WebSocketServerSession>, message: Message) {
    connections[message.to]?.send(Message(message.action, fromId, message.to, message.text).toFrame())
    println("sent ICE_CANDIDATE to ${message.to}")
}

suspend fun onSessionDescription(fromId: String, connections: Map<String, WebSocketServerSession>, message: Message) {
    val action = if (message.text.contains("offer", true)) {
        MessageAction.CREATE_ANSWER
    } else {
        MessageAction.SESSION_DESCRIPTION
    }
    connections[message.to]?.send(Message(action, fromId, message.to, message.text).toFrame())
    println("sent SESSION_DESCRIPTION to ${message.to}")
}

suspend fun onJoin(fromId: String, connections: Map<String, WebSocketServerSession>) {
    println("got JOIN message from $fromId")
    connections.forEach { (id, _) ->
        if (id != fromId) {
            connections[fromId]?.send(Message(MessageAction.CREATE_OFFER, id, fromId).toFrame())
            println("sent CREATE_OFFER to $fromId")
        }
    }
}

suspend fun onExit(whoId: String, connections: MutableMap<String, WebSocketServerSession>) {
    connections.forEach { (id, client) ->
        client.send(Message(MessageAction.REMOVE, null, id, whoId).toFrame())
    }
}

fun Message.toFrame(): Frame {
    val json = Gson().toJson(this)
    return Frame.Text(json)
}