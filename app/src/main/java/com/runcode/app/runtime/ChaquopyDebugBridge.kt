package com.runcode.app.runtime

import com.chaquo.python.Python

/** [DebugBridge] over runcode_debugger.py in the embedded interpreter. */
class ChaquopyDebugBridge : DebugBridge {
    private val module get() = Python.getInstance().getModule("runcode_debugger")

    override fun command(sessionId: String, name: String): String =
        module.callAttr("command", sessionId, name).toString()

    override fun evaluate(sessionId: String, expression: String): String =
        module.callAttr("evaluate", sessionId, expression).toString()

    override fun setBreakpoints(sessionId: String, file: String, linesJson: String): String =
        module.callAttr("set_breakpoints", sessionId, file, linesJson).toString()
}
