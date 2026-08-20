// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.sun.jna.FunctionMapper
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import java.lang.reflect.Method

/**
 * Process-wide, lazy Objective-C runtime access for macOS JVM bridges.
 *
 * The dispatch methods deliberately have unique names for every native ABI
 * shape. JNA maps only those methods to objc_msgSend; the runtime C functions
 * retain their exported names. Keeping the load lazy lets non-macOS JVM code
 * load these source sets without resolving libobjc.
 */
internal object MacObjectiveCRuntime {
    private val binding: MacObjectiveCRuntimeBinding by lazy {
        Native.load(
            "objc",
            MacObjectiveCRuntimeBinding::class.java,
            mapOf(Library.OPTION_FUNCTION_MAPPER to MacObjectiveCFunctionMapper),
        )
    }

    fun objcGetClass(name: String): Pointer? = binding.objc_getClass(name)

    fun selector(name: String): Pointer = binding.sel_registerName(name)

    fun allocateClassPair(
        superclass: Pointer?,
        name: String,
    ): Pointer? = binding.objc_allocateClassPair(superclass, name, NativeLong(0L))

    fun registerClassPair(runtimeClass: Pointer) {
        binding.objc_registerClassPair(runtimeClass)
    }

    fun disposeClassPair(runtimeClass: Pointer) {
        binding.objc_disposeClassPair(runtimeClass)
    }

    fun classAddMethod(
        runtimeClass: Pointer,
        selector: Pointer,
        implementation: Pointer,
        typeEncoding: String,
    ): Boolean = binding.class_addMethod(runtimeClass, selector, implementation, typeEncoding).toInt() != 0

    fun objectGetClassName(instance: Pointer): String? = binding.object_getClassName(instance)

    fun sendPointerReturnNoArgs(
        receiver: Pointer,
        selector: Pointer,
    ): Pointer? = binding.sendPointerReturnNoArgs(receiver, selector)

    fun sendPointerReturnOnePointer(
        receiver: Pointer,
        selector: Pointer,
        argument: Pointer,
    ): Pointer? = binding.sendPointerReturnOnePointer(receiver, selector, argument)

    fun sendPointerReturnTwoPointersAndUnsignedNativeLong(
        receiver: Pointer,
        selector: Pointer,
        first: Pointer,
        second: Pointer,
        count: NativeLong,
    ): Pointer? = binding.sendPointerReturnTwoPointersAndUnsignedNativeLong(receiver, selector, first, second, count)

    fun sendPointerReturnDouble(
        receiver: Pointer,
        selector: Pointer,
        value: Double,
    ): Pointer? = binding.sendPointerReturnDouble(receiver, selector, value)

    fun sendPointerReturnRect(
        receiver: Pointer,
        selector: Pointer,
        frame: NSRect.ByValue,
    ): Pointer? = binding.sendPointerReturnRect(receiver, selector, frame)

    fun sendPointerReturnRectAndPointer(
        receiver: Pointer,
        selector: Pointer,
        frame: NSRect.ByValue,
        argument: Pointer,
    ): Pointer? = binding.sendPointerReturnRectAndPointer(receiver, selector, frame, argument)

    fun sendPointerReturnUnsignedNativeLong(
        receiver: Pointer,
        selector: Pointer,
        index: NativeLong,
    ): Pointer? = binding.sendPointerReturnUnsignedNativeLong(receiver, selector, index)

    fun sendUnsignedNativeLongReturnNoArgs(
        receiver: Pointer,
        selector: Pointer,
    ): NativeLong = binding.sendUnsignedNativeLongReturnNoArgs(receiver, selector)

    fun sendDoubleReturnNoArgs(
        receiver: Pointer,
        selector: Pointer,
    ): Double = binding.sendDoubleReturnNoArgs(receiver, selector)

    fun sendVoidReturnNoArgs(
        receiver: Pointer,
        selector: Pointer,
    ) {
        binding.sendVoidReturnNoArgs(receiver, selector)
    }

    fun sendVoidReturnNullablePointer(
        receiver: Pointer,
        selector: Pointer,
        value: Pointer?,
    ) {
        binding.sendVoidReturnNullablePointer(receiver, selector, value)
    }

    fun sendVoidReturnTwoPointers(
        receiver: Pointer,
        selector: Pointer,
        first: Pointer,
        second: Pointer,
    ) {
        binding.sendVoidReturnTwoPointers(receiver, selector, first, second)
    }

    fun sendVoidReturnByte(
        receiver: Pointer,
        selector: Pointer,
        value: Byte,
    ) {
        binding.sendVoidReturnByte(receiver, selector, value)
    }

    fun sendVoidReturnDouble(
        receiver: Pointer,
        selector: Pointer,
        value: Double,
    ) {
        binding.sendVoidReturnDouble(receiver, selector, value)
    }

    fun sendVoidReturnRect(
        receiver: Pointer,
        selector: Pointer,
        frame: NSRect.ByValue,
    ) {
        binding.sendVoidReturnRect(receiver, selector, frame)
    }

    fun sendVoidReturnPointerSignedNativeLongAndNullablePointer(
        receiver: Pointer,
        selector: Pointer,
        view: Pointer,
        position: NativeLong,
        relativeTo: Pointer?,
    ) {
        binding.sendVoidReturnPointerSignedNativeLongAndNullablePointer(receiver, selector, view, position, relativeTo)
    }

    fun sendVoidReturnPointerAndSignedNativeLong(
        receiver: Pointer,
        selector: Pointer,
        values: Pointer,
        parameter: NativeLong,
    ) {
        binding.sendVoidReturnPointerAndSignedNativeLong(receiver, selector, values, parameter)
    }
}

@Suppress("FunctionName")
private interface MacObjectiveCRuntimeBinding : Library {
    fun objc_getClass(name: String): Pointer?

    fun sel_registerName(name: String): Pointer

    fun objc_allocateClassPair(
        superclass: Pointer?,
        name: String,
        extraBytes: NativeLong,
    ): Pointer?

    fun objc_registerClassPair(runtimeClass: Pointer)

    fun objc_disposeClassPair(runtimeClass: Pointer)

    fun class_addMethod(
        runtimeClass: Pointer,
        selector: Pointer,
        implementation: Pointer,
        typeEncoding: String,
    ): Byte

    fun object_getClassName(instance: Pointer): String?

    fun sendPointerReturnNoArgs(
        receiver: Pointer,
        selector: Pointer,
    ): Pointer?

    fun sendPointerReturnOnePointer(
        receiver: Pointer,
        selector: Pointer,
        argument: Pointer,
    ): Pointer?

    fun sendPointerReturnTwoPointersAndUnsignedNativeLong(
        receiver: Pointer,
        selector: Pointer,
        first: Pointer,
        second: Pointer,
        count: NativeLong,
    ): Pointer?

    fun sendPointerReturnDouble(
        receiver: Pointer,
        selector: Pointer,
        value: Double,
    ): Pointer?

    fun sendPointerReturnRect(
        receiver: Pointer,
        selector: Pointer,
        frame: NSRect.ByValue,
    ): Pointer?

    fun sendPointerReturnRectAndPointer(
        receiver: Pointer,
        selector: Pointer,
        frame: NSRect.ByValue,
        argument: Pointer,
    ): Pointer?

    fun sendPointerReturnUnsignedNativeLong(
        receiver: Pointer,
        selector: Pointer,
        index: NativeLong,
    ): Pointer?

    fun sendUnsignedNativeLongReturnNoArgs(
        receiver: Pointer,
        selector: Pointer,
    ): NativeLong

    fun sendDoubleReturnNoArgs(
        receiver: Pointer,
        selector: Pointer,
    ): Double

    fun sendVoidReturnNoArgs(
        receiver: Pointer,
        selector: Pointer,
    )

    fun sendVoidReturnNullablePointer(
        receiver: Pointer,
        selector: Pointer,
        value: Pointer?,
    )

    fun sendVoidReturnTwoPointers(
        receiver: Pointer,
        selector: Pointer,
        first: Pointer,
        second: Pointer,
    )

    fun sendVoidReturnByte(
        receiver: Pointer,
        selector: Pointer,
        value: Byte,
    )

    fun sendVoidReturnDouble(
        receiver: Pointer,
        selector: Pointer,
        value: Double,
    )

    fun sendVoidReturnRect(
        receiver: Pointer,
        selector: Pointer,
        frame: NSRect.ByValue,
    )

    fun sendVoidReturnPointerSignedNativeLongAndNullablePointer(
        receiver: Pointer,
        selector: Pointer,
        view: Pointer,
        position: NativeLong,
        relativeTo: Pointer?,
    )

    fun sendVoidReturnPointerAndSignedNativeLong(
        receiver: Pointer,
        selector: Pointer,
        values: Pointer,
        parameter: NativeLong,
    )
}

private object MacObjectiveCFunctionMapper : FunctionMapper {
    override fun getFunctionName(
        library: NativeLibrary,
        method: Method,
    ): String =
        if (method.name.startsWith("send")) {
            "objc_msgSend"
        } else {
            method.name
        }
}
