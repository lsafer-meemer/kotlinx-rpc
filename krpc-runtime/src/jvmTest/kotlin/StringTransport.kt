import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.jetbrains.krpc.RPCMessage
import org.jetbrains.krpc.RPCTransport
import org.jetbrains.krpc.broadcast
import org.jetbrains.krpc.multiclient
import kotlin.coroutines.CoroutineContext

class StringTransport(waiting: Boolean = true) : CoroutineScope {
    override val coroutineContext = Job()
    private val clientIncoming = Channel<RPCMessage>()
    private val serverIncoming = Channel<RPCMessage>()


    val client: RPCTransport = object : RPCTransport(coroutineContext) {

        override suspend fun send(message: RPCMessage) {
            serverIncoming.send(message)
        }

        override suspend fun subscribe(block: suspend (RPCMessage) -> Boolean) {
            for (message in clientIncoming) {
                block(message)
            }
        }
    }.broadcast(waiting)

    val server: RPCTransport = object : RPCTransport(coroutineContext) {
        override suspend fun send(message: RPCMessage) {
            clientIncoming.send(message)
        }

        override suspend fun subscribe(block: suspend (RPCMessage) -> Boolean) {
            for (message in serverIncoming) {
                block(message)
            }
        }
    }.broadcast(waiting)
}