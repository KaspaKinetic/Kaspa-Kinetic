package com.kaspakinetic.wallet.core.network

import com.kaspakinetic.wallet.core.crypto.KaspaAddress
import com.kaspakinetic.wallet.core.crypto.KaspaCrypto
import com.kaspakinetic.wallet.core.model.FeeEstimate
import com.kaspakinetic.wallet.core.model.NodeHealth
import com.kaspakinetic.wallet.core.model.UtxoItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream

/**
 * Strips raw HTML markup (such as `<html><body><h1>429 Too Many Requests...`) and extracts
 * concise, human-readable network/node error messages for UI banners and snackbars.
 */
object KaspaErrorSanitizer {
    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun sanitize(raw: String?, httpCode: Int? = null): String {
        val text = raw?.trim().orEmpty()
        if (httpCode == 429 || text.contains("429") || text.contains("Too Many Requests", ignoreCase = true)) {
            return "Rate limited by node/API (HTTP 429). Backing off briefly and retrying..."
        }
        if (text.isEmpty()) {
            return if (httpCode != null) "Request failed (HTTP $httpCode)" else "Network request failed"
        }
        val stripped = if (text.contains('<') && text.contains('>')) {
            text.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
                .replace(HTML_TAG_REGEX, " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace(WHITESPACE_REGEX, " ")
                .trim()
        } else {
            text.replace(WHITESPACE_REGEX, " ").trim()
        }
        val concise = stripped.ifBlank {
            if (httpCode != null) "Request failed (HTTP $httpCode)" else "Network request failed"
        }
        // Preserve full untruncated node/RPC/VM error details so users can read & copy the complete message.
        return concise
    }
}

/**
 * Pure-Kotlin, zero-reflection Proto3 wire codec for Kaspium-compatible `protowire.RPC/MessageStream`
 * (`KaspadRequest` / `KaspadResponse`) matching Rusty-Kaspa `messages.proto` & `rpc.proto`.
 */
object KaspadProtoCodec {
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2

    // KaspadRequest / KaspadResponse common correlation ID
    const val TAG_ID = 101

    // Request payload field tags (messages.proto)
    const val TAG_REQ_NOTIFY_BLOCK_ADDED = 1007
    const val TAG_REQ_GET_PEER_ADDRESSES = 1010
    const val TAG_REQ_SUBMIT_TX = 1020
    const val TAG_REQ_GET_BLOCK_DAG_INFO = 1035
    const val TAG_REQ_NOTIFY_UTXOS_CHANGED = 1049
    const val TAG_REQ_GET_UTXOS_BY_ADDRESSES = 1052
    const val TAG_REQ_GET_INFO = 1063
    const val TAG_REQ_STOP_NOTIFYING_UTXOS_CHANGED = 1065
    const val TAG_REQ_NOTIFY_VIRTUAL_DAA_SCORE = 1074
    const val TAG_REQ_GET_BALANCES_BY_ADDRESSES = 1079
    const val TAG_REQ_GET_SERVER_INFO = 1092
    const val TAG_REQ_GET_FEE_ESTIMATE = 1106
    const val TAG_REQ_GET_UTXOS_BY_COVENANT_IDS = 1122

    // Response & notification payload field tags (messages.proto)
    const val TAG_RESP_NOTIFY_BLOCK_ADDED = 1008
    const val TAG_NOTIF_BLOCK_ADDED = 1009
    const val TAG_RESP_GET_PEER_ADDRESSES = 1011
    const val TAG_RESP_SUBMIT_TX = 1021
    const val TAG_RESP_GET_BLOCK_DAG_INFO = 1036
    const val TAG_RESP_NOTIFY_UTXOS_CHANGED = 1050
    const val TAG_NOTIF_UTXOS_CHANGED = 1051
    const val TAG_RESP_GET_UTXOS_BY_ADDRESSES = 1053
    const val TAG_RESP_GET_INFO = 1064
    const val TAG_RESP_STOP_NOTIFYING_UTXOS_CHANGED = 1066
    const val TAG_RESP_NOTIFY_VIRTUAL_DAA_SCORE = 1075
    const val TAG_NOTIF_VIRTUAL_DAA_SCORE = 1076
    const val TAG_RESP_GET_BALANCES_BY_ADDRESSES = 1080
    const val TAG_RESP_GET_SERVER_INFO = 1093
    const val TAG_RESP_GET_FEE_ESTIMATE = 1107
    const val TAG_RESP_GET_UTXOS_BY_COVENANT_IDS = 1123

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class LiveBlockTxInfo(
        val transactionId: String,
        val inputCount: Int,
        val outputCount: Int,
        val totalOutputSompi: Long,
        val primaryRecipient: String,
        val blockTimeMs: Long = 0L,
    )

    data class LiveBlockHeaderInfo(
        val hash: String,
        val daaScore: Long,
        val blueScore: Long,
        val timestampMs: Long,
        val parentHashes: List<String>,
        val selectedParentHash: String,
        val mergeSetBlueHashes: List<String>,
        val mergeSetRedHashes: List<String>,
        val isChainBlock: Boolean,
        val txCount: Int,
        val transactions: List<LiveBlockTxInfo> = emptyList(),
    )

    data class RemovedUtxoRef(
        val address: String,
        val transactionId: String,
        val index: Int,
    ) {
        val outpointKey: String get() = "$transactionId:$index"
    }

    sealed class Payload {
        data class PeerAddresses(val addresses: List<String>, val error: String? = null) : Payload()
        data class ServerInfo(val health: NodeHealth, val error: String? = null) : Payload()
        data class BlockDagInfo(
            val networkName: String,
            val virtualDaaScore: Long,
            val pastMedianTime: Long = 0L,
            val error: String? = null,
        ) : Payload()
        data class Balances(val balances: Map<String, Long>, val error: String? = null) : Payload()
        data class Utxos(val utxos: List<UtxoItem>, val error: String? = null) : Payload()
        data class FeeEstimateResult(val feeEstimate: FeeEstimate, val error: String? = null) : Payload()
        data class SubmitTx(val transactionId: String, val error: String? = null) : Payload()
        data class Ack(val tag: Int, val error: String? = null) : Payload()
        data class VirtualDaaScoreChanged(val virtualDaaScore: Long) : Payload()
        data class BlockAdded(val block: LiveBlockHeaderInfo) : Payload()
        data class UtxosChanged(
            val added: List<UtxoItem>,
            val removed: List<RemovedUtxoRef>,
        ) : Payload()
        data class Unknown(val tag: Int) : Payload()
    }

    data class DecodedResponse(
        val id: Long,
        val payload: Payload,
    )

    // -------------------------------------------------------------------------
    // Request Encoders
    // -------------------------------------------------------------------------

    fun encodeGetPeerAddressesRequest(id: Long): ByteArray =
        encodeEnvelope(id, TAG_REQ_GET_PEER_ADDRESSES, ByteArray(0))

    fun encodeGetInfoRequest(id: Long): ByteArray =
        encodeEnvelope(id, TAG_REQ_GET_INFO, ByteArray(0))

    fun encodeGetServerInfoRequest(id: Long): ByteArray =
        encodeEnvelope(id, TAG_REQ_GET_SERVER_INFO, ByteArray(0))

    fun encodeGetBlockDagInfoRequest(id: Long): ByteArray =
        encodeEnvelope(id, TAG_REQ_GET_BLOCK_DAG_INFO, ByteArray(0))

    fun encodeGetFeeEstimateRequest(id: Long): ByteArray =
        encodeEnvelope(id, TAG_REQ_GET_FEE_ESTIMATE, ByteArray(0))

    fun encodeGetBalancesByAddressesRequest(id: Long, addresses: List<String>): ByteArray {
        val body = ProtoWriter().apply {
            addresses.forEach { writeString(1, it) }
        }.toByteArray()
        return encodeEnvelope(id, TAG_REQ_GET_BALANCES_BY_ADDRESSES, body)
    }

    fun encodeGetUtxosByAddressesRequest(id: Long, addresses: List<String>): ByteArray {
        val body = ProtoWriter().apply {
            addresses.forEach { writeString(1, it) }
        }.toByteArray()
        return encodeEnvelope(id, TAG_REQ_GET_UTXOS_BY_ADDRESSES, body)
    }

    fun encodeGetUtxosByCovenantIdsRequest(id: Long, covenantIds: List<String>): ByteArray {
        val body = ProtoWriter().apply {
            covenantIds.distinct().sorted().forEach { writeString(1, it) }
        }.toByteArray()
        return encodeEnvelope(id, TAG_REQ_GET_UTXOS_BY_COVENANT_IDS, body)
    }

    fun encodeNotifyVirtualDaaScoreChangedRequest(id: Long, stop: Boolean = false): ByteArray {
        val body = ProtoWriter().apply {
            if (stop) writeVarintField(101, 1L) // RpcNotifyCommand.NOTIFY_STOP = 1
        }.toByteArray()
        return encodeEnvelope(id, TAG_REQ_NOTIFY_VIRTUAL_DAA_SCORE, body)
    }

    fun encodeNotifyBlockAddedRequest(id: Long, stop: Boolean = false): ByteArray {
        val body = ProtoWriter().apply {
            if (stop) writeVarintField(101, 1L) // RpcNotifyCommand.NOTIFY_STOP = 1
        }.toByteArray()
        return encodeEnvelope(id, TAG_REQ_NOTIFY_BLOCK_ADDED, body)
    }

    fun encodeNotifyUtxosChangedRequest(id: Long, addresses: List<String>, stop: Boolean = false): ByteArray {
        val body = ProtoWriter().apply {
            addresses.forEach { writeString(1, it) }
            if (stop) writeVarintField(101, 1L)
        }.toByteArray()
        return encodeEnvelope(id, TAG_REQ_NOTIFY_UTXOS_CHANGED, body)
    }

    fun encodeStopNotifyingUtxosChangedRequest(id: Long, addresses: List<String>): ByteArray {
        val body = ProtoWriter().apply {
            addresses.forEach { writeString(1, it) }
        }.toByteArray()
        return encodeEnvelope(id, TAG_REQ_STOP_NOTIFYING_UTXOS_CHANGED, body)
    }

    /**
     * Converts our canonical `SignedTx.submitJson` (`{"transaction": {...}, "allowOrphan": false}`)
     * into a bit-exact `SubmitTransactionRequestMessage` wrapped inside `KaspadRequest`.
     */
    fun encodeSubmitTransactionRequest(id: Long, submitJson: String): ByteArray {
        val root = json.parseToJsonElement(submitJson).jsonObject
        val txObj = root["transaction"]?.jsonObject ?: root
        val allowOrphan = root["allowOrphan"]?.jsonPrimitive?.booleanOrNull ?: false

        val txBytes = ProtoWriter().apply {
            val version = txObj["version"]?.jsonPrimitive?.intOrNull ?: 0
            if (version != 0) writeVarintField(1, version.toLong())

            val inputs = (txObj["inputs"] as? JsonArray).orEmpty()
            for (el in inputs) {
                val inp = el as? JsonObject ?: continue
                val prev = inp["previousOutpoint"] as? JsonObject
                val outpointBytes = ProtoWriter().apply {
                    val prevTxId = prev?.get("transactionId")?.jsonPrimitive?.content.orEmpty()
                    val prevIdx = prev?.get("index")?.jsonPrimitive?.intOrNull ?: 0
                    if (prevTxId.isNotEmpty()) writeString(1, prevTxId)
                    if (prevIdx != 0) writeVarintField(2, prevIdx.toLong() and 0xFFFFFFFFL)
                }.toByteArray()
                val inpBytes = ProtoWriter().apply {
                    writeBytes(1, outpointBytes)
                    val sigScript = inp["signatureScript"]?.jsonPrimitive?.content.orEmpty()
                    if (sigScript.isNotEmpty()) writeString(2, sigScript)
                    val seq = inp["sequence"]?.jsonPrimitive?.longOrNull ?: 0L
                    if (seq != 0L) writeVarintField(3, seq)
                    val sigOpCount = inp["sigOpCount"]?.jsonPrimitive?.intOrNull ?: 0
                    if (version < 1 && sigOpCount != 0) {
                        writeVarintField(5, sigOpCount.toLong() and 0xFFFFFFFFL)
                    }
                    val computeBudget = inp["computeBudget"]?.jsonPrimitive?.intOrNull
                        ?: inp["compute_budget"]?.jsonPrimitive?.intOrNull
                        ?: 0
                    if (version >= 1 && computeBudget != 0) {
                        writeVarintField(6, computeBudget.toLong() and 0xFFFFFFFFL)
                    }
                }.toByteArray()
                writeBytes(2, inpBytes)
            }

            val outputs = (txObj["outputs"] as? JsonArray).orEmpty()
            for (el in outputs) {
                val out = el as? JsonObject ?: continue
                val amount = out["amount"]?.jsonPrimitive?.longOrNull ?: 0L
                val spkObj = out["scriptPublicKey"] as? JsonObject
                val spkBytes = ProtoWriter().apply {
                    val spkVer = spkObj?.get("version")?.jsonPrimitive?.intOrNull ?: 0
                    val spkHex = spkObj?.get("scriptPublicKey")?.jsonPrimitive?.content.orEmpty()
                    if (spkVer != 0) writeVarintField(1, spkVer.toLong() and 0xFFFFFFFFL)
                    if (spkHex.isNotEmpty()) writeString(2, spkHex)
                }.toByteArray()
                val covObj = out["covenant"] as? JsonObject
                val covBytes = if (version >= 1 && covObj != null) {
                    ProtoWriter().apply {
                        val authIn = covObj["authorizingInput"]?.jsonPrimitive?.intOrNull
                            ?: covObj["authorizing_input"]?.jsonPrimitive?.intOrNull
                            ?: 0
                        val covId = covObj["covenantId"]?.jsonPrimitive?.content
                            ?: covObj["covenant_id"]?.jsonPrimitive?.content
                            ?: ""
                        if (authIn != 0) writeVarintField(1, authIn.toLong() and 0xFFFFFFFFL)
                        if (covId.isNotEmpty()) writeString(2, covId)
                    }.toByteArray()
                } else {
                    null
                }
                val outBytes = ProtoWriter().apply {
                    if (amount != 0L) writeVarintField(1, amount)
                    writeBytes(2, spkBytes)
                    if (covBytes != null) writeBytes(4, covBytes)
                }.toByteArray()
                writeBytes(3, outBytes)
            }

            val lockTime = txObj["lockTime"]?.jsonPrimitive?.longOrNull ?: 0L
            if (lockTime != 0L) writeVarintField(4, lockTime)
            val subnetworkId = txObj["subnetworkId"]?.jsonPrimitive?.content.orEmpty()
            if (subnetworkId.isNotEmpty()) writeString(5, subnetworkId)
            val gas = txObj["gas"]?.jsonPrimitive?.longOrNull ?: 0L
            if (gas != 0L) writeVarintField(6, gas)
            val payloadHex = txObj["payload"]?.jsonPrimitive?.content.orEmpty()
            if (payloadHex.isNotEmpty()) writeString(8, payloadHex)
            val storageMass = txObj["storageMass"]?.jsonPrimitive?.longOrNull
                ?: txObj["storage_mass"]?.jsonPrimitive?.longOrNull
                ?: 0L
            if (version >= 1 && storageMass != 0L) writeVarintField(10, storageMass)
        }.toByteArray()

        val reqBytes = ProtoWriter().apply {
            writeBytes(1, txBytes)
            if (allowOrphan) writeVarintField(2, 1L)
        }.toByteArray()
        return encodeEnvelope(id, TAG_REQ_SUBMIT_TX, reqBytes)
    }

    private fun encodeEnvelope(id: Long, payloadTag: Int, payloadBytes: ByteArray): ByteArray {
        val w = ProtoWriter()
        if (id != 0L) w.writeVarintField(TAG_ID, id)
        // Always write the oneof field even when payloadBytes is empty (length 0) so kaspad recognizes the request!
        w.writeBytes(payloadTag, payloadBytes)
        return w.toByteArray()
    }

    // -------------------------------------------------------------------------
    // Response & Notification Decoder
    // -------------------------------------------------------------------------

    fun decodeResponse(bytes: ByteArray): DecodedResponse {
        val r = ProtoReader(bytes)
        var id = 0L
        var payload: Payload = Payload.Unknown(0)
        while (r.hasNext()) {
            val tagAndWire = r.readVarint()
            val fieldNum = (tagAndWire ushr 3).toInt()
            val wireType = (tagAndWire and 0x7L).toInt()
            when {
                fieldNum == TAG_ID && wireType == WIRE_VARINT -> id = r.readVarint()
                wireType == WIRE_LEN -> {
                    val msgBytes = r.readBytes()
                    payload = when (fieldNum) {
                        TAG_RESP_GET_PEER_ADDRESSES -> decodePeerAddressesResponse(msgBytes)
                        TAG_RESP_GET_INFO -> decodeGetInfoResponse(msgBytes)
                        TAG_RESP_GET_SERVER_INFO -> decodeServerInfoResponse(msgBytes)
                        TAG_RESP_GET_BLOCK_DAG_INFO -> decodeBlockDagInfoResponse(msgBytes)
                        TAG_RESP_GET_BALANCES_BY_ADDRESSES -> decodeBalancesResponse(msgBytes)
                        TAG_RESP_GET_UTXOS_BY_ADDRESSES,
                        TAG_RESP_GET_UTXOS_BY_COVENANT_IDS,
                        -> decodeUtxosResponse(msgBytes)
                        TAG_RESP_GET_FEE_ESTIMATE -> decodeFeeEstimateResponse(msgBytes)
                        TAG_RESP_SUBMIT_TX -> decodeSubmitTxResponse(msgBytes)
                        TAG_NOTIF_VIRTUAL_DAA_SCORE -> decodeVirtualDaaScoreNotif(msgBytes)
                        TAG_NOTIF_BLOCK_ADDED -> decodeBlockAddedNotif(msgBytes)
                        TAG_NOTIF_UTXOS_CHANGED -> decodeUtxosChangedNotif(msgBytes)
                        TAG_RESP_NOTIFY_BLOCK_ADDED,
                        TAG_RESP_NOTIFY_VIRTUAL_DAA_SCORE,
                        TAG_RESP_NOTIFY_UTXOS_CHANGED,
                        TAG_RESP_STOP_NOTIFYING_UTXOS_CHANGED,
                        -> Payload.Ack(fieldNum, decodeErrorOnly(msgBytes))
                        else -> Payload.Unknown(fieldNum)
                    }
                }
                else -> r.skipField(wireType)
            }
        }
        return DecodedResponse(id = id, payload = payload)
    }

    private fun decodeRpcError(bytes: ByteArray): String {
        val r = ProtoReader(bytes)
        var msg = ""
        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            if (field == 1 && wire == WIRE_LEN) msg = r.readString() else r.skipField(wire)
        }
        return KaspaErrorSanitizer.sanitize(msg)
    }

    private fun decodeErrorOnly(bytes: ByteArray): String? {
        val r = ProtoReader(bytes)
        var err: String? = null
        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            if (field == 1000 && wire == WIRE_LEN) err = decodeRpcError(r.readBytes()) else r.skipField(wire)
        }
        return err?.takeIf { it.isNotBlank() }
    }

    private fun decodePeerAddressesResponse(bytes: ByteArray): Payload.PeerAddresses {
        val r = ProtoReader(bytes)
        val addresses = ArrayList<String>()
        var error: String? = null
        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when {
                field == 1 && wire == WIRE_LEN -> {
                    val kr = ProtoReader(r.readBytes())
                    while (kr.hasNext()) {
                        val kk = kr.readVarint()
                        val kf = (kk ushr 3).toInt()
                        val kw = (kk and 7L).toInt()
                        if (kf == 1 && kw == WIRE_LEN) {
                            val addr = kr.readString().trim()
                            if (addr.isNotEmpty()) addresses.add(addr)
                        } else {
                            kr.skipField(kw)
                        }
                    }
                }
                field == 1000 && wire == WIRE_LEN -> error = decodeRpcError(r.readBytes())
                else -> r.skipField(wire)
            }
        }
        return Payload.PeerAddresses(addresses = addresses, error = error)
    }

    private fun decodeGetInfoResponse(bytes: ByteArray): Payload.ServerInfo {
        val r = ProtoReader(bytes)
        var serverVersion = ""
        var isUtxoIndexed = false
        var isSynced = false
        var error: String? = null
        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when {
                field == 3 && wire == WIRE_LEN -> serverVersion = r.readString()
                field == 4 && wire == WIRE_VARINT -> isUtxoIndexed = r.readVarint() != 0L
                field == 5 && wire == WIRE_VARINT -> isSynced = r.readVarint() != 0L
                field == 1000 && wire == WIRE_LEN -> error = decodeRpcError(r.readBytes())
                else -> r.skipField(wire)
            }
        }
        val health = NodeHealth(
            reachable = error == null,
            isSynced = isSynced,
            isUtxoIndexed = isUtxoIndexed,
            serverVersion = serverVersion,
            transportUsed = "gRPC",
            errorMessage = error,
        )
        return Payload.ServerInfo(health, error)
    }

    private fun decodeServerInfoResponse(bytes: ByteArray): Payload.ServerInfo {
        val r = ProtoReader(bytes)
        var serverVersion = ""
        var networkId = ""
        var hasUtxoIndex = false
        var isSynced = false
        var virtualDaaScore = 0L
        var error: String? = null
        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when {
                field == 3 && wire == WIRE_LEN -> serverVersion = r.readString()
                field == 4 && wire == WIRE_LEN -> networkId = r.readString()
                field == 5 && wire == WIRE_VARINT -> hasUtxoIndex = r.readVarint() != 0L
                field == 6 && wire == WIRE_VARINT -> isSynced = r.readVarint() != 0L
                field == 7 && wire == WIRE_VARINT -> virtualDaaScore = r.readVarint()
                field == 1000 && wire == WIRE_LEN -> error = decodeRpcError(r.readBytes())
                else -> r.skipField(wire)
            }
        }
        val health = NodeHealth(
            reachable = error == null,
            isSynced = isSynced,
            isUtxoIndexed = hasUtxoIndex,
            networkName = networkId,
            virtualDaaScore = virtualDaaScore,
            serverVersion = serverVersion,
            transportUsed = "gRPC",
            errorMessage = error,
        )
        return Payload.ServerInfo(health, error)
    }

    private fun decodeBlockDagInfoResponse(bytes: ByteArray): Payload.BlockDagInfo {
        val r = ProtoReader(bytes)
        var networkName = ""
        var pastMedianTime = 0L
        var virtualDaaScore = 0L
        var error: String? = null
        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when {
                field == 1 && wire == WIRE_LEN -> networkName = r.readString()
                field == 6 && wire == WIRE_VARINT -> pastMedianTime = r.readVarint()
                field == 9 && wire == WIRE_VARINT -> virtualDaaScore = r.readVarint()
                field == 1000 && wire == WIRE_LEN -> error = decodeRpcError(r.readBytes())
                else -> r.skipField(wire)
            }
        }
        return Payload.BlockDagInfo(
            networkName = networkName,
            virtualDaaScore = virtualDaaScore,
            pastMedianTime = pastMedianTime,
            error = error,
        )
    }

    private fun decodeBalancesResponse(bytes: ByteArray): Payload.Balances {
        val r = ProtoReader(bytes)
        val map = LinkedHashMap<String, Long>()
        var error: String? = null
        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when {
                field == 1 && wire == WIRE_LEN -> {
                    val er = ProtoReader(r.readBytes())
                    var addr = ""
                    var bal = 0L
                    while (er.hasNext()) {
                        val ek = er.readVarint()
                        val ef = (ek ushr 3).toInt()
                        val ew = (ek and 7L).toInt()
                        when {
                            ef == 1 && ew == WIRE_LEN -> addr = er.readString()
                            ef == 2 && ew == WIRE_VARINT -> bal = er.readVarint()
                            else -> er.skipField(ew)
                        }
                    }
                    if (addr.isNotBlank()) map[addr] = bal
                }
                field == 1000 && wire == WIRE_LEN -> error = decodeRpcError(r.readBytes())
                else -> r.skipField(wire)
            }
        }
        return Payload.Balances(map, error)
    }

    private fun decodeUtxosResponse(bytes: ByteArray): Payload.Utxos {
        val r = ProtoReader(bytes)
        val out = ArrayList<UtxoItem>()
        var error: String? = null
        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when {
                field == 1 && wire == WIRE_LEN -> decodeUtxoByAddressEntry(r.readBytes())?.let { out.add(it) }
                field == 1000 && wire == WIRE_LEN -> error = decodeRpcError(r.readBytes())
                else -> r.skipField(wire)
            }
        }
        return Payload.Utxos(out.sortedByDescending { it.amountSompi }, error)
    }

    private fun decodeUtxosChangedNotif(bytes: ByteArray): Payload.UtxosChanged {
        val r = ProtoReader(bytes)
        val added = ArrayList<UtxoItem>()
        val removed = ArrayList<RemovedUtxoRef>()
        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when {
                field == 1 && wire == WIRE_LEN -> decodeUtxoByAddressEntry(r.readBytes())?.let { added.add(it) }
                field == 2 && wire == WIRE_LEN -> decodeRemovedUtxoEntry(r.readBytes())?.let { removed.add(it) }
                else -> r.skipField(wire)
            }
        }
        return Payload.UtxosChanged(added, removed)
    }

    private fun decodeUtxoByAddressEntry(bytes: ByteArray): UtxoItem? {
        val r = ProtoReader(bytes)
        var address = ""
        var txId = ""
        var index = 0
        var amount = 0L
        var spkHex = ""
        var spkVersion = 0
        var blockDaaScore = 0L
        var isCoinbase = false
        var covenantId: String? = null

        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when {
                field == 1 && wire == WIRE_LEN -> address = r.readString()
                field == 2 && wire == WIRE_LEN -> {
                    val or = ProtoReader(r.readBytes())
                    while (or.hasNext()) {
                        val ok = or.readVarint()
                        val of = (ok ushr 3).toInt()
                        val ow = (ok and 7L).toInt()
                        when {
                            of == 1 && ow == WIRE_LEN -> txId = or.readString()
                            of == 2 && ow == WIRE_VARINT -> index = or.readVarint().toInt()
                            else -> or.skipField(ow)
                        }
                    }
                }
                field == 3 && wire == WIRE_LEN -> {
                    val er = ProtoReader(r.readBytes())
                    while (er.hasNext()) {
                        val ek = er.readVarint()
                        val ef = (ek ushr 3).toInt()
                        val ew = (ek and 7L).toInt()
                        when {
                            ef == 1 && ew == WIRE_VARINT -> amount = er.readVarint()
                            ef == 2 && ew == WIRE_LEN -> {
                                val sr = ProtoReader(er.readBytes())
                                while (sr.hasNext()) {
                                    val sk = sr.readVarint()
                                    val sf = (sk ushr 3).toInt()
                                    val sw = (sk and 7L).toInt()
                                    when {
                                        sf == 1 && sw == WIRE_VARINT -> spkVersion = sr.readVarint().toInt()
                                        sf == 2 && sw == WIRE_LEN -> spkHex = sr.readString()
                                        else -> sr.skipField(sw)
                                    }
                                }
                            }
                            ef == 3 && ew == WIRE_VARINT -> blockDaaScore = er.readVarint()
                            ef == 4 && ew == WIRE_VARINT -> isCoinbase = er.readVarint() != 0L
                            ef == 6 && ew == WIRE_LEN -> covenantId = er.readString().takeIf { it.isNotBlank() }
                            else -> er.skipField(ew)
                        }
                    }
                }
                else -> r.skipField(wire)
            }
        }
        if (address.isBlank() || txId.isBlank() || amount <= 0L) return null
        val resolvedSpk = spkHex.ifBlank {
            KaspaAddress.decode(address)?.toScriptPublicKey()?.let { KaspaCrypto.bytesToHex(it) }.orEmpty()
        }
        if (resolvedSpk.isBlank()) return null
        return UtxoItem(
            address = address,
            transactionId = txId,
            index = index,
            amountSompi = amount,
            scriptPublicKeyHex = resolvedSpk,
            scriptPublicKeyVersion = spkVersion,
            blockDaaScore = blockDaaScore,
            isCoinbase = isCoinbase,
            covenantId = covenantId,
        )
    }

    private fun decodeRemovedUtxoEntry(bytes: ByteArray): RemovedUtxoRef? {
        val r = ProtoReader(bytes)
        var address = ""
        var txId = ""
        var index = 0
        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when {
                field == 1 && wire == WIRE_LEN -> address = r.readString()
                field == 2 && wire == WIRE_LEN -> {
                    val or = ProtoReader(r.readBytes())
                    while (or.hasNext()) {
                        val ok = or.readVarint()
                        val of = (ok ushr 3).toInt()
                        val ow = (ok and 7L).toInt()
                        when {
                            of == 1 && ow == WIRE_LEN -> txId = or.readString()
                            of == 2 && ow == WIRE_VARINT -> index = or.readVarint().toInt()
                            else -> or.skipField(ow)
                        }
                    }
                }
                else -> r.skipField(wire)
            }
        }
        if (txId.isBlank()) return null
        return RemovedUtxoRef(address = address, transactionId = txId, index = index)
    }

    private fun decodeFeeEstimateResponse(bytes: ByteArray): Payload.FeeEstimateResult {
        val r = ProtoReader(bytes)
        var priorityRate = 1.0
        var prioritySec = 1.0
        var normalRate: Double? = null
        var lowRate: Double? = null
        var error: String? = null
        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when {
                field == 1 && wire == WIRE_LEN -> {
                    val er = ProtoReader(r.readBytes())
                    while (er.hasNext()) {
                        val ek = er.readVarint()
                        val ef = (ek ushr 3).toInt()
                        val ew = (ek and 7L).toInt()
                        when {
                            ef == 1 && ew == WIRE_LEN -> {
                                val (rate, secs) = decodeFeeBucket(er.readBytes())
                                priorityRate = rate.coerceAtLeast(1.0)
                                prioritySec = secs
                            }
                            ef == 2 && ew == WIRE_LEN -> {
                                val (rate, _) = decodeFeeBucket(er.readBytes())
                                if (normalRate == null) normalRate = rate.coerceAtLeast(1.0)
                            }
                            ef == 3 && ew == WIRE_LEN -> {
                                val (rate, _) = decodeFeeBucket(er.readBytes())
                                if (lowRate == null) lowRate = rate.coerceAtLeast(1.0)
                            }
                            else -> er.skipField(ew)
                        }
                    }
                }
                field == 1000 && wire == WIRE_LEN -> error = decodeRpcError(r.readBytes())
                else -> r.skipField(wire)
            }
        }
        val resolvedNormal = normalRate ?: priorityRate
        val resolvedLow = lowRate ?: 1.0
        return Payload.FeeEstimateResult(
            feeEstimate = FeeEstimate(
                lowRate = resolvedLow,
                normalRate = resolvedNormal,
                priorityRate = maxOf(priorityRate, resolvedNormal),
                priorityEstSeconds = prioritySec,
            ),
            error = error,
        )
    }

    private fun decodeFeeBucket(bytes: ByteArray): Pair<Double, Double> {
        val r = ProtoReader(bytes)
        var feerate = 1.0
        var estSec = 1.0
        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when {
                field == 1 && wire == WIRE_FIXED64 -> feerate = r.readDouble()
                field == 2 && wire == WIRE_FIXED64 -> estSec = r.readDouble()
                else -> r.skipField(wire)
            }
        }
        return feerate to estSec
    }

    private fun decodeSubmitTxResponse(bytes: ByteArray): Payload.SubmitTx {
        val r = ProtoReader(bytes)
        var txId = ""
        var error: String? = null
        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when {
                field == 1 && wire == WIRE_LEN -> txId = r.readString()
                field == 1000 && wire == WIRE_LEN -> error = decodeRpcError(r.readBytes())
                else -> r.skipField(wire)
            }
        }
        return Payload.SubmitTx(txId, error)
    }

    private fun decodeVirtualDaaScoreNotif(bytes: ByteArray): Payload.VirtualDaaScoreChanged {
        val r = ProtoReader(bytes)
        var score = 0L
        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            if (field == 1 && wire == WIRE_VARINT) score = r.readVarint() else r.skipField(wire)
        }
        return Payload.VirtualDaaScoreChanged(score)
    }

    private fun decodeBlockAddedNotif(bytes: ByteArray): Payload {
        val r = ProtoReader(bytes)
        var blockBytes: ByteArray? = null
        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            if (field == 3 && wire == WIRE_LEN) {
                blockBytes = r.readBytes()
            } else {
                r.skipField(wire)
            }
        }
        val b = blockBytes ?: return Payload.Unknown(TAG_NOTIF_BLOCK_ADDED)
        return decodeRpcBlock(b)?.let { Payload.BlockAdded(it) } ?: Payload.Unknown(TAG_NOTIF_BLOCK_ADDED)
    }

    private fun decodeRpcBlock(bytes: ByteArray): LiveBlockHeaderInfo? {
        val r = ProtoReader(bytes)
        var headerHash = ""
        var verboseHash = ""
        var daaScore = 0L
        var headerBlueScore = 0L
        var verboseBlueScore = 0L
        var timestampMs = 0L
        val directParents = ArrayList<String>()
        var selectedParentHash = ""
        val mergeSetBlues = ArrayList<String>()
        val mergeSetReds = ArrayList<String>()
        var isChainBlock = false
        val parsedTxs = ArrayList<LiveBlockTxInfo>()
        val verboseTxIds = ArrayList<String>()

        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when {
                field == 1 && wire == WIRE_LEN -> {
                    val hr = ProtoReader(r.readBytes())
                    var levelIndex = 0
                    while (hr.hasNext()) {
                        val hk = hr.readVarint()
                        val hf = (hk ushr 3).toInt()
                        val hw = (hk and 7L).toInt()
                        when {
                            hf == 6 && hw == WIRE_VARINT -> timestampMs = hr.readVarint()
                            hf == 9 && hw == WIRE_VARINT -> daaScore = hr.readVarint()
                            hf == 12 && hw == WIRE_LEN -> {
                                val pr = ProtoReader(hr.readBytes())
                                if (levelIndex == 0) {
                                    while (pr.hasNext()) {
                                        val pk = pr.readVarint()
                                        val pf = (pk ushr 3).toInt()
                                        val pw = (pk and 7L).toInt()
                                        if (pf == 1 && pw == WIRE_LEN) {
                                            val ph = pr.readString()
                                            if (ph.isNotBlank()) directParents.add(ph)
                                        } else {
                                            pr.skipField(pw)
                                        }
                                    }
                                }
                                levelIndex++
                            }
                            hf == 13 && hw == WIRE_VARINT -> headerBlueScore = hr.readVarint()
                            hf == 15 && hw == WIRE_LEN -> headerHash = hr.readString()
                            else -> hr.skipField(hw)
                        }
                    }
                }
                field == 2 && wire == WIRE_LEN -> {
                    parsedTxs.add(decodeRpcTransactionSummary(r.readBytes()))
                }
                field == 3 && wire == WIRE_LEN -> {
                    val vr = ProtoReader(r.readBytes())
                    while (vr.hasNext()) {
                        val vk = vr.readVarint()
                        val vf = (vk ushr 3).toInt()
                        val vw = (vk and 7L).toInt()
                        when {
                            vf == 1 && vw == WIRE_LEN -> verboseHash = vr.readString()
                            vf == 13 && vw == WIRE_LEN -> selectedParentHash = vr.readString()
                            vf == 14 && vw == WIRE_LEN -> {
                                val tid = vr.readString()
                                if (tid.isNotBlank()) verboseTxIds.add(tid)
                            }
                            vf == 16 && vw == WIRE_VARINT -> verboseBlueScore = vr.readVarint()
                            vf == 18 && vw == WIRE_LEN -> {
                                val h = vr.readString()
                                if (h.isNotBlank()) mergeSetBlues.add(h)
                            }
                            vf == 19 && vw == WIRE_LEN -> {
                                val h = vr.readString()
                                if (h.isNotBlank()) mergeSetReds.add(h)
                            }
                            vf == 20 && vw == WIRE_VARINT -> isChainBlock = vr.readVarint() != 0L
                            else -> vr.skipField(vw)
                        }
                    }
                }
                else -> r.skipField(wire)
            }
        }
        val finalHash = verboseHash.ifBlank { headerHash }
        if (finalHash.isBlank()) return null
        val resolvedTxs = ArrayList<LiveBlockTxInfo>(parsedTxs.size)
        for (i in parsedTxs.indices) {
            val tx = parsedTxs[i]
            val finalTxId = tx.transactionId.ifBlank { verboseTxIds.getOrNull(i).orEmpty() }
            if (finalTxId.isNotBlank()) {
                resolvedTxs.add(
                    if (finalTxId == tx.transactionId) tx else tx.copy(transactionId = finalTxId),
                )
            }
        }
        return LiveBlockHeaderInfo(
            hash = finalHash,
            daaScore = daaScore,
            blueScore = if (verboseBlueScore > 0L) verboseBlueScore else headerBlueScore,
            timestampMs = timestampMs,
            parentHashes = directParents.distinct(),
            selectedParentHash = selectedParentHash.ifBlank { directParents.firstOrNull().orEmpty() },
            mergeSetBlueHashes = mergeSetBlues,
            mergeSetRedHashes = mergeSetReds,
            isChainBlock = isChainBlock,
            txCount = maxOf(parsedTxs.size, verboseTxIds.size),
            transactions = resolvedTxs,
        )
    }

    /**
     * Lightweight proto3 decoder for `RpcTransaction` inside `RpcBlock.transactions` (field 2),
     * powering real-time gRPC-first Block Explorer live transactions without REST polling.
     */
    private fun decodeRpcTransactionSummary(bytes: ByteArray): LiveBlockTxInfo {
        val r = ProtoReader(bytes)
        var inputCount = 0
        var outputCount = 0
        var totalOutputSompi = 0L
        var primaryRecipient = ""
        var transactionId = ""
        var blockTimeMs = 0L

        while (r.hasNext()) {
            val key = r.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when {
                field == 2 && wire == WIRE_LEN -> {
                    // RpcTransaction.inputs (fast skip; we only need inputCount to classify coinbase vs transfer)
                    r.skipField(wire)
                    inputCount++
                }
                field == 3 && wire == WIRE_LEN -> {
                    outputCount++
                    val or = ProtoReader(r.readBytes())
                    while (or.hasNext()) {
                        val ok = or.readVarint()
                        val of = (ok ushr 3).toInt()
                        val ow = (ok and 7L).toInt()
                        when {
                            of == 1 && ow == WIRE_VARINT -> {
                                totalOutputSompi += or.readVarint().coerceAtLeast(0L)
                            }
                            of == 3 && ow == WIRE_LEN -> {
                                if (primaryRecipient.isEmpty()) {
                                    val ovr = ProtoReader(or.readBytes())
                                    while (ovr.hasNext()) {
                                        val ovk = ovr.readVarint()
                                        val ovf = (ovk ushr 3).toInt()
                                        val ovw = (ovk and 7L).toInt()
                                        if (ovf == 6 && ovw == WIRE_LEN) {
                                            primaryRecipient = ovr.readString()
                                        } else {
                                            ovr.skipField(ovw)
                                        }
                                    }
                                } else {
                                    or.skipField(ow)
                                }
                            }
                            else -> or.skipField(ow)
                        }
                    }
                }
                field == 9 && wire == WIRE_LEN -> {
                    val vr = ProtoReader(r.readBytes())
                    while (vr.hasNext()) {
                        val vk = vr.readVarint()
                        val vf = (vk ushr 3).toInt()
                        val vw = (vk and 7L).toInt()
                        when {
                            vf == 1 && vw == WIRE_LEN -> transactionId = vr.readString()
                            vf == 14 && vw == WIRE_VARINT -> blockTimeMs = vr.readVarint()
                            else -> vr.skipField(vw)
                        }
                    }
                }
                else -> r.skipField(wire)
            }
        }
        return LiveBlockTxInfo(
            transactionId = transactionId,
            inputCount = inputCount,
            outputCount = outputCount,
            totalOutputSompi = totalOutputSompi,
            primaryRecipient = primaryRecipient,
            blockTimeMs = blockTimeMs,
        )
    }

    // -------------------------------------------------------------------------
    // Minimal Proto3 Binary Reader / Writer Primitives
    // -------------------------------------------------------------------------

    private class ProtoWriter {
        private val out = ByteArrayOutputStream(128)

        fun writeTag(fieldNumber: Int, wireType: Int) {
            writeVarint((fieldNumber.toLong() shl 3) or wireType.toLong())
        }

        fun writeVarintField(fieldNumber: Int, value: Long) {
            writeTag(fieldNumber, WIRE_VARINT)
            writeVarint(value)
        }

        fun writeString(fieldNumber: Int, value: String) {
            writeBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))
        }

        fun writeBytes(fieldNumber: Int, value: ByteArray) {
            writeTag(fieldNumber, WIRE_LEN)
            writeVarint(value.size.toLong())
            out.write(value)
        }

        fun writeVarint(v: Long) {
            var x = v
            while (true) {
                if ((x and 0x7FL.inv()) == 0L) {
                    out.write(x.toInt())
                    return
                }
                out.write(((x.toInt() and 0x7F) or 0x80))
                x = x ushr 7
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private class ProtoReader(private val data: ByteArray) {
        private var pos = 0

        fun hasNext(): Boolean = pos < data.size

        fun readVarint(): Long {
            var shift = 0
            var result = 0L
            while (pos < data.size && shift < 64) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return result
        }

        fun readFixed64(): Long {
            if (pos + 8 > data.size) {
                pos = data.size
                return 0L
            }
            var res = 0L
            for (i in 0 until 8) {
                res = res or ((data[pos++].toLong() and 0xFFL) shl (i * 8))
            }
            return res
        }

        fun readDouble(): Double = java.lang.Double.longBitsToDouble(readFixed64())

        fun readBytes(): ByteArray {
            val len = readVarint().toInt().coerceAtLeast(0)
            val safeEnd = (pos + len).coerceAtMost(data.size)
            val slice = data.copyOfRange(pos, safeEnd)
            pos = safeEnd
            return slice
        }

        fun readString(): String = String(readBytes(), Charsets.UTF_8)

        fun skipField(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> pos = (pos + 8).coerceAtMost(data.size)
                2 -> {
                    val len = readVarint().toInt().coerceAtLeast(0)
                    pos = (pos + len).coerceAtMost(data.size)
                }
                5 -> pos = (pos + 4).coerceAtMost(data.size)
                else -> pos = data.size
            }
        }
    }
}
