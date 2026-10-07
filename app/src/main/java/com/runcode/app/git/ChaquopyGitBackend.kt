package com.runcode.app.git

import com.chaquo.python.Python
import org.json.JSONObject

/** Runs `runcode_git.py` in the embedded interpreter. */
class ChaquopyGitBackend : GitBackend {
    override fun call(function: String, vararg args: Any): String {
        if (!Python.isStarted()) {
            return JSONObject().put("ok", false).put("error", "The embedded Python interpreter is not running.").toString()
        }
        return Python.getInstance().getModule("runcode_git").callAttr(function, *args).toString()
    }
}
