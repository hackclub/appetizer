package com.fantamomo.slack.approver.utils

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resumeWithException

/**
 * Invokes a compiled Kotlin suspend function represented by a Java reflection method.
 *
 * The supplied method must have a trailing Continuation parameter, and the number of
 * provided arguments must match the method parameter count excluding that continuation.
 * Any exception thrown by the invoked method is resumed on the current continuation.
 *
 * @param method the reflected method representing a suspend function.
 * @param receiver the instance on which to invoke the method, or null for static methods.
 * @param args the arguments to pass to the method, excluding the implicit Continuation argument.
 * @return the result produced by the invoked suspend function, or null if the function returns null.
 * @throws IllegalArgumentException if the method parameter count does not match the provided arguments
 * or if the last method parameter is not a Continuation.
 */
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