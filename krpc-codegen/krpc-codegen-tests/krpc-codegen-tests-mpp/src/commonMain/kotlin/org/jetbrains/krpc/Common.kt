package org.jetbrains.krpc

import kotlinx.coroutines.Job
import org.jetbrains.krpc.client.clientOf
import org.jetbrains.krpc.server.rpcServiceMethodSerializationTypeOf
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.typeOf

interface EmptyService {
    suspend fun empty()
}

val stubEngine = object : RPCEngine {
    override val coroutineContext: CoroutineContext = Job()

    override suspend fun call(callInfo: RPCCallInfo): Any? {
        println("Called ${callInfo.methodName}")
        return null
    }
}

interface CommonService : RPC, EmptyService {
    override suspend fun empty()
}

inline fun <reified T : RPC> testService(test: T.() -> Unit) {
    RPC.clientOf<T>(stubEngine).test()
    RPC.clientOf<T>(typeOf<T>(), stubEngine).test()

    println(rpcServiceMethodSerializationTypeOf<T>("empty"))
    println(rpcServiceMethodSerializationTypeOf(typeOf<T>(), "empty"))
}
