package org.jetbrains.krpc.transport.ktor.client

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.util.*
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import org.jetbrains.krpc.RPC
import org.jetbrains.krpc.RPCConfig
import org.jetbrains.krpc.RPCConfigBuilder
import org.jetbrains.krpc.client.clientOf
import org.jetbrains.krpc.rpcClientConfig
import org.jetbrains.krpc.transport.ktor.KtorTransport
import kotlin.reflect.KClass

private val RPCConfigAttributeKey = AttributeKey<RPCConfig.Client>("RPCConfigAttributeKey")

fun HttpRequestBuilder.rpcConfig(configBuilder: RPCConfigBuilder.Client.() -> Unit = {}) {
    // todo apply settings from ContentNegotiation
    val config = rpcClientConfig(configBuilder)

    attributes.put(RPCConfigAttributeKey, config)
}

suspend inline fun <reified T : RPC> HttpClient.rpc(
    urlString: String,
    crossinline block: HttpRequestBuilder.() -> Unit = {},
): T {
    return rpc {
        url(urlString)
        block()
    }
}

suspend inline fun <reified T : RPC> HttpClient.rpc(
    noinline block: HttpRequestBuilder.() -> Unit = {}
): T = rpc(T::class, block)

@OptIn(InternalCoroutinesApi::class)
suspend fun <T : RPC> HttpClient.rpc(
    serviceKClass: KClass<T>,
    block: HttpRequestBuilder.() -> Unit,
): T {
    val session = webSocketSession(block)
    val rpcConfig = session.call.attributes[RPCConfigAttributeKey]
    val transport = KtorTransport(rpcConfig.serialFormatInitializer.build(), session)
    val result = RPC.clientOf(serviceKClass, transport, rpcConfig)

    result.coroutineContext.job.invokeOnCompletion(onCancelling = true) {
        transport.cancel()
    }

    return result
}
