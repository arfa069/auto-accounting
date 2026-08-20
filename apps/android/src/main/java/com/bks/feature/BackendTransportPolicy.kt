package com.bks.feature

internal fun String?.isPrivateTestHost(): Boolean {
    val normalized = this?.lowercase() ?: return false
    if (normalized == "localhost" || normalized == "::1" || normalized == "[::1]") {
        return true
    }
    val octets = normalized.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 127 ||
        octets[0] == 10 ||
        (octets[0] == 192 && octets[1] == 168) ||
        (octets[0] == 172 && octets[1] in 16..31)
}
