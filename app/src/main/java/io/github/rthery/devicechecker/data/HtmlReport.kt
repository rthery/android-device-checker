package io.github.rthery.devicechecker.data

private val OUTCOME_CLASS = mapOf(
    Outcome.PASS to "pass", Outcome.FAIL to "fail", Outcome.WARN to "warn", Outcome.NEEDS_SHELL to "warn",
    Outcome.TO_VERIFY to "needs", Outcome.UNTESTED to "none", Outcome.NOT_APPLICABLE to "none",
    Outcome.WAIVED to "none", Outcome.NOT_ON_DEVICE to "none",
)

private val OUTCOME_LABEL = mapOf(
    Outcome.PASS to "Met", Outcome.FAIL to "Not met", Outcome.WARN to "To check", Outcome.NEEDS_SHELL to "Needs Shizuku",
    Outcome.TO_VERIFY to "Needs you", Outcome.UNTESTED to "Not tested", Outcome.NOT_APPLICABLE to "N/A",
    Outcome.WAIVED to "Waived", Outcome.NOT_ON_DEVICE to "Off-device",
)

private val STYLE = """
:root{color-scheme:light dark;--bg:#f6fbf7;--card:#e9efea;--line:#c2cbc5;--text:#171d1a;--muted:#3e4842;--pass:#1e7041;--fail:#b3261e;--warn:#7a5000;--needs:#2155c4;--none:#5f6862}
@media (prefers-color-scheme:dark){:root{--bg:#0f1512;--card:#1b211e;--line:#3f4943;--text:#dee4df;--muted:#bfc9c2;--pass:#7ddba0;--fail:#ffb4ab;--warn:#f5c065;--needs:#b0c6ff;--none:#a3aca6}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 system-ui,-apple-system,Roboto,sans-serif}
main{max-width:880px;margin:0 auto;padding:24px 16px 48px}h1{font-size:26px;margin:0 0 4px}h2{font-size:19px;margin:32px 0 12px}
.muted{color:var(--muted)}.card{background:var(--card);border-radius:14px;padding:14px 16px;margin:8px 0}
.counts{display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));gap:8px}.count b{display:block;font-size:24px}
.badge{display:inline-block;padding:1px 8px;border-radius:999px;font-size:12px;font-weight:700;border:1.5px solid currentColor;margin-right:6px;white-space:nowrap}
.pass{color:var(--pass)}.fail{color:var(--fail)}.warn{color:var(--warn)}.needs{color:var(--needs)}.none{color:var(--none)}
.req h3{font-size:15px;margin:0 0 4px}.req ul{margin:6px 0 0;padding-left:18px}pre{white-space:pre-wrap;word-break:break-word;font-size:12px;background:var(--bg);border-radius:8px;padding:8px;margin:6px 0}
details summary{cursor:pointer;color:var(--muted)}dl{display:grid;grid-template-columns:max-content 1fr;gap:2px 12px;margin:0}dt{color:var(--muted)}dd{margin:0;word-break:break-word}
""".trimIndent()

private fun String.escaped(): String = buildString {
    this@escaped.forEach { c ->
        when (c) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}

private fun outcomeOf(name: String): Outcome = runCatching { Outcome.valueOf(name) }.getOrDefault(Outcome.UNTESTED)

private fun StringBuilder.badge(outcome: Outcome) {
    append("<span class=\"badge ").append(OUTCOME_CLASS.getValue(outcome)).append("\">").append(OUTCOME_LABEL.getValue(outcome)).append("</span>")
}

/** One self-contained page for people: no scripts, no external files, readable in light and dark. */
fun JsonReport.toHtml(): String = buildString {
    appendLine("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
    append("<title>Device requirements report · ").append((device["Device"] ?: "device").escaped()).appendLine("</title>")
    append("<style>").append(STYLE).appendLine("</style></head><body><main>")
    appendLine("<h1>Device requirements report</h1>")
    append("<p class=\"muted\">Generated ").append(generatedAt.escaped()).append(" by ").append(app.escaped())
        .append(". Checklist: ").append(checklistSource.escaped()).append(", ").append(checklistLicense.escaped()).appendLine(".</p>")

    appendLine("<div class=\"card\"><dl>")
    device.forEach { (key, value) -> append("<dt>").append(key.escaped()).append("</dt><dd>").append(value.escaped()).appendLine("</dd>") }
    buildType?.let { append("<dt>Build type</dt><dd>").append(it.escaped()).appendLine("</dd>") }
    conditions?.label?.ifEmpty { null }?.let { append("<dt>Root state</dt><dd>").append(it.escaped()).appendLine("</dd>") }
    if (packs.isNotEmpty()) append("<dt>Device packs</dt><dd>").append(packs.joinToString { it.name }.escaped()).appendLine("</dd>")
    appendLine("</dl></div>")

    appendLine("<h2>Summary</h2><div class=\"counts\">")
    summary.entries.sortedBy { outcomeOf(it.key).ordinal }.forEach { (name, count) ->
        val outcome = outcomeOf(name)
        append("<div class=\"card count ").append(OUTCOME_CLASS.getValue(outcome)).append("\"><b>").append(count).append("</b>")
            .append(OUTCOME_LABEL.getValue(outcome)).appendLine("</div>")
    }
    appendLine("</div>")

    if (nextSteps.isNotEmpty()) {
        appendLine("<h2>Next steps</h2><ul>")
        nextSteps.forEach { step ->
            append("<li><b>").append(step.title.escaped()).append("</b>: ").append(step.why.escaped())
            if (step.needsHuman) append(" <span class=\"badge needs\">Needs you</span>")
            appendLine("</li>")
        }
        appendLine("</ul>")
    }

    requirements.groupBy { it.section }.forEach { (section, items) ->
        append("<h2>").append(section.escaped()).appendLine("</h2>")
        items.sortedBy { outcomeOf(it.outcome).ordinal }.forEach { item ->
            append("<section class=\"card req\"><h3>")
            badge(outcomeOf(item.outcome))
            append(item.level.escaped()).append(": ").append(item.text.escaped()).appendLine("</h3>")
            val notes = listOfNotNull(
                item.rom?.let { "$it builds only" } ?: if (item.lineageOnly) "LineageOS-based builds only" else null,
                item.source?.let { "From $it" },
                item.waiver?.let { "Waived by ${item.waivedBy ?: "a device pack"}: $it" },
                item.verdict?.let { "Tester verdict: $it${item.verdictNote?.let { note -> " ($note)" }.orEmpty()}" },
            )
            if (notes.isNotEmpty()) append("<p class=\"muted\">").append(notes.joinToString(" · ").escaped()).appendLine("</p>")
            if (item.entries.isNotEmpty()) {
                appendLine("<ul>")
                item.entries.forEach { entry ->
                    append("<li>").append(entry.title.escaped()).append(": <b>").append((entry.status ?: "NOT RUN").escaped()).append("</b>")
                    entry.summary?.let { append(" · ").append(it.escaped()) }
                    entry.details?.let { append("<details><summary>Output</summary><pre>").append(it.escaped()).append("</pre></details>") }
                    appendLine("</li>")
                }
                appendLine("</ul>")
            }
            appendLine("</section>")
        }
    }
    appendLine("</main></body></html>")
}
