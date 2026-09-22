package com.runcode.app.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runcode.app.ui.theme.AccentCyan
import com.runcode.app.ui.theme.DarkSurface
import com.runcode.app.ui.theme.TextMuted
import com.runcode.app.ui.theme.TextPrimary
import kotlinx.coroutines.flow.StateFlow

private val GUTTER_WIDTH = 44.dp
private val EDITOR_PADDING = 8.dp

/**
 * The code editor: a text field with a line-number gutter that stays in step with it.
 *
 * The field scrolls itself, which is what keeps the cursor in view while typing. An earlier
 * attempt put the old text field inside an outer scroller so the numbers could share it,
 * but that field asks its parent to "bring the cursor into view" with a stale position during
 * fast edits and the view jumped to line 1. Here the field owns the scroll state and the
 * gutter draws each number at the position the field's own layout reports, so long lines can
 * wrap and still get exactly one number.
 *
 * [documentKey] identifies the open file: a new key starts a fresh editing session.
 */
@Composable
fun CodeEditor(
    documentKey: String,
    content: StateFlow<String>,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = remember(documentKey) { TextFieldState(content.value) }
    val scroll = remember(documentKey) { ScrollState(0) }
    var layout by remember(documentKey) { mutableStateOf<TextLayoutResult?>(null) }
    val currentOnChange by rememberUpdatedState(onContentChange)

    // Only edits the person makes go back to the view model. Programmatic updates (undo,
    // opening a file, an AI client's edit arriving) bypass input transformations, so a stale
    // frame while switching tabs can never write one file's text into another.
    val reportUserEdits = remember(documentKey) {
        object : InputTransformation {
            override fun TextFieldBuffer.transformInput() {
                currentOnChange(asCharSequence().toString())
            }
        }
    }

    // Changes that come from the view model: undo/redo, a reload, or the file itself.
    // Compare with the flow's current value, not the emitted one: user edits reach the view
    // model synchronously, so its current value is never behind the field, while an emitted
    // value can be, and applying it would drop the keystroke typed since.
    LaunchedEffect(state) {
        content.collect {
            val latest = content.value
            if (state.text.toString() != latest) {
                state.edit {
                    val cursor = selection.end
                    replace(0, length, latest)
                    selection = TextRange(cursor.coerceIn(0, length))
                }
            }
        }
    }

    // Start offset of every logical line. Derived, so it is only recomputed after an edit
    // and only read while drawing the gutter.
    val lineStarts by remember(state) {
        derivedStateOf {
            val text = state.text
            val starts = ArrayList<Int>(64)
            starts.add(0)
            for (i in text.indices) if (text[i] == '\n') starts.add(i + 1)
            starts
        }
    }

    val measurer = rememberTextMeasurer(cacheSize = 512)
    val numberStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted)

    Row(modifier = modifier) {
        Box(
            modifier = Modifier
                .width(GUTTER_WIDTH)
                .fillMaxHeight()
                .background(DarkSurface)
                .clipToBounds()
                .drawBehind {
                    val result = layout ?: return@drawBehind
                    val starts = lineStarts
                    val textLength = result.layoutInput.text.length
                    val padding = EDITOR_PADDING.toPx()
                    val shift = padding - scroll.value
                    val rightEdge = size.width - 6.dp.toPx()

                    // Binary search for the first logical line whose top is on screen.
                    var low = 0
                    var high = starts.size - 1
                    while (low < high) {
                        val mid = (low + high + 1) / 2
                        val top = result.getLineTop(result.getLineForOffset(starts[mid].coerceAtMost(textLength)))
                        if (top + shift <= 0f) low = mid else high = mid - 1
                    }

                    clipRect(top = padding, bottom = size.height - padding) {
                        for (index in low until starts.size) {
                            val visualLine = result.getLineForOffset(starts[index].coerceAtMost(textLength))
                            if (result.getLineTop(visualLine) + shift > size.height) break
                            val number = measurer.measure((index + 1).toString(), numberStyle)
                            drawText(
                                textLayoutResult = number,
                                topLeft = Offset(
                                    x = rightEdge - number.size.width,
                                    // Line the digits up on the code's baseline, whatever the font sizes.
                                    y = shift + result.getLineBaseline(visualLine) - number.firstBaseline
                                )
                            )
                        }
                    }
                }
        )

        BasicTextField(
            state = state,
            inputTransformation = reportUserEdits,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = TextPrimary
            ),
            cursorBrush = SolidColor(AccentCyan),
            scrollState = scroll,
            onTextLayout = { getResult -> layout = getResult() },
            decorator = TextFieldDecorator { innerTextField ->
                Box(modifier = Modifier.padding(EDITOR_PADDING)) { innerTextField() }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .testTag("editor_text_input")
        )
    }
}
