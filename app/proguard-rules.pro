# Chaquopy loads the interpreter and its bridge classes reflectively, and
# runcode_runner.py calls PythonEngine$OutputSink.onOutput by name. R8 is off for
# release today, but these keeps mean turning it on will not silently break Python.
-keep class com.chaquo.python.** { *; }
-keep class com.runcode.app.runtime.PythonEngine$OutputSink { *; }
