/**
 * @author qingyu
 * <p>Create on 2026/08/14 16:36</p>
 */
package cn.buffcow.hyperste.extension

import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Invokes a reflected method and propagates the exception thrown by the target method.
 */
internal fun Method.invokeUnwrapped(receiver: Any?, vararg arguments: Any?): Any? {
    return try {
        invoke(receiver, *arguments)
    } catch (error: InvocationTargetException) {
        throw error.cause ?: error
    }
}

/**
 * Finds an accessible field named [name] in this class or its superclass hierarchy.
 */
internal fun Class<*>.findField(name: String): Field {
    return generateSequence(this) { type -> type.superclass }
        .firstNotNullOfOrNull { type ->
            runCatching { type.getDeclaredField(name) }.getOrNull()
        }?.apply {
            isAccessible = true
        } ?: error("Field is unavailable: ${this.name}#$name")
}

/**
 * Finds an accessible method named [name] with [parameterCount] parameters.
 *
 * Public inherited methods are preferred before declared methods are searched through the class
 * hierarchy. Use the exact-parameter overload when a class exposes ambiguous overloads with the
 * same number of parameters.
 */
internal fun Class<*>.findMethod(name: String, parameterCount: Int): Method {
    return methods.firstOrNull { method ->
        method.name == name && method.parameterCount == parameterCount
    }?.apply {
        isAccessible = true
    } ?: generateSequence(this) { type -> type.superclass }
        .flatMap { type -> type.declaredMethods.asSequence() }
        .firstOrNull { method ->
            method.name == name && method.parameterCount == parameterCount
        }?.apply {
            isAccessible = true
        } ?: error("Method is unavailable: ${this.name}#$name/$parameterCount")
}

/**
 * Finds an accessible method named [name] with the exact [parameterTypes].
 *
 * Public inherited methods are preferred before declared methods are searched through the class
 * hierarchy.
 */
internal fun Class<*>.findMethod(name: String, vararg parameterTypes: Class<*>): Method {
    return runCatching {
        getMethod(name, *parameterTypes)
    }.recoverCatching {
        generateSequence(this) { type -> type.superclass }
            .firstNotNullOfOrNull { type ->
                runCatching {
                    type.getDeclaredMethod(name, *parameterTypes)
                }.getOrNull()
            } ?: throw it
    }.getOrThrow().apply {
        isAccessible = true
    }
}
