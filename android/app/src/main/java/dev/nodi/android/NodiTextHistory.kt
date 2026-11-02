package dev.nodi.android

data class NodiTextEntry(val text: String, val incoming: Boolean, val at: Long)

/** Bounded, process-local history. No clipboard access and no text written to disk.
 * The network thread records incoming text before acknowledging delivery.
 */
object NodiTextHistory {
    private val entries = mutableListOf<NodiTextEntry>()
    @Synchronized fun add(text: String, incoming: Boolean) {
        entries.add(0, NodiTextEntry(text, incoming, System.currentTimeMillis()))
        while (entries.size > 10) entries.removeAt(entries.lastIndex)
    }
    @Synchronized fun snapshot(): List<NodiTextEntry> = entries.toList()
}
