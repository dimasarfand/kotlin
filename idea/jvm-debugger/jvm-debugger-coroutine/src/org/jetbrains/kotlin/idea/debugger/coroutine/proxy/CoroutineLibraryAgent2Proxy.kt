/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutine.proxy

import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.jdi.ClassesByNameProvider
import com.intellij.debugger.jdi.GeneratedLocation
import com.intellij.util.containers.ContainerUtil
import com.sun.jdi.*
import org.jetbrains.kotlin.idea.debugger.coroutine.data.*
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.mirror.CoroutineContext
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.mirror.DebugProbesImpl
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.mirror.JavaLangMirror
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.mirror.MirrorOfCoroutineInfo
import org.jetbrains.kotlin.idea.debugger.coroutine.util.logger
import org.jetbrains.kotlin.idea.debugger.evaluate.DefaultExecutionContext

class CoroutineLibraryAgent2Proxy(private val executionContext: DefaultExecutionContext) :
    CoroutineInfoProvider {
    val log by logger
    private val debugProbesImpl = DebugProbesImpl(executionContext)

    private val classesByName = ClassesByNameProvider.createCache(executionContext.vm.allClasses())

    override fun dumpCoroutinesInfo(): List<CoroutineInfoData> {
        val result = debugProbesImpl.dumpCoroutinesInfo(executionContext)
        return result.mapNotNull { mapToCoroutineInfoData(it) }
    }

    fun mapToCoroutineInfoData(mirror: MirrorOfCoroutineInfo): CoroutineInfoData? {
        val cnis =
            CoroutineNameIdState(mirror.context?.name ?: "coroutine", "${mirror.sequenceNumber}", State.valueOf(mirror.state ?: "UNKNOWN"))
        val stackTrace = mirror.enchancedStackTrace?.mapNotNull { it.stackTraceElement() } ?: emptyList()
        var stackFrames = findStackFrames(stackTrace)
        return CoroutineInfoData(
            cnis,
            stackFrames.restoredStackFrames,
            stackFrames.creationStackFrames,
            mirror.lastObservedThread,
            mirror.lastObservedFrame
        )
    }

    fun isInstalled(): Boolean {
        try {
            return debugProbesImpl.isInstalledValue ?: false
        } catch (e: Exception) {
            log.error("Exception happened while checking agent status.", e)
            return false
        }
    }

    private fun createLocation(stackTraceElement: StackTraceElement): Location = findLocation(
        ContainerUtil.getFirstItem(classesByName[stackTraceElement.className]),
        stackTraceElement.methodName,
        stackTraceElement.lineNumber
    )

    private fun findLocation(
        type: ReferenceType?,
        methodName: String,
        line: Int
    ): Location {
        if (type != null && line >= 0) {
            try {
                val location = type.locationsOfLine(DebugProcess.JAVA_STRATUM, null, line).stream()
                    .filter { l: Location -> l.method().name() == methodName }
                    .findFirst().orElse(null)
                if (location != null) {
                    return location
                }
            } catch (ignored: AbsentInformationException) {
            }
        }
        return GeneratedLocation(executionContext.debugProcess, type, methodName, line)
    }

    private fun findStackFrames(frames: List<StackTraceElement>): CoroutineStackFrames {
        val index = frames.indexOfFirst { it.isCreationSeparatorFrame() }
        return CoroutineStackFrames(frames.take(index).map {
            SuspendCoroutineStackFrameItem(it, createLocation(it))
        }, frames.subList(index + 1, frames.size).map {
            CreationCoroutineStackFrameItem(it, createLocation(it))
        })
    }

    data class CoroutineStackFrames(
        val restoredStackFrames: List<SuspendCoroutineStackFrameItem>,
        val creationStackFrames: List<CreationCoroutineStackFrameItem>
    )

    companion object {
        fun instance(executionContext: DefaultExecutionContext): CoroutineLibraryAgent2Proxy? {
            val agentProxy = CoroutineLibraryAgent2Proxy(executionContext)
            if (agentProxy.isInstalled())
                return agentProxy
            else
                return null
        }
    }

}
