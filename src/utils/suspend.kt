package com.fantamomo.slack.approver.utils

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resumeWithException

suspend fun invokeSuspendMethode(
    method: Method,
    receiver: Any?,
    vararg args: Any?
): Any? {
    require(method.parameterCount == args.size + 1) { "Expected ${args.size + 1} arguments, got ${method.parameterCount}" }
    require(Continuation::class.java.isAssignableFrom(method.parameterTypes.last())) { "Expected ${Continuation::class.java} at last argument" }

    return suspendCoroutineUninterceptedOrReturn { continuation ->
        try {
            method.invoke(
                receiver,
                *args,
                continuation,
            )
        } catch (e: InvocationTargetException) {
            continuation.resumeWithException(e.targetException)
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
}