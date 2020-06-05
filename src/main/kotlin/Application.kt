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
    REMOVE,
    EXIT
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

    val connections = Collections.synchronizedMap(
        mutableMapOf<String, WebSocketServerSession>()
    )
    val locals = Collections.synchronizedMap(
        mutableMapOf<String, MutableSet<String>>()
    )
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
                        when(message.action) {
                            MessageAction.JOIN -> if (connections.size > 1) onJoin(id, connections, message, locals)
                            MessageAction.SESSION_DESCRIPTION -> onSessionDescription(id, connections, message, locals)
                            MessageAction.ICE_CANDIDATE -> onIceCandidate(id, connections, message, locals)
                            MessageAction.EXIT -> onExit(id, connections, message, locals)
                            else -> println("Unknown action for server: ${message.action}")
                        }
                    }
                }
            } finally {
                exitAllFromThatClient(id, locals, connections)
            }
        }
    }
}

suspend fun exitAllFromThatClient(
    fromId: String,
    locals: MutableMap<String, MutableSet<String>>,
    connections: MutableMap<String, WebSocketServerSession>
) {
    val curLocals = locals[fromId]
    if (curLocals == null) {
        connections.forEach { (id, client) ->
            if (id != fromId)
                client.send(Message(MessageAction.REMOVE, fromId, id, fromId).toFrame())
        }
    } else {
        curLocals.forEach { local ->
            val fullId = getFullId(fromId, local)
            connections.forEach { (id, client) ->
                if (id != fromId)
                    client.send(Message(MessageAction.REMOVE, fullId, id, fullId).toFrame())
            }
        }
    }
    connections.remove(fromId)
}

suspend fun onJoin(
    fromId: String,
    connections: MutableMap<String, WebSocketServerSession>,
    message: Message,
    locals: MutableMap<String, MutableSet<String>>
) {
    println("got JOIN message from $fromId")

    val curLocals = locals[fromId]
    if (message.from != null) {
        if (curLocals == null) {
            locals[fromId] = mutableSetOf(message.from)
        } else {
            curLocals.add(message.from)
        }
    }
    val connection  = connections[fromId]
    connections.forEach { (id, _) ->
        if (id != fromId) {
            val toLocals = locals[id]
            if (toLocals == null) {
                connection?.send(Message(MessageAction.CREATE_OFFER, id, getFullId(fromId, message.from)).toFrame())
            } else {
                toLocals.forEach { localId ->
                    connection?.send(Message(MessageAction.CREATE_OFFER, getFullId(id, localId), getFullId(fromId, message.from)).toFrame())
                }
            }
            println("sent CREATE_OFFER to $fromId")
        }
    }
}

suspend fun onIceCandidate(
    fromId: String,
    connections: MutableMap<String, WebSocketServerSession>,
    message: Message,
    locals: MutableMap<String, MutableSet<String>>
) {
    val mainId = getMainId(message.to)
    val connection = connections[mainId]
    val toLocals = locals[message.to]
    if (toLocals?.isNotEmpty() == true) {
        toLocals.forEach { local ->
            connection?.send(Message(message.action, getFullId(fromId, message.from), getFullId(message.to, local), message.text).toFrame())
        }
    } else {
        connection?.send(Message(message.action, getFullId(fromId, message.from), message.to, message.text).toFrame())
    }
    println("sent ICE_CANDIDATE to $mainId")
}

suspend fun onSessionDescription(
    fromId: String,
    connections: MutableMap<String, WebSocketServerSession>,
    message: Message,
    locals: MutableMap<String, MutableSet<String>>
) {
    val action = if (message.text.contains("offer", true)) {
        MessageAction.CREATE_ANSWER
    } else {
        MessageAction.SESSION_DESCRIPTION
    }
    val mainId = getMainId(message.to)
    val connection = connections[mainId]
    val toLocals = locals[message.to]
    if (toLocals?.isNotEmpty() == true) {
        toLocals.forEach { local ->
            connection?.send(Message(action, getFullId(fromId, message.from), getFullId(message.to, local), message.text).toFrame())
        }
    } else {
        connection?.send(Message(action, getFullId(fromId, message.from), message.to, message.text).toFrame())
    }
    println("sent SESSION_DESCRIPTION to $mainId")
}

fun getMainId(to: String?): String {
    if (to == null) return ""
    if (":" in to) return to.split(":")[0]
    return to
}

suspend fun onExit(
    fromId: String,
    connections: MutableMap<String, WebSocketServerSession>,
    message: Message,
    locals: MutableMap<String, MutableSet<String>>
) {
    val fullId = getFullId(fromId, message.from)
    connections.forEach { (id, client) ->
        if (id != fromId)
            client.send(Message(MessageAction.REMOVE, fullId, id).toFrame())
    }
    locals[fromId]?.remove(message.from)
}

fun getFullId(id: String?, local: String?): String {
    if (local == null) return id ?: ""
    return "$id:$local"
}

fun Message.toFrame(): Frame {
    val json = Gson().toJson(this)
    return Frame.Text(json)
}