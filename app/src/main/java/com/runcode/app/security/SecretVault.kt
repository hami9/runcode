package com.runcode.app.security

interface SecretVault {
    fun setSecret(key: String, plainText: String): Boolean
    fun getSecret(key: String): String?
    fun removeSecret(key: String)
}

object SecretReferences {
    private val pattern = Regex("""\$\{SEC_([A-Za-z0-9_]+)\}""")

    fun key(value: String): String? = pattern.matchEntire(value)?.groupValues?.get(1)
    fun placeholder(key: String): String = "\${SEC_$key}"
}
