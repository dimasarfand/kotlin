/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutine.proxy

import com.intellij.debugger.engine.JavaValue
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.jdi.GeneratedLocation
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XStackFrame
import com.sun.jdi.*
import org.jetbrains.kotlin.idea.debugger.coroutine.coroutineDebuggerTraceEnabled
import org.jetbrains.kotlin.idea.debugger.coroutine.data.ContinuationValueDescriptorImpl
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineStackFrameItem
import org.jetbrains.kotlin.idea.debugger.coroutine.data.DefaultCoroutineStackFrameItem
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.mirror.DebugMetadata
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.mirror.FieldVariable
import org.jetbrains.kotlin.idea.debugger.coroutine.standaloneCoroutineDebuggerEnabled
import org.jetbrains.kotlin.idea.debugger.coroutine.util.logger
import org.jetbrains.kotlin.idea.debugger.evaluate.DefaultExecutionContext
import org.jetbrains.kotlin.idea.debugger.invokeInManagerThread

data class ContinuationHolder(val continuation: ObjectReference, val context: DefaultExecutionContext) {
    val log by logger
    private val debugMetadata: DebugMetadata? = DebugMetadata.instance(context)

    fun getAsyncStackTraceIfAny(): List<CoroutineStackFrameItem> {
        try {
            return collectFrames()
        } catch (e: Exception) {
            log.error("Error while looking for stack frame.", e)
        }
        return emptyList()
    }

    private fun collectFrames(): List<CoroutineStackFrameItem> {
        val consumer = mutableListOf<CoroutineStackFrameItem>()
        var completion = this
        while (completion.isBaseContinuationImpl()) {
            val coroutineStackFrame = context.debugProcess.invokeInManagerThread {
                completion.createLocation()
            }
            if (coroutineStackFrame != null) {
                consumer.add(coroutineStackFrame)
            }
            completion = completion.findCompletion() ?: break
        }
        return consumer
    }

    private fun createLocation(): DefaultCoroutineStackFrameItem? {
        val stackTraceElementMirror = debugMetadata?.getStackTraceElement(continuation, context) ?: return null
        val stackTraceElement = stackTraceElementMirror.stackTraceElement()
        val locationClass = context.findClassSafe(stackTraceElement.className) ?: return null
        val generatedLocation = GeneratedLocation(context.debugProcess, locationClass, stackTraceElement.methodName, stackTraceElement.lineNumber)
        val spilledVariables = getSpilledVariables() ?: emptyList()
        return DefaultCoroutineStackFrameItem(generatedLocation, spilledVariables)
    }

    fun getSpilledVariables(): List<XNamedValue>? {
        val variables: List<JavaValue> = context.debugProcess.invokeInManagerThread {
            debugMetadata?.getSpilledVariableFieldMapping(continuation, context)?.mapNotNull {
                fieldVariableToNamedValue(it, this)
            }
        } ?: emptyList()
        return variables
    }

    fun fieldVariableToNamedValue(fieldVariable: FieldVariable, continuation: ContinuationHolder) : JavaValue {
        val valueDescriptor = ContinuationValueDescriptorImpl(
            context.project,
            continuation,
            fieldVariable.fieldName,
            fieldVariable.variableName
        )
        return JavaValue.create(
            null,
            valueDescriptor,
            context.evaluationContext,
            context.debugProcess.xdebugProcess!!.nodeManager,
            false
        )
    }

    fun referenceType(): ClassType? =
        continuation.referenceType() as? ClassType

    fun value() =
        continuation

    fun field(field: Field): Value? =
        continuation.getValue(field)

    fun findCompletion(): ContinuationHolder? {
        val type = continuation.type()
        if (type is ClassType && type.isBaseContinuationImpl()) {
            val completionField = type.completionField() ?: return null
            return ContinuationHolder(continuation.getValue(completionField) as? ObjectReference ?: return null, context)
        }
        return null
    }

    fun isBaseContinuationImpl() =
        continuation.type().isBaseContinuationImpl()

    companion object {
        val log by logger

        fun lookupForResumeMethodContinuation(
            suspendContext: SuspendContextImpl,
            frame: StackFrameProxyImpl
        ): ContinuationHolder? {
            if (frame.location().isPreExitFrame()) {
                val context = suspendContext.executionContext() ?: return null
                var continuation = frame.completionVariableValue() ?: return null
                return ContinuationHolder(continuation, context)
            } else
                return null
        }

        fun coroutineExitFrame(
            frame: StackFrameProxyImpl,
            suspendContext: SuspendContextImpl?
        ): XStackFrame? {
            suspendContext ?: return null
            return suspendContext.invokeInManagerThread {
                if (frame.location().isPreFlight()) {
                    if(coroutineDebuggerTraceEnabled())
                        log.trace("Entry frame found: ${formatLocation(frame.location())}")
                    constructPreFlightFrame(frame, suspendContext)
                } else
                    null
            }
        }

        fun constructPreFlightFrame(
            invokeSuspendFrame: StackFrameProxyImpl,
            suspendContext: SuspendContextImpl
        ): CoroutinePreflightStackFrame? {
            var frames = invokeSuspendFrame.threadProxy().frames()
            val indexOfCurrentFrame = frames.indexOf(invokeSuspendFrame)
            val indexofgetcoroutineSuspended = findGetCoroutineSuspended(frames)
            // @TODO if found - skip this thread stack
            if (indexOfCurrentFrame > 0 && frames.size > indexOfCurrentFrame && indexofgetcoroutineSuspended < 0) {
                val resumeWithFrame = frames[indexOfCurrentFrame + 1] ?: return null
                val ch = lookupForResumeMethodContinuation(suspendContext, resumeWithFrame) ?: return null

                val coroutineStackTrace = ch.getAsyncStackTraceIfAny()
                return CoroutinePreflightStackFrame.preflight(
                    invokeSuspendFrame,
                    resumeWithFrame,
                    coroutineStackTrace,
                    frames.drop(indexOfCurrentFrame)
                )
            }
            return null
        }

        private fun formatLocation(location: Location): String {
            return "${location.method().name()}:${location.lineNumber()}, ${location.method().declaringType()} in ${location.sourceName()}"
        }

        /**
         * Find continuation for the [frame]
         * Gets current CoroutineInfo.lastObservedFrame and finds next frames in it until null or needed stackTraceElement is found
         * @return null if matching continuation is not found or is not BaseContinuationImpl
         */
        fun lookup(
            context: SuspendContextImpl,
            initialContinuation: ObjectReference?,
        ): ContinuationHolder? {
            var continuation = initialContinuation ?: return null
            val executionContext = context.executionContext() ?: return null

            do {
                continuation = getNextFrame(executionContext, continuation) ?: return null
            } while (continuation.type().isBaseContinuationImpl()  /* && position != classLine */)

            return if (continuation.type().isBaseContinuationImpl())
                ContinuationHolder(continuation, executionContext)
            else
                return null
        }
    }
}

