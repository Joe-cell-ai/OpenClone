// Adapted from APK Toolbox by bl3xand (GPL-3.0-only). Upstream: https://github.com/bl3xand/APK-Toolbox
package org.openclone.app.clone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages multiple APK signing identities. All private keys are encrypted together at rest with
 * an Android Keystore AES-GCM key; PKCS#12 import/export provides password-protected backups.
 */
class SigningKeys private constructor(private val context: Context) {

    private val storage = File(context.filesDir, "signing/keys.bin")
    private val legacyStorage = File(context.filesDir, "signing/key.bin")
    private val identities = LinkedHashMap<String, SigningKey>()

    @Volatile
    private var selectedAlias: String? = null
    @Volatile
    private var loadFailure: Exception? = null

    init {
        try {
            load()
        } catch (error: Exception) {
            // Preserve the encrypted file and refuse to overwrite it if it cannot be opened.
            loadFailure = error
        }
    }

    private fun ensureLoaded() {
        loadFailure?.let {
            throw IllegalStateException(
                "Saved signing identities could not be decrypted. The original backup file was preserved; reset the Android Keystore only if you accept losing those identities.",
                it
            )
        }
    }

    /** The currently selected user identity, or null when the per-install key has not been created. */
    val custom: SigningKey?
        get() = synchronized(this) { ensureLoaded(); selectedAlias?.let(identities::get) }

    val activeAlias: String?
        get() = synchronized(this) { ensureLoaded(); selectedAlias }

    val identityAliases: List<String>
        get() = synchronized(this) { ensureLoaded(); identities.keys.toList() }

    /** What new clones are signed with. A default per-install identity is created on first use. */
    val active: SigningKey
        get() = synchronized(this) {
            ensureLoaded()
            custom ?: run {
                generate("OpenClone")
                custom ?: error("Could not create signing key")
            }
        }

    /** Selects which stored identity new clones and updates will use. */
    @Synchronized
    fun select(alias: String) {
        ensureLoaded()
        require(alias in identities) { "No signing identity named \"$alias\" exists." }
        selectedAlias = alias
        persist()
    }

    /** Creates a new RSA identity and makes it active. [alias] is the name shown in OpenClone. */
    @Synchronized
    fun generate(alias: String, commonName: String) {
        ensureLoaded()
        validateAlias(alias)
        require(alias !in identities) { "A signing identity named \"$alias\" already exists." }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
        val certificate = SelfSignedCertificate.create(pair.private, pair.public.encoded, commonName, VALIDITY_YEARS)
        identities[alias] = SigningKey(pair.private, certificate)
        selectedAlias = alias
        persist()
    }

    /** Backwards-compatible convenience method: creates a unique alias from the common name. */
    @Synchronized
    fun generate(commonName: String) {
        ensureLoaded()
        val base = commonName.trim().take(48).ifBlank { "OpenClone" }
        var alias = base
        var suffix = 2
        while (alias in identities) {
            alias = "$base $suffix"
            suffix++
        }
        generate(alias, commonName)
    }

    /**
     * Reads a PKCS#12 or BKS keystore. [alias] chooses the source entry; [identityAlias] chooses
     * the name stored in OpenClone. If either is blank, the first available source alias is used.
     */
    @Synchronized
    fun import(input: InputStream, password: CharArray, alias: String, identityAlias: String = alias) {
        ensureLoaded()
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            total += count
            require(total <= MAX_KEYSTORE_BYTES) { "The signing backup is too large." }
            buffer.write(chunk, 0, count)
        }
        val bytes = buffer.toByteArray()
        var failure: Exception? = null
        for (type in listOf("PKCS12", "BKS")) {
            try {
                val keystore = KeyStore.getInstance(type).apply { load(ByteArrayInputStream(bytes), password) }
                val entryAlias = alias.ifBlank { keystore.aliases().asSequence().firstOrNull(keystore::isKeyEntry) }
                    ?: error("The keystore has no key entries")
                val key = keystore.getKey(entryAlias, password) as? PrivateKey
                    ?: error("No private key under \"$entryAlias\"")
                val certificate = keystore.getCertificate(entryAlias) as? X509Certificate
                    ?: error("No certificate under \"$entryAlias\"")
                validateKeyPair(key, certificate)
                val targetAlias = identityAlias.ifBlank { entryAlias }
                validateAlias(targetAlias)
                require(targetAlias !in identities) { "A signing identity named \"$targetAlias\" already exists." }
                identities[targetAlias] = SigningKey(key, certificate)
                selectedAlias = targetAlias
                persist()
                return
            } catch (error: Exception) {
                failure = error
            }
        }
        throw failure ?: IllegalStateException("Unsupported keystore")
    }

    /** Writes the selected identity as a password-protected PKCS#12 backup. */
    fun export(output: OutputStream, password: CharArray) {
        val alias = activeAlias ?: error("No signing identity is selected.")
        export(alias, output, password)
    }

    /** Writes the named identity as a password-protected PKCS#12 backup. */
    @Synchronized
    fun export(alias: String, output: OutputStream, password: CharArray) {
        ensureLoaded()
        require(password.isNotEmpty()) { "Choose a non-empty backup password." }
        val key = identities[alias] ?: error("No signing identity named \"$alias\" exists.")
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(EXPORT_ALIAS, key.privateKey, password, arrayOf(key.certificate))
            store(output, password)
        }
    }

    /** Removes an identity. If it was active, another remaining identity becomes active. */
    @Synchronized
    fun delete(alias: String) {
        ensureLoaded()
        require(alias in identities) { "No signing identity named \"$alias\" exists." }
        identities.remove(alias)
        if (selectedAlias == alias) selectedAlias = identities.keys.firstOrNull()
        persist()
    }

    /** Removes every custom identity. A new per-install key is created on next use. */
    @Synchronized
    fun reset() {
        identities.clear()
        selectedAlias = null
        storage.delete()
        legacyStorage.delete()
    }

    /** Finds the identity that signed an installed clone, even if it is not currently selected. */
    @Synchronized
    fun matching(certificateBytes: ByteArray): SigningKey? {
        ensureLoaded()
        return identities.values.firstOrNull { it.signs(certificateBytes) }
    }

    @Synchronized
    fun matchingFingerprint(fingerprint: String): SigningKey? {
        ensureLoaded()
        return identities.values.firstOrNull { it.fingerprint.equals(fingerprint, ignoreCase = true) }
    }

    private fun validateAlias(alias: String) {
        require(alias.matches(Regex("[A-Za-z0-9][A-Za-z0-9 _.-]{0,63}"))) {
            "Use a signing identity name with 1–64 letters, numbers, spaces, dots, underscores, or hyphens."
        }
    }

    private fun persist() {
        if (identities.isEmpty()) {
            storage.delete()
            return
        }
        val plain = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(FORMAT_MAGIC)
                out.writeInt(FORMAT_VERSION)
                out.writeUTF(selectedAlias.orEmpty())
                out.writeInt(identities.size)
                identities.forEach { (alias, key) ->
                    out.writeUTF(alias)
                    val privateBytes = key.privateKey.encoded
                    val certificateBytes = key.certificate.encoded
                    out.writeInt(privateBytes.size)
                    out.write(privateBytes)
                    out.writeInt(certificateBytes.size)
                    out.write(certificateBytes)
                }
            }
        }.toByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        storage.parentFile?.mkdirs()
        val temporary = File(storage.parentFile, "keys.tmp")
        DataOutputStream(temporary.outputStream()).use { out ->
            out.writeInt(cipher.iv.size)
            out.write(cipher.iv)
            out.write(cipher.doFinal(plain))
        }
        try {
            Files.move(
                temporary.toPath(),
                storage.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (error: Exception) {
            temporary.delete()
            throw IllegalStateException("Could not atomically save signing identities; the previous encrypted file was preserved.", error)
        }
    }

    private fun load() {
        if (storage.exists()) {
            val plain = decrypt(storage)
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                require(input.readInt() == FORMAT_MAGIC) { "Unknown signing identity file format." }
                require(input.readInt() == FORMAT_VERSION) { "Unsupported signing identity file version." }
                selectedAlias = input.readUTF().ifBlank { null }
                val count = input.readInt()
                require(count in 1..MAX_IDENTITIES) { "Invalid signing identity count." }
                repeat(count) {
                    val alias = input.readUTF()
                    validateAlias(alias)
                    require(alias !in identities) { "Duplicate signing identity name in encrypted storage." }
                    val privateBytes = ByteArray(readSize(input)).also(input::readFully)
                    val certificateBytes = ByteArray(readSize(input)).also(input::readFully)
                    identities[alias] = decodeKey(privateBytes, certificateBytes)
                }
            }
            if (selectedAlias == null || selectedAlias !in identities) selectedAlias = identities.keys.firstOrNull()
            return
        }

        // Migrate the previous single-key encrypted file without losing existing clone updates.
        if (legacyStorage.exists()) {
            val plain = decrypt(legacyStorage)
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                val privateBytes = ByteArray(readSize(input)).also(input::readFully)
                val certificateBytes = ByteArray(readSize(input)).also(input::readFully)
                identities["Imported"] = decodeKey(privateBytes, certificateBytes)
                selectedAlias = "Imported"
            }
            persist()
            legacyStorage.delete()
        }
    }

    private fun decrypt(file: File): ByteArray =
        DataInputStream(file.inputStream()).use { input ->
            val ivSize = input.readInt()
            require(ivSize == GCM_IV_BYTES) { "Invalid signing identity encryption header." }
            val iv = ByteArray(ivSize).also(input::readFully)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            cipher.doFinal(input.readBytes())
        }

    private fun readSize(input: DataInputStream): Int =
        input.readInt().also { require(it in 1..MAX_KEY_BYTES) { "Invalid signing key data size." } }

    private fun decodeKey(privateBytes: ByteArray, certificateBytes: ByteArray): SigningKey {
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateBytes))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificateBytes)) as X509Certificate
        validateKeyPair(key, certificate)
        return SigningKey(key, certificate)
    }

    private fun validateKeyPair(key: PrivateKey, certificate: X509Certificate) {
        require(key.algorithm.equals("RSA", ignoreCase = true) &&
            certificate.publicKey.algorithm.equals("RSA", ignoreCase = true)) {
            "OpenClone currently supports RSA signing identities only."
        }
        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(challenge)
            sign()
        }
        val matches = Signature.getInstance("SHA256withRSA").run {
            initVerify(certificate.publicKey)
            update(challenge)
            verify(signature)
        }
        require(matches) { "The private key does not match its signing certificate." }
    }

    /**
     * The signing key has to be usable by apksig as plain key material, so it cannot live in the
     * Android Keystore itself. It is instead encrypted at rest with a key that does.
     */
    private fun wrappingKey(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keystore.getKey(WRAPPING_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(WRAPPING_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    companion object {
        private const val KEY_BITS = 2048
        private const val VALIDITY_YEARS = 30
        private const val EXPORT_ALIAS = "openclone"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAPPING_ALIAS = "signing-key-wrap"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
        private const val FORMAT_MAGIC = 0x4F434B32
        private const val FORMAT_VERSION = 2
        private const val MAX_IDENTITIES = 32
        private const val MAX_KEY_BYTES = 1024 * 1024
        private const val MAX_KEYSTORE_BYTES = 4 * 1024 * 1024

        @Volatile
        private var instance: SigningKeys? = null

        /** One instance per process, so a key change is seen everywhere at once. */
        fun get(context: Context): SigningKeys = instance ?: synchronized(this) {
            instance ?: SigningKeys(context.applicationContext).also { instance = it }
        }
    }
}

private object SelfSignedCertificate {

    private val SHA256_WITH_RSA = sequence(oid(1, 2, 840, 113549, 1, 1, 11), der(0x05, ByteArray(0)))

    fun create(privateKey: PrivateKey, publicKeyInfo: ByteArray, commonName: String, years: Int): X509Certificate {
        val name = sequence(der(0x31, sequence(oid(2, 5, 4, 3), der(0x0C, commonName.toByteArray()))))
        val notBefore = Date()
        val notAfter = Calendar.getInstance().apply { time = notBefore; add(Calendar.YEAR, years) }.time
        val tbs = sequence(
            der(0xA0, integer(BigInteger.valueOf(2))),
            integer(BigInteger(64, SecureRandom()).setBit(0)),
            SHA256_WITH_RSA,
            name,
            sequence(time(notBefore), time(notAfter)),
            name,
            publicKeyInfo,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(tbs)
            sign()
        }
        val certificate = sequence(tbs, SHA256_WITH_RSA, der(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val length = when {
            content.size < 0x80 -> byteArrayOf(content.size.toByte())
            content.size < 0x100 -> byteArrayOf(0x81.toByte(), content.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (content.size shr 8).toByte(), content.size.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + content
    }

    private fun sequence(vararg parts: ByteArray) = der(0x30, parts.fold(ByteArray(0)) { all, part -> all + part })

    private fun integer(value: BigInteger) = der(0x02, value.toByteArray())

    private fun oid(vararg arcs: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(arcs[0] * 40 + arcs[1])
        for (arc in arcs.drop(2)) {
            val groups = generateSequence(arc) { it shr 7 }.takeWhile { it > 0 }.map { it and 0x7F }.toList().reversed()
            groups.forEachIndexed { index, group -> out.write(if (index < groups.lastIndex) group or 0x80 else group) }
        }
        return der(0x06, out.toByteArray())
    }

    /** UTCTime up to 2049, GeneralizedTime after, as X.509 requires. */
    private fun time(date: Date): ByteArray {
        val year = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = date }.get(Calendar.YEAR)
        val (tag, pattern) = if (year < 2050) 0x17 to "yyMMddHHmmss'Z'" else 0x18 to "yyyyMMddHHmmss'Z'"
        val format = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return der(tag, format.format(date).toByteArray())
    }
}
