// ─────────────────────────────────────────────────────────────────────────────
// FILE: app/src/main/java/com/enigma/dreamer/core/LyricExtensions.kt
//
// FIX: toLrcText() is called in LyricEditorScreen but is not exported by
// the com.enigma.devlyric.core library. We define it here as a local
// extension on LyricDocument so the editor compiles and works immediately.
//
// The output format is standard LRC:
//   [ti:Song Title]
//   [ar:Artist]
//   [00:10.25]First lyric line
//   [00:14.50]Second line
//   ...
// Lines without a timestamp are emitted as plain text (useful for PLAIN format
// fallback or metadata lines like [ti:] that the parser inserts without a ts).
// ─────────────────────────────────────────────────────────────────────────────
package com.enigma.dreamer.core

import com.enigma.devlyric.core.LyricDocument

/**
 * Serialises a [LyricDocument] back to LRC-formatted text.
 *
 * This mirrors what `LyricParser.parse(text, LyricFormat.LRC)` would produce
 * if you round-tripped the document through text. The editor uses this to
 * pre-fill the raw text field with the song's existing lyrics.
 */
fun LyricDocument.toLrcText(): String = buildString {
    for (line in lines) {
        val ts = line.timestampMs
        if (ts != null) {
            val mins = ts / 60_000L
            val secs = (ts % 60_000L) / 1000.0
            // LRC timestamp format: [mm:ss.xx]
            append("[%02d:%05.2f]".format(mins, secs))
        }
        appendLine(line.text)
    }
}.trimEnd()
