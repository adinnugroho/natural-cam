package com.adin.naturalcam.image.core

/**
 * Per-stage processing durations for the SPEC 109 debug log ("processing
 * duration"). Stages are aggregated by name, in first-seen order, so a chain
 * that runs the same stage twice (the base and style chroma passes) reports one
 * number instead of two.
 *
 * Release builds pass no instance at all: every call site is a `timings?.` call,
 * so a null collector costs one null check per stage.
 */
class ProcessingTimings {
    private val names = ArrayList<String>(20)
    private val totals = ArrayList<Long>(20)

    /** Runs [block], recording its duration in milliseconds under [name]. */
    fun <T> measure(name: String, block: () -> T): T {
        val start = System.nanoTime()
        val value = block()
        accumulate(name, (System.nanoTime() - start) / 1_000_000)
        return value
    }

    /** Records a span the caller measured itself (suspending work cannot be wrapped in a block). */
    fun record(name: String, elapsedMillis: Long) = accumulate(name, elapsedMillis)

    private fun accumulate(name: String, elapsedMillis: Long) {
        val index = names.indexOf(name)
        if (index < 0) {
            names.add(name)
            totals.add(elapsedMillis)
        } else {
            totals[index] = totals[index] + elapsedMillis
        }
    }

    /** Stage names in first-seen order. */
    val stages: List<String> get() = names

    fun millisOf(name: String): Long = names.indexOf(name).let { if (it < 0) 0L else totals[it] }

    private val factNames = ArrayList<String>(8)
    private val factValues = ArrayList<String>(8)

    /**
     * Records a value the pipeline *decided* (a measured grain, an applied strength) for the
     * debug log. Not a duration, and never pixel data (AGENTS 55) — this is how a tuning choice
     * becomes observable on a device instead of only inside the code.
     */
    fun fact(name: String, value: String) {
        val index = factNames.indexOf(name)
        if (index < 0) {
            factNames.add(name)
            factValues.add(value)
        } else {
            factValues[index] = value
        }
    }

    fun factOf(name: String): String? = factNames.indexOf(name).let { if (it < 0) null else factValues[it] }

    /** `name=ms` pairs in first-seen order, then the decided facts — the capture log line. */
    fun summary(): String {
        val durations = names.indices.joinToString(" ") { "${names[it]}=${totals[it]}ms" }
        val facts = factNames.indices.joinToString(" ") { "${factNames[it]}=${factValues[it]}" }
        return if (facts.isEmpty()) durations else "$durations $facts"
    }
}

/**
 * Runs [block] through [measure] when a collector is present, and just runs it
 * otherwise. Not an inline function: the lambda is a real object here, but an
 * inline version cannot hand its parameter to [ProcessingTimings.measure].
 */
internal fun <T> ProcessingTimings?.measureStage(name: String, block: () -> T): T =
    if (this == null) block() else measure(name, block)
