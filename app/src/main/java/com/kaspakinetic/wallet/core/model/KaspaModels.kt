package com.kaspakinetic.wallet.core.model

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

const val SOMPI_PER_KAS: Long = 100_000_000L
const val DUST_LIMIT_SOMPI: Long = 10_000L
const val MAX_INPUTS_PER_TX: Int = 80
const val GAP_LIMIT_DEFAULT: Int = 20

@Serializable
enum class KaspaNetwork(
    val id: String,
    val hrp: String,
    val defaultGrpcHost: String,
    val defaultGrpcPort: Int,
    val defaultGrpcTls: Boolean,
    val defaultRestUrl: String,
    val defaultWrpcUrl: String,
    val explorerBaseUrl: String,
    val isTestnet: Boolean,
) {
    MAINNET(
        id = "mainnet",
        hrp = "kaspa",
        defaultGrpcHost = "node.kaspium.io",
        defaultGrpcPort = 443,
        defaultGrpcTls = true,
        defaultRestUrl = "https://api.kaspa.org",
        defaultWrpcUrl = "wss://api.kaspa.org/wrpc",
        explorerBaseUrl = "https://kaspa.stream",
        isTestnet = false,
    ),
    TESTNET_10(
        id = "testnet-10",
        hrp = "kaspatest",
        defaultGrpcHost = "tn10.kaspa.stream",
        defaultGrpcPort = 16210,
        defaultGrpcTls = false,
        defaultRestUrl = "https://api-tn10.kaspa.org",
        defaultWrpcUrl = "wss://api-tn10.kaspa.org/wrpc",
        explorerBaseUrl = "https://tn10.kaspa.stream",
        isTestnet = true,
    ),
}

@Serializable
enum class WalletMode {
    HOT_HD,
    WATCH_KPUB,
    WATCH_ADDRESS,
}

@Serializable
enum class DerivationKind {
    STANDARD_111111,
    LEGACY_972_KDX,
    LEGACY_972_BIP44,
}

@Serializable
data class WalletRecord(
    val id: String,
    val name: String,
    val mode: WalletMode,
    val derivationKind: DerivationKind = DerivationKind.STANDARD_111111,
    val hasPassphrase: Boolean = false,
    val primaryAddressMainnet: String,
    val kpubMainnet: String? = null,
    val receiveIndex: Int = 0,
    val changeIndex: Int = 0,
    val highestUsedReceiveIndex: Int = -1,
    val highestUsedChangeIndex: Int = -1,
    val cachedBalanceSompi: Long = 0L,
    val hasCompletedInitialScan: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    val isWatchOnly: Boolean get() = mode != WalletMode.HOT_HD
}

@Serializable
data class DerivedAddress(
    val address: String,
    val chain: Int, // 0 = receive, 1 = change
    val index: Int,
    val derivationPath: String,
    val balanceSompi: Long = 0L,
    val isUsed: Boolean = false,
)

@Serializable
data class UtxoItem(
    val address: String,
    val transactionId: String,
    val index: Int,
    val amountSompi: Long,
    val scriptPublicKeyHex: String,
    val scriptPublicKeyVersion: Int = 0,
    val blockDaaScore: Long = 0L,
    val isCoinbase: Boolean = false,
    val covenantId: String? = null,
) {
    val outpointKey: String get() = "$transactionId:$index"
}

@Serializable
enum class TxDirection {
    RECEIVED,
    SENT,
    SELF_TRANSFER,
    COMPOUND,
    BURN,
    MINER_DONATION,
    COVENANT,
}

@Serializable
data class TxInputSummary(
    val previousOutpointHash: String,
    val previousOutpointIndex: Int,
    val address: String?,
    val amountSompi: Long?,
)

@Serializable
data class TxOutputSummary(
    val index: Int,
    val address: String,
    val amountSompi: Long,
    val scriptPublicKeyHex: String,
)

@Serializable
data class WalletTx(
    val transactionId: String,
    val blockTimeMs: Long,
    val isAccepted: Boolean,
    val acceptingBlockBlueScore: Long? = null,
    val direction: TxDirection,
    val netAmountSompi: Long,
    val feeSompi: Long,
    val mass: Long = 0L,
    val inputs: List<TxInputSummary> = emptyList(),
    val outputs: List<TxOutputSummary> = emptyList(),
    val counterpartyAddress: String = "",
)

@Serializable
data class ContactRecord(
    val id: String,
    val name: String,
    val address: String,
    val note: String = "",
)

@Serializable
enum class FeePriority {
    LOW,
    NORMAL,
    PRIORITY,
}

@Serializable
data class FeeEstimate(
    val lowRate: Double = 1.0,
    val normalRate: Double = 1.0,
    val priorityRate: Double = 2.0,
    val priorityEstSeconds: Double = 1.0,
) {
    fun rateFor(priority: FeePriority): Double = when (priority) {
        FeePriority.LOW -> lowRate.coerceAtLeast(1.0)
        FeePriority.NORMAL -> normalRate.coerceAtLeast(1.0)
        FeePriority.PRIORITY -> priorityRate.coerceAtLeast(1.0)
    }
}

@Serializable
enum class NodeConnectionMode {
    AUTOMATIC_SCAN,
    TLS_ONLY,
    CUSTOM,
}

@Serializable
data class NodeEndpoint(
    val host: String,
    val port: Int,
    val useTls: Boolean,
) {
    val formattedHost: String
        get() {
            val clean = host.trim().trim('[', ']')
            return if (clean.contains(':')) "[$clean]" else clean
        }

    val hostPort: String get() = "$formattedHost:$port"
    val key: String get() = "$hostPort:${if (useTls) "tls" else "plain"}"
    val displayWithTransport: String get() = "$hostPort (${if (useTls) "TLS" else "Plaintext"})"
}

@Serializable
data class NodeConfig(
    val network: KaspaNetwork = KaspaNetwork.MAINNET,
    val connectionMode: NodeConnectionMode = NodeConnectionMode.AUTOMATIC_SCAN,
    val useCustomNode: Boolean = false,
    val customGrpcHostPort: String = "",
    val customGrpcUseTls: Boolean = true,
    val customWrpcUrl: String = "",
    val customRestUrl: String = "",
) {
    /**
     * Backwards-compatible resolved connection mode:
     * preserves existing persisted `useCustomNode = true` configs as [NodeConnectionMode.CUSTOM].
     */
    val effectiveConnectionMode: NodeConnectionMode
        get() = if (useCustomNode || connectionMode == NodeConnectionMode.CUSTOM) {
            NodeConnectionMode.CUSTOM
        } else {
            connectionMode
        }

    val effectiveRestUrl: String
        get() = if (effectiveConnectionMode == NodeConnectionMode.CUSTOM && customRestUrl.isNotBlank()) {
            customRestUrl.trim().trimEnd('/')
        } else {
            network.defaultRestUrl
        }

    val effectiveWrpcUrl: String
        get() = if (effectiveConnectionMode == NodeConnectionMode.CUSTOM && customWrpcUrl.isNotBlank()) {
            customWrpcUrl.trim()
        } else {
            network.defaultWrpcUrl
        }

    /**
     * Resolves `(host, port, useTls)` for the active direct `kaspad` gRPC endpoint
     * (used directly for [NodeConnectionMode.CUSTOM] and [NodeConnectionMode.TLS_ONLY],
     * and as the Mainnet TLS firewall fallback for [NodeConnectionMode.AUTOMATIC_SCAN]).
     * Supports `host:port`, `grpcs://host:port`, `grpc://host:port`, or bare `host`.
     */
    fun resolvedGrpcEndpoint(): Triple<String, Int, Boolean> {
        if (effectiveConnectionMode != NodeConnectionMode.CUSTOM || customGrpcHostPort.isBlank()) {
            return Triple(network.defaultGrpcHost, network.defaultGrpcPort, network.defaultGrpcTls)
        }
        val raw = customGrpcHostPort.trim()
        val tls = when {
            raw.startsWith("grpcs://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> true
            raw.startsWith("grpc://", ignoreCase = true) || raw.startsWith("http://", ignoreCase = true) -> false
            else -> customGrpcUseTls
        }
        val stripped = raw
            .removePrefix("grpcs://").removePrefix("GRPCS://")
            .removePrefix("grpc://").removePrefix("GRPC://")
            .removePrefix("https://").removePrefix("HTTPS://")
            .removePrefix("http://").removePrefix("HTTP://")
            .substringBefore('/')
            .trim()
        val lastColon = stripped.lastIndexOf(':')
        val defaultPort = when {
            tls -> 443
            network.isTestnet -> 16210
            else -> 16110
        }
        return if (lastColon > 0 && lastColon < stripped.length - 1 && !stripped.endsWith(']')) {
            val host = stripped.substring(0, lastColon).trim('[', ']')
            val port = stripped.substring(lastColon + 1).toIntOrNull() ?: defaultPort
            Triple(host, port, tls)
        } else {
            Triple(stripped.trim('[', ']'), defaultPort, tls)
        }
    }

    val effectiveGrpcDisplay: String
        get() {
            val (h, p, tls) = resolvedGrpcEndpoint()
            val proto = if (tls) "TLS" else "plaintext"
            return "$h:$p ($proto)"
        }
}

enum class SyncStatus {
    CONNECTING,
    SYNCED,
    OFFLINE,
}

@Serializable
data class NodeHealth(
    val reachable: Boolean,
    val isSynced: Boolean,
    val isUtxoIndexed: Boolean,
    val networkName: String = "",
    val virtualDaaScore: Long = 0L,
    val serverVersion: String = "",
    val latencyMs: Long = 0L,
    val transportUsed: String = "REST",
    val errorMessage: String? = null,
    val activeEndpoint: String = "",
    val isTls: Boolean = false,
    val activePoolCount: Int = 0,
)

@Serializable
enum class FiatCurrency(val code: String, val symbol: String, val displayName: String) {
    USD("USD", "$", "US Dollar"),
    BTC("BTC", "₿", "Bitcoin"),
    EUR("EUR", "€", "Euro"),
    GBP("GBP", "£", "British Pound"),
    JPY("JPY", "¥", "Japanese Yen"),
    KRW("KRW", "₩", "South Korean Won"),
    AUD("AUD", "A$", "Australian Dollar"),
    CAD("CAD", "C$", "Canadian Dollar"),
    CHF("CHF", "Fr", "Swiss Franc"),
    CNY("CNY", "¥", "Chinese Yuan"),
    INR("INR", "₹", "Indian Rupee"),
    BRL("BRL", "R$", "Brazilian Real"),
    TRY("TRY", "₺", "Turkish Lira"),
}

@Serializable
data class SpotPrice(
    val currency: FiatCurrency,
    val price: Double,
    val change24hPercent: Double? = null,
    val fetchedAtMs: Long = System.currentTimeMillis(),
)

enum class TxFilter {
    ALL,
    RECEIVED,
    SENT,
    SELF_COMPOUND,
    FUN_STUFF,
}

enum class AutoLockTimeout(val minutes: Int) {
    IMMEDIATE(0),
    ONE_MINUTE(1),
    FIVE_MINUTES(5),
    FIFTEEN_MINUTES(15),
    NEVER(-1),
}

enum class MainTab {
    TRANSACTIONS,
    UTXOS,
    COVENANTS,
}

data class MassBreakdown(
    val computeMass: Long,
    val transientMass: Long,
    val storageMass: Long,
    val normalizedMass: Long,
)

data class PreparedTx(
    val senderAddress: String,
    val recipientAddress: String,
    val changeAddress: String,
    val amountSompi: Long,
    val changeSompi: Long,
    val feeSompi: Long,
    val totalInputSompi: Long,
    val selectedUtxos: List<UtxoItem>,
    val massBreakdown: MassBreakdown,
    val reviewHash: String,
    val isSendAll: Boolean = false,
    val isCompound: Boolean = false,
    val isBurn: Boolean = false,
    val isMinerDonation: Boolean = false,
)

data class SignedTx(
    val transactionId: String,
    val feeSompi: Long,
    val mass: Long,
    val submitJson: String,
    val reviewHash: String,
    val outputs: List<TxOutputSummary>,
)

data class MinerDonationProgress(
    val totalSplits: Int,
    val completedSplits: Int,
    val totalDonatedSompi: Long,
    val targetTotalSompi: Long,
    val txIds: List<String> = emptyList(),
    val isRunning: Boolean = false,
    val isCancelled: Boolean = false,
    val errorMessage: String? = null,
)

object KasFormat {
    fun sompiToKas(sompi: Long): BigDecimal =
        BigDecimal.valueOf(sompi).divide(BigDecimal.valueOf(SOMPI_PER_KAS), 8, RoundingMode.DOWN)

    fun kasStringToSompi(input: String): Long? {
        val cleaned = input.trim().replace(",", "")
        if (cleaned.isEmpty()) return null
        return runCatching {
            val bd = BigDecimal(cleaned)
            if (bd < BigDecimal.ZERO) return null
            val sompi = bd.multiply(BigDecimal.valueOf(SOMPI_PER_KAS))
                .setScale(0, RoundingMode.DOWN)
            sompi.longValueExact()
        }.getOrNull()
    }

    fun formatKas(
        sompi: Long,
        maxDecimals: Int = 8,
        trimTrailingZeros: Boolean = true,
        grouping: Boolean = false,
    ): String {
        val negative = sompi < 0
        val absVal = kotlin.math.abs(sompi)
        val bd = BigDecimal.valueOf(absVal)
            .divide(BigDecimal.valueOf(SOMPI_PER_KAS), maxDecimals, RoundingMode.DOWN)
        val plain = if (trimTrailingZeros) {
            val stripped = bd.stripTrailingZeros()
            if (stripped.scale() < 2) stripped.setScale(2, RoundingMode.DOWN).toPlainString()
            else stripped.toPlainString()
        } else {
            bd.toPlainString()
        }
        val formatted = if (grouping) addThousandsSeparators(plain) else plain
        return if (negative) "-$formatted" else formatted
    }

    /**
     * Summary KAS formatter for Total Balance, Recent Transactions list, UTXOs, and Drawer:
     * thousands-separator commas + up to 4 decimal places, strictly truncating (RoundingMode.DOWN)
     * so displayed balances never overstate actual sompi holdings.
     */
    fun formatKasSummary(sompi: Long): String =
        formatKas(sompi = sompi, maxDecimals = 4, trimTrailingZeros = true, grouping = true)

    private fun addThousandsSeparators(plainDecimal: String): String {
        val dotIdx = plainDecimal.indexOf('.')
        val intPart = if (dotIdx >= 0) plainDecimal.substring(0, dotIdx) else plainDecimal
        val fracPart = if (dotIdx >= 0) plainDecimal.substring(dotIdx) else ""
        if (intPart.length <= 3) return plainDecimal
        val sb = StringBuilder(plainDecimal.length + intPart.length / 3)
        val rem = intPart.length % 3
        for (i in intPart.indices) {
            if (i > 0 && (i - rem) % 3 == 0) sb.append(',')
            sb.append(intPart[i])
        }
        sb.append(fracPart)
        return sb.toString()
    }

    fun formatFiat(sompi: Long, spot: SpotPrice?): String {
        if (spot == null || !spot.price.isFinite() || spot.price <= 0.0) return ""
        val kas = sompi.toDouble() / SOMPI_PER_KAS.toDouble()
        val value = kas * spot.price
        return when (spot.currency) {
            FiatCurrency.BTC -> {
                val formatted = String.format(java.util.Locale.US, "%.8f", value)
                "${spot.currency.symbol}$formatted"
            }
            FiatCurrency.JPY, FiatCurrency.KRW -> {
                val formatted = String.format(java.util.Locale.US, "%,.0f", value)
                "${spot.currency.symbol}$formatted"
            }
            else -> {
                val formatted = String.format(java.util.Locale.US, "%,.2f", value)
                "${spot.currency.symbol}$formatted"
            }
        }
    }

    fun formatSpotRate(spot: SpotPrice): String {
        val decimals = when (spot.currency) {
            FiatCurrency.BTC -> 8
            FiatCurrency.JPY, FiatCurrency.KRW -> 2
            else -> 4
        }
        return String.format(java.util.Locale.US, "%s%.${decimals}f", spot.currency.symbol, spot.price)
    }

    fun formatDurationSeconds(totalSeconds: Double): String {
        val secs = totalSeconds.coerceAtLeast(0.1)
        return when {
            secs < 60.0 -> String.format(java.util.Locale.US, "~%.1fs", secs)
            secs < 3600.0 -> String.format(java.util.Locale.US, "~%.1fm", secs / 60.0)
            secs < 86400.0 -> String.format(java.util.Locale.US, "~%.1fh", secs / 3600.0)
            else -> String.format(java.util.Locale.US, "~%.1fd", secs / 86400.0)
        }
    }
}
