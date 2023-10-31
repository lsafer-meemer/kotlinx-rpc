package org.jetbrains.krpc.client

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import org.jetbrains.krpc.*
import org.jetbrains.krpc.client.internal.FieldDataObject
import org.jetbrains.krpc.client.internal.RPCFlow
import org.jetbrains.krpc.internal.*
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

private val CLIENT_ENGINE_ID = atomic(initial = 0L)

internal class RPCClientEngineImpl(
    override val transport: RPCTransport,
    serviceKClass: KClass<out RPC>,
    override val config: RPCConfig.Client = RPCConfig.Client.Default,
) : RPCEngine(), RPCClientEngine {
    private val callCounter = atomic(0L)
    private val engineId: Long = CLIENT_ENGINE_ID.incrementAndGet()
    override val serviceTypeString = serviceKClass.toString()
    private val logger = KotlinLogging.logger("RPCClientEngine[$serviceTypeString][0x${hashCode().toString(16)}]")

    override val coroutineContext: CoroutineContext = Job() + Dispatchers.Default

    init {
        coroutineContext.job.invokeOnCompletion {
            logger.trace { "Job completed with $it" }
        }
    }

    override fun <T> registerPlainFlowField(fieldName: String, type: KType): Flow<T> {
        return RPCFlow.Plain<T>(serviceTypeString).also { rpcFlow ->
            initializeFlowField(rpcFlow, fieldName, type)
        }
    }

    override fun <T> registerSharedFlowField(fieldName: String, type: KType): SharedFlow<T> {
        return RPCFlow.Shared<T>(serviceTypeString).also { rpcFlow ->
            initializeFlowField(rpcFlow, fieldName, type)
        }
    }

    override fun <T> registerStateFlowField(fieldName: String, type: KType): StateFlow<T> {
        return RPCFlow.State<T>(serviceTypeString).also { rpcFlow ->
            initializeFlowField(rpcFlow, fieldName, type)
        }
    }

    private fun initializeFlowField(rpcFlow: RPCFlow<*, *>, fieldName: String, type: KType) {
        val callInfo = RPCCallInfo(
            callableName = fieldName,
            data = FieldDataObject,
            dataType = typeOf<FieldDataObject>(),
            returnType = type,
        )

        launch {
            call(
                callInfo = callInfo,
                deferred = rpcFlow.deferred,
            )
        }
    }

    override suspend fun call(callInfo: RPCCallInfo, deferred: CompletableDeferred<*>): Any? {
        val (callContext, serialFormat) = prepareAndExecuteCall(callInfo, deferred)

        launch {
            handleOutgoingStreams(this, callContext, serialFormat)
        }

        launch {
            handleIncomingHotFlows(this, callContext)
        }

        return deferred.await()
    }

    private suspend fun <T> prepareAndExecuteCall(
        callInfo: RPCCallInfo,
        deferred: CompletableDeferred<T>,
    ): Pair<LazyRPCStreamContext, SerialFormat> {
        val id = callCounter.incrementAndGet()
        val callId = "$engineId:${callInfo.dataType}:$id"

        logger.trace { "start a call[$callId] ${callInfo.callableName}" }

        val flowContext = LazyRPCStreamContext { RPCStreamContext(callId, config) }
        val serialFormat = prepareSerialFormat(flowContext)
        val firstMessage = serializeRequest(callId, callInfo, serialFormat)

        @Suppress("UNCHECKED_CAST")
        executeCall(callId, flowContext, callInfo, firstMessage, serialFormat, deferred as CompletableDeferred<Any?>)

        return flowContext to serialFormat
    }

    private suspend fun executeCall(
        callId: String,
        flowContext: LazyRPCStreamContext,
        callInfo: RPCCallInfo,
        firstMessage: RPCMessage,
        serialFormat: SerialFormat,
        deferred: CompletableDeferred<Any?>,
    ) {
        launch {
            transport.subscribe { message ->
                if (message.callId != callId) return@subscribe false

                when (message) {
                    is RPCMessage.CallData -> error("Unexpected message")
                    is RPCMessage.CallException -> {
                        val cause = runCatching {
                            message.cause.deserialize()
                        }

                        val result = if (cause.isFailure) {
                            cause.exceptionOrNull()!!
                        } else {
                            cause.getOrNull()!!
                        }

                        deferred.completeExceptionally(result)
                    }

                    is RPCMessage.CallSuccess -> {
                        val value = runCatching {
                            val serializerResult = serialFormat.serializersModule.rpcSerializerForType(callInfo.returnType)

                            decodeMessageData(serialFormat, serializerResult, message)
                        }

                        deferred.completeWith(value)
                    }

                    is RPCMessage.StreamCancel -> {
                        flowContext.initialize().cancelStream(message)
                    }

                    is RPCMessage.StreamFinished -> {
                        flowContext.initialize().closeStream(message)
                    }

                    is RPCMessage.StreamMessage -> {
                        flowContext.initialize().send(message, serialFormat)
                    }
                }

                return@subscribe true
            }
        }

        coroutineContext.job.invokeOnCompletion {
            flowContext.valueOrNull?.close()
        }

        transport.send(firstMessage)
    }

    private fun serializeRequest(callId: String, callInfo: RPCCallInfo, serialFormat: SerialFormat): RPCMessage {
        val serializerData = serialFormat.serializersModule.rpcSerializerForType(callInfo.dataType)
        return when (serialFormat) {
            is StringFormat -> {
                val stringValue = serialFormat.encodeToString(serializerData, callInfo.data)
                RPCMessage.CallDataString(callId, serviceTypeString, callInfo.callableName, stringValue)
            }
            is BinaryFormat -> {
                val binaryValue = serialFormat.encodeToByteArray(serializerData, callInfo.data)
                RPCMessage.CallDataBinary(callId, serviceTypeString, callInfo.callableName, binaryValue)
            }
            else -> unsupportedSerialFormatError(serialFormat)
        }
    }
}