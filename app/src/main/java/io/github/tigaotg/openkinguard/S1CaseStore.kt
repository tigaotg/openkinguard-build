package io.github.tigaotg.openkinguard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.tigaotg.openkinguard.core.model.MessageId
import io.github.tigaotg.openkinguard.security.protocol.ReplayWindow
import io.github.tigaotg.openkinguard.security.protocol.SafetyZonePolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ExerciseStatus { OPEN, CLAIM_PENDING, ACCEPTED, CLOSED, EXPIRED }

data class ExerciseCase(
    val eventId: String,
    val relationId: String,
    val subjectFingerprint: String,
    val ownSos: Boolean,
    val createdAt: Long,
    val status: ExerciseStatus = ExerciseStatus.OPEN,
    val acceptedBy: String = "",
    val deliveredRelations: Set<String> = emptySet(),
    val attempts: Map<String, Int> = emptyMap(),
    val closedSyncedRelations: Set<String> = emptySet(),
    val closeAttempts: Map<String, Int> = emptyMap(),
    /** A newer event from the same subject replaced this card; the record remains in history. */
    val supersededBy: String = "",
    /** Signed event-origin timestamp, not this phone's time of reception. */
    val originatedAt: Long = 0L,
    /** Persisted per-helper close retry time; the attempt counter never permanently blocks sync. */
    val closeLastAttemptAt: Map<String, Long> = emptyMap(),
)

data class ManualContact(val name: String, val phone: String)
data class HandshakeEntry(val time: Long, val direction: String, val stage: String,
    val relation: String, val result: String, val detail: String)

/** User-facing command outcomes. A transport timeout is never recorded as a remote denial. */
data class CommandRecord(val time: Long, val action: String, val outcome: String,
                         val code: String, val detail: String)

/** A signed check-in, not an emergency alert. Only a verified receipt counts as delivered. */
data class CheckinRecord(
    val eventId: String,
    val relationId: String,
    val fingerprint: String,
    val own: Boolean,
    val timestamp: Long,
    val deliveredRelations: Set<String> = emptySet(),
    val attempts: Map<String, Int> = emptyMap(),
    val supersededBy: String = "",
    val originatedAt: Long = 0L,
    /** Blank means broadcast to all helpers; otherwise only the specified relation. */
    val targetRelationId: String = "",
)

/** Signed remote position, stored in the same encrypted private exercise database. */
data class ReceivedLocationFix(
    val relationId: String,
    val senderFingerprint: String,
    val latitudeE7: Int,
    val longitudeE7: Int,
    val accuracyCm: Int,
    val capturedAtMillis: Long,
    val receivedAtMillis: Long,
    val provider: String,
)

/** A local finding, not a guaranteed alert or an assertion of the person's safety. */
data class SafetyZoneEvent(
    val relationId: String,
    val senderFingerprint: String,
    val transition: SafetyZonePolicy.Transition,
    val capturedAtMillis: Long,
    val distanceMeters: Int,
)

data class ExerciseState(
    val sequences: Map<String, Long> = emptyMap(),
    val replay: Map<String, ReplayWindow> = emptyMap(),
    val cases: List<ExerciseCase> = emptyList(),
    val contacts: List<ManualContact> = emptyList(),
    val retentionDays: Int = 30,
    val handshakeLog: List<HandshakeEntry> = emptyList(),
    val ownNickname: String = "",
    val peerNicknames: Map<String, String> = emptyMap(),
    val checkins: List<CheckinRecord> = emptyList(),
    val verifiedPeerNicknames: Map<String, String> = emptyMap(),
    val lastReadCheckinTimestamp: Long = 0L,
    /** A signed identity survives relation churn and intermittent mDNS discovery. */
    val verifiedDeviceNicknames: Map<String, String> = emptyMap(),
    /** Last *cryptographically verified* local reception time, never an mDNS sighting. */
    val lastVerifiedAt: Map<String, Long> = emptyMap(),
    /** Last level already reported to this device; prevents duplicate offline alerts. */
    val lastNotifiedLossStage: Map<String, Int> = emptyMap(),
    /** Per-relation explicit *local subject's* consent to answer location requests. */
    val locationSharingRelations: Set<String> = emptySet(),
    /** Only explicitly pulled, authenticated remote fixes; no passive tracking. */
    val receivedLocations: List<ReceivedLocationFix> = emptyList(),
    /** Recent local commands; failure reason is visible without entering diagnostic mode. */
    val commandRecords: List<CommandRecord> = emptyList(),
    /** Private SAF tree URI only; medical document bytes are never imported into this store. */
    val medicalFolderTreeUri: String = "",
    /** Per authenticated device fingerprint, a URL from the reviewed official allowlist. */
    val deviceFindPortals: Map<String, String> = emptyMap(),
    /** Map choice is local-only; never synced with peers or included in location requests. */
    val preferredMapHandler: String = ThirdPartyMapLauncher.ASK,
    /** Local helper's zones, keyed by pinned public-key fingerprint; never sent to peers. */
    val safetyZones: Map<String, SafetyZonePolicy.Zone> = emptyMap(),
    val safetyZoneEvents: List<SafetyZoneEvent> = emptyList(),
    /** User-controlled WSS node; off by default, never automatically discovered. */
    val relayEndpoint: String = "",
    val relayEnabled: Boolean = false,
    /** Consent to store unsigned-to-relay, signed-to-peer notification bytes in relay RAM. */
    val relayOfflineQueueEnabled: Boolean = false,
    /** Separate, explicitly opted-in public Nostr relay adapter. Old WSS settings remain intact. */
    val nostrRelayEndpoints: String = S2NostrTransport.DEFAULT_RELAYS,
    val nostrRelayEnabled: Boolean = false,
    /** Local UX preference, never sent to a peer. */
    val simpleHomeEnabled: Boolean = true,
    /** Signed-heartbeat loss thresholds. Never infer the person's physical safety. */
    val lossWarningMinutes: Int = 3,
    val lossCriticalMinutes: Int = 10,
    /** Battery levels only for local diagnostics; no auto-message to relatives. */
    val batteryWarningPercent: Int = 20,
    val batteryCriticalPercent: Int = 10,
    /** False: follow Android's current device name; true: use the user's saved nickname. */
    val ownNicknameCustomized: Boolean = false,
    /** Display-only names for trusted private relays, never sent to those routers. */
    val relayRemarks: Map<String, String> = emptyMap(),
    /** A subject can deny future remote-assist requests from individual signed helpers. */
    val remoteAssistBlockedRelations: Set<String> = emptySet(),
)

/** Transactional encrypted local exercise log. Identity key and pairings are never changed. */
internal class S1CaseStore(context: Context) {
    companion object {
        private val STORAGE_MUTEX = Any()
        // The UI polls frequently. Re-decrypting the whole event history through
        // Android Keystore on every Compose refresh stalls older Android 10 phones.
        // One process-wide cache also keeps PairingSection's second store instance
        // coherent. All mutations still commit atomically to encrypted storage first.
        private var cacheFilePath: String? = null
        private var cacheState: ExerciseState? = null
    }
    private val dataFile = File(context.filesDir, "s1-exercise-v1.aes")
    private val file = AtomicFile(dataFile)
    private val keyAlias = "openkinguard.s1.exercise.aes.v1"

    private fun current(): ExerciseState {
        if (cacheFilePath == dataFile.absolutePath && cacheState != null) return cacheState!!
        return load().also { cacheFilePath = dataFile.absolutePath; cacheState = it }
    }

    fun snapshot(): ExerciseState = synchronized(STORAGE_MUTEX) { current() }

    /** Serializes every state transition and persists it before any network reply is emitted. */
    fun <T> update(block: (ExerciseState) -> Pair<ExerciseState, T>): T = synchronized(STORAGE_MUTEX) {
        val (next, result) = block(current())
        save(next)
        cacheFilePath = dataFile.absolutePath
        cacheState = next
        result
    }

    fun recordHandshake(direction: String, stage: String, relation: String,
                        result: String, detail: String) {
        update { state ->
            state.copy(handshakeLog = (state.handshakeLog + HandshakeEntry(
                System.currentTimeMillis(), direction.take(16), stage.take(32),
                relation.take(64), result.take(32), detail.take(180))).takeLast(120)) to Unit
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build())
        return generator.generateKey()
    }

    private fun decrypt(data: ByteArray): ByteArray {
        require(data.size in 32..1_000_000 && data.take(4).toByteArray().contentEquals(
            byteArrayOf(0x4f, 0x4b, 0x47, 0x31))) { "Unknown local history format" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(4, 16)))
        return cipher.doFinal(data.copyOfRange(16, data.size))
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        require(plain.size <= 900_000) { "History record too large" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return byteArrayOf(0x4f, 0x4b, 0x47, 0x31) + cipher.iv + cipher.doFinal(plain)
    }

    private fun load(): ExerciseState {
        val root = try {
            JSONObject(String(decrypt(file.readFully()), Charsets.UTF_8))
        } catch (_: FileNotFoundException) { return ExerciseState() }
        require(root.getInt("schema") == 1) { "Unsupported encrypted history schema" }
        val seq = root.getJSONObject("sequences").let { json ->
            json.keys().asSequence().associateWith { json.getLong(it) }
        }
        val replay = root.getJSONObject("replay").let { json ->
            json.keys().asSequence().associateWith { id ->
                val value = json.getJSONObject(id)
                val ids = value.getJSONArray("ids")
                ReplayWindow(value.getLong("greatest"), value.getLong("bits"),
                    (0 until ids.length()).map { MessageId(ids.getString(it)) })
            }
        }
        val arr = root.getJSONArray("cases")
        val cases = (0 until arr.length()).map { index ->
            val item = arr.getJSONObject(index)
            val delivered = item.getJSONArray("delivered")
            val attempts = item.optJSONObject("attempts") ?: JSONObject()
            val closedSync = item.optJSONArray("closedSynced") ?: JSONArray()
            val closing = item.optJSONObject("closeAttempts") ?: JSONObject()
            val lastClosing = item.optJSONObject("closeLastAttemptAt") ?: JSONObject()
            ExerciseCase(item.getString("event"), item.getString("relation"),
                item.getString("subject"), item.getBoolean("own"), item.getLong("created"),
                ExerciseStatus.valueOf(item.getString("status")), item.optString("accepted"),
                (0 until delivered.length()).map { delivered.getString(it) }.toSet(),
                attempts.keys().asSequence().associateWith { attempts.getInt(it) },
                (0 until closedSync.length()).map { closedSync.getString(it) }.toSet(),
                closing.keys().asSequence().associateWith { closing.getInt(it) },
                item.optString("supersededBy"), item.optLong("originatedAt", 0L),
                lastClosing.keys().asSequence().associateWith { lastClosing.getLong(it) })
        }
        val contactsArr = root.getJSONArray("contacts")
        val contacts = (0 until contactsArr.length()).map { i ->
            val item = contactsArr.getJSONObject(i)
            ManualContact(item.getString("name"), item.getString("phone"))
        }
        val days = root.optInt("retentionDays", 30).takeIf { it in setOf(7, 30, 90) } ?: 30
        val log = root.optJSONArray("handshakeLog") ?: JSONArray()
        val entries = (0 until log.length()).map { i ->
            val entry = log.getJSONObject(i)
            HandshakeEntry(entry.getLong("time"), entry.getString("direction"),
                entry.getString("stage"), entry.optString("relation"),
                entry.getString("result"), entry.optString("detail"))
        }.takeLast(120)
        val warningMinutes = root.optInt("lossWarningMinutes", 3)
            .takeIf { it in 3..30 } ?: 3
        val criticalMinutes = root.optInt("lossCriticalMinutes", 10)
            .takeIf { it in (warningMinutes + 1)..60 } ?: maxOf(10, warningMinutes + 1)
        val aliases = root.optJSONObject("peerNicknames") ?: JSONObject()
        val peerNicknames = aliases.keys().asSequence().associateWith { aliases.getString(it) }
        val signedNames = root.optJSONObject("verifiedPeerNicknames") ?: JSONObject()
        val verifiedPeerNicknames = signedNames.keys().asSequence()
            .associateWith { signedNames.getString(it) }
        val checkinJson = root.optJSONArray("checkins") ?: JSONArray()
        val checkins = (0 until checkinJson.length()).map { i ->
            val item = checkinJson.getJSONObject(i)
            val delivered = item.optJSONArray("delivered") ?: JSONArray()
            val attempts = item.optJSONObject("attempts") ?: JSONObject()
            CheckinRecord(item.getString("event"), item.getString("relation"),
                item.getString("fingerprint"), item.getBoolean("own"), item.getLong("time"),
                (0 until delivered.length()).map { delivered.getString(it) }.toSet(),
                attempts.keys().asSequence().associateWith { attempts.getInt(it) },
                item.optString("supersededBy"), item.optLong("originatedAt", 0L),
                item.optString("targetRelationId", ""))
        }.takeLast(100)
        // Current cases are retained; closed/superseded historical cases obey retention.
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        return ExerciseState(seq, replay,
            cases.filter { (it.status !in setOf(ExerciseStatus.CLOSED, ExerciseStatus.EXPIRED) &&
                it.supersededBy.isBlank()) ||
                it.createdAt >= cutoff },
            contacts, days, entries,
            root.optString("ownNickname", "我的设备"), peerNicknames,
            checkins.filter { it.timestamp >= cutoff }.takeLast(100),
            verifiedPeerNicknames, root.optLong("lastReadCheckinTimestamp", 0L),
            (root.optJSONObject("verifiedDeviceNicknames") ?: JSONObject()).let { names ->
                names.keys().asSequence().associateWith { names.getString(it) }
            },
            (root.optJSONObject("lastVerifiedAt") ?: JSONObject()).let { times ->
                times.keys().asSequence().associateWith { times.getLong(it) }
            },
            (root.optJSONObject("lastNotifiedLossStage") ?: JSONObject()).let { stages ->
                stages.keys().asSequence().associateWith { stages.getInt(it) }
            },
            (root.optJSONArray("locationSharingRelations") ?: JSONArray()).let { grants ->
                (0 until grants.length()).map { grants.getString(it) }.toSet()
            },
            (root.optJSONArray("receivedLocations") ?: JSONArray()).let { locations ->
                (0 until locations.length()).map { i ->
                    val item = locations.getJSONObject(i)
                    ReceivedLocationFix(item.getString("relation"),
                        item.getString("fingerprint"), item.getInt("latitudeE7"),
                        item.getInt("longitudeE7"), item.getInt("accuracyCm"),
                        item.getLong("capturedAt"), item.getLong("receivedAt"),
                        item.getString("provider"))
                }.filter { it.receivedAtMillis >= cutoff }.takeLast(200)
            },
            (root.optJSONArray("commandRecords") ?: JSONArray()).let { commands ->
                (0 until commands.length()).map { i ->
                    val item = commands.getJSONObject(i)
                    CommandRecord(item.getLong("time"), item.getString("action"),
                        item.getString("outcome"), item.getString("code"),
                        item.getString("detail"))
                }.filter { it.time >= cutoff }.takeLast(80)
            }, root.optString("medicalFolderTreeUri", ""),
            (root.optJSONObject("deviceFindPortals") ?: JSONObject()).let { portals ->
                portals.keys().asSequence().associateWith { portals.getString(it) }
                    .filterValues { url -> OfficialDeviceFindPortals.entries.any { it.url == url } }
            }, root.optString("preferredMapHandler", ThirdPartyMapLauncher.ASK),
            (root.optJSONObject("safetyZones") ?: JSONObject()).let { zones ->
                zones.keys().asSequence().mapNotNull { fingerprint ->
                    runCatching {
                        val zone = zones.getJSONObject(fingerprint)
                        val centerLat = zone.getInt("centerLatitudeE7")
                        val centerLon = zone.getInt("centerLongitudeE7")
                        val radius = zone.getInt("radiusMeters")
                        SafetyZonePolicy.create(centerLat, centerLon, radius,
                            zone.getLong("createdAtMillis"))
                            .copy(position = SafetyZonePolicy.Position.valueOf(
                                zone.optString("position", "UNKNOWN")),
                                candidate = SafetyZonePolicy.Position.valueOf(
                                    zone.optString("candidate", "UNKNOWN")),
                                candidateSinceMillis = zone.optLong("candidateSinceMillis"),
                                lastSampleAtMillis = zone.optLong("lastSampleAtMillis"),
                                lastDistanceMeters = zone.optInt("lastDistanceMeters"))
                    }.getOrNull()?.let { fingerprint to it }
                }.toMap()
            },
            (root.optJSONArray("safetyZoneEvents") ?: JSONArray()).let { events ->
                (0 until events.length()).mapNotNull { i ->
                    runCatching {
                        val entry = events.getJSONObject(i)
                        SafetyZoneEvent(entry.getString("relation"),
                            entry.getString("fingerprint"), SafetyZonePolicy.Transition.valueOf(
                                entry.getString("transition")), entry.getLong("capturedAt"),
                            entry.getInt("distanceMeters"))
                    }.getOrNull()
                }.filter { it.capturedAtMillis >= cutoff }.takeLast(100)
            }, root.optString("relayEndpoint", ""), root.optBoolean("relayEnabled", false),
            root.optBoolean("relayOfflineQueueEnabled", false),
            S2NostrTransport.migrateBundledDefaults(
                root.optString("nostrRelayEndpoints", S2NostrTransport.DEFAULT_RELAYS)),
            root.optBoolean("nostrRelayEnabled", false),
            root.optBoolean("simpleHomeEnabled", true),
            warningMinutes,
            criticalMinutes,
            root.optInt("batteryWarningPercent", 20).takeIf { it in 15..40 } ?: 20,
            root.optInt("batteryCriticalPercent", 10).takeIf { it in 5..14 } ?: 10,
            ownNicknameCustomized = if (root.has("ownNicknameCustomized"))
                root.optBoolean("ownNicknameCustomized", false) else
                SystemNicknamePolicy.isLegacyCustom(root.optString("ownNickname", "我的设备")),
            relayRemarks = (root.optJSONObject("relayRemarks") ?: JSONObject()).let { notes ->
                notes.keys().asSequence().take(8).associateWith { notes.optString(it).take(32) }
            },
            remoteAssistBlockedRelations =
                (root.optJSONArray("remoteAssistBlockedRelations") ?: JSONArray()).let { ids ->
                    (0 until ids.length()).map { ids.getString(it) }.toSet()
                })
    }

    private fun save(state: ExerciseState) {
        val root = JSONObject().put("schema", 1)
        val seq = JSONObject()
        state.sequences.forEach { (id, next) -> seq.put(id, next) }
        root.put("sequences", seq)
        val replay = JSONObject()
        state.replay.forEach { (id, window) ->
            replay.put(id, JSONObject().put("greatest", window.greatestSequence)
                .put("bits", window.receivedBits)
                .put("ids", JSONArray(window.recentMessageIds.map { it.value })))
        }
        root.put("replay", replay)
        val cases = JSONArray()
        state.cases.forEach { item ->
            val attempts = JSONObject().apply { item.attempts.forEach { (id, n) -> put(id, n) } }
            val closing = JSONObject().apply { item.closeAttempts.forEach { (id, n) -> put(id, n) } }
            val lastClosing = JSONObject().apply {
                item.closeLastAttemptAt.forEach { (id, value) -> put(id, value) }
            }
            cases.put(JSONObject().put("event", item.eventId).put("relation", item.relationId)
                .put("subject", item.subjectFingerprint).put("own", item.ownSos)
                .put("created", item.createdAt).put("status", item.status.name)
                .put("accepted", item.acceptedBy)
                .put("supersededBy", item.supersededBy)
                .put("originatedAt", item.originatedAt)
                .put("delivered", JSONArray(item.deliveredRelations.toList()))
                .put("attempts", attempts)
                .put("closedSynced", JSONArray(item.closedSyncedRelations.toList()))
                .put("closeAttempts", closing)
                .put("closeLastAttemptAt", lastClosing))
        }
        root.put("cases", cases)
        root.put("retentionDays", state.retentionDays)
        root.put("ownNickname", state.ownNickname)
        root.put("ownNicknameCustomized", state.ownNicknameCustomized)
        root.put("peerNicknames", JSONObject().apply {
            state.peerNicknames.forEach { (relation, nickname) -> put(relation, nickname) }
        })
        root.put("verifiedPeerNicknames", JSONObject().apply {
            state.verifiedPeerNicknames.forEach { (relation, nickname) -> put(relation, nickname) }
        })
        root.put("verifiedDeviceNicknames", JSONObject().apply {
            state.verifiedDeviceNicknames.forEach { (fingerprint, nickname) ->
                put(fingerprint, nickname)
            }
        })
        root.put("deviceFindPortals", JSONObject().apply {
            state.deviceFindPortals.forEach { (fingerprint, url) -> put(fingerprint, url) }
        })
        root.put("preferredMapHandler", state.preferredMapHandler)
        root.put("relayEndpoint", state.relayEndpoint)
        root.put("relayRemarks", JSONObject().apply {
            state.relayRemarks.forEach { (endpoint, note) -> put(endpoint, note) }
        })
        root.put("relayEnabled", state.relayEnabled)
        root.put("relayOfflineQueueEnabled", state.relayOfflineQueueEnabled)
        root.put("nostrRelayEndpoints", state.nostrRelayEndpoints)
        root.put("nostrRelayEnabled", state.nostrRelayEnabled)
        root.put("simpleHomeEnabled", state.simpleHomeEnabled)
        root.put("lossWarningMinutes", state.lossWarningMinutes)
        root.put("lossCriticalMinutes", state.lossCriticalMinutes)
        root.put("batteryWarningPercent", state.batteryWarningPercent)
        root.put("batteryCriticalPercent", state.batteryCriticalPercent)
        root.put("safetyZones", JSONObject().apply {
            state.safetyZones.forEach { (fingerprint, zone) ->
                put(fingerprint, JSONObject()
                    .put("centerLatitudeE7", zone.centerLatitudeE7)
                    .put("centerLongitudeE7", zone.centerLongitudeE7)
                    .put("radiusMeters", zone.radiusMeters)
                    .put("createdAtMillis", zone.createdAtMillis)
                    .put("position", zone.position.name)
                    .put("candidate", zone.candidate.name)
                    .put("candidateSinceMillis", zone.candidateSinceMillis)
                    .put("lastSampleAtMillis", zone.lastSampleAtMillis)
                    .put("lastDistanceMeters", zone.lastDistanceMeters))
            }
        })
        root.put("safetyZoneEvents", JSONArray().apply {
            state.safetyZoneEvents.takeLast(100).forEach { event ->
                put(JSONObject().put("relation", event.relationId)
                    .put("fingerprint", event.senderFingerprint)
                    .put("transition", event.transition.name)
                    .put("capturedAt", event.capturedAtMillis)
                    .put("distanceMeters", event.distanceMeters))
            }
        })
        root.put("lastVerifiedAt", JSONObject().apply {
            state.lastVerifiedAt.forEach { (relation, time) -> put(relation, time) }
        })
        root.put("lastNotifiedLossStage", JSONObject().apply {
            state.lastNotifiedLossStage.forEach { (relation, stage) -> put(relation, stage) }
        })
        root.put("locationSharingRelations", JSONArray(state.locationSharingRelations.toList()))
        root.put("remoteAssistBlockedRelations",
            JSONArray(state.remoteAssistBlockedRelations.toList()))
        root.put("receivedLocations", JSONArray().apply {
            state.receivedLocations.takeLast(200).forEach { fix ->
                put(JSONObject().put("relation", fix.relationId)
                    .put("fingerprint", fix.senderFingerprint)
                    .put("latitudeE7", fix.latitudeE7)
                    .put("longitudeE7", fix.longitudeE7)
                    .put("accuracyCm", fix.accuracyCm)
                    .put("capturedAt", fix.capturedAtMillis)
                    .put("receivedAt", fix.receivedAtMillis)
                    .put("provider", fix.provider))
            }
        })
        root.put("commandRecords", JSONArray().apply {
            state.commandRecords.takeLast(80).forEach { item ->
                put(JSONObject().put("time", item.time).put("action", item.action)
                    .put("outcome", item.outcome).put("code", item.code)
                    .put("detail", item.detail))
            }
        })
        root.put("medicalFolderTreeUri", state.medicalFolderTreeUri)
        root.put("lastReadCheckinTimestamp", state.lastReadCheckinTimestamp)
        root.put("checkins", JSONArray().apply {
            state.checkins.takeLast(100).forEach { entry ->
                put(JSONObject().put("event", entry.eventId).put("relation", entry.relationId)
                    .put("fingerprint", entry.fingerprint).put("own", entry.own)
                    .put("time", entry.timestamp)
                    .put("supersededBy", entry.supersededBy)
                    .put("originatedAt", entry.originatedAt)
                    .put("targetRelationId", entry.targetRelationId)
                    .put("delivered", JSONArray(entry.deliveredRelations.toList()))
                    .put("attempts", JSONObject().apply {
                        entry.attempts.forEach { (id, n) -> put(id, n) }
                    }))
            }
        })
        root.put("handshakeLog", JSONArray().apply {
            state.handshakeLog.takeLast(120).forEach { entry ->
                put(JSONObject().put("time", entry.time).put("direction", entry.direction)
                    .put("stage", entry.stage).put("relation", entry.relation)
                    .put("result", entry.result).put("detail", entry.detail))
            }
        })
        val contacts = JSONArray()
        state.contacts.take(10).forEach {
            contacts.put(JSONObject().put("name", it.name).put("phone", it.phone))
        }
        root.put("contacts", contacts)
        val bytes = encrypt(root.toString().toByteArray(Charsets.UTF_8))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            if (stream != null) file.failWrite(stream)
            throw e
        }
    }
}
