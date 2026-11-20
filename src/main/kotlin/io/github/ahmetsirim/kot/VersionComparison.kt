package io.github.ahmetsirim.kot

/**
 * Compares two versions as int lists, component-wise from the left, treating a missing component
 * as 0 (so 2.2 equals 2.2.0). Follows the usual comparator contract: negative when [left] is
 * lower, zero when equal, positive when higher. Ints rather than text on purpose: as strings,
 * "2.10" would sort below "2.9".
 */
internal fun compareVersionParts(left: List<Int>, right: List<Int>): Int {
    return (0 until maxOf(left.size, right.size))
        .map { index: Int -> left.getOrElse(index) { 0 } - right.getOrElse(index) { 0 } }
        .firstOrNull { difference: Int -> difference != 0 }
        ?: 0
}

/**
 * Null-tolerant maximum over version int lists: an absent side loses, two present sides compare
 * component-wise.
 */
internal fun maxVersionParts(left: List<Int>?, right: List<Int>?): List<Int>? {
    return when {
        left == null -> right
        right == null -> left
        compareVersionParts(left = left, right = right) >= 0 -> left
        else -> right
    }
}
