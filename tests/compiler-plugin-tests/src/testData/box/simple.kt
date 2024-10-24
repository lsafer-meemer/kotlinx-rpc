/*
 * Copyright 2023-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import kotlinx.coroutines.runBlocking
import kotlinx.rpc.RPC
import kotlinx.rpc.withService
import kotlinx.rpc.codegen.test.TestRpcClient

interface BoxService : RPC {
    suspend fun simple(): String
}

fun box(): String = runBlocking {
    val result = TestRpcClient.withService<BoxService>().simple()

    if (result == "call_42") "OK" else "Fail: $result"
}
