package com.bks.feature.settings

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

internal inline fun <T> DataOutputStream.writeList(
    values: List<T>,
    writeValue: DataOutputStream.(T) -> Unit
) {
    writeInt(values.size)
    values.forEach { writeValue(it) }
}

internal inline fun <T> DataInputStream.readList(
    readValue: DataInputStream.() -> T
): List<T> {
    val size = readInt()
    require(size in 0..MAX_BACKUP_RECORDS) { "Invalid backup payload" }
    return List(size) { readValue() }
}

internal fun DataOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

internal fun DataInputStream.readString(): String {
    val size = readInt()
    require(size in 0..MAX_BACKUP_STRING_BYTES) { "Invalid backup payload" }
    val bytes = ByteArray(size)
    readFully(bytes)
    return String(bytes, StandardCharsets.UTF_8)
}

internal fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    value?.let(::writeString)
}

internal fun DataInputStream.readNullableString(): String? =
    if (readBoolean()) readString() else null

internal fun DataOutputStream.writeNullableLong(value: Long?) {
    writeBoolean(value != null)
    value?.let(::writeLong)
}

internal fun DataInputStream.readNullableLong(): Long? =
    if (readBoolean()) readLong() else null
