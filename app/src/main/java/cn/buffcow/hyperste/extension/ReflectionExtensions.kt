package cn.buffcow.hyperste.extension

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Invokes a reflected method and propagates the exception thrown by the target method.
 *
 * @author qingyu
 * <p>Create on 2026/08/14 16:36</p>
 */
internal fun Method.invokeUnwrapped(receiver: Any?, vararg arguments: Any?): Any? {
    return try {
        invoke(receiver, *arguments)
    } catch (error: InvocationTargetException) {
        throw error.cause ?: error
    }
}
