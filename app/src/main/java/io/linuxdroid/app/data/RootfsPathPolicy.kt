package io.linuxdroid.app.data

/**
 * Lexical path policy for archive entries. It is intentionally independent from
 * the host filesystem so valid RootFS links such as `../usr/lib/os-release`
 * can be accepted without following them during extraction.
 */
internal object RootfsPathPolicy {
    /**
     * Returns true only when [target] is a relative symlink target that resolves
     * at or below the RootFS root when evaluated from [linkPath]'s parent.
     */
    fun isSafeRelativeSymlink(linkPath: String, target: String): Boolean {
        require(!target.startsWith('/')) { "Expected a relative symlink target." }
        val stack = ArrayDeque<String>()
        appendSegments(stack, linkPath.substringBeforeLast('/', ""))
        for (segment in target.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (stack.isEmpty()) return false else stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        return true
    }

    private fun appendSegments(stack: ArrayDeque<String>, path: String) {
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
    }
}
