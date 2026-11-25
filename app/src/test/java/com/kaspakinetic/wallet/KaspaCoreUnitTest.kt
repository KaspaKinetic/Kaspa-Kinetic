package com.kaspakinetic.wallet

import com.kaspakinetic.wallet.core.crypto.Bip39
import com.kaspakinetic.wallet.core.crypto.Bip39Wordlist
import com.kaspakinetic.wallet.core.crypto.KaspaAddress
import com.kaspakinetic.wallet.core.crypto.KaspaCrypto
import com.kaspakinetic.wallet.core.model.DUST_LIMIT_SOMPI
import com.kaspakinetic.wallet.core.model.DerivationKind
import com.kaspakinetic.wallet.core.model.KasFormat
import com.kaspakinetic.wallet.core.model.KaspaNetwork
import com.kaspakinetic.wallet.core.model.SOMPI_PER_KAS
import com.kaspakinetic.wallet.core.model.TxDirection
import com.kaspakinetic.wallet.core.model.UtxoItem
import com.kaspakinetic.wallet.core.model.WalletTx
import com.kaspakinetic.wallet.core.tx.KaspaMassAndTx
import com.kaspakinetic.wallet.data.preferences.ColorPalette
import com.kaspakinetic.wallet.data.preferences.KineticAeroAppearanceSettings
import com.kaspakinetic.wallet.data.preferences.KineticAeroDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 100% offline JVM unit test suite verifying Kaspa L1 cryptography, Bech32 encoding,
 * Kaspire mainnet vectors, Mainnet/Testnet-10 zero-point burn addresses, kpub/ktub watch-only
 * derivation, legacy 12-word m/44'/972 derivation, BIP-340 Schnorr & KIP-5 personal message
 * sign/verify, post-Toccata / KIP-9 mass rules, high-fee guards, Miner Donation split chaining,
 * and Kinetic Aero default Green palette compliance.
 */
class KaspaCoreUnitTest {

    private val testMnemonic12 =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun kineticAeroDefaultPaletteIsGreen() {
        assertEquals(ColorPalette.Green, KineticAeroAppearanceSettings().colorPalette)
        assertTrue(KineticAeroDefaults.DEFAULT_DISABLE_PROGRESSIVE_BLUR)
    }

    @Test
    fun bip39WordlistAndPrefixAutocomplete() {
        assertEquals(2048, Bip39Wordlist.ENGLISH.size)
        assertEquals("abandon", Bip39Wordlist.ENGLISH.first())
        assertEquals("zoo", Bip39Wordlist.ENGLISH.last())

        // Standard BIP-39 rule: every first-4-letter prefix uniquely identifies a single word
        assertEquals("abandon", Bip39.uniqueWordForPrefix("aban"))
        assertEquals("ability", Bip39.uniqueWordForPrefix("abil"))
        assertEquals("zoo", Bip39.uniqueWordForPrefix("zoo"))
        assertTrue(Bip39.suggestWords("ab").size >= 2)

        for (count in listOf(12, 15, 18, 21, 24)) {
            val phrase = Bip39.generateMnemonic(count)
            assertEquals(count, phrase.split(' ').size)
            assertTrue("Generated $count-word mnemonic must validate", Bip39.validateMnemonic(phrase))
        }
        assertFalse(Bip39.validateMnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"))
    }

    @Test
    fun kaspireMainnetVectorAndKpubWatchOnlyParity() {
        val seed = Bip39.mnemonicToSeed(testMnemonic12, "")
        val addr0 = KaspaCrypto.deriveAddressFromSeed(
            seed = seed,
            chain = 0,
            index = 0,
            kind = DerivationKind.STANDARD_111111,
            network = KaspaNetwork.MAINNET,
        )
        // Verified Kaspire / rusty-kaspa mainnet derivation vector
        assertEquals(
            "kaspa:qqd6e65yefepe9wk0m9vuxdufxd80sphy67gwwd0vdaumzdt4tc9s3qt0lqeh",
            addr0,
        )

        // kpub derivation must match seed derivation across receive (0/i) and change (1/i) chains!
        val kpubMainnet = KaspaCrypto.deriveKpubFromSeed(seed, DerivationKind.STANDARD_111111, KaspaNetwork.MAINNET)
        assertTrue(kpubMainnet.startsWith("kpub"))
        val ktubTestnet = KaspaCrypto.convertKpubForNetwork(kpubMainnet, KaspaNetwork.TESTNET_10)
        assertTrue(ktubTestnet.startsWith("ktub"))
        assertEquals(kpubMainnet, KaspaCrypto.normalizeKpubToMainnet(ktubTestnet))

        for (chain in 0..1) {
            for (idx in 0..4) {
                val fromSeedMain = KaspaCrypto.deriveAddressFromSeed(seed, chain, idx, DerivationKind.STANDARD_111111, KaspaNetwork.MAINNET)
                val fromKpubMain = KaspaCrypto.deriveAddressFromKpub(kpubMainnet, chain, idx, KaspaNetwork.MAINNET)
                assertEquals("Mainnet parity mismatch at $chain/$idx", fromSeedMain, fromKpubMain)

                val fromSeedTn10 = KaspaCrypto.deriveAddressFromSeed(seed, chain, idx, DerivationKind.STANDARD_111111, KaspaNetwork.TESTNET_10)
                val fromKtubTn10 = KaspaCrypto.deriveAddressFromKpub(ktubTestnet, chain, idx, KaspaNetwork.TESTNET_10)
                assertEquals("Testnet-10 parity mismatch at $chain/$idx", fromSeedTn10, fromKtubTn10)
                assertTrue(fromKtubTn10.startsWith("kaspatest:"))
            }
        }
    }

    @Test
    fun legacy12WordDerivationDistinctFromStandard() {
        val seed = Bip39.mnemonicToSeed(testMnemonic12, "")
        val std = KaspaCrypto.deriveAddressFromSeed(seed, 0, 0, DerivationKind.STANDARD_111111, KaspaNetwork.MAINNET)
        val kdx = KaspaCrypto.deriveAddressFromSeed(seed, 0, 0, DerivationKind.LEGACY_972_KDX, KaspaNetwork.MAINNET)
        val bip972 = KaspaCrypto.deriveAddressFromSeed(seed, 0, 0, DerivationKind.LEGACY_972_BIP44, KaspaNetwork.MAINNET)

        assertNotNull(KaspaAddress.decode(kdx))
        assertNotNull(KaspaAddress.decode(bip972))
        assertNotEquals(std, kdx)
        assertNotEquals(std, bip972)
    }

    @Test
    fun mainnetAndTestnetZeroPointBurnAddressesAreExact() {
        val mainnetBurn = KaspaAddress.burnAddress(KaspaNetwork.MAINNET)
        assertEquals(
            "kaspa:qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqkx9awp4e",
            mainnetBurn,
        )
        val decodedMain = KaspaAddress.decode(mainnetBurn)
        assertNotNull(decodedMain)
        assertEquals("kaspa", decodedMain!!.prefix)
        assertEquals(0.toByte(), decodedMain.version)
        assertTrue(decodedMain.payload.all { it == 0.toByte() })

        val testnetBurn = KaspaAddress.burnAddress(KaspaNetwork.TESTNET_10)
        assertTrue(testnetBurn.startsWith("kaspatest:qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"))
        val decodedTn = KaspaAddress.decode(testnetBurn)
        assertNotNull(decodedTn)
        assertEquals("kaspatest", decodedTn!!.prefix)
        assertEquals(0.toByte(), decodedTn.version)
        assertTrue(decodedTn.payload.all { it == 0.toByte() })

        // Highlight split check: prefix + middle + final 8 checksum characters
        val parts = KaspaAddress.splitForHighlight(mainnetBurn)
        assertEquals("kaspa:", parts.prefixWithColon)
        assertEquals("kx9awp4e", parts.checksum8)
        assertEquals(mainnetBurn, parts.prefixWithColon + parts.middleBody + parts.checksum8)
    }

    @Test
    fun bip340SchnorrAndKip5PersonalMessageSignAndVerify() {
        val seed = Bip39.mnemonicToSeed(testMnemonic12, "")
        val privKey = KaspaCrypto.derivePrivateKeyFromSeed(seed, 0, 0, DerivationKind.STANDARD_111111)
        val addr = KaspaCrypto.deriveAddressFromSeed(seed, 0, 0, DerivationKind.STANDARD_111111, KaspaNetwork.MAINNET)

        val message = "Kaspa Kinetic Pure L1 Wallet — Verified Message!"
        val sigHex = KaspaCrypto.signPersonalMessage(privKey, message)
        assertEquals(128, sigHex.length)

        assertTrue(KaspaCrypto.verifyPersonalMessage(addr, message, sigHex))
        assertFalse(KaspaCrypto.verifyPersonalMessage(addr, "$message tampered", sigHex))

        val otherAddr = KaspaCrypto.deriveAddressFromSeed(seed, 0, 1, DerivationKind.STANDARD_111111, KaspaNetwork.MAINNET)
        assertFalse(KaspaCrypto.verifyPersonalMessage(otherAddr, message, sigHex))
    }

    @Test
    fun postToccataMassAndTransactionSignAndPskt() {
        val seed = Bip39.mnemonicToSeed(testMnemonic12, "")
        val senderAddr = KaspaCrypto.deriveAddressFromSeed(seed, 0, 0, DerivationKind.STANDARD_111111, KaspaNetwork.MAINNET)
        val changeAddr = KaspaCrypto.deriveAddressFromSeed(seed, 1, 0, DerivationKind.STANDARD_111111, KaspaNetwork.MAINNET)
        val recipientAddr = KaspaCrypto.deriveAddressFromSeed(seed, 0, 1, DerivationKind.STANDARD_111111, KaspaNetwork.MAINNET)
        val senderSpkHex = KaspaCrypto.bytesToHex(KaspaAddress.decode(senderAddr)!!.toScriptPublicKey())

        val mockUtxo = UtxoItem(
            address = senderAddr,
            transactionId = "11".repeat(32),
            index = 0,
            amountSompi = 50L * SOMPI_PER_KAS,
            scriptPublicKeyHex = senderSpkHex,
            scriptPublicKeyVersion = 0,
            blockDaaScore = 100_000L,
            isCoinbase = false,
        )

        val prepared = KaspaMassAndTx.prepareSend(
            network = KaspaNetwork.MAINNET,
            availableUtxos = listOf(mockUtxo),
            recipientAddress = recipientAddr,
            changeAddress = changeAddr,
            amountSompi = 10L * SOMPI_PER_KAS,
            feeRate = 1.0,
        )
        assertEquals(10L * SOMPI_PER_KAS, prepared.amountSompi)
        assertTrue(prepared.feeSompi >= prepared.massBreakdown.normalizedMass)
        assertTrue(prepared.massBreakdown.computeMass > 0L)
        assertTrue(prepared.massBreakdown.transientMass > 0L)
        assertEquals(prepared.totalInputSompi, prepared.amountSompi + prepared.changeSompi + prepared.feeSompi)

        // Sign & verify non-malleable TransactionID + valid BIP-340 Schnorr signature inside script
        val signed = KaspaMassAndTx.signPreparedTransaction(prepared) {
            KaspaCrypto.derivePrivateKeyFromSeed(seed, 0, 0, DerivationKind.STANDARD_111111)
        }
        assertEquals(64, signed.transactionId.length)
        assertTrue(signed.submitJson.contains("\"transaction\""))
        assertTrue(signed.submitJson.contains("\"signatureScript\""))

        // Unsigned PSKT export for watch-only wallets
        val pskt = KaspaMassAndTx.exportUnsignedPsktJson(prepared, KaspaNetwork.MAINNET)
        assertTrue(pskt.contains("\"format\":\"kaspa-pskt-v1\""))
        assertTrue(pskt.contains(recipientAddr))

        // Dust rejection
        val dustError = runCatching {
            KaspaMassAndTx.prepareSend(
                network = KaspaNetwork.MAINNET,
                availableUtxos = listOf(mockUtxo),
                recipientAddress = recipientAddr,
                changeAddress = changeAddr,
                amountSompi = DUST_LIMIT_SOMPI - 1L,
                feeRate = 1.0,
            )
        }.exceptionOrNull()
        assertNotNull("Dust send should be rejected", dustError)
    }

    @Test
    fun minerDonationSplitExplicitlyBypassesNormalHighFeeGuard() {
        val seed = Bip39.mnemonicToSeed(testMnemonic12, "")
        val selfAddr = KaspaCrypto.deriveAddressFromSeed(seed, 0, 0, DerivationKind.STANDARD_111111, KaspaNetwork.MAINNET)
        val selfSpkHex = KaspaCrypto.bytesToHex(KaspaAddress.decode(selfAddr)!!.toScriptPublicKey())

        val mockUtxo = UtxoItem(
            address = selfAddr,
            transactionId = "22".repeat(32),
            index = 0,
            amountSompi = 100L * SOMPI_PER_KAS,
            scriptPublicKeyHex = selfSpkHex,
            scriptPublicKeyVersion = 0,
            blockDaaScore = 100_000L,
            isCoinbase = false,
        )

        // Normal send with 10 KAS fee rate would trigger high-fee typo guard,
        // whereas prepareMinerDonationSplit intentionally pays 5 KAS as pure miner fee!
        val donationTx = KaspaMassAndTx.prepareMinerDonationSplit(
            network = KaspaNetwork.MAINNET,
            availableUtxos = listOf(mockUtxo),
            selfAddress = selfAddr,
            donationFeeSompi = 5L * SOMPI_PER_KAS,
            minNetworkFeeRate = 1.0,
        )
        assertTrue(donationTx.isMinerDonation)
        assertEquals(selfAddr, donationTx.recipientAddress)
        assertEquals(5L * SOMPI_PER_KAS, donationTx.feeSompi)
        assertEquals(95L * SOMPI_PER_KAS, donationTx.amountSompi)
        assertEquals(0L, donationTx.changeSompi)
    }

    @Test
    fun compoundUtxosBuildsSingleOutputCleanly() {
        val seed = Bip39.mnemonicToSeed(testMnemonic12, "")
        val addr = KaspaCrypto.deriveAddressFromSeed(seed, 0, 0, DerivationKind.STANDARD_111111, KaspaNetwork.MAINNET)
        val spkHex = KaspaCrypto.bytesToHex(KaspaAddress.decode(addr)!!.toScriptPublicKey())

        val utxos = (0..3).map { i ->
            UtxoItem(
                address = addr,
                transactionId = "${i + 1}0".repeat(32),
                index = i,
                amountSompi = 2L * SOMPI_PER_KAS,
                scriptPublicKeyHex = spkHex,
            )
        }
        val compound = KaspaMassAndTx.prepareCompound(
            network = KaspaNetwork.MAINNET,
            utxosToCompound = utxos,
            destinationAddress = addr,
            feeRate = 1.0,
        )
        assertTrue(compound.isCompound)
        assertEquals(4, compound.selectedUtxos.size)
        assertEquals(0L, compound.changeSompi)
        assertEquals(8L * SOMPI_PER_KAS - compound.feeSompi, compound.amountSompi)
    }

    @Test
    fun kasFormatAndUriParsing() {
        assertEquals("1.00", KasFormat.formatKas(1L * SOMPI_PER_KAS))
        assertEquals("0.12345678", KasFormat.formatKas(12_345_678L))
        assertEquals(150_000_000L, KasFormat.kasStringToSompi("1.5"))
        assertNull(KasFormat.kasStringToSompi("-5"))

        val mainnetBurn = KaspaAddress.burnAddress(KaspaNetwork.MAINNET)
        val parsed = KaspaAddress.parseUriOrAddress("$mainnetBurn?amount=2.5")
        assertNotNull(parsed)
        assertEquals(mainnetBurn, parsed!!.address)
        assertEquals(0, parsed.amountKas!!.compareTo(java.math.BigDecimal("2.5")))
    }

    @Test
    fun btcCurrencyFormattingAndDurationHelpers() {
        val btcSpot = com.kaspakinetic.wallet.core.model.SpotPrice(
            currency = com.kaspakinetic.wallet.core.model.FiatCurrency.BTC,
            price = 0.00000142,
        )
        assertEquals("₿0.00014200", KasFormat.formatFiat(100L * SOMPI_PER_KAS, btcSpot))
        assertEquals("₿0.00000142", KasFormat.formatSpotRate(btcSpot))
        assertEquals("~2.5s", KasFormat.formatDurationSeconds(2.5))
        assertEquals("~15.0m", KasFormat.formatDurationSeconds(900.0))
        assertEquals("~2.0h", KasFormat.formatDurationSeconds(7200.0))
        assertEquals("~2.5d", KasFormat.formatDurationSeconds(216000.0))
    }

    @Test
    fun diceRollEntropyGeneratesValidDeterministicMnemonics() {
        val rolls50 = List(50) { (it % 6) + 1 }
        val phrase12 = Bip39.mnemonicFromDiceRolls(rolls50, wordCount = 12)
        assertEquals(12, phrase12.split(' ').size)
        assertTrue(Bip39.validateMnemonic(phrase12))
        assertEquals(phrase12, Bip39.mnemonicFromDiceRolls(rolls50, wordCount = 12))

        val rolls99 = List(99) { ((it * 5) % 6) + 1 }
        val phrase24 = Bip39.mnemonicFromDiceRolls(rolls99, wordCount = 24)
        assertEquals(24, phrase24.split(' ').size)
        assertTrue(Bip39.validateMnemonic(phrase24))

        val tooFew = runCatching { Bip39.mnemonicFromDiceRolls(listOf(1, 2, 3), wordCount = 12) }.exceptionOrNull()
        assertNotNull(tooFew)
    }

    @Test
    fun simplifiedChineseLocalizationCoversSettingsBurnAndDonate() {
        val zh = com.kaspakinetic.wallet.ui.locale.resolveKaspaWalletStrings(java.util.Locale.SIMPLIFIED_CHINESE)
        assertEquals("货币与隐私", zh.settingsCurrencySection)
        assertEquals("销毁 Kaspa", zh.burnTitle)
        assertEquals("添加钱包", zh.addWalletButton)
        assertFalse(zh.addWalletButton.startsWith("+"))
        assertEquals("后台持续运行", zh.donateBackgroundTitle)
        assertEquals("检测到截屏", zh.screenshotWarningTitle)
    }

    @Test
    fun traditionalChineseOnboardingIsFullyTranslatedWithoutDuplicateDiceBadge() {
        val zhTw = com.kaspakinetic.wallet.ui.locale.resolveKaspaWalletStrings(java.util.Locale.TRADITIONAL_CHINESE)
        val en = com.kaspakinetic.wallet.ui.locale.KaspaWalletStrings()
        assertNotEquals(en.safetyFirstBody, zhTw.safetyFirstBody)
        assertNotEquals(en.recoveryPhraseSubtitle, zhTw.recoveryPhraseSubtitle)
        assertNotEquals(en.backupConfirmBody, zhTw.backupConfirmBody)
        assertFalse(
            "diceEntropyToggleLabel should not duplicate '進階' already present in diceEntropyBadge",
            zhTw.diceEntropyToggleLabel.contains("進階"),
        )
        assertTrue(zhTw.diceEntropyBadge.contains("進階"))
    }

    @Test
    fun walletRecordPersistsCachedBalanceAndScanFlag() {
        val sampleAddr = KaspaAddress.burnAddress(KaspaNetwork.MAINNET)
        val record = com.kaspakinetic.wallet.core.model.WalletRecord(
            id = "w-test",
            name = "Vault A",
            mode = com.kaspakinetic.wallet.core.model.WalletMode.HOT_HD,
            primaryAddressMainnet = sampleAddr,
            cachedBalanceSompi = 123_456_789_000L,
            hasCompletedInitialScan = true,
        )
        assertEquals(123_456_789_000L, record.cachedBalanceSompi)
        assertTrue(record.hasCompletedInitialScan)

        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val encoded = json.encodeToString(com.kaspakinetic.wallet.core.model.WalletRecord.serializer(), record)
        val decoded = json.decodeFromString(com.kaspakinetic.wallet.core.model.WalletRecord.serializer(), encoded)
        assertEquals(123_456_789_000L, decoded.cachedBalanceSompi)
        assertTrue(decoded.hasCompletedInitialScan)

        val fresh = com.kaspakinetic.wallet.core.model.WalletRecord(
            id = "w-fresh",
            name = "Fresh",
            mode = com.kaspakinetic.wallet.core.model.WalletMode.HOT_HD,
            primaryAddressMainnet = sampleAddr,
        )
        assertEquals(0L, fresh.cachedBalanceSompi)
        assertFalse(fresh.hasCompletedInitialScan)
    }

    @Test
    fun standardBip32XpubAndKaspaKpubBothDecodeAndDeriveIdenticalAddresses() {
        val seed = Bip39.mnemonicToSeed(testMnemonic12, "")
        val master = KaspaCrypto.HdNode.fromSeed(seed)
        val account = master.derivePath("m/44'/111111'/0'")
        val kpub = account.toExtendedPublicKey(KaspaNetwork.MAINNET)
        val xpub = account.toExtendedPublicKeyWithVersion(KaspaCrypto.XPUB_BITCOIN_VERSION)
        val tpub = account.toExtendedPublicKeyWithVersion(KaspaCrypto.TPUB_BITCOIN_VERSION)
        val zpub = account.toExtendedPublicKeyWithVersion(KaspaCrypto.ZPUB_SLIP132_VERSION)
        assertTrue(kpub.startsWith("kpub"))
        assertTrue(xpub.startsWith("xpub"))
        assertTrue(tpub.startsWith("tpub"))
        assertTrue(zpub.startsWith("zpub"))

        // Ledger-style xpub + dirty wrapped clipboard/JSON/descriptor pastes all normalize cleanly to kpub
        assertEquals(kpub, KaspaCrypto.normalizeKpubToMainnet(xpub))
        assertEquals(kpub, KaspaCrypto.normalizeKpubToMainnet("  $xpub \n "))
        assertEquals(kpub, KaspaCrypto.normalizeKpubToMainnet("\"xpub\": \"$xpub\""))
        assertEquals(kpub, KaspaCrypto.normalizeKpubToMainnet("[a1b2c3d4/44'/111111'/0']$xpub/0/*"))
        assertEquals(kpub, KaspaCrypto.normalizeKpubToMainnet(tpub))
        assertEquals(kpub, KaspaCrypto.normalizeKpubToMainnet(zpub))

        // Both xpub and kpub derive the exact verified Kaspire mainnet receive/change addresses
        val expectedAddr0 = "kaspa:qqd6e65yefepe9wk0m9vuxdufxd80sphy67gwwd0vdaumzdt4tc9s3qt0lqeh"
        assertEquals(expectedAddr0, KaspaCrypto.deriveAddressFromKpub(xpub, chain = 0, index = 0, network = KaspaNetwork.MAINNET))
        assertEquals(expectedAddr0, KaspaCrypto.deriveAddressFromKpub(kpub, chain = 0, index = 0, network = KaspaNetwork.MAINNET))
        assertEquals(
            KaspaCrypto.deriveAddressFromSeed(seed, chain = 1, index = 3, kind = DerivationKind.STANDARD_111111, network = KaspaNetwork.MAINNET),
            KaspaCrypto.deriveAddressFromKpub(xpub, chain = 1, index = 3, network = KaspaNetwork.MAINNET),
        )

        // Private extended keys (xprv) and corrupted checksums must be rejected
        val badChecksum = xpub.dropLast(1) + if (xpub.last() == 'a') 'b' else 'a'
        assertFalse(KaspaCrypto.isValidExtendedPublicKey(badChecksum))
        assertFalse(KaspaCrypto.isValidExtendedPublicKey("xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi"))
    }

    @Test
    fun donationSupportPopupStringsAreLocalizedAcrossAllLocales() {
        val en = com.kaspakinetic.wallet.ui.locale.KaspaWalletStrings()
        assertEquals(
            "Enjoying the app? Please consider donating to help me maintain the app for you <3",
            en.supportPopupMessage,
        )
        assertEquals("We won't bother you again!", en.supportWontBotherAgain)
        assertEquals("App feels laggy? You can disable transparency here:", en.supportLagTipPrompt)
        assertEquals("Bugs or glitches? Contact me on X/Twitter @KaspaKinetic", en.supportBugContact)
        assertNotNull(KaspaAddress.decode(com.kaspakinetic.wallet.ui.components.DEVELOPER_DONATION_KASPA_ADDRESS))

        val locales = listOf(
            java.util.Locale.SIMPLIFIED_CHINESE,
            java.util.Locale.TRADITIONAL_CHINESE,
            java.util.Locale.JAPANESE,
            java.util.Locale.KOREAN,
            java.util.Locale("ar"),
            java.util.Locale("es"),
            java.util.Locale.FRENCH,
            java.util.Locale.GERMAN,
            java.util.Locale("pt"),
            java.util.Locale("ru"),
        )
        for (loc in locales) {
            val s = com.kaspakinetic.wallet.ui.locale.resolveKaspaWalletStrings(loc)
            assertNotEquals("Missing supportPopupMessage for $loc", en.supportPopupMessage, s.supportPopupMessage)
            assertNotEquals("Missing supportLagTipPrompt for $loc", en.supportLagTipPrompt, s.supportLagTipPrompt)
            assertNotEquals("Missing supportBugContact for $loc", en.supportBugContact, s.supportBugContact)
            assertTrue("Contact handle @KaspaKinetic missing for $loc", s.supportBugContact.contains("@KaspaKinetic"))
            assertNotEquals("Missing kpubHelperTitle for $loc", en.kpubHelperTitle, s.kpubHelperTitle)
            assertNotEquals("Missing kpubHelperFormatsBody for $loc", en.kpubHelperFormatsBody, s.kpubHelperFormatsBody)
            assertNotEquals("Missing kpubHelperPasteTip for $loc", en.kpubHelperPasteTip, s.kpubHelperPasteTip)
            assertNotEquals("Missing kpubHelperLedgerTip for $loc", en.kpubHelperLedgerTip, s.kpubHelperLedgerTip)
        }
    }

    @Test
    fun ledgerRawApduHexXpubParsesAndDerivesVerifiedKaspaAddresses() {
        val seed = Bip39.mnemonicToSeed(testMnemonic12, "")
        val account = KaspaCrypto.HdNode.fromSeed(seed).derivePath("m/44'/111111'/0'")
        // Construct exact 99-byte (198-char) Ledger Wallet APDU GET_PUBLIC_KEY hex response:
        // 0x41 + 65-byte uncompressed pubkey (0x04 + X + Y) + 0x20 + 32-byte chainCode
        val curve = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256k1").curve
        val uncompressed65 = curve.decodePoint(account.compressedPublicKey).normalize().getEncoded(false)
        assertEquals(65, uncompressed65.size)
        assertEquals(0x04.toByte(), uncompressed65[0])

        val apdu99 = ByteArray(99)
        apdu99[0] = 0x41
        uncompressed65.copyInto(apdu99, destinationOffset = 1)
        apdu99[66] = 0x20
        account.chainCode.copyInto(apdu99, destinationOffset = 67)
        val ledgerHex = KaspaCrypto.bytesToHex(apdu99)
        assertEquals(198, ledgerHex.length)
        assertTrue(ledgerHex.startsWith("4104"))

        assertTrue(KaspaCrypto.isValidExtendedPublicKey(ledgerHex))
        assertTrue(KaspaCrypto.isValidExtendedPublicKey(ledgerHex.uppercase()))
        assertTrue(KaspaCrypto.isValidExtendedPublicKey("\"xpub\": \" $ledgerHex \n\""))

        // Normalized kpub from Ledger raw hex must derive identical receive (0/i) and change (1/i) addresses!
        val normalizedKpub = KaspaCrypto.normalizeKpubToMainnet(ledgerHex)
        assertTrue(normalizedKpub.startsWith("kpub"))
        val expectedAddr0 = "kaspa:qqd6e65yefepe9wk0m9vuxdufxd80sphy67gwwd0vdaumzdt4tc9s3qt0lqeh"
        assertEquals(expectedAddr0, KaspaCrypto.deriveAddressFromKpub(ledgerHex, chain = 0, index = 0, network = KaspaNetwork.MAINNET))
        assertEquals(expectedAddr0, KaspaCrypto.deriveAddressFromKpub(normalizedKpub, chain = 0, index = 0, network = KaspaNetwork.MAINNET))
        for (idx in 0..3) {
            assertEquals(
                KaspaCrypto.deriveAddressFromSeed(seed, chain = 0, index = idx, kind = DerivationKind.STANDARD_111111, network = KaspaNetwork.MAINNET),
                KaspaCrypto.deriveAddressFromKpub(normalizedKpub, chain = 0, index = idx, network = KaspaNetwork.MAINNET),
            )
            assertEquals(
                KaspaCrypto.deriveAddressFromSeed(seed, chain = 1, index = idx, kind = DerivationKind.STANDARD_111111, network = KaspaNetwork.MAINNET),
                KaspaCrypto.deriveAddressFromKpub(normalizedKpub, chain = 1, index = idx, network = KaspaNetwork.MAINNET),
            )
        }

        // Malformed markers or wrong length must be rejected
        val badLengthMarker = "42" + ledgerHex.substring(2)
        val badPubPrefix = "4105" + ledgerHex.substring(4)
        val badChainMarker = ledgerHex.substring(0, 132) + "21" + ledgerHex.substring(134)
        assertFalse(KaspaCrypto.isValidExtendedPublicKey(badLengthMarker))
        assertFalse(KaspaCrypto.isValidExtendedPublicKey(badPubPrefix))
        assertFalse(KaspaCrypto.isValidExtendedPublicKey(badChainMarker))
        assertFalse(KaspaCrypto.isValidExtendedPublicKey(ledgerHex.dropLast(2)))
    }

    @Test
    fun kasSummaryFormattingUsesCommasMax4DecimalsAndTruncatesDown() {
        // Exact prompt example: 297185.9172696 -> 297,185.9172 (strictly truncates down, never rounds up!)
        val sampleSompi = 29_718_591_726_960L
        assertEquals("297,185.9172", KasFormat.formatKasSummary(sampleSompi))
        // Full sompi precision preserved for Transaction Details modal
        assertEquals("297,185.9172696", KasFormat.formatKas(sampleSompi, maxDecimals = 8, grouping = true))

        // Rounding-down guard: 1234.99999999 KAS must truncate to 1,234.9999, NEVER round up to 1,235.00
        assertEquals("1,234.9999", KasFormat.formatKasSummary(123_499_999_999L))
        assertEquals("1,000,000.00", KasFormat.formatKasSummary(1_000_000L * SOMPI_PER_KAS))
        assertEquals("0.00", KasFormat.formatKasSummary(0L))
        assertEquals("12.50", KasFormat.formatKasSummary(1_250_000_000L))
    }

    @Test
    fun transactionMergeNeverWipesExistingHistoryAndUpgradesOptimisticEntries() {
        val confirmedTx = com.kaspakinetic.wallet.core.model.WalletTx(
            transactionId = "tx_confirmed_1",
            blockTimeMs = 1_700_000_000_000L,
            isAccepted = true,
            acceptingBlockBlueScore = 100L,
            direction = com.kaspakinetic.wallet.core.model.TxDirection.RECEIVED,
            netAmountSompi = 5L * SOMPI_PER_KAS,
            feeSompi = 0L,
            inputs = listOf(com.kaspakinetic.wallet.core.model.TxInputSummary("prev", 0, "kaspa:sender", 5L * SOMPI_PER_KAS)),
        )
        // Transient empty fetch MUST NEVER wipe existing transactions
        val afterEmpty = com.kaspakinetic.wallet.viewmodel.WalletViewModel.mergeTransactions(
            existing = listOf(confirmedTx),
            incoming = emptyList(),
        )
        assertEquals(1, afterEmpty.size)
        assertEquals("tx_confirmed_1", afterEmpty.first().transactionId)

        // Instant optimistic detected receive from UTXO diff appears immediately (with real 64-char hex txId)
        val addr = KaspaAddress.burnAddress(KaspaNetwork.MAINNET)
        val liveTxId = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val newUtxo = UtxoItem(
            address = addr,
            transactionId = liveTxId,
            index = 0,
            amountSompi = 25L * SOMPI_PER_KAS,
            scriptPublicKeyHex = "00",
        )
        val synthesized = com.kaspakinetic.wallet.viewmodel.WalletViewModel.synthesizeDetectedTxsFromUtxoDiff(
            prevUtxos = emptyList(),
            newUtxos = listOf(newUtxo),
            existingTxIds = setOf("tx_confirmed_1"),
            nowMs = 1_700_000_050_000L,
        )
        assertEquals(1, synthesized.size)
        assertEquals(liveTxId, synthesized.first().transactionId)
        assertEquals(com.kaspakinetic.wallet.core.model.TxDirection.RECEIVED, synthesized.first().direction)
        assertEquals(25L * SOMPI_PER_KAS, synthesized.first().netAmountSompi)
        assertFalse(synthesized.first().isAccepted)

        val withOptimistic = com.kaspakinetic.wallet.viewmodel.WalletViewModel.mergeTransactions(afterEmpty, synthesized)
        assertEquals(2, withOptimistic.size)
        assertEquals(liveTxId, withOptimistic.first().transactionId)

        // When full confirmed tx arrives, it upgrades the optimistic entry in place without losing tx_confirmed_1
        val fullLiveReceive = synthesized.first().copy(
            isAccepted = true,
            acceptingBlockBlueScore = 200L,
            inputs = listOf(com.kaspakinetic.wallet.core.model.TxInputSummary("p2", 0, "kaspa:other", 25L * SOMPI_PER_KAS)),
        )
        val upgraded = com.kaspakinetic.wallet.viewmodel.WalletViewModel.mergeTransactions(withOptimistic, listOf(fullLiveReceive))
        assertEquals(2, upgraded.size)
        assertTrue(upgraded.first().isAccepted)
        assertEquals(200L, upgraded.first().acceptingBlockBlueScore)
        assertEquals("tx_confirmed_1", upgraded[1].transactionId)

        // Full spend without a new change UTXO must NEVER fabricate a broken pending_spend_* placeholder!
        val fullSpendSynthesized = com.kaspakinetic.wallet.viewmodel.WalletViewModel.synthesizeDetectedTxsFromUtxoDiff(
            prevUtxos = listOf(newUtxo),
            newUtxos = emptyList(),
            existingTxIds = emptySet(),
            nowMs = 1_700_000_090_000L,
        )
        assertTrue(fullSpendSynthesized.isEmpty())

        // Legacy pending_* broken rows cached from earlier versions must be purged on merge
        val legacyBroken = com.kaspakinetic.wallet.core.model.WalletTx(
            transactionId = "pending_spend_1700000000_1",
            blockTimeMs = 1_700_000_099_000L,
            isAccepted = false,
            direction = com.kaspakinetic.wallet.core.model.TxDirection.SENT,
            netAmountSompi = 10L * SOMPI_PER_KAS,
            feeSompi = 0L,
        )
        val purged = com.kaspakinetic.wallet.viewmodel.WalletViewModel.mergeTransactions(
            existing = listOf(legacyBroken, confirmedTx),
            incoming = emptyList(),
        )
        assertEquals(1, purged.size)
        assertEquals("tx_confirmed_1", purged.first().transactionId)
    }

    @Test
    fun errorSanitizerStripsRawHtmlAndPreservesUsefulText() {
        val raw429Html = """
            <html><body><h1>429 Too Many Requests</h1>
            You have sent too many requests in a given amount of time.
            </body></html>
        """.trimIndent()
        val clean = com.kaspakinetic.wallet.core.network.KaspaErrorSanitizer.sanitize(raw429Html, httpCode = 429)
        assertFalse("Sanitized error must not contain HTML tags: $clean", clean.contains("<") || clean.contains(">"))
        assertTrue("Expected rate-limit hint in: $clean", clean.contains("429") || clean.lowercase().contains("rate"))

        val raw502Html = "<!DOCTYPE html><html><head><title>502 Bad Gateway</title></head><body><h1>502 Bad Gateway</h1></body></html>"
        val clean502 = com.kaspakinetic.wallet.core.network.KaspaErrorSanitizer.sanitize(raw502Html, httpCode = 502)
        assertFalse(clean502.contains("<"))
        assertTrue(clean502.contains("502"))

        val plainRpcError = "transaction rejected: orphan input"
        assertEquals(plainRpcError, com.kaspakinetic.wallet.core.network.KaspaErrorSanitizer.sanitize(plainRpcError))
    }

    @Test
    fun nodeConfigResolvesPublicAndCustomGrpcEndpointsCorrectly() {
        val publicMain = com.kaspakinetic.wallet.core.model.NodeConfig(
            network = KaspaNetwork.MAINNET,
            useCustomNode = false,
        )
        assertEquals(Triple("node.kaspium.io", 443, true), publicMain.resolvedGrpcEndpoint())
        assertEquals("node.kaspium.io:443 (TLS)", publicMain.effectiveGrpcDisplay)

        val customLan = com.kaspakinetic.wallet.core.model.NodeConfig(
            network = KaspaNetwork.MAINNET,
            useCustomNode = true,
            customGrpcHostPort = "192.168.1.50:16110",
            customGrpcUseTls = false,
        )
        assertEquals(Triple("192.168.1.50", 16110, false), customLan.resolvedGrpcEndpoint())
        assertEquals("192.168.1.50:16110 (plaintext)", customLan.effectiveGrpcDisplay)

        val customTlsScheme = com.kaspakinetic.wallet.core.model.NodeConfig(
            network = KaspaNetwork.MAINNET,
            useCustomNode = true,
            customGrpcHostPort = "grpcs://my.kaspa.node:443",
            customGrpcUseTls = false,
        )
        assertEquals(Triple("my.kaspa.node", 443, true), customTlsScheme.resolvedGrpcEndpoint())

        val customBareHost = com.kaspakinetic.wallet.core.model.NodeConfig(
            network = KaspaNetwork.MAINNET,
            useCustomNode = true,
            customGrpcHostPort = "kaspa.local",
            customGrpcUseTls = false,
        )
        assertEquals(Triple("kaspa.local", 16110, false), customBareHost.resolvedGrpcEndpoint())
    }

    @Test
    fun kaspadProtoCodecEncodesRequestsAndDecodesResponsesBitAccurately() {
        // 1. Request encoding sanity
        val infoReq = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.encodeGetServerInfoRequest(id = 7L)
        assertTrue(infoReq.isNotEmpty())
        val utxoReq = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.encodeGetUtxosByAddressesRequest(
            id = 42L,
            addresses = listOf(KaspaAddress.burnAddress(KaspaNetwork.MAINNET)),
        )
        assertTrue(utxoReq.size > 40)

        // 2. SubmitTransaction encoding from real signed Kaspa tx JSON
        val seed = Bip39.mnemonicToSeed(testMnemonic12, "")
        val rxAddr = KaspaCrypto.deriveAddressFromSeed(seed, 0, 0, DerivationKind.STANDARD_111111, KaspaNetwork.MAINNET)
        val chAddr = KaspaCrypto.deriveAddressFromSeed(seed, 1, 0, DerivationKind.STANDARD_111111, KaspaNetwork.MAINNET)
        val burnAddr = KaspaAddress.burnAddress(KaspaNetwork.MAINNET)
        val utxo = UtxoItem(
            address = rxAddr,
            transactionId = "11".repeat(32),
            index = 0,
            amountSompi = 50L * SOMPI_PER_KAS,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(KaspaAddress.decode(rxAddr)!!.toScriptPublicKey()),
        )
        val prepared = KaspaMassAndTx.prepareSend(
            network = KaspaNetwork.MAINNET,
            availableUtxos = listOf(utxo),
            recipientAddress = burnAddr,
            changeAddress = chAddr,
            amountSompi = 10L * SOMPI_PER_KAS,
            feeRate = 1.0,
        )
        val signed = KaspaMassAndTx.signPreparedTransaction(prepared) {
            KaspaCrypto.derivePrivateKeyFromSeed(seed, 0, 0, DerivationKind.STANDARD_111111)
        }
        val submitWire = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.encodeSubmitTransactionRequest(
            id = 99L,
            submitJson = signed.submitJson,
        )
        assertTrue("Encoded SubmitTransaction protobuf should be non-trivial", submitWire.size > 150)

        // 3. Round-trip decode of synthetic KaspadResponse frames:
        // Build a VirtualDaaScoreChangedNotification (tag 1076) with virtualDaaScore = 123456789
        val notifBody = encodeVarintFieldForTest(1, 123_456_789L)
        val daaResponseWire = encodeLengthDelimitedFieldForTest(1076, notifBody)
        val parsedDaa = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.decodeResponse(daaResponseWire)
        assertTrue(parsedDaa.payload is com.kaspakinetic.wallet.core.network.KaspadProtoCodec.Payload.VirtualDaaScoreChanged)
        assertEquals(
            123_456_789L,
            (parsedDaa.payload as com.kaspakinetic.wallet.core.network.KaspadProtoCodec.Payload.VirtualDaaScoreChanged).virtualDaaScore,
        )

        // Build a GetServerInfoResponse (tag 1093) matching rpc.proto:
        // 3 = serverVersion, 4 = networkId, 5 = hasUtxoIndex, 6 = isSynced, 7 = virtualDaaScore
        val serverInfoBody = concatBytes(
            encodeStringFieldForTest(3, "0.15.2"),
            encodeStringFieldForTest(4, "mainnet"),
            encodeVarintFieldForTest(5, 1L),
            encodeVarintFieldForTest(6, 1L),
            encodeVarintFieldForTest(7, 987_654L),
        )
        val serverInfoWire = concatBytes(
            encodeVarintFieldForTest(101, 7L),
            encodeLengthDelimitedFieldForTest(1093, serverInfoBody),
        )
        val parsedInfo = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.decodeResponse(serverInfoWire)
        assertEquals(7L, parsedInfo.id)
        assertTrue(parsedInfo.payload is com.kaspakinetic.wallet.core.network.KaspadProtoCodec.Payload.ServerInfo)
        val info = parsedInfo.payload as com.kaspakinetic.wallet.core.network.KaspadProtoCodec.Payload.ServerInfo
        assertTrue(info.health.isSynced)
        assertTrue(info.health.isUtxoIndexed)
        assertEquals(987_654L, info.health.virtualDaaScore)
    }

    @Test
    fun watchOnlyAndGrpcNodeStringsAreTranslatedAcrossAll10Locales() {
        val en = com.kaspakinetic.wallet.ui.locale.KaspaWalletStrings()
        val locales = listOf(
            java.util.Locale.SIMPLIFIED_CHINESE,
            java.util.Locale.TRADITIONAL_CHINESE,
            java.util.Locale.JAPANESE,
            java.util.Locale.KOREAN,
            java.util.Locale("ar"),
            java.util.Locale("es"),
            java.util.Locale.FRENCH,
            java.util.Locale.GERMAN,
            java.util.Locale("pt"),
            java.util.Locale("ru"),
        )
        for (loc in locales) {
            val s = com.kaspakinetic.wallet.ui.locale.resolveKaspaWalletStrings(loc)
            assertNotEquals("Missing watchOnlyBanner for $loc", en.watchOnlyBanner, s.watchOnlyBanner)
            assertNotEquals("Missing watchOnlyBannerSubtitle for $loc", en.watchOnlyBannerSubtitle, s.watchOnlyBannerSubtitle)
            assertNotEquals("Missing watchOnlySendTitle for $loc", en.watchOnlySendTitle, s.watchOnlySendTitle)
            assertNotEquals("Missing watchOnlySendBody for $loc", en.watchOnlySendBody, s.watchOnlySendBody)
            assertNotEquals("Missing burnWatchOnlyNotice for $loc", en.burnWatchOnlyNotice, s.burnWatchOnlyNotice)
            assertNotEquals("Missing donateWatchOnlyNotice for $loc", en.donateWatchOnlyNotice, s.donateWatchOnlyNotice)
            assertNotEquals("Missing compoundWatchOnlyNotice for $loc", en.compoundWatchOnlyNotice, s.compoundWatchOnlyNotice)
            assertNotEquals("Missing signMsgWatchOnlyNotice for $loc", en.signMsgWatchOnlyNotice, s.signMsgWatchOnlyNotice)
            assertNotEquals("Missing customGrpcLabel for $loc", en.customGrpcLabel, s.customGrpcLabel)
            assertNotEquals("Missing customGrpcTlsTitle for $loc", en.customGrpcTlsTitle, s.customGrpcTlsTitle)
            assertNotEquals("Missing customGrpcTlsDesc for $loc", en.customGrpcTlsDesc, s.customGrpcTlsDesc)
        }
    }

    @Test
    fun utxoReconcilerPreventsDoubleCountedBalanceAndClearsPendingDaaZeroDuplicates() {
        val reconciler = com.kaspakinetic.wallet.core.model.UtxoReconciler()
        val myAddr = KaspaAddress.burnAddress(KaspaNetwork.MAINNET)
        val walletSet = setOf(myAddr)
        val now = 1_700_000_000_000L

        val initialUtxo = UtxoItem(
            address = myAddr,
            transactionId = "aa".repeat(32),
            index = 0,
            amountSompi = 3_451_590_000L, // 34.5159 KAS
            scriptPublicKeyHex = "00",
            blockDaaScore = 598_263_730L,
        )
        val step0 = reconciler.applyFullSnapshot(listOf(initialUtxo), walletSet, now)
        assertEquals(1, step0.size)
        assertEquals(3_451_590_000L, step0.sumOf { it.amountSompi })

        // Split #1 broadcasts: spends initialUtxo (34.5159), returns change1 (34.4359, Block DAA: 0)
        val change1 = UtxoItem(
            address = myAddr,
            transactionId = "bb".repeat(32),
            index = 0,
            amountSompi = 3_443_590_000L, // 34.4359 KAS
            scriptPublicKeyHex = "00",
            blockDaaScore = 0L,
        )
        val step1 = reconciler.recordLocalBroadcast(
            spentOutpoints = listOf(initialUtxo.outpointKey),
            optimisticChangeOutputs = listOf(change1),
            nowMs = now + 100L,
        )
        assertEquals(1, step1.size)
        assertEquals(3_443_590_000L, step1.sumOf { it.amountSompi })
        assertEquals(0L, step1.first().blockDaaScore)

        // Stale GetUtxosByAddresses snapshot arrives while split #1 is still in mempool (still has initialUtxo):
        // MUST NOT double-count (never 68.9518 KAS!) — initialUtxo stays excluded, change1 stays sole UTXO!
        val step2 = reconciler.applyFullSnapshot(listOf(initialUtxo), walletSet, now + 300L)
        assertEquals(1, step2.size)
        assertEquals(change1.outpointKey, step2.first().outpointKey)
        assertEquals(3_443_590_000L, step2.sumOf { it.amountSompi })

        // Split #2 rapidly chains off change1 BEFORE split #1 confirms: spends change1, returns change2 (34.3559, DAA 0)
        val change2 = UtxoItem(
            address = myAddr,
            transactionId = "cc".repeat(32),
            index = 0,
            amountSompi = 3_435_590_000L, // 34.3559 KAS
            scriptPublicKeyHex = "00",
            blockDaaScore = 0L,
        )
        val step3 = reconciler.recordLocalBroadcast(
            spentOutpoints = listOf(change1.outpointKey),
            optimisticChangeOutputs = listOf(change2),
            nowMs = now + 500L,
        )
        assertEquals(1, step3.size)
        assertEquals(change2.outpointKey, step3.first().outpointKey)
        assertEquals(3_435_590_000L, step3.sumOf { it.amountSompi })

        // Late NotifyUtxosChanged for split #1 arrives (removed=initialUtxo, added=change1 confirmed at DAA 598263735):
        // Because change1 was ALREADY spent by split #2, change1 MUST NOT reappear or double-count balance!
        val change1Confirmed = change1.copy(blockDaaScore = 598_263_735L)
        val step4 = reconciler.applyUtxosChangedPush(
            added = listOf(change1Confirmed),
            removed = listOf(
                com.kaspakinetic.wallet.core.network.KaspadProtoCodec.RemovedUtxoRef(
                    address = myAddr,
                    transactionId = initialUtxo.transactionId,
                    index = 0,
                ),
            ),
            walletAddressSet = walletSet,
            nowMs = now + 700L,
        )
        assertEquals(1, step4.size)
        assertEquals(change2.outpointKey, step4.first().outpointKey)
        assertEquals(3_435_590_000L, step4.sumOf { it.amountSompi })

        // Finally NotifyUtxosChanged for split #2 confirms (removed=change1, added=change2 confirmed at DAA 598263740):
        // Block DAA: 0 pending change2 is cleanly superseded by confirmed change2 with zero duplicates!
        val change2Confirmed = change2.copy(blockDaaScore = 598_263_740L)
        val step5 = reconciler.applyUtxosChangedPush(
            added = listOf(change2Confirmed),
            removed = listOf(
                com.kaspakinetic.wallet.core.network.KaspadProtoCodec.RemovedUtxoRef(
                    address = myAddr,
                    transactionId = change1.transactionId,
                    index = 0,
                ),
            ),
            walletAddressSet = walletSet,
            nowMs = now + 900L,
        )
        assertEquals(1, step5.size)
        assertEquals(change2.outpointKey, step5.first().outpointKey)
        assertEquals(598_263_740L, step5.first().blockDaaScore)
        assertEquals(3_435_590_000L, step5.sumOf { it.amountSompi })
        assertEquals(0, reconciler.pendingSpentCount())
        assertEquals(0, reconciler.pendingChangeCount())
    }

    @Test
    fun kaspadProtoCodecDecodesUtxosChangedNotificationAddedAndRemoved() {
        val addr = KaspaAddress.burnAddress(KaspaNetwork.MAINNET)
        val addedTxId = "dd".repeat(32)
        val removedTxId = "ee".repeat(32)
        val spkHex = KaspaCrypto.bytesToHex(KaspaAddress.decode(addr)!!.toScriptPublicKey())

        val addedOutpoint = concatBytes(
            encodeStringFieldForTest(1, addedTxId),
            encodeVarintFieldForTest(2, 0L),
        )
        val spkMsg = concatBytes(
            encodeVarintFieldForTest(1, 0L),
            encodeStringFieldForTest(2, spkHex),
        )
        val utxoEntryMsg = concatBytes(
            encodeVarintFieldForTest(1, 123_456_789L),
            encodeLengthDelimitedFieldForTest(2, spkMsg),
            encodeVarintFieldForTest(3, 598_263_999L),
        )
        val addedEntry = concatBytes(
            encodeStringFieldForTest(1, addr),
            encodeLengthDelimitedFieldForTest(2, addedOutpoint),
            encodeLengthDelimitedFieldForTest(3, utxoEntryMsg),
        )
        val removedOutpoint = concatBytes(
            encodeStringFieldForTest(1, removedTxId),
            encodeVarintFieldForTest(2, 1L),
        )
        val removedEntry = concatBytes(
            encodeStringFieldForTest(1, addr),
            encodeLengthDelimitedFieldForTest(2, removedOutpoint),
        )
        val notifBody = concatBytes(
            encodeLengthDelimitedFieldForTest(1, addedEntry),
            encodeLengthDelimitedFieldForTest(2, removedEntry),
        )
        val wire = encodeLengthDelimitedFieldForTest(1051, notifBody)

        val decoded = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.decodeResponse(wire)
        assertTrue(decoded.payload is com.kaspakinetic.wallet.core.network.KaspadProtoCodec.Payload.UtxosChanged)
        val payload = decoded.payload as com.kaspakinetic.wallet.core.network.KaspadProtoCodec.Payload.UtxosChanged
        assertEquals(1, payload.added.size)
        assertEquals(addedTxId, payload.added.first().transactionId)
        assertEquals(123_456_789L, payload.added.first().amountSompi)
        assertEquals(598_263_999L, payload.added.first().blockDaaScore)
        assertEquals(1, payload.removed.size)
        assertEquals("$removedTxId:1", payload.removed.first().outpointKey)
    }

    @Test
    fun kaspaNodePoolFiltersPrivateIpsMapsP2pPortsAndRanksByLatencyAndMedianDaa() {
        // 1. P2P port mapping & IP filtering
        val mainnetIpv4 = com.kaspakinetic.wallet.core.network.KaspaNodePool.parseGossipPeerToGrpcEndpoint(
            "212.227.144.45:16111",
            KaspaNetwork.MAINNET,
        )
        assertNotNull(mainnetIpv4)
        assertEquals("212.227.144.45", mainnetIpv4!!.host)
        assertEquals(16110, mainnetIpv4.port)
        assertFalse(mainnetIpv4.useTls)

        val mainnetIpv6 = com.kaspakinetic.wallet.core.network.KaspaNodePool.parseGossipPeerToGrpcEndpoint(
            "[2001:4860:4860::8888]:16111",
            KaspaNetwork.MAINNET,
        )
        assertNotNull(mainnetIpv6)
        assertEquals(16110, mainnetIpv6!!.port)
        assertTrue(mainnetIpv6.hostPort.startsWith("["))

        val tn10 = com.kaspakinetic.wallet.core.network.KaspaNodePool.parseGossipPeerToGrpcEndpoint(
            "8.8.4.4:16211",
            KaspaNetwork.TESTNET_10,
        )
        assertNotNull(tn10)
        assertEquals(16210, tn10!!.port)

        // Reject cross-network ports, loopback, private LAN, CGNAT, link-local, ULA, and arbitrary domains
        val rejected = listOf(
            "212.227.144.45:16211", // testnet port on mainnet
            "127.0.0.1:16111",
            "10.0.0.5:16111",
            "192.168.1.25:16111",
            "172.16.10.2:16111",
            "100.64.1.2:16111",
            "169.254.1.1:16111",
            "[::1]:16111",
            "[fe80::1]:16111",
            "[fd00::1234]:16111",
            "evil.example.com:16111",
        )
        for (bad in rejected) {
            assertNull(
                "Expected $bad to be rejected",
                com.kaspakinetic.wallet.core.network.KaspaNodePool.parseGossipPeerToGrpcEndpoint(bad, KaspaNetwork.MAINNET),
            )
        }

        // 2. Ranking: lowest latency first among fresh nodes, lagging (>100 DAA behind median) demoted!
        val now = 1_700_000_000_000L
        val fastFresh = com.kaspakinetic.wallet.core.network.PooledNodeEntry(
            endpoint = com.kaspakinetic.wallet.core.model.NodeEndpoint("1.1.1.1", 16110, false),
            isSynced = true,
            isUtxoIndexed = true,
            virtualDaaScore = 500_000L,
            latencyMs = 35L,
            probedAtMs = now,
        )
        val mediumFresh = com.kaspakinetic.wallet.core.network.PooledNodeEntry(
            endpoint = com.kaspakinetic.wallet.core.model.NodeEndpoint("2.2.2.2", 16110, false),
            isSynced = true,
            isUtxoIndexed = true,
            virtualDaaScore = 500_010L,
            latencyMs = 80L,
            probedAtMs = now,
        )
        val ultraFastButLagging = com.kaspakinetic.wallet.core.network.PooledNodeEntry(
            endpoint = com.kaspakinetic.wallet.core.model.NodeEndpoint("3.3.3.3", 16110, false),
            isSynced = true,
            isUtxoIndexed = true,
            virtualDaaScore = 499_500L, // 500 DAA behind median (> 100 threshold!)
            latencyMs = 8L,
            probedAtMs = now,
        )
        val noUtxoIndex = com.kaspakinetic.wallet.core.network.PooledNodeEntry(
            endpoint = com.kaspakinetic.wallet.core.model.NodeEndpoint("4.4.4.4", 16110, false),
            isSynced = true,
            isUtxoIndexed = false,
            virtualDaaScore = 500_005L,
            latencyMs = 5L,
            probedAtMs = now,
        )
        val ranked = com.kaspakinetic.wallet.core.network.KaspaNodePool.rankActiveNodes(
            listOf(ultraFastButLagging, mediumFresh, noUtxoIndex, fastFresh),
            nowMs = now,
        )
        assertEquals(3, ranked.size) // noUtxoIndex excluded
        assertEquals("1.1.1.1", ranked[0].endpoint.host)
        assertEquals("2.2.2.2", ranked[1].endpoint.host)
        assertEquals("3.3.3.3", ranked[2].endpoint.host) // demoted due to DAA lag

        // 3. Quarantine after 3 strikes & forgiveness on network path change
        val pool = com.kaspakinetic.wallet.core.network.KaspaNodePool(
            appContext = null,
            cacheStore = com.kaspakinetic.wallet.core.network.InMemoryNodePoolCacheStore(),
        )
        val ep = com.kaspakinetic.wallet.core.model.NodeEndpoint("5.5.5.5", 16110, false)
        pool.recordSuccess(KaspaNetwork.MAINNET, ep, latencyMs = 25L, virtualDaaScore = 500_000L)
        assertEquals(1, pool.activeVerifiedCount(KaspaNetwork.MAINNET))
        repeat(3) { pool.recordFailure(KaspaNetwork.MAINNET, ep) }
        assertEquals(0, pool.activeVerifiedCount(KaspaNetwork.MAINNET))
        pool.onNetworkPathChanged()
        assertEquals(1, pool.activeVerifiedCount(KaspaNetwork.MAINNET))
    }

    @Test
    fun kaspadProtoCodecDecodesGetPeerAddressesAndNodeModesAreLocalized() {
        val reqWire = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.encodeGetPeerAddressesRequest(id = 12L)
        assertTrue(reqWire.isNotEmpty())

        val peer1 = encodeStringFieldForTest(1, "212.227.144.45:16111")
        val peer2 = encodeStringFieldForTest(1, "87.236.31.226:16111")
        val body = concatBytes(
            encodeLengthDelimitedFieldForTest(1, peer1),
            encodeLengthDelimitedFieldForTest(1, peer2),
        )
        val respWire = concatBytes(
            encodeVarintFieldForTest(101, 12L),
            encodeLengthDelimitedFieldForTest(1011, body),
        )
        val decoded = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.decodeResponse(respWire)
        assertEquals(12L, decoded.id)
        assertTrue(decoded.payload is com.kaspakinetic.wallet.core.network.KaspadProtoCodec.Payload.PeerAddresses)
        val peers = (decoded.payload as com.kaspakinetic.wallet.core.network.KaspadProtoCodec.Payload.PeerAddresses).addresses
        assertEquals(listOf("212.227.144.45:16111", "87.236.31.226:16111"), peers)

        // Verify universal GetInfo (1063/1064) encode & decode
        val getInfoReq = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.encodeGetInfoRequest(id = 33L)
        assertTrue(getInfoReq.isNotEmpty())
        val getInfoBody = concatBytes(
            encodeStringFieldForTest(3, "0.15.2"),
            encodeVarintFieldForTest(4, 1L), // isUtxoIndexed
            encodeVarintFieldForTest(5, 1L), // isSynced
        )
        val getInfoRespWire = concatBytes(
            encodeVarintFieldForTest(101, 33L),
            encodeLengthDelimitedFieldForTest(1064, getInfoBody),
        )
        val decodedGetInfo = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.decodeResponse(getInfoRespWire)
        assertEquals(33L, decodedGetInfo.id)
        val gi = decodedGetInfo.payload as com.kaspakinetic.wallet.core.network.KaspadProtoCodec.Payload.ServerInfo
        assertTrue(gi.health.isSynced)
        assertTrue(gi.health.isUtxoIndexed)
        assertEquals("0.15.2", gi.health.serverVersion)

        // Verify both Mainnet TLS gRPC endpoints (toccata + node) are present for fallback/failover
        val tlsHosts = com.kaspakinetic.wallet.core.network.KaspaNodePool.MAINNET_TLS_FALLBACKS.map { "${it.host}:${it.port}:${it.useTls}" }
        assertTrue(tlsHosts.contains("toccata.kaspium.io:443:true"))
        assertTrue(tlsHosts.contains("node.kaspium.io:443:true"))

        // Default NodeConfig uses Automatic Scan; legacy useCustomNode = true migrates cleanly to CUSTOM
        assertEquals(
            com.kaspakinetic.wallet.core.model.NodeConnectionMode.AUTOMATIC_SCAN,
            com.kaspakinetic.wallet.core.model.NodeConfig().effectiveConnectionMode,
        )
        assertEquals(
            com.kaspakinetic.wallet.core.model.NodeConnectionMode.CUSTOM,
            com.kaspakinetic.wallet.core.model.NodeConfig(useCustomNode = true).effectiveConnectionMode,
        )

        // All 10 locales translate Automatic Scan / TLS Only / Custom Node / Active Pool labels
        val en = com.kaspakinetic.wallet.ui.locale.KaspaWalletStrings()
        val locales = listOf(
            java.util.Locale.SIMPLIFIED_CHINESE,
            java.util.Locale.TRADITIONAL_CHINESE,
            java.util.Locale.JAPANESE,
            java.util.Locale.KOREAN,
            java.util.Locale("ar"),
            java.util.Locale("es"),
            java.util.Locale.FRENCH,
            java.util.Locale.GERMAN,
            java.util.Locale("pt"),
            java.util.Locale("ru"),
        )
        for (loc in locales) {
            val s = com.kaspakinetic.wallet.ui.locale.resolveKaspaWalletStrings(loc)
            assertNotEquals("Missing nodeModeAutoScanTitle for $loc", en.nodeModeAutoScanTitle, s.nodeModeAutoScanTitle)
            assertNotEquals("Missing nodeModeAutoScanDesc for $loc", en.nodeModeAutoScanDesc, s.nodeModeAutoScanDesc)
            assertNotEquals("Missing nodeModeTlsOnlyDesc for $loc", en.nodeModeTlsOnlyDesc, s.nodeModeTlsOnlyDesc)
            assertNotEquals("Missing nodeModeCustomTitle for $loc", en.nodeModeCustomTitle, s.nodeModeCustomTitle)
            assertNotEquals("Missing nodeActivePoolLabel for $loc", en.nodeActivePoolLabel, s.nodeActivePoolLabel)
            assertNotEquals("Missing nodeTransportPlaintextTag for $loc", en.nodeTransportPlaintextTag, s.nodeTransportPlaintextTag)
            assertNotEquals("Missing restFallbackDialogTitle for $loc", en.restFallbackDialogTitle, s.restFallbackDialogTitle)
            assertNotEquals("Missing restFallbackDialogBody for $loc", en.restFallbackDialogBody, s.restFallbackDialogBody)
            assertNotEquals("Missing restFallbackOpenSettingsButton for $loc", en.restFallbackOpenSettingsButton, s.restFallbackOpenSettingsButton)
            assertNotEquals("Missing restFallbackGotItButton for $loc", en.restFallbackGotItButton, s.restFallbackGotItButton)
        }
    }

    @Test
    fun guardedStreamObserverPreventsConcurrentDoubleHalfClose() {
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val sentCount = java.util.concurrent.atomic.AtomicInteger(0)
        val alreadyClosedThrown = java.util.concurrent.atomic.AtomicBoolean(false)

        val strictMockObserver = object : io.grpc.stub.StreamObserver<ByteArray> {
            private val closedFlag = java.util.concurrent.atomic.AtomicBoolean(false)

            override fun onNext(value: ByteArray) {
                if (closedFlag.get()) {
                    alreadyClosedThrown.set(true)
                    throw IllegalStateException("call already half-closed")
                }
                sentCount.incrementAndGet()
            }

            override fun onError(t: Throwable) = Unit

            override fun onCompleted() {
                if (!closedFlag.compareAndSet(false, true)) {
                    alreadyClosedThrown.set(true)
                    throw IllegalStateException("call already half-closed")
                }
                completedCount.incrementAndGet()
            }
        }

        val guarded = com.kaspakinetic.wallet.core.network.GuardedStreamObserver(strictMockObserver)
        assertTrue(guarded.send(byteArrayOf(1, 2, 3)))
        assertEquals(1, sentCount.get())

        val threads = 16
        val startLatch = java.util.concurrent.CountDownLatch(1)
        val doneLatch = java.util.concurrent.CountDownLatch(threads)
        val winners = java.util.concurrent.atomic.AtomicInteger(0)

        for (i in 0 until threads) {
            Thread {
                try {
                    startLatch.await()
                    if (i % 3 == 0) {
                        guarded.send(byteArrayOf(i.toByte()))
                    }
                    if (guarded.completeOnce()) {
                        winners.incrementAndGet()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }.start()
        }
        startLatch.countDown()
        assertTrue("Concurrent teardown threads should finish cleanly", doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))

        assertFalse("Underlying StreamObserver must NEVER receive double half-close", alreadyClosedThrown.get())
        assertEquals("onCompleted() must fire exactly once", 1, completedCount.get())
        assertEquals("Exactly one caller should win completeOnce()", 1, winners.get())
        assertTrue(guarded.isHalfClosed)
        assertFalse("send() must return false after half-close", guarded.send(byteArrayOf(9)))
        assertFalse("Subsequent completeOnce() must be a no-op", guarded.completeOnce())
    }

    @Test
    fun kasVsBtcComparisonComputesAlignedNormalizedOutperformance() {
        val dayMs = 86_400_000L
        val t0 = 1_662_000_000_000L // Sept 2022

        // BTC has earlier history predating Kaspa's listing; overlap must align cleanly at t0
        val btcCandles = listOf(
            com.kaspakinetic.wallet.core.explore.CandlePoint(t0 - 10 * dayMs, 15_000.0),
            com.kaspakinetic.wallet.core.explore.CandlePoint(t0, 20_000.0),
            com.kaspakinetic.wallet.core.explore.CandlePoint(t0 + dayMs, 22_000.0),
            com.kaspakinetic.wallet.core.explore.CandlePoint(t0 + 2 * dayMs, 25_000.0), // +25% from t0
        )
        val kasCandles = listOf(
            com.kaspakinetic.wallet.core.explore.CandlePoint(t0, 0.010),
            com.kaspakinetic.wallet.core.explore.CandlePoint(t0 + dayMs, 0.015),
            com.kaspakinetic.wallet.core.explore.CandlePoint(t0 + 2 * dayMs, 0.020), // +100% from t0
        )

        val result = com.kaspakinetic.wallet.core.explore.ExploreAnalyticsMath.computeKasVsBtcComparison(
            timeframe = com.kaspakinetic.wallet.core.explore.MarketTimeframe.ALL_5Y,
            sourceName = "MEXC",
            kasCandlesRaw = kasCandles,
            btcCandlesRaw = btcCandles,
        )
        assertNotNull(result)
        val r = result!!
        assertEquals(t0, r.earliestTimestampMs)
        assertEquals(0.0, r.kasPctPoints.first().second, 1e-6)
        assertEquals(0.0, r.btcPctPoints.first().second, 1e-6)
        assertEquals(100.0, r.kasChangePct, 1e-6)
        assertEquals(25.0, r.btcChangePct, 1e-6)
        assertEquals(75.0, r.relativeOutperformancePct, 1e-6)
        assertEquals("MEXC", r.sourceName)
    }

    @Test
    fun richListSearchSupportsRankJumpAddressAndEntityLabel() {
        val sampleEntries = (1..6000).map { rank ->
            val label = when (rank) {
                1 -> "Gate.io Cold Wallet"
                42 -> "Kraken Exchange"
                5000 -> "Community Whale"
                else -> null
            }
            com.kaspakinetic.wallet.core.explore.RichListEntry(
                rank = rank,
                address = "kaspa:q${rank.toString().padStart(60, '0')}",
                amountKas = 1_000_000_000L / rank,
                label = label,
            )
        }

        // 1. Exact rank jump with `#5000`
        val jumpHash = com.kaspakinetic.wallet.core.explore.ExploreAnalyticsMath.filterAndSearchRichList(sampleEntries, "#5000")
        assertEquals(5000, jumpHash.exactJumpRank)
        assertNotNull(jumpHash.scrollToIndex)
        assertEquals(5000, jumpHash.entries[jumpHash.scrollToIndex!!].rank)

        // 2. Exact rank jump without `#` (`5000`)
        val jumpBare = com.kaspakinetic.wallet.core.explore.ExploreAnalyticsMath.filterAndSearchRichList(sampleEntries, "5000")
        assertEquals(5000, jumpBare.exactJumpRank)
        assertEquals(5000, jumpBare.entries[jumpBare.scrollToIndex!!].rank)

        // 3. Entity label search (`kraken`, case-insensitive)
        val labelMatch = com.kaspakinetic.wallet.core.explore.ExploreAnalyticsMath.filterAndSearchRichList(sampleEntries, "kraken")
        assertEquals(1, labelMatch.entries.size)
        assertEquals(42, labelMatch.entries.first().rank)
        assertEquals("Kraken Exchange", labelMatch.entries.first().label)

        // 4. Direct address search
        val targetAddr = sampleEntries[99].address
        val addrMatch = com.kaspakinetic.wallet.core.explore.ExploreAnalyticsMath.filterAndSearchRichList(sampleEntries, targetAddr)
        assertEquals(1, addrMatch.entries.size)
        assertEquals(100, addrMatch.entries.first().rank)

        // 5. Out-of-bounds rank returns empty cleanly
        val miss = com.kaspakinetic.wallet.core.explore.ExploreAnalyticsMath.filterAndSearchRichList(sampleEntries, "#99999")
        assertTrue(miss.entries.isEmpty())
    }

    @Test
    fun kaspadProtoCodecDecodesBlockAddedNotificationAndEncodesNotifyRequest() {
        val startReq = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.encodeNotifyBlockAddedRequest(id = 12L, stop = false)
        val stopReq = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.encodeNotifyBlockAddedRequest(id = 13L, stop = true)
        assertTrue(startReq.isNotEmpty())
        assertTrue(stopReq.size > startReq.size)

        // Construct a synthetic BlockAddedNotification (tag 1009 -> field 3 RpcBlock)
        val level0Parents = concatBytes(
            encodeStringFieldForTest(1, "aa".repeat(32)),
            encodeStringFieldForTest(1, "bb".repeat(32)),
        )
        val header = concatBytes(
            encodeVarintFieldForTest(6, 1_795_311_119_462L), // timestamp
            encodeVarintFieldForTest(9, 598_906_900L), // daaScore
            encodeLengthDelimitedFieldForTest(12, level0Parents), // parents level 0
            encodeVarintFieldForTest(13, 596_798_450L), // blueScore
        )
        val verbose = concatBytes(
            encodeStringFieldForTest(1, "cc".repeat(32)), // hash
            encodeStringFieldForTest(13, "aa".repeat(32)), // selectedParentHash
            encodeStringFieldForTest(14, "dd".repeat(32)), // txId #1
            encodeStringFieldForTest(14, "ee".repeat(32)), // txId #2
            encodeVarintFieldForTest(16, 596_798_450L), // blueScore
            encodeVarintFieldForTest(20, 1L), // isChainBlock = true
        )
        val rpcBlock = concatBytes(
            encodeLengthDelimitedFieldForTest(1, header),
            encodeLengthDelimitedFieldForTest(3, verbose),
        )
        val notifBody = encodeLengthDelimitedFieldForTest(3, rpcBlock)
        val responseWire = encodeLengthDelimitedFieldForTest(1009, notifBody)

        val decoded = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.decodeResponse(responseWire)
        assertTrue(decoded.payload is com.kaspakinetic.wallet.core.network.KaspadProtoCodec.Payload.BlockAdded)
        val blk = (decoded.payload as com.kaspakinetic.wallet.core.network.KaspadProtoCodec.Payload.BlockAdded).block
        assertEquals("cc".repeat(32), blk.hash)
        assertEquals(598_906_900L, blk.daaScore)
        assertEquals(596_798_450L, blk.blueScore)
        assertTrue(blk.isChainBlock)
        assertEquals(2, blk.parentHashes.size)
        assertEquals("aa".repeat(32), blk.selectedParentHash)
        assertEquals(2, blk.txCount)
    }

    @Test
    fun stage2ExploreAndStatsStringsAreTranslatedAcrossAll10Locales() {
        val en = com.kaspakinetic.wallet.ui.locale.KaspaWalletStrings()
        val locales = listOf(
            java.util.Locale.SIMPLIFIED_CHINESE,
            java.util.Locale.TRADITIONAL_CHINESE,
            java.util.Locale.JAPANESE,
            java.util.Locale.KOREAN,
            java.util.Locale("ar"),
            java.util.Locale("es"),
            java.util.Locale.FRENCH,
            java.util.Locale.GERMAN,
            java.util.Locale("pt"),
            java.util.Locale("ru"),
        )
        for (loc in locales) {
            val s = com.kaspakinetic.wallet.ui.locale.resolveKaspaWalletStrings(loc)
            assertNotEquals("Missing explorerLiveFeedTitle for $loc", en.explorerLiveFeedTitle, s.explorerLiveFeedTitle)
            assertNotEquals("Missing statsKasVsBtcTitle for $loc", en.statsKasVsBtcTitle, s.statsKasVsBtcTitle)
            assertNotEquals("Missing statsPrivacyModeTitle for $loc", en.statsPrivacyModeTitle, s.statsPrivacyModeTitle)
            assertNotEquals("Missing statsHashrateHistoryTitle for $loc", en.statsHashrateHistoryTitle, s.statsHashrateHistoryTitle)
            assertNotEquals("Missing statsDistributionTitle for $loc", en.statsDistributionTitle, s.statsDistributionTitle)
            assertNotEquals("Missing dagJumpToLiveButton for $loc", en.dagJumpToLiveButton, s.dagJumpToLiveButton)
            assertNotEquals("Missing dagTapHint for $loc", en.dagTapHint, s.dagTapHint)
            assertNotEquals("Missing statsKasPerformanceTitle for $loc", en.statsKasPerformanceTitle, s.statsKasPerformanceTitle)
            assertNotEquals("Missing yourPerformanceTitle for $loc", en.yourPerformanceTitle, s.yourPerformanceTitle)
            assertNotEquals("Missing yourPerformanceMilestonesTitle for $loc", en.yourPerformanceMilestonesTitle, s.yourPerformanceMilestonesTitle)
        }
    }

    @Test
    fun walletPerformanceEngineComputesTimelineDcaTiersMilestonesAndEmptyEdgeCase() {
        val dayMs = 86_400_000L
        val month1Ms = 1_736_899_200_000L // Jan 15, 2025
        val month2Ms = 1_739_577_600_000L // Feb 15, 2025
        val month3Ms = 1_742_083_200_000L // Mar 16, 2025
        val nowMs = 1_744_675_200_000L // Apr 15, 2025 (~90 days / ~3 months)

        // 1. Brand-new empty wallet edge case (must never divide by zero or crash!)
        val emptySnap = com.kaspakinetic.wallet.core.explore.WalletPerformanceEngine.computePerformance(
            transactions = emptyList(),
            currentBalanceSompi = 0L,
            historicalKasPricesInCurrency = emptyList(),
            currentSpotPriceInCurrency = 0.05,
            timeframe = com.kaspakinetic.wallet.core.explore.PerformanceTimeframe.ALL,
            privacyKasOnly = false,
            nowMs = nowMs,
        )
        assertTrue(emptySnap.isEmpty)
        assertEquals(com.kaspakinetic.wallet.core.explore.OceanTier.PLANKTON, emptySnap.currentTier)
        assertEquals(0.0, emptySnap.avgMonthlyDcaKas, 1e-6)
        assertTrue(emptySnap.milestones.isEmpty())

        // 2. Realistic multi-month stacking wallet crossing Fish -> Dolphin!
        val txs = listOf(
            WalletTx(
                transactionId = "11".repeat(32),
                blockTimeMs = month1Ms,
                isAccepted = true,
                direction = TxDirection.RECEIVED,
                netAmountSompi = 20_000L * SOMPI_PER_KAS, // crosses Fish (10K+)
                feeSompi = 0L,
            ),
            WalletTx(
                transactionId = "22".repeat(32),
                blockTimeMs = month2Ms,
                isAccepted = true,
                direction = TxDirection.RECEIVED,
                netAmountSompi = 100_000L * SOMPI_PER_KAS, // crosses Dolphin (100K+) -> biggest month!
                feeSompi = 0L,
            ),
            WalletTx(
                transactionId = "33".repeat(32),
                blockTimeMs = month3Ms,
                isAccepted = true,
                direction = TxDirection.RECEIVED,
                netAmountSompi = 5_000L * SOMPI_PER_KAS, // slowest month!
                feeSompi = 0L,
            ),
            WalletTx(
                transactionId = "44".repeat(32),
                blockTimeMs = month3Ms + dayMs,
                isAccepted = true,
                direction = TxDirection.SENT,
                netAmountSompi = 5_000L * SOMPI_PER_KAS,
                feeSompi = 0L,
            ),
        )
        // Net live balance: 20K + 100K + 5K - 5K = 120,000 KAS
        val pricesAud = listOf(
            com.kaspakinetic.wallet.core.explore.CandlePoint(month1Ms, 0.10),
            com.kaspakinetic.wallet.core.explore.CandlePoint(month2Ms, 0.15),
            com.kaspakinetic.wallet.core.explore.CandlePoint(month3Ms, 0.12),
            com.kaspakinetic.wallet.core.explore.CandlePoint(nowMs, 0.20),
        )

        val snap = com.kaspakinetic.wallet.core.explore.WalletPerformanceEngine.computePerformance(
            transactions = txs,
            currentBalanceSompi = 120_000L * SOMPI_PER_KAS,
            historicalKasPricesInCurrency = pricesAud,
            currentSpotPriceInCurrency = 0.20,
            timeframe = com.kaspakinetic.wallet.core.explore.PerformanceTimeframe.ALL,
            privacyKasOnly = false,
            nowMs = nowMs,
        )
        assertFalse(snap.isEmpty)
        assertEquals(120_000.0, snap.currentKas, 1e-4)
        assertEquals(24_000.0, snap.currentFiatValue!!, 1e-2)
        // Cost basis: 20K*0.10 + 100K*0.15 + 5K*0.12 = 2,000 + 15,000 + 600 = 17,600 AUD
        assertEquals(17_600.0, snap.estimatedCostBasisFiat!!, 1e-2)
        assertEquals(com.kaspakinetic.wallet.core.explore.OceanTier.DOLPHIN, snap.currentTier)
        assertEquals(com.kaspakinetic.wallet.core.explore.OceanTier.SHARK, snap.nextTier)
        assertEquals(880_000.0, snap.kasRemainingToNextTier, 1e-2)
        assertTrue("Avg monthly DCA should be positive", snap.avgMonthlyDcaKas > 30_000.0)
        // Diamond hands ratio: 120K / 125K = 96.0%
        assertEquals(96.0, snap.diamondHandsRatioPct, 1e-2)
        assertTrue("KAS timeline should have sampled points", snap.kasTimeline.size >= 10)
        assertTrue("Value timeline should have sampled points", snap.valueTimeline.size >= 10)

        // Check Dolphin unlock milestone date == month2Ms
        val dolphinUnlock = snap.milestones
            .filterIsInstance<com.kaspakinetic.wallet.core.explore.PerformanceMilestone.TierUnlock>()
            .firstOrNull { it.tier == com.kaspakinetic.wallet.core.explore.OceanTier.DOLPHIN }
        assertNotNull("Dolphin tier unlock milestone must be detected", dolphinUnlock)
        assertEquals(month2Ms, dolphinUnlock!!.achievedAtMs)

        // Check biggest stacking month is February 2025 (+100,000 KAS)
        val biggestMonth = snap.milestones
            .filterIsInstance<com.kaspakinetic.wallet.core.explore.PerformanceMilestone.BiggestStackingMonth>()
            .firstOrNull()
        assertNotNull(biggestMonth)
        assertTrue(biggestMonth!!.monthLabel.contains("2025"))
        assertEquals(100_000.0, biggestMonth.netKas, 1e-2)

        // Check privacy mode hides fiat fields while keeping KAS metrics & milestones alive
        val privSnap = com.kaspakinetic.wallet.core.explore.WalletPerformanceEngine.computePerformance(
            transactions = txs,
            currentBalanceSompi = 120_000L * SOMPI_PER_KAS,
            historicalKasPricesInCurrency = pricesAud,
            currentSpotPriceInCurrency = 0.20,
            timeframe = com.kaspakinetic.wallet.core.explore.PerformanceTimeframe.ALL,
            privacyKasOnly = true,
            nowMs = nowMs,
        )
        assertNull(privSnap.currentFiatValue)
        assertNull(privSnap.estimatedCostBasisFiat)
        assertTrue(privSnap.valueTimeline.isEmpty())
        assertEquals(120_000.0, privSnap.currentKas, 1e-4)
        assertEquals(com.kaspakinetic.wallet.core.explore.OceanTier.DOLPHIN, privSnap.currentTier)
    }

    @Test
    fun richListPaginationAndRollingLiveFeedBufferWorkCleanly() {
        val entries = (1..10_000).map { rank ->
            com.kaspakinetic.wallet.core.explore.RichListEntry(
                rank = rank,
                address = "kaspa:q${rank.toString().padStart(60, '0')}",
                amountKas = 1_000_000_000L / rank,
                label = if (rank == 5000) "Kraken Vault" else null,
            )
        }

        // Default page 0 with 10 rows per page -> ranks 1..10, 1000 pages total
        val page0 = com.kaspakinetic.wallet.core.explore.ExploreAnalyticsMath.paginateRichList(entries, "", 0, 10)
        assertEquals(10, page0.pageItems.size)
        assertEquals(1000, page0.totalPages)
        assertEquals(1, page0.pageItems.first().rank)
        assertEquals(10, page0.pageItems.last().rank)

        // Jump to `#5000` with 25 rows per page -> lands straight on page 199 (0-based) with rank 5000 highlighted!
        val jump5000 = com.kaspakinetic.wallet.core.explore.ExploreAnalyticsMath.paginateRichList(entries, "#5000", 0, 25)
        assertEquals(199, jump5000.currentPage)
        assertEquals(5000, jump5000.highlightedRank)
        assertTrue(jump5000.pageItems.any { it.rank == 5000 })

        // Rolling Live Feed buffer never wipes existing rows when a refresh tick returns empty!
        val repo = com.kaspakinetic.wallet.core.explore.KaspaExploreRepository()
        val sampleTx = com.kaspakinetic.wallet.core.explore.LiveTxFeedItem(
            transactionId = "aa".repeat(32),
            blockHash = "bb".repeat(32),
            blockBlueScore = 100L,
            timestampMs = 1_700_000_000_000L,
            isCoinbase = false,
            isAccepted = true,
            totalOutputSompi = 500L * SOMPI_PER_KAS,
            inputCount = 1,
            outputCount = 2,
            primaryRecipient = "kaspa:qqqqqq",
        )
        val afterFirst = repo.mergeIntoLiveFeedBuffer(KaspaNetwork.MAINNET, listOf(sampleTx))
        assertEquals(1, afterFirst.size)
        val afterEmptyTick = repo.mergeIntoLiveFeedBuffer(KaspaNetwork.MAINNET, emptyList())
        assertEquals("Empty poll tick must NEVER wipe existing Live Transactions", 1, afterEmptyTick.size)
        assertEquals(sampleTx.transactionId, afterEmptyTick.first().transactionId)
    }

    @Test
    fun setGoalEstimateComputesBaselineAndPlusMinus30PctPriceBandAccurately() {
        // Current stack: 20,000 KAS, Target: 100,000 KAS -> Remaining: 80,000 KAS
        // Monthly fiat DCA: $1,000 / mo, Spot price: $0.10 / KAS -> 10,000 KAS/mo -> Baseline: 8.0 months
        // -30% price ($0.07/KAS): $1,000 buys 14,285.71 KAS/mo -> 5.6 months (8.0 * 0.70)
        // +30% price ($0.13/KAS): $1,000 buys  7,692.31 KAS/mo -> 10.4 months (8.0 * 1.30)
        val est = com.kaspakinetic.wallet.core.explore.WalletPerformanceEngine.computeGoalEstimate(
            currentKas = 20_000.0,
            targetKas = 100_000.0,
            historicalMonthlyDcaKas = 10_000.0,
            historicalMonthlyDcaFiat = 1_000.0,
            currentPriceFiat = 0.10,
            privacyKasOnly = false,
        )
        assertFalse(est.isReached)
        assertEquals(80_000.0, est.remainingKas, 1e-6)
        assertEquals(20.0, est.progressPct, 1e-6)
        assertEquals(8.0, est.baselineMonths!!, 1e-6)
        assertEquals(5.6, est.fasterMonthsMinus30Pct!!, 1e-6)
        assertEquals(10.4, est.slowerMonthsPlus30Pct!!, 1e-6)
        assertTrue(est.fasterMonthsMinus30Pct!! < est.baselineMonths!!)
        assertTrue(est.slowerMonthsPlus30Pct!! > est.baselineMonths!!)

        // Already-reached goal
        val reached = com.kaspakinetic.wallet.core.explore.WalletPerformanceEngine.computeGoalEstimate(
            currentKas = 150_000.0,
            targetKas = 100_000.0,
            historicalMonthlyDcaKas = 10_000.0,
            historicalMonthlyDcaFiat = 1_000.0,
            currentPriceFiat = 0.10,
            privacyKasOnly = false,
        )
        assertTrue(reached.isReached)
        assertEquals(0.0, reached.remainingKas, 1e-6)
        assertEquals(100.0, reached.progressPct, 1e-6)
        assertEquals(0.0, reached.baselineMonths!!, 1e-6)

        // Zero / negative historical DCA without override -> null ETA, graceful fallback with custom override
        val zeroDca = com.kaspakinetic.wallet.core.explore.WalletPerformanceEngine.computeGoalEstimate(
            currentKas = 5_000.0,
            targetKas = 100_000.0,
            historicalMonthlyDcaKas = 0.0,
            historicalMonthlyDcaFiat = 0.0,
            currentPriceFiat = 0.10,
            privacyKasOnly = false,
        )
        assertNull(zeroDca.baselineMonths)
        assertNull(zeroDca.fasterMonthsMinus30Pct)
        assertNull(zeroDca.slowerMonthsPlus30Pct)

        val withOverride = com.kaspakinetic.wallet.core.explore.WalletPerformanceEngine.computeGoalEstimate(
            currentKas = 5_000.0,
            targetKas = 100_000.0,
            historicalMonthlyDcaKas = 0.0,
            historicalMonthlyDcaFiat = 0.0,
            currentPriceFiat = 0.10,
            privacyKasOnly = false,
            customMonthlyDcaOverride = 500.0, // $500/mo @ $0.10 = 5,000 KAS/mo -> 95k / 5k = 19 mo
        )
        assertTrue(withOverride.usedCustomDcaOverride)
        assertEquals(19.0, withOverride.baselineMonths!!, 1e-6)
        assertEquals(13.3, withOverride.fasterMonthsMinus30Pct!!, 1e-6)
        assertEquals(24.7, withOverride.slowerMonthsPlus30Pct!!, 1e-6)

        // KAS Only Privacy Mode -> pure KAS/month pace, no fiat price band
        val privacyEst = com.kaspakinetic.wallet.core.explore.WalletPerformanceEngine.computeGoalEstimate(
            currentKas = 10_000.0,
            targetKas = 50_000.0,
            historicalMonthlyDcaKas = 4_000.0,
            historicalMonthlyDcaFiat = 400.0,
            currentPriceFiat = 0.10,
            privacyKasOnly = true,
        )
        assertTrue(privacyEst.isPrivacyKasOnlyMode)
        assertEquals(10.0, privacyEst.baselineMonths!!, 1e-6)
        assertNull(privacyEst.fasterMonthsMinus30Pct)
        assertNull(privacyEst.slowerMonthsPlus30Pct)

        // Duration formatting
        assertEquals("< 1 mo", com.kaspakinetic.wallet.core.explore.WalletPerformanceEngine.formatDurationMonths(0.4))
        assertEquals("5.6 mo", com.kaspakinetic.wallet.core.explore.WalletPerformanceEngine.formatDurationMonths(5.6))
        assertEquals("1 yr 6 mo", com.kaspakinetic.wallet.core.explore.WalletPerformanceEngine.formatDurationMonths(18.0))
        assertEquals("2 yr", com.kaspakinetic.wallet.core.explore.WalletPerformanceEngine.formatDurationMonths(24.0))
    }

    @Test
    fun kaspadProtoCodecDecodesRpcBlockTransactionsForGrpcLiveExplorerFeed() {
        val blockHash = "ab".repeat(32)
        val coinbaseTxId = "11".repeat(32)
        val transferTxId = "22".repeat(32)
        val minerAddr = "kaspa:qp2kf6gf8py4gy7xe77gkey54vxacc7xslxm5qcwfw268wwurfqacyznv5cuk"
        val recipientAddr = "kaspa:qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqkx9awp4e"

        // Tx 1: Coinbase (0 inputs, 1 output = 50 KAS, verboseData.transactionId)
        val cbOutVerbose = encodeStringFieldForTest(6, minerAddr)
        val cbOut = concatBytes(
            encodeVarintFieldForTest(1, 50L * SOMPI_PER_KAS),
            encodeLengthDelimitedFieldForTest(3, cbOutVerbose),
        )
        val cbVerbose = concatBytes(
            encodeStringFieldForTest(1, coinbaseTxId),
            encodeVarintFieldForTest(14, 1_735_000_000_123L),
        )
        val cbTx = concatBytes(
            encodeLengthDelimitedFieldForTest(3, cbOut),
            encodeLengthDelimitedFieldForTest(9, cbVerbose),
        )

        // Tx 2: Transfer (2 inputs, 2 outputs = 12,000 + 3,000 = 15,000 KAS)
        val tx2Out1 = concatBytes(
            encodeVarintFieldForTest(1, 12_000L * SOMPI_PER_KAS),
            encodeLengthDelimitedFieldForTest(3, encodeStringFieldForTest(6, recipientAddr)),
        )
        val tx2Out2 = encodeVarintFieldForTest(1, 3_000L * SOMPI_PER_KAS)
        val tx2Verbose = encodeStringFieldForTest(1, transferTxId)
        val tx2 = concatBytes(
            encodeLengthDelimitedFieldForTest(2, ByteArray(4)),
            encodeLengthDelimitedFieldForTest(2, ByteArray(4)),
            encodeLengthDelimitedFieldForTest(3, tx2Out1),
            encodeLengthDelimitedFieldForTest(3, tx2Out2),
            encodeLengthDelimitedFieldForTest(9, tx2Verbose),
        )

        val header = concatBytes(
            encodeVarintFieldForTest(6, 1_735_000_000_500L),
            encodeVarintFieldForTest(9, 600_000_000L),
            encodeVarintFieldForTest(13, 300_000_000L),
            encodeStringFieldForTest(15, blockHash),
        )
        val blkVerbose = concatBytes(
            encodeStringFieldForTest(1, blockHash),
            encodeStringFieldForTest(14, coinbaseTxId),
            encodeStringFieldForTest(14, transferTxId),
            encodeVarintFieldForTest(16, 300_000_000L),
            encodeVarintFieldForTest(20, 1L),
        )
        val rpcBlock = concatBytes(
            encodeLengthDelimitedFieldForTest(1, header),
            encodeLengthDelimitedFieldForTest(2, cbTx),
            encodeLengthDelimitedFieldForTest(2, tx2),
            encodeLengthDelimitedFieldForTest(3, blkVerbose),
        )
        val notifBody = encodeLengthDelimitedFieldForTest(3, rpcBlock)
        val envelope = encodeLengthDelimitedFieldForTest(
            com.kaspakinetic.wallet.core.network.KaspadProtoCodec.TAG_NOTIF_BLOCK_ADDED,
            notifBody,
        )

        val decoded = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.decodeResponse(envelope)
        val blockAdded = decoded.payload as com.kaspakinetic.wallet.core.network.KaspadProtoCodec.Payload.BlockAdded
        assertEquals(blockHash, blockAdded.block.hash)
        assertTrue(blockAdded.block.isChainBlock)
        assertEquals(2, blockAdded.block.txCount)
        assertEquals(2, blockAdded.block.transactions.size)

        val decodedCb = blockAdded.block.transactions[0]
        assertEquals(coinbaseTxId, decodedCb.transactionId)
        assertEquals(0, decodedCb.inputCount)
        assertEquals(1, decodedCb.outputCount)
        assertEquals(50L * SOMPI_PER_KAS, decodedCb.totalOutputSompi)
        assertEquals(minerAddr, decodedCb.primaryRecipient)

        val decodedTransfer = blockAdded.block.transactions[1]
        assertEquals(transferTxId, decodedTransfer.transactionId)
        assertEquals(2, decodedTransfer.inputCount)
        assertEquals(2, decodedTransfer.outputCount)
        assertEquals(15_000L * SOMPI_PER_KAS, decodedTransfer.totalOutputSompi)
        assertEquals(recipientAddr, decodedTransfer.primaryRecipient)
    }

    @Test
    fun setGoalStringsAreTranslatedAcrossAll10Locales() {
        val en = com.kaspakinetic.wallet.ui.locale.KaspaWalletStrings()
        val locales = listOf(
            java.util.Locale.SIMPLIFIED_CHINESE,
            java.util.Locale.TRADITIONAL_CHINESE,
            java.util.Locale.JAPANESE,
            java.util.Locale.KOREAN,
            java.util.Locale("ar"),
            java.util.Locale("es"),
            java.util.Locale.FRENCH,
            java.util.Locale.GERMAN,
            java.util.Locale("pt"),
            java.util.Locale("ru"),
        )
        for (loc in locales) {
            val s = com.kaspakinetic.wallet.ui.locale.resolveKaspaWalletStrings(loc)
            assertNotEquals("Missing goalSetButton for $loc", en.goalSetButton, s.goalSetButton)
            assertNotEquals("Missing goalReachedTitle for $loc", en.goalReachedTitle, s.goalReachedTitle)
            assertNotEquals("Missing goalDisclaimerNote for $loc", en.goalDisclaimerNote, s.goalDisclaimerNote)
            assertNotEquals("Missing explorerWaitingLiveFeed for $loc", en.explorerWaitingLiveFeed, s.explorerWaitingLiveFeed)
        }
    }

    @Test
    fun allParameterizedFormatStringsAcrossAll11LocalesFormatWithoutThrowing() {
        val all11Locales = listOf(
            java.util.Locale.ENGLISH,
            java.util.Locale.SIMPLIFIED_CHINESE,
            java.util.Locale.TRADITIONAL_CHINESE,
            java.util.Locale.JAPANESE,
            java.util.Locale.KOREAN,
            java.util.Locale("ar"),
            java.util.Locale("es"),
            java.util.Locale.FRENCH,
            java.util.Locale.GERMAN,
            java.util.Locale("pt"),
            java.util.Locale("ru"),
        )
        for (loc in all11Locales) {
            val s = com.kaspakinetic.wallet.ui.locale.resolveKaspaWalletStrings(loc)
            // Validate EVERY parameterized format template via strict String.format(Locale.US, ...)
            // so an unescaped literal '%' (e.g. "-30% to +30%") can NEVER cause UnknownFormatConversionException!
            val formattedList = listOf(
                String.format(java.util.Locale.US, s.yourPerformanceTierUnlockFmt, "Jan 1, 2025", "🐬", "Dolphin"),
                String.format(java.util.Locale.US, s.yourPerformanceBiggestMonthFmt, "March 2025", "12,500"),
                String.format(java.util.Locale.US, s.yourPerformanceSlowestMonthFmt, "April 2025", "+100"),
                String.format(java.util.Locale.US, s.yourPerformanceBiggestDepositFmt, "50,000", "Feb 2, 2025"),
                String.format(java.util.Locale.US, s.yourPerformanceActivitySummaryFmt, 14, 3, "Jan 1, 2025"),
                String.format(java.util.Locale.US, s.goalCustomDcaPlaceholderFmt, "$500.00"),
                String.format(java.util.Locale.US, s.goalRemainingFmt, "80,000 KAS"),
                String.format(java.util.Locale.US, s.goalEtaRangeFmt, "5.6 mo", "10.4 mo"),
                String.format(java.util.Locale.US, s.goalEtaBandCaptionFmt, "8 mo"),
                String.format(java.util.Locale.US, s.goalEtaPrivacyCaptionFmt, "10 mo"),
                String.format(java.util.Locale.US, s.goalEtaBasedOnPaceFmt, "$1,000.00"),
                String.format(java.util.Locale.US, s.covActivationProceedCountdownFmt, 5),
                String.format(java.util.Locale.US, s.covBackupMetaFmt, 3, "Nov 22, 20:15"),
                String.format(java.util.Locale.US, s.covStrayUtxoWarningFmt, "2.5 KAS"),
            )
            for (result in formattedList) {
                assertTrue("Formatted string must be non-blank for $loc", result.isNotBlank())
                assertFalse("Formatted string should not retain double %% for $loc: $result", result.contains("%%"))
            }
            // Specifically confirm goalEtaBandCaptionFmt renders clean -30% and +30% in every locale!
            val band = String.format(java.util.Locale.US, s.goalEtaBandCaptionFmt, "8 mo")
            assertTrue("Expected -30% in $loc band caption: $band", band.contains("-30%"))
            assertTrue("Expected +30% in $loc band caption: $band", band.contains("+30%"))
            assertTrue("Expected baseline '8 mo' in $loc band caption: $band", band.contains("8 mo"))
        }

        // Also verify safeFormatLocalized never throws even on a malformed specifier
        val recovered = com.kaspakinetic.wallet.ui.locale.safeFormatLocalized("Bad -30% to +30% ~%s", "8 mo")
        assertTrue(recovered.contains("8 mo"))
    }

    // =========================================================================
    // Stage 3 (v3) — Toccata Transaction v1, BLAKE3, KIP-20, 9 Covenants VM,
    //                .kinetic Backup, KKCV1 Payload, & Conflict-Free Merge Tests
    // =========================================================================

    @Test
    fun blake3AndTransactionV1MatchOfficialRustyKaspaTestVectors() {
        // 1. Official rusty-kaspa PayloadDigest BLAKE3 vectors (crypto/hashes/src/hashers.rs)
        assertEquals(
            "9c0ca2acb45e92ffe6ceb4ae29188b35c82d9676cdd3ce067fd6ccc30a9c4a38",
            KaspaCrypto.bytesToHex(KaspaCrypto.blake3(ByteArray(0), KaspaMassAndTx.PAYLOAD_DIGEST_DOMAIN)),
        )
        assertEquals(
            "a69883bfb2bb000249639d8bbf5019fd08ceb7daa7a86f170530522dfedba124",
            KaspaCrypto.bytesToHex(KaspaCrypto.blake3(byteArrayOf(1), KaspaMassAndTx.PAYLOAD_DIGEST_DOMAIN)),
        )

        // 2. Official rusty-kaspa tx.rs Test #10 (version = 1 with payload [1,2,3], subnetwork REGISTRY)
        val dummyUtxo = UtxoItem(
            address = "kaspa:qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqkx9awp4e",
            transactionId = "59b3d6dc6cdc660c389c3fdb5704c48c598d279cdf1bab54182db586a4c95dd5",
            index = 2,
            amountSompi = 1000L,
            scriptPublicKeyHex = "20" + "00".repeat(32) + "ac",
        )
        val subnetworkRegistry = ByteArray(20).apply { this[0] = 2 }
        val tx10 = KaspaMassAndTx.RawTx(
            version = 1,
            inputs = listOf(
                KaspaMassAndTx.TxInput(
                    outpointTxId = "59b3d6dc6cdc660c389c3fdb5704c48c598d279cdf1bab54182db586a4c95dd5",
                    outpointIndex = 2,
                    signatureScript = byteArrayOf(1, 2),
                    sequence = 7L,
                    sigOpCount = 5,
                    utxo = dummyUtxo,
                    computeBudget = 0,
                ),
            ),
            outputs = listOf(
                KaspaMassAndTx.TxOutput(
                    valueSompi = 1564L,
                    scriptPublicKeyVersion = 7,
                    scriptPublicKey = byteArrayOf(1, 2, 3, 4, 5),
                    address = "",
                    covenant = null,
                ),
            ),
            lockTime = 54L,
            subnetworkId = subnetworkRegistry,
            gas = 3L,
            payload = byteArrayOf(1, 2, 3),
            storageMassOverride = 0L,
        )
        assertEquals(
            "a08a500b21be3e692c080b14e399fcfa2cfa01b25c08f2f8e7414d1c116e8d18",
            KaspaMassAndTx.computeTransactionId(tx10),
        )
        assertEquals(
            "773f5582d847a1c48947eb4e6e6ac569f90f0f9d979b4c939b72ef008f025e02",
            KaspaMassAndTx.computeTransactionHash(tx10),
        )

        // 3. Official rusty-kaspa tx.rs Tests #11 & #12: compute_budget affects tx::hash, NOT txid!
        val zeroTxId = "00".repeat(32)
        val tx11 = KaspaMassAndTx.RawTx(
            version = 1,
            inputs = listOf(
                KaspaMassAndTx.TxInput(
                    outpointTxId = zeroTxId,
                    outpointIndex = 0,
                    signatureScript = ByteArray(0),
                    sequence = 0L,
                    sigOpCount = 0,
                    utxo = dummyUtxo,
                    computeBudget = 111,
                ),
            ),
            outputs = emptyList(),
            lockTime = 0L,
            subnetworkId = KaspaMassAndTx.SUBNETWORK_ID_NATIVE,
            gas = 0L,
            payload = ByteArray(0),
            storageMassOverride = 0L,
        )
        val tx12 = tx11.copy(inputs = listOf(tx11.inputs[0].copy(computeBudget = 222)))
        assertEquals(
            "5978e7aa1a9ba8fdf12dae6aa39aa198a91985e91192b291e207d4d6246349e6",
            KaspaMassAndTx.computeTransactionId(tx11),
        )
        assertEquals(
            "5978e7aa1a9ba8fdf12dae6aa39aa198a91985e91192b291e207d4d6246349e6",
            KaspaMassAndTx.computeTransactionId(tx12),
        )
        assertEquals(
            "c41c18964aab2abe309a79de3dcf0353eee216e29ab83448cbec0c4c5792056c",
            KaspaMassAndTx.computeTransactionHash(tx11),
        )
        assertEquals(
            "415dfbc5b38e5805e20831d43a49bc770f4f591b00964ac922d108f6a224c590",
            KaspaMassAndTx.computeTransactionHash(tx12),
        )

        // 4. Official rusty-kaspa sighash.rs v1 vector: compute_budget is excluded from v1 sighash!
        val prevTxId = "880eb9819a31821d9d2399e2f35e2433b72637e393d71ecc9b8d0250f49153c3"
        val spk1Hex = "208325613d2eeaf7176ac6c670b13c0043156c427438ed72d74b7800862ad884e8ac"
        val spk2Hex = "20fcef4c106cf11135bbd70f02a726a92162d2fb8b22f0469126f800862ad884e8ac"
        val v1SigTx = KaspaMassAndTx.RawTx(
            version = 1,
            inputs = listOf(
                KaspaMassAndTx.TxInput(
                    prevTxId, 0, ByteArray(0), 0L, 0,
                    UtxoItem("", prevTxId, 0, 100L, spk1Hex), computeBudget = 11,
                ),
                KaspaMassAndTx.TxInput(
                    prevTxId, 1, ByteArray(0), 1L, 0,
                    UtxoItem("", prevTxId, 1, 200L, spk2Hex), computeBudget = 22,
                ),
                KaspaMassAndTx.TxInput(
                    prevTxId, 2, ByteArray(0), 2L, 0,
                    UtxoItem("", prevTxId, 2, 300L, spk2Hex), computeBudget = 33,
                ),
            ),
            outputs = listOf(
                KaspaMassAndTx.TxOutput(300L, 0, KaspaCrypto.hexToBytes(spk2Hex), ""),
                KaspaMassAndTx.TxOutput(300L, 0, KaspaCrypto.hexToBytes(spk1Hex), ""),
            ),
            lockTime = 1615462089000L,
            subnetworkId = KaspaMassAndTx.SUBNETWORK_ID_NATIVE,
            gas = 0L,
            payload = ByteArray(0),
        )
        assertEquals(
            "5b2657524be672e019897646b56da3d192b453d78ae5e6e5c07f029a69f5f075",
            KaspaCrypto.bytesToHex(KaspaMassAndTx.computeSigHashAll(v1SigTx, 0)),
        )
        // Mutating computeBudget MUST NOT change v1 sighash
        val v1SigTxModifiedBudget = v1SigTx.copy(
            inputs = v1SigTx.inputs.mapIndexed { idx, i -> if (idx == 0) i.copy(computeBudget = 1234) else i },
        )
        assertEquals(
            "5b2657524be672e019897646b56da3d192b453d78ae5e6e5c07f029a69f5f075",
            KaspaCrypto.bytesToHex(KaspaMassAndTx.computeSigHashAll(v1SigTxModifiedBudget, 0)),
        )

        // 5. Output CovenantBinding MUST change id_v1, tx::hash, AND v1 sighash!
        val covId = KaspaMassAndTx.computeCovenantId(prevTxId, 0, listOf(0 to v1SigTx.outputs[0]))
        assertEquals(64, covId.length)
        val v1WithCov = v1SigTx.copy(
            outputs = listOf(
                v1SigTx.outputs[0].copy(covenant = KaspaMassAndTx.CovenantBinding(0, covId)),
                v1SigTx.outputs[1],
            ),
        )
        assertNotEquals(KaspaMassAndTx.computeTransactionId(v1SigTx), KaspaMassAndTx.computeTransactionId(v1WithCov))
        assertNotEquals(KaspaMassAndTx.computeTransactionHash(v1SigTx), KaspaMassAndTx.computeTransactionHash(v1WithCov))
        assertNotEquals(
            KaspaCrypto.bytesToHex(KaspaMassAndTx.computeSigHashAll(v1SigTx, 0)),
            KaspaCrypto.bytesToHex(KaspaMassAndTx.computeSigHashAll(v1WithCov, 0)),
        )
    }

    @Test
    fun kcc01DispatchTagPushDataFramingAndProtobufV1CodecWorkCleanly() {
        val tag = com.kaspakinetic.wallet.core.covenants.SilAbiCodec.computeKcc01DispatchTag("withdraw(sig)")
        assertEquals(4, tag.size)
        val fullBlake3 = KaspaCrypto.blake3("withdraw(sig)".toByteArray(Charsets.UTF_8))
        org.junit.Assert.assertArrayEquals(fullBlake3.copyOfRange(0, 4), tag)

        // Redeem scripts > 75 bytes MUST use OP_PUSHDATA1 (0x4c), and > 255 bytes MUST use OP_PUSHDATA2 (0x4d)
        val pushed80 = com.kaspakinetic.wallet.core.covenants.KaspaScriptBuilder().pushRawBytes(ByteArray(80) { 0x51 }).toByteArray()
        assertEquals(0x4C, pushed80[0].toInt() and 0xFF)
        assertEquals(80, pushed80[1].toInt() and 0xFF)

        val pushed300 = com.kaspakinetic.wallet.core.covenants.KaspaScriptBuilder().pushRawBytes(ByteArray(300) { 0x51 }).toByteArray()
        assertEquals(0x4D, pushed300[0].toInt() and 0xFF)
        assertEquals(300 and 0xFF, pushed300[1].toInt() and 0xFF)
        assertEquals((300 ushr 8) and 0xFF, pushed300[2].toInt() and 0xFF)

        // Protobuf v1 SubmitTransactionRequest encoding + RpcUtxoEntry covenant_id field 6 decoding
        val v1Json = """
            {
              "transaction": {
                "version": 1,
                "inputs": [{"previousOutpoint":{"transactionId":"${"aa".repeat(32)}","index":0},"signatureScript":"0102","sequence":0,"computeBudget":3}],
                "outputs": [{"amount":100000,"scriptPublicKey":{"version":0,"scriptPublicKey":"aa20${"11".repeat(32)}87"},"covenant":{"authorizingInput":0,"covenantId":"${"bb".repeat(32)}"}}],
                "lockTime": 0,
                "subnetworkId": "0000000000000000000000000000000000000000",
                "payload": "4b4b435631",
                "storageMass": 1200
              },
              "allowOrphan": false
            }
        """.trimIndent()
        val reqBytes = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.encodeSubmitTransactionRequest(77L, v1Json)
        assertTrue(reqBytes.isNotEmpty())
        assertTrue(String(reqBytes, Charsets.ISO_8859_1).contains("bb".repeat(32)))

        // GetUtxosByCovenantIds (1122/1123) round-trip
        val covIdHex = "cc".repeat(32)
        val covReq = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.encodeGetUtxosByCovenantIdsRequest(88L, listOf(covIdHex))
        assertTrue(covReq.isNotEmpty())
        val spkMsg = concatBytes(encodeVarintFieldForTest(1, 0L), encodeStringFieldForTest(2, "aa20${"11".repeat(32)}87"))
        val utxoEntryMsg = concatBytes(
            encodeVarintFieldForTest(1, 500_000_000L),
            encodeLengthDelimitedFieldForTest(2, spkMsg),
            encodeVarintFieldForTest(3, 475_000_123L),
            encodeStringFieldForTest(6, covIdHex),
        )
        val outpointMsg = concatBytes(encodeStringFieldForTest(1, "dd".repeat(32)), encodeVarintFieldForTest(2, 0L))
        val entryMsg = concatBytes(
            encodeStringFieldForTest(1, "kaspatest:pr" + "q".repeat(59)),
            encodeLengthDelimitedFieldForTest(2, outpointMsg),
            encodeLengthDelimitedFieldForTest(3, utxoEntryMsg),
        )
        val respWire = concatBytes(
            encodeVarintFieldForTest(101, 88L),
            encodeLengthDelimitedFieldForTest(1123, encodeLengthDelimitedFieldForTest(1, entryMsg)),
        )
        val decoded = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.decodeResponse(respWire)
        val utxos = (decoded.payload as com.kaspakinetic.wallet.core.network.KaspadProtoCodec.Payload.Utxos).utxos
        assertEquals(1, utxos.size)
        assertEquals(covIdHex, utxos[0].covenantId)
    }

    @Test
    fun allSevenConsumerCovenantsCompileAndPassLocalKaspaScriptVmPositiveAndNegativeTests() {
        // Strictly 7 curated consumer covenants — HTLC, Bounded P2P Escrow, and AI Agent Spend Card MUST be absent!
        val allTemplates = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.entries
        assertEquals(7, allTemplates.size)
        assertFalse(allTemplates.any { it.id.contains("htlc", ignoreCase = true) || it.id.contains("escrow", ignoreCase = true) || it.id.contains("agent", ignoreCase = true) })

        val seed = Bip39.mnemonicToSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "")
        val ownerPriv = KaspaCrypto.derivePrivateKeyFromSeed(seed, 0, 0)
        val ownerPub = KaspaCrypto.xOnlyPublicKey(ownerPriv)
        val ownerPubHex = KaspaCrypto.bytesToHex(ownerPub)
        val ownerAddr = KaspaAddress.encodePubKey(ownerPub, KaspaNetwork.TESTNET_10)

        val altPriv = KaspaCrypto.derivePrivateKeyFromSeed(seed, 0, 1)
        val altPub = KaspaCrypto.xOnlyPublicKey(altPriv)
        val altPubHex = KaspaCrypto.bytesToHex(altPub)
        val altAddr = KaspaAddress.encodePubKey(altPub, KaspaNetwork.TESTNET_10)

        val thirdPriv = KaspaCrypto.derivePrivateKeyFromSeed(seed, 0, 2)
        val thirdPub = KaspaCrypto.xOnlyPublicKey(thirdPriv)
        val thirdPubHex = KaspaCrypto.bytesToHex(thirdPub)
        val thirdAddr = KaspaAddress.encodePubKey(thirdPub, KaspaNetwork.TESTNET_10)

        val dummyCovId = "ab".repeat(32)
        val dummyTxId = "cd".repeat(32)

        fun makeTrackedAndUtxo(
            tpl: com.kaspakinetic.wallet.core.covenants.CovenantTemplateId,
            params: com.kaspakinetic.wallet.core.covenants.CovenantParams,
            balanceSompi: Long = 200_000_000L,
            blockDaa: Long = 1_000_000L,
        ): Pair<com.kaspakinetic.wallet.core.covenants.TrackedCovenant, UtxoItem> {
            val bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(tpl, params, KaspaNetwork.TESTNET_10)
            val redeemHex = KaspaCrypto.bytesToHex(bundle.redeemScript)
            // Verify NO OP_DROP immediately follows OP_CHECKSEQUENCEVERIFY (0xB1) in any compiled script!
            for (i in 0 until bundle.redeemScript.size - 1) {
                if ((bundle.redeemScript[i].toInt() and 0xFF) == com.kaspakinetic.wallet.core.covenants.KaspaOpcodes.OP_CHECKSEQUENCEVERIFY) {
                    assertNotEquals(
                        "CSV in ${tpl.id} must NOT emit trailing OP_DROP!",
                        com.kaspakinetic.wallet.core.covenants.KaspaOpcodes.OP_DROP,
                        bundle.redeemScript[i + 1].toInt() and 0xFF,
                    )
                }
            }
            val cov = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
                canonicalKey = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.canonicalKeyFor(
                    KaspaNetwork.TESTNET_10, tpl, if (tpl.isStatefulKip20) dummyCovId else null, bundle.redeemScriptHashHex,
                ),
                walletId = "w1",
                network = KaspaNetwork.TESTNET_10,
                templateId = tpl,
                label = tpl.contractName,
                covenantIdHex = if (tpl.isStatefulKip20) dummyCovId else null,
                currentP2shAddress = bundle.p2shAddress,
                currentRedeemScriptHex = redeemHex,
                redeemScriptHashHex = bundle.redeemScriptHashHex,
                silSource = bundle.silSource,
                abiArtifact = bundle.abiArtifact,
                params = params,
                liveBalanceSompi = balanceSompi,
                liveUtxoTxId = dummyTxId,
                liveUtxoIndex = 0,
                liveUtxoBlockDaaScore = blockDaa,
            )
            val utxo = UtxoItem(
                address = bundle.p2shAddress,
                transactionId = dummyTxId,
                index = 0,
                amountSompi = balanceSompi,
                scriptPublicKeyHex = KaspaCrypto.bytesToHex(bundle.p2shScriptPubKey),
                blockDaaScore = blockDaa,
                covenantId = cov.covenantIdHex,
            )
            return cov to utxo
        }

        val ownerSpk = KaspaAddress.decode(ownerAddr)!!.toScriptPublicKey()
        val altSpk = KaspaAddress.decode(altAddr)!!.toScriptPublicKey()
        val thirdSpk = KaspaAddress.decode(thirdAddr)!!.toScriptPublicKey()

        // 1. Clawback Vault: initiateUnvault + instant alarmClawback
        val (clawCov, clawUtxo) = makeTrackedAndUtxo(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.CLAWBACK_VAULT,
            com.kaspakinetic.wallet.core.covenants.CovenantParams(
                ownerPubKeyHex = ownerPubHex, ownerAddress = ownerAddr,
                alarmPubKeyHex = altPubHex, recoveryAddress = thirdAddr, unvaultDelayDaa = 1000L, vaultStage = 0,
            ),
        )
        val clawUnvault = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
            clawCov, clawUtxo, "initiateUnvault",
            outputs = listOf(KaspaMassAndTx.TxOutput(199_990_000L, 0, KaspaCrypto.hexToBytes(clawUtxo.scriptPublicKeyHex), clawUtxo.address, KaspaMassAndTx.CovenantBinding(0, dummyCovId))),
            signerPrivateKeys = listOf(ownerPriv),
        )
        assertTrue(clawUnvault.vmResult!!.success)
        val clawAlarm = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
            clawCov, clawUtxo, "alarmClawback",
            outputs = listOf(KaspaMassAndTx.TxOutput(199_990_000L, 0, thirdSpk, thirdAddr)),
            signerPrivateKeys = listOf(altPriv),
        )
        assertTrue(clawAlarm.vmResult!!.success)

        // 2. Dead Man's Switch: checkIn + heirClaim (and early heirClaim rejection)
        val (dmsCov, dmsUtxo) = makeTrackedAndUtxo(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH,
            com.kaspakinetic.wallet.core.covenants.CovenantParams(
                ownerPubKeyHex = ownerPubHex, ownerAddress = ownerAddr,
                heirAddress = altAddr, heirPubKeyHex = altPubHex, inactivityDaa = 5000L,
            ),
        )
        assertTrue(
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                dmsCov, dmsUtxo, "checkIn",
                outputs = listOf(KaspaMassAndTx.TxOutput(199_990_000L, 0, KaspaCrypto.hexToBytes(dmsUtxo.scriptPublicKeyHex), dmsUtxo.address, KaspaMassAndTx.CovenantBinding(0, dummyCovId))),
                signerPrivateKeys = listOf(ownerPriv),
            ).vmResult!!.success,
        )
        assertTrue(
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                dmsCov, dmsUtxo, "heirClaim",
                outputs = listOf(KaspaMassAndTx.TxOutput(199_990_000L, 0, altSpk, altAddr)),
                sequence = 5000L, virtualDaaScore = 1_006_000L, signerPrivateKeys = listOf(altPriv),
            ).vmResult!!.success,
        )
        assertTrue(
            runCatching {
                com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                    dmsCov, dmsUtxo, "heirClaim",
                    outputs = listOf(KaspaMassAndTx.TxOutput(199_990_000L, 0, altSpk, altAddr)),
                    sequence = 5000L, virtualDaaScore = 1_001_000L, signerPrivateKeys = listOf(altPriv),
                )
            }.isFailure,
        )

        // 3. Daily Spend Limit: within cap PASS, over cap FAIL
        val (dailyCov, dailyUtxo) = makeTrackedAndUtxo(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DAILY_SPEND_LIMIT,
            com.kaspakinetic.wallet.core.covenants.CovenantParams(ownerPubKeyHex = ownerPubHex, ownerAddress = ownerAddr, dailyCapSompi = 50_000_000L),
        )
        assertTrue(
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                dailyCov, dailyUtxo, "spendWithinLimit",
                outputs = listOf(
                    KaspaMassAndTx.TxOutput(40_000_000L, 0, altSpk, altAddr),
                    KaspaMassAndTx.TxOutput(159_990_000L, 0, KaspaCrypto.hexToBytes(dailyUtxo.scriptPublicKeyHex), dailyUtxo.address, KaspaMassAndTx.CovenantBinding(0, dummyCovId)),
                ),
                signerPrivateKeys = listOf(ownerPriv),
            ).vmResult!!.success,
        )
        assertTrue(
            runCatching {
                com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                    dailyCov, dailyUtxo, "spendWithinLimit",
                    outputs = listOf(
                        KaspaMassAndTx.TxOutput(60_000_000L, 0, altSpk, altAddr),
                        KaspaMassAndTx.TxOutput(139_990_000L, 0, KaspaCrypto.hexToBytes(dailyUtxo.scriptPublicKeyHex), dailyUtxo.address, KaspaMassAndTx.CovenantBinding(0, dummyCovId)),
                    ),
                    signerPrivateKeys = listOf(ownerPriv),
                )
            }.isFailure,
        )

        // 4. Cold-Only Address Whitelist: whitelisted PASS, non-whitelisted FAIL
        val (wlCov, wlUtxo) = makeTrackedAndUtxo(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.COLD_WHITELIST,
            com.kaspakinetic.wallet.core.covenants.CovenantParams(
                ownerPubKeyHex = ownerPubHex, ownerAddress = ownerAddr, whitelistAddresses = listOf(ownerAddr, altAddr),
            ),
        )
        assertTrue(
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                wlCov, wlUtxo, "spend",
                outputs = listOf(KaspaMassAndTx.TxOutput(199_990_000L, 0, altSpk, altAddr)),
                signerPrivateKeys = listOf(ownerPriv),
            ).vmResult!!.success,
        )
        assertTrue(
            runCatching {
                com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                    wlCov, wlUtxo, "spend",
                    outputs = listOf(KaspaMassAndTx.TxOutput(199_990_000L, 0, thirdSpk, thirdAddr)),
                    signerPrivateKeys = listOf(ownerPriv),
                )
            }.isFailure,
        )

        // 5. Hodl Time-Lock: unlocked PASS, early FAIL
        val (hodlCov, hodlUtxo) = makeTrackedAndUtxo(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.HODL_TIMELOCK,
            com.kaspakinetic.wallet.core.covenants.CovenantParams(ownerPubKeyHex = ownerPubHex, ownerAddress = ownerAddr, unlockDaa = 2_000_000L),
        )
        assertTrue(
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                hodlCov, hodlUtxo, "unlock",
                outputs = listOf(KaspaMassAndTx.TxOutput(199_990_000L, 0, ownerSpk, ownerAddr)),
                lockTime = 2_000_000L, virtualDaaScore = 2_000_500L, signerPrivateKeys = listOf(ownerPriv),
            ).vmResult!!.success,
        )
        assertTrue(
            runCatching {
                com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                    hodlCov, hodlUtxo, "unlock",
                    outputs = listOf(KaspaMassAndTx.TxOutput(199_990_000L, 0, ownerSpk, ownerAddr)),
                    lockTime = 1_500_000L, virtualDaaScore = 1_500_000L, signerPrivateKeys = listOf(ownerPriv),
                )
            }.isFailure,
        )

        // 6. Goal-Locked Piggy Bank: goal reached PASS, fallback PASS, below goal FAIL
        val (goalCov, goalUtxo) = makeTrackedAndUtxo(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.GOAL_PIGGY_BANK,
            com.kaspakinetic.wallet.core.covenants.CovenantParams(
                ownerPubKeyHex = ownerPubHex, ownerAddress = ownerAddr, targetGoalSompi = 500_000_000L, fallbackUnlockDaa = 3_000_000L,
            ),
            balanceSompi = 200_000_000L,
        )
        assertTrue(
            runCatching {
                com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                    goalCov, goalUtxo, "unlockGoal",
                    outputs = listOf(KaspaMassAndTx.TxOutput(199_990_000L, 0, ownerSpk, ownerAddr)),
                    signerPrivateKeys = listOf(ownerPriv),
                )
            }.isFailure,
        )
        assertTrue(
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                goalCov, goalUtxo, "unlockFallback",
                outputs = listOf(KaspaMassAndTx.TxOutput(199_990_000L, 0, ownerSpk, ownerAddr)),
                lockTime = 3_000_000L, virtualDaaScore = 3_000_100L, signerPrivateKeys = listOf(ownerPriv),
            ).vmResult!!.success,
        )

        // 7. Recurring Allowance (Mecenas): pull allowance + owner cancel
        val (recCov, recUtxo) = makeTrackedAndUtxo(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            com.kaspakinetic.wallet.core.covenants.CovenantParams(
                ownerPubKeyHex = ownerPubHex, ownerAddress = ownerAddr,
                recipientAddress = altAddr, recipientPubKeyHex = altPubHex,
                allowanceSompi = 25_000_000L, periodDaa = 1_000L,
            ),
        )
        assertTrue(
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                recCov, recUtxo, "withdraw",
                outputs = listOf(
                    KaspaMassAndTx.TxOutput(25_000_000L, 0, altSpk, altAddr),
                    KaspaMassAndTx.TxOutput(174_990_000L, 0, KaspaCrypto.hexToBytes(recUtxo.scriptPublicKeyHex), recUtxo.address, KaspaMassAndTx.CovenantBinding(0, dummyCovId)),
                ),
                sequence = 1_000L, virtualDaaScore = 1_002_000L, signerPrivateKeys = listOf(altPriv),
            ).vmResult!!.success,
        )
        assertTrue(
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                recCov, recUtxo, "cancelReclaim",
                outputs = listOf(KaspaMassAndTx.TxOutput(199_990_000L, 0, ownerSpk, ownerAddr)),
                signerPrivateKeys = listOf(ownerPriv),
            ).vmResult!!.success,
        )

        // Strictly 7 curated consumer covenants (Bounded P2P Escrow & AI Agent Spend Card removed in v3.1)
        assertEquals(7, com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.entries.size)
    }

    @Test
    fun kineticBackupFilenameEncryptionKkcv1AndConflictFreeMergeWorkFlawlessly() {
        // 1. Default `.kinetic` filename sanitization
        val createdMs = 1795305600000L // 2026-11-23 UTC
        val updatedMs = 1795564800000L // 2026-11-26 UTC
        val fn = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.buildDefaultKineticFilename(
            walletName = "  Hot wallet (unsecured/key:*?\"<>|)  ",
            createdAtMs = createdMs,
            lastUpdatedAtMs = updatedMs,
        )
        assertTrue(fn.startsWith("Hot_wallet_unsecured_key_"))
        assertTrue(fn.endsWith(".kinetic"))
        assertFalse(fn.contains("(") || fn.contains("/") || fn.contains(":") || fn.contains("*"))

        // 2. `.kinetic` AES-256-GCM seed & passphrase round-trip + wrong passphrase rejection + atomic write
        val seedA = Bip39.mnemonicToSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "")
        val seedB = Bip39.mnemonicToSeed("legal winner thank year wave sausage worth useful legal winner thank yellow", "")
        val keyA = KaspaCrypto.deriveCovenantBackupKey(seedA)
        val keyB = KaspaCrypto.deriveCovenantBackupKey(seedB)

        val ownerPubHex = KaspaCrypto.bytesToHex(KaspaCrypto.xOnlyPublicKey(KaspaCrypto.derivePrivateKeyFromSeed(seedA, 0, 0)))
        val ownerAddr = KaspaAddress.encodePubKey(KaspaCrypto.hexToBytes(ownerPubHex), KaspaNetwork.TESTNET_10)
        val p1 = com.kaspakinetic.wallet.core.covenants.CovenantParams(ownerPubKeyHex = ownerPubHex, ownerAddress = ownerAddr, unlockDaa = 5_000_000L)
        val b1 = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.HODL_TIMELOCK, p1, KaspaNetwork.TESTNET_10,
        )
        val cov1 = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.canonicalKeyFor(
                KaspaNetwork.TESTNET_10, com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.HODL_TIMELOCK, null, b1.redeemScriptHashHex,
            ),
            walletId = "w1",
            network = KaspaNetwork.TESTNET_10,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.HODL_TIMELOCK,
            label = "My Hodl Vault",
            currentP2shAddress = b1.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(b1.redeemScript),
            redeemScriptHashHex = b1.redeemScriptHashHex,
            silSource = b1.silSource,
            abiArtifact = b1.abiArtifact,
            params = p1,
            liveBalanceSompi = 500_000_000L,
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.ACTIVE,
            lastChainVerifiedAtMs = 2000L,
        )
        val vaultPayload = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.KineticVaultPayload(
            walletId = "w1",
            walletName = "Main Kaspa Wallet",
            walletFingerprint = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.walletFingerprint(ownerAddr),
            createdAtMs = createdMs,
            lastUpdatedAtMs = updatedMs,
            covenants = listOf(cov1),
        )

        val seedEncrypted = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.encryptKineticBackup(vaultPayload, seedBackupKey32 = keyA)
        val tmpFile = java.io.File.createTempFile("kinetic_test", ".kinetic")
        try {
            com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.writeFileAtomically(tmpFile, seedEncrypted)
            val restoredSeed = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.decryptKineticBackup(tmpFile.readBytes(), seedBackupKey32 = keyA)
            assertEquals(1, restoredSeed.covenants.size)
            assertEquals("My Hodl Vault", restoredSeed.covenants[0].label)
            assertTrue(runCatching {
                com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.decryptKineticBackup(tmpFile.readBytes(), seedBackupKey32 = keyB)
            }.isFailure)
        } finally {
            tmpFile.delete()
        }

        val passEncrypted = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.encryptKineticBackup(vaultPayload, passphrase = "correct-horse-battery")
        val restoredPass = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.decryptKineticBackup(passEncrypted, passphrase = "correct-horse-battery")
        assertEquals(1, restoredPass.covenants.size)
        assertTrue(runCatching {
            com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.decryptKineticBackup(passEncrypted, passphrase = "wrong-passphrase")
        }.isFailure)

        // 3. Seed-encrypted KKCV1 on-chain payload round-trip + wrong-seed rejection
        val recipe = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.Kkcv1Recipe(
            t = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.HODL_TIMELOCK.id,
            n = KaspaNetwork.TESTNET_10.id,
            l = "Recovered Hodl",
            c = null,
            p = p1,
        )
        val kkcv1Bytes = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.encodeKkcv1Payload(recipe, keyA)
        val decodedRecipe = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.tryDecodeKkcv1Payload(kkcv1Bytes, keyA)
        assertNotNull(decodedRecipe)
        assertEquals("Recovered Hodl", decodedRecipe!!.l)
        assertNull(com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.tryDecodeKkcv1Payload(kkcv1Bytes, keyB))

        // 4. Conflict & Partial-Pruning Merge Suite (local ∪ older .kinetic ∪ partial KKCV1):
        //    - ZERO duplicates
        //    - Older .kinetic NEVER deletes newer local covenant
        //    - Older .kinetic NEVER downgrades live chain balance/status!
        val cov2Id = "ef".repeat(32)
        val b2 = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.GOAL_PIGGY_BANK, p1, KaspaNetwork.TESTNET_10,
        )
        val cov2 = cov1.copy(
            canonicalKey = "kip20:testnet-10:$cov2Id",
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.GOAL_PIGGY_BANK,
            label = "Newer Piggy Bank",
            covenantIdHex = cov2Id,
            currentP2shAddress = b2.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(b2.redeemScript),
            redeemScriptHashHex = b2.redeemScriptHashHex,
            liveBalanceSompi = 900_000_000L,
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.ACTIVE,
            lastChainVerifiedAtMs = 5000L,
            lastUpdatedAtMs = 5000L,
        )
        val staleBackupCov1 = cov1.copy(
            label = "",
            liveBalanceSompi = 100_000_000L,
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.UNCONFIRMED,
            lastChainVerifiedAtMs = 500L, // OLDER than local 2000L!
            lastUpdatedAtMs = 500L,
        )
        val kkcv1Cov1 = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.reconstructFromKkcv1(decodedRecipe, "w1", "11".repeat(32))!!

        val merge1 = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.mergeAdditive(
            existing = listOf(cov1, cov2),
            incoming = listOf(staleBackupCov1),
            targetWalletId = "w1",
        )
        val merge2 = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.mergeAdditive(
            existing = merge1.merged,
            incoming = listOf(kkcv1Cov1, staleBackupCov1, cov2),
            targetWalletId = "w1",
        )
        assertEquals("Zero duplicates allowed across local + .kinetic + KKCV1!", 2, merge2.merged.size)
        val mergedCov1 = merge2.merged.first { it.canonicalKey == cov1.canonicalKey }
        assertEquals("Older backup MUST NOT downgrade live balance!", 500_000_000L, mergedCov1.liveBalanceSompi)
        assertEquals("Older backup MUST NOT wipe user label!", "My Hodl Vault", mergedCov1.label)
        assertTrue("Newer local covenant MUST survive older .kinetic import!", merge2.merged.any { it.canonicalKey == cov2.canonicalKey })

        // Live chain UTXO reconciliation is always Single Source of Truth
        val reconciled = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = merge2.merged,
            liveUtxos = listOf(
                UtxoItem(
                    address = cov1.currentP2shAddress,
                    transactionId = "22".repeat(32),
                    index = 0,
                    amountSompi = 777_000_000L,
                    scriptPublicKeyHex = KaspaCrypto.bytesToHex(b1.p2shScriptPubKey),
                    blockDaaScore = 5_000_001L,
                ),
            ),
            currentVirtualDaaScore = 5_000_050L,
            verifiedAtMs = 999_999L,
        )
        val liveCov1 = reconciled.first { it.canonicalKey == cov1.canonicalKey }
        val liveCov2 = reconciled.first { it.canonicalKey == cov2.canonicalKey }
        assertEquals(777_000_000L, liveCov1.liveBalanceSompi)
        assertEquals(com.kaspakinetic.wallet.core.covenants.CovenantStatus.READY, liveCov1.liveStatus)
        assertEquals(0L, liveCov2.liveBalanceSompi)
        assertEquals(com.kaspakinetic.wallet.core.covenants.CovenantStatus.SETTLED, liveCov2.liveStatus)
    }

    @Test
    fun stage3CovenantStringsAreTranslatedAcrossAll10Locales() {
        val en = com.kaspakinetic.wallet.ui.locale.KaspaWalletStrings()
        val locales = listOf(
            java.util.Locale.SIMPLIFIED_CHINESE,
            java.util.Locale.TRADITIONAL_CHINESE,
            java.util.Locale.JAPANESE,
            java.util.Locale.KOREAN,
            java.util.Locale("ar"),
            java.util.Locale("es"),
            java.util.Locale.FRENCH,
            java.util.Locale.GERMAN,
            java.util.Locale("pt"),
            java.util.Locale("ru"),
        )
        for (loc in locales) {
            val s = com.kaspakinetic.wallet.ui.locale.resolveKaspaWalletStrings(loc)
            assertNotEquals("Missing covHomeInactiveMessage for $loc", en.covHomeInactiveMessage, s.covHomeInactiveMessage)
            assertNotEquals("Missing covActivationTitle for $loc", en.covActivationTitle, s.covActivationTitle)
            assertNotEquals("Missing covActivationBackupBody for $loc", en.covActivationBackupBody, s.covActivationBackupBody)
            assertNotEquals("Missing covReminderPopupBody for $loc", en.covReminderPopupBody, s.covReminderPopupBody)
            assertNotEquals("Missing covBackupDownloadButton for $loc", en.covBackupDownloadButton, s.covBackupDownloadButton)
            assertNotEquals("Missing copyErrorButton for $loc", en.copyErrorButton, s.copyErrorButton)
            assertNotEquals("Missing covVerifyOnChainButton for $loc", en.covVerifyOnChainButton, s.covVerifyOnChainButton)
            assertNotEquals("Missing covTabYourCovenants for $loc", en.covTabYourCovenants, s.covTabYourCovenants)
            assertNotEquals("Missing covReadActivationWarningButton for $loc", en.covReadActivationWarningButton, s.covReadActivationWarningButton)
        }
    }

    @Test
    fun stage31UniqueVaultSaltPreventsP2shCollisionAndZombieResurrection() {
        val seed = Bip39.mnemonicToSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        val priv = KaspaCrypto.derivePrivateKeyFromSeed(seed, 0, 0)
        val pubHex = KaspaCrypto.bytesToHex(KaspaCrypto.xOnlyPublicKey(priv))
        val ownerAddr = KaspaAddress.encodePubKey(KaspaCrypto.hexToBytes(pubHex), KaspaNetwork.TESTNET_10)

        val salt1 = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex()
        val salt2 = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex()
        assertNotEquals(salt1, salt2)

        val p1 = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = pubHex, ownerAddress = ownerAddr, vaultSaltHex = salt1,
            heirAddress = ownerAddr, inactivityDaa = 25_920_000L,
        )
        val p2 = p1.copy(vaultSaltHex = salt2)

        val b1 = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH, p1, KaspaNetwork.TESTNET_10,
        )
        val b2 = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH, p2, KaspaNetwork.TESTNET_10,
        )
        assertNotEquals("Two vaults with identical user settings MUST NOT share the same P2SH address!", b1.p2shAddress, b2.p2shAddress)
        assertNotEquals(b1.redeemScriptHashHex, b2.redeemScriptHashHex)

        // Even if two legacy unsalted Dead Man's Switches shared a P2SH address, strict covenant_id
        // matching guarantees the old settled vault NEVER resurrects when a new one is funded!
        val legacyBundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH,
            p1.copy(vaultSaltHex = ""),
            KaspaNetwork.TESTNET_10,
        )
        val oldCovId = "11".repeat(32)
        val newCovId = "22".repeat(32)
        val oldSettled = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "kip20:testnet-10:$oldCovId",
            walletId = "w1",
            network = KaspaNetwork.TESTNET_10,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH,
            label = "Old Settled DMS",
            covenantIdHex = oldCovId,
            currentP2shAddress = legacyBundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(legacyBundle.redeemScript),
            redeemScriptHashHex = legacyBundle.redeemScriptHashHex,
            silSource = legacyBundle.silSource,
            abiArtifact = legacyBundle.abiArtifact,
            params = p1.copy(vaultSaltHex = ""),
            liveBalanceSompi = 0L,
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.SETTLED,
        )
        val newActive = oldSettled.copy(
            canonicalKey = "kip20:testnet-10:$newCovId",
            label = "New Active DMS",
            covenantIdHex = newCovId,
            liveBalanceSompi = 10L * SOMPI_PER_KAS,
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.ACTIVE,
        )
        val liveNewUtxo = UtxoItem(
            address = legacyBundle.p2shAddress,
            transactionId = "aa".repeat(32),
            index = 0,
            amountSompi = 10L * SOMPI_PER_KAS,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(legacyBundle.p2shScriptPubKey),
            blockDaaScore = 100_000L,
            covenantId = newCovId,
        )
        val reconciled = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(oldSettled, newActive),
            liveUtxos = listOf(liveNewUtxo),
            currentVirtualDaaScore = 100_500L,
        )
        val recOld = reconciled.first { it.covenantIdHex == oldCovId }
        val recNew = reconciled.first { it.covenantIdHex == newCovId }
        assertEquals("Settled DMS must stay SETTLED!", com.kaspakinetic.wallet.core.covenants.CovenantStatus.SETTLED, recOld.liveStatus)
        assertEquals(0L, recOld.liveBalanceSompi)
        assertEquals(com.kaspakinetic.wallet.core.covenants.CovenantStatus.ACTIVE, recNew.liveStatus)
        assertEquals(10L * SOMPI_PER_KAS, recNew.liveBalanceSompi)
    }

    @Test
    fun stage31DailySpendLimitImmediateDay1SpendDynamicWindowAndBypassUnlock() {
        val seed = Bip39.mnemonicToSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        val priv = KaspaCrypto.derivePrivateKeyFromSeed(seed, 0, 0)
        val pubHex = KaspaCrypto.bytesToHex(KaspaCrypto.xOnlyPublicKey(priv))
        val ownerAddr = KaspaAddress.encodePubKey(KaspaCrypto.hexToBytes(pubHex), KaspaNetwork.TESTNET_10)
        val ownerSpk = KaspaAddress.decode(ownerAddr)!!.toScriptPublicKey()
        val covId = "33".repeat(32)

        val params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = pubHex,
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            dailyCapSompi = 5L * SOMPI_PER_KAS,
            windowDaa = 600L, // 1 minute dynamic window
            spentInWindowSompi = 0L,
            windowStartDaa = 1_000_000L,
            fullWithdrawUnlockDaa = 1_005_000L,
        )
        val bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DAILY_SPEND_LIMIT,
            params,
            KaspaNetwork.TESTNET_10,
        )
        val cov = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "kip20:testnet-10:$covId",
            walletId = "w1",
            network = KaspaNetwork.TESTNET_10,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DAILY_SPEND_LIMIT,
            label = "Daily Card",
            covenantIdHex = covId,
            currentP2shAddress = bundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(bundle.redeemScript),
            redeemScriptHashHex = bundle.redeemScriptHashHex,
            silSource = bundle.silSource,
            abiArtifact = bundle.abiArtifact,
            params = params,
        )
        val utxo = UtxoItem(
            address = bundle.p2shAddress,
            transactionId = "bb".repeat(32),
            index = 0,
            amountSompi = 10L * SOMPI_PER_KAS,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(bundle.p2shScriptPubKey),
            blockDaaScore = 1_000_000L,
            covenantId = covId,
        )

        // 1. Immediate Day-1 spend (ageDaa = 0!) within 5 KAS cap MUST SUCCEED immediately!
        val day1Spend = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
            covenant = cov,
            covenantUtxo = utxo,
            entryName = "spendWithinLimit",
            outputs = listOf(
                KaspaMassAndTx.TxOutput(4L * SOMPI_PER_KAS, 0, ownerSpk, ownerAddr),
                KaspaMassAndTx.TxOutput(5_500_000_000L, 0, bundle.p2shScriptPubKey, bundle.p2shAddress, KaspaMassAndTx.CovenantBinding(0, covId)),
            ),
            virtualDaaScore = 1_000_000L, // Zero blocks aged -> Day 1 immediate!
            signerPrivateKeys = listOf(priv.copyOf()),
        )
        assertTrue(day1Spend.vmResult!!.success)

        // 2. Dynamic reset spend (`spendAfterReset`) MUST FAIL before windowDaa ages and PASS once ageDaa >= windowDaa
        assertTrue(
            "spendAfterReset must fail before windowDaa elapses (ageDaa=100 < 600)",
            runCatching {
                com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                    covenant = cov, covenantUtxo = utxo, entryName = "spendAfterReset",
                    outputs = listOf(
                        KaspaMassAndTx.TxOutput(5L * SOMPI_PER_KAS, 0, ownerSpk, ownerAddr),
                        KaspaMassAndTx.TxOutput(4_500_000_000L, 0, bundle.p2shScriptPubKey, bundle.p2shAddress, KaspaMassAndTx.CovenantBinding(0, covId)),
                    ),
                    sequence = 600L, virtualDaaScore = 1_000_100L, signerPrivateKeys = listOf(priv.copyOf()),
                )
            }.isFailure,
        )
        assertTrue(
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                covenant = cov, covenantUtxo = utxo, entryName = "spendAfterReset",
                outputs = listOf(
                    KaspaMassAndTx.TxOutput(5L * SOMPI_PER_KAS, 0, ownerSpk, ownerAddr),
                    KaspaMassAndTx.TxOutput(4_500_000_000L, 0, bundle.p2shScriptPubKey, bundle.p2shAddress, KaspaMassAndTx.CovenantBinding(0, covId)),
                ),
                sequence = 600L, virtualDaaScore = 1_000_700L, signerPrivateKeys = listOf(priv.copyOf()),
            ).vmResult!!.success,
        )

        // 3. Full withdraw bypass (`ownerReclaim`) fails before fullWithdrawUnlockDaa and succeeds once reached!
        assertTrue(
            runCatching {
                com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                    covenant = cov, covenantUtxo = utxo, entryName = "ownerReclaim",
                    outputs = listOf(KaspaMassAndTx.TxOutput(9_500_000_000L, 0, ownerSpk, ownerAddr)),
                    lockTime = 1_005_000L, virtualDaaScore = 1_002_000L, signerPrivateKeys = listOf(priv.copyOf()),
                )
            }.isFailure,
        )
        assertTrue(
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                covenant = cov, covenantUtxo = utxo, entryName = "ownerReclaim",
                outputs = listOf(KaspaMassAndTx.TxOutput(9_500_000_000L, 0, ownerSpk, ownerAddr)),
                lockTime = 1_005_000L, virtualDaaScore = 1_005_100L, signerPrivateKeys = listOf(priv.copyOf()),
            ).vmResult!!.success,
        )
    }

    @Test
    fun stage31PiggyBankPreservesFullDepositTopUpAboveGoalProofUrlsAndUntruncatedErrors() {
        val seed = Bip39.mnemonicToSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        val priv = KaspaCrypto.derivePrivateKeyFromSeed(seed, 0, 0)
        val pubHex = KaspaCrypto.bytesToHex(KaspaCrypto.xOnlyPublicKey(priv))
        val ownerAddr = KaspaAddress.encodePubKey(KaspaCrypto.hexToBytes(pubHex), KaspaNetwork.TESTNET_10)
        val ownerSpk = KaspaAddress.decode(ownerAddr)!!.toScriptPublicKey()
        val ownerSpkHex = KaspaCrypto.bytesToHex(ownerSpk)

        // 1. Piggy Bank deploy preserves exact 10.00 KAS deposit (miner fee paid strictly from funding UTXOs!)
        val params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = pubHex,
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            targetGoalSompi = 15L * SOMPI_PER_KAS,
            fallbackUnlockDaa = 9_000_000L,
        )
        val bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.GOAL_PIGGY_BANK,
            params,
            KaspaNetwork.TESTNET_10,
        )
        val funding = UtxoItem(
            address = ownerAddr, transactionId = "cc".repeat(32), index = 0,
            amountSompi = 30L * SOMPI_PER_KAS, scriptPublicKeyHex = ownerSpkHex, blockDaaScore = 10_000L,
        )
        val deploy = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildAndSignDeployTxV1(
            bundle = bundle,
            amountSompi = 10L * SOMPI_PER_KAS,
            fundingUtxos = listOf(funding),
            changeAddress = ownerAddr,
            network = KaspaNetwork.TESTNET_10,
            keyLookup = { priv.copyOf() },
        )
        assertEquals("Full 10.00 KAS deposit must land untouched inside Piggy Bank!", 10L * SOMPI_PER_KAS, deploy.rawTx.outputs[0].valueSompi)

        // 2. Top-up ABOVE goal (10 KAS + 8 KAS = 18 KAS > 15 KAS goal) succeeds and fee comes from extra wallet UTXO!
        val covId = deploy.covenantIdHex!!
        val piggyCov = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "kip20:testnet-10:$covId", walletId = "w1", network = KaspaNetwork.TESTNET_10,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.GOAL_PIGGY_BANK,
            label = "Goal Piggy", covenantIdHex = covId,
            currentP2shAddress = bundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(bundle.redeemScript),
            redeemScriptHashHex = bundle.redeemScriptHashHex,
            silSource = bundle.silSource, abiArtifact = bundle.abiArtifact, params = params,
        )
        val covUtxo = UtxoItem(
            address = bundle.p2shAddress, transactionId = deploy.txId, index = 0,
            amountSompi = 10L * SOMPI_PER_KAS, scriptPublicKeyHex = KaspaCrypto.bytesToHex(bundle.p2shScriptPubKey),
            blockDaaScore = 10_050L, covenantId = covId,
        )
        val topUpFunding = UtxoItem(
            address = ownerAddr, transactionId = "dd".repeat(32), index = 1,
            amountSompi = 10L * SOMPI_PER_KAS, scriptPublicKeyHex = ownerSpkHex, blockDaaScore = 10_050L,
        )
        val topUpTx = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
            covenant = piggyCov,
            covenantUtxo = covUtxo,
            entryName = "topUp",
            outputs = listOf(
                KaspaMassAndTx.TxOutput(18L * SOMPI_PER_KAS, 0, bundle.p2shScriptPubKey, bundle.p2shAddress, KaspaMassAndTx.CovenantBinding(0, covId)),
                KaspaMassAndTx.TxOutput(1_500_000_000L, 0, ownerSpk, ownerAddr),
            ),
            signerPrivateKeys = listOf(priv.copyOf()),
            extraFundingUtxos = listOf(topUpFunding),
            extraKeyLookup = { priv.copyOf() },
        )
        assertTrue(topUpTx.vmResult!!.success)
        assertEquals(18L * SOMPI_PER_KAS, topUpTx.rawTx.outputs[0].valueSompi)

        // 3. Verify On-Chain script-hash proof & Mainnet / TN10 explorer URL generation
        assertTrue(
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.verifyRedeemScriptProof(
                KaspaCrypto.bytesToHex(bundle.redeemScript), bundle.p2shAddress, KaspaNetwork.TESTNET_10,
            ),
        )
        assertFalse(
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.verifyRedeemScriptProof(
                "00010203", bundle.p2shAddress, KaspaNetwork.TESTNET_10,
            ),
        )
        val sampleTx = "fe".repeat(32)
        assertEquals(
            "https://kaspa.stream/transactions/$sampleTx",
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.kaspaStreamUrl(sampleTx, bundle.p2shAddress, KaspaNetwork.MAINNET),
        )
        assertEquals(
            "https://tn10.kaspa.stream/transactions/$sampleTx",
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.kaspaStreamUrl(sampleTx, bundle.p2shAddress, KaspaNetwork.TESTNET_10),
        )
        assertEquals(
            "https://explorer.kaspa.org/txs/$sampleTx",
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.explorerKaspaOrgUrl(sampleTx, bundle.p2shAddress, KaspaNetwork.MAINNET),
        )
        assertEquals(
            "https://explorer-tn10.kaspa.org/txs/$sampleTx",
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.explorerKaspaOrgUrl(sampleTx, bundle.p2shAddress, KaspaNetwork.TESTNET_10),
        )

        // 4. Full untruncated error strings (>250 chars) preserved end-to-end by KaspaErrorSanitizer
        val longErr = "Broadcast rejected: Rejected transaction $sampleTx: " +
            "failed to verify signature script on input #0 because sequence locktime was not satisfied " +
            "and additional full node diagnostic context at the very end [END_MARKER_12345]"
        assertTrue(longErr.length > 220)
        val sanitized = com.kaspakinetic.wallet.core.network.KaspaErrorSanitizer.sanitize(longErr)
        assertTrue("Long error must NOT be truncated with ellipsis!", sanitized.endsWith("[END_MARKER_12345]"))
        assertEquals(longErr, sanitized)
    }

    @Test
    fun toccataV1ComputeBudgetAndLocalVmRejectUnderBudgetedInputs() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = Bip39.mnemonicToSeed(mnemonic)
        val priv = KaspaCrypto.derivePrivateKeyFromSeed(seed, 0, 0, com.kaspakinetic.wallet.core.model.DerivationKind.STANDARD_111111)
        val pub = KaspaCrypto.xOnlyPublicKey(priv)
        val pubHex = KaspaCrypto.bytesToHex(pub)
        val ownerAddr = KaspaAddress.encodePubKey(pub, KaspaNetwork.TESTNET_10)
        val ownerSpkHex = KaspaCrypto.bytesToHex(KaspaAddress.decode(ownerAddr)!!.toScriptPublicKey())

        val fundingUtxo = UtxoItem(
            address = ownerAddr,
            transactionId = "ab".repeat(32),
            index = 0,
            amountSompi = 10L * SOMPI_PER_KAS,
            scriptPublicKeyHex = ownerSpkHex,
            scriptPublicKeyVersion = 0,
            blockDaaScore = 10_000L,
        )

        val params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = pubHex,
            ownerAddress = ownerAddr,
            unlockDaa = 20_000L,
        )
        val bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.HODL_TIMELOCK,
            params,
            KaspaNetwork.TESTNET_10,
        )

        // 1. Deploy v1 tx MUST commit computeBudget >= 12 on standard P2PK funding inputs, pass local VM,
        //    and pay >= normalizedMass * 100 sompi/gram (Toccata default mempool floor).
        val deploy = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildAndSignDeployTxV1(
            bundle = bundle,
            amountSompi = 2L * SOMPI_PER_KAS,
            fundingUtxos = listOf(fundingUtxo),
            changeAddress = ownerAddr,
            network = KaspaNetwork.TESTNET_10,
            keyLookup = { priv.copyOf() },
        )
        assertEquals(1, deploy.rawTx.version)
        assertTrue(
            "Standard P2PK v1 funding input must commit compute_budget >= 12",
            deploy.rawTx.inputs.all { it.computeBudget >= KaspaMassAndTx.DEFAULT_V1_P2PK_COMPUTE_BUDGET },
        )
        assertTrue(
            "Deploy v1 fee (${deploy.feeSompi}) must satisfy 100 sompi/gram floor for mass ${deploy.mass}",
            deploy.feeSompi >= deploy.mass * KaspaMassAndTx.TOCCATA_MIN_FEE_RATE_SOMPI_PER_GRAM,
        )
        val okCheck = com.kaspakinetic.wallet.core.covenants.KaspaScriptVm.verifyP2pkInput(
            tx = deploy.rawTx,
            inputIndex = 0,
            enforceCommittedBudget = true,
        )
        assertTrue("Valid compute_budget=12 P2PK input must pass VM: ${okCheck.error}", okCheck.success)
        assertTrue("OP_CHECKSIG must cost >= 100,000 script units", okCheck.scriptUnitsUsed >= 100_000L)

        // 2. Under-budgeted v1 inputs (compute_budget = 0 or 1, old broken bug!) MUST be rejected locally!
        for (badBudget in listOf(0, 1, 9)) {
            val badTx = deploy.rawTx.copy(
                inputs = deploy.rawTx.inputs.map { it.copy(computeBudget = badBudget) },
            )
            val badCheck = com.kaspakinetic.wallet.core.covenants.KaspaScriptVm.verifyP2pkInput(
                tx = badTx,
                inputIndex = 0,
                enforceCommittedBudget = true,
            )
            assertFalse("compute_budget=$badBudget MUST fail local VM", badCheck.success)
            assertTrue(
                "Error message should mention script units exceeded, got: ${badCheck.error}",
                badCheck.error?.contains("script units") == true,
            )
        }

        // 3. P2SH covenant spend (both 1-sig HodlTimelock and 2-sig BoundedEscrow) commits >= 16 / >= 22
        val trackedHodl = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "p2sh:testnet-10:hodl_timelock:${bundle.redeemScriptHashHex}",
            walletId = "w1",
            network = KaspaNetwork.TESTNET_10,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.HODL_TIMELOCK,
            label = "Hodl Test",
            currentP2shAddress = bundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(bundle.redeemScript),
            redeemScriptHashHex = bundle.redeemScriptHashHex,
            silSource = bundle.silSource,
            abiArtifact = bundle.abiArtifact,
            params = params,
        )
        val covUtxo = UtxoItem(
            address = bundle.p2shAddress,
            transactionId = deploy.txId,
            index = 0,
            amountSompi = 2L * SOMPI_PER_KAS,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(bundle.p2shScriptPubKey),
            blockDaaScore = 10_010L,
        )
        val spendFee = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.estimateSpendFeeSompi(
            covenant = trackedHodl,
            covenantUtxo = covUtxo,
            entryName = "unlock",
            outputAmountsSompi = listOf(2L * SOMPI_PER_KAS - 400_000L),
            hasCovenantOutput = false,
        )
        val spend = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
            covenant = trackedHodl,
            covenantUtxo = covUtxo,
            entryName = "unlock",
            outputs = listOf(
                KaspaMassAndTx.TxOutput(
                    valueSompi = 2L * SOMPI_PER_KAS - spendFee,
                    scriptPublicKeyVersion = 0,
                    scriptPublicKey = KaspaAddress.decode(ownerAddr)!!.toScriptPublicKey(),
                    address = ownerAddr,
                ),
            ),
            lockTime = 20_000L,
            sequence = 0L,
            virtualDaaScore = 25_000L,
            signerPrivateKeys = listOf(priv.copyOf()),
        )
        assertTrue(
            "P2SH spend compute_budget should be >= 16, got ${spend.rawTx.inputs[0].computeBudget}",
            spend.rawTx.inputs[0].computeBudget >= 16,
        )
        assertTrue(
            "Estimated spend fee ($spendFee) should satisfy 100 sompi/gram floor for actual mass ${spend.mass}",
            spendFee >= spend.mass * KaspaMassAndTx.TOCCATA_MIN_FEE_RATE_SOMPI_PER_GRAM,
        )

        // Under-budgeted P2SH spend (compute_budget = 1) MUST also fail local VM!
        val badP2sh = spend.rawTx.copy(
            inputs = listOf(spend.rawTx.inputs[0].copy(computeBudget = 1)),
        )
        val badP2shCheck = com.kaspakinetic.wallet.core.covenants.KaspaScriptVm.verifyP2shInput(
            tx = badP2sh,
            inputIndex = 0,
            virtualDaaScore = 25_000L,
            enforceCommittedBudget = true,
        )
        assertFalse("Under-budgeted P2SH spend MUST fail local VM", badP2shCheck.success)
    }

    @Test
    fun protoCodecEncodesV1ComputeBudgetAndCovenantBindingAndOmitsSigOpCount() {
        val dummyCovId = "11".repeat(32)
        val submitJson = """
            {
              "transaction": {
                "version": 1,
                "inputs": [
                  {
                    "previousOutpoint": {"transactionId": "${"aa".repeat(32)}", "index": 0},
                    "signatureScript": "41${"00".repeat(65)}",
                    "sequence": 0,
                    "sigOpCount": 1,
                    "computeBudget": 12
                  }
                ],
                "outputs": [
                  {
                    "amount": 100000000,
                    "scriptPublicKey": {"version": 0, "scriptPublicKey": "aa20${"22".repeat(32)}87"},
                    "covenant": {"authorizingInput": 0, "covenantId": "$dummyCovId"}
                  }
                ],
                "lockTime": 0,
                "subnetworkId": "0000000000000000000000000000000000000000",
                "payload": "4b4b435631"
              },
              "allowOrphan": false
            }
        """.trimIndent()

        val wire = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.encodeSubmitTransactionRequest(7L, submitJson)
        // Parse top-level envelope -> field 1005 (submitTransactionRequest) -> field 1 (transaction)
        fun readFields(bytes: ByteArray): List<Triple<Int, Int, Any>> {
            val out = ArrayList<Triple<Int, Int, Any>>()
            var pos = 0
            fun readVarint(): Long {
                var res = 0L; var shift = 0
                while (pos < bytes.size) {
                    val b = bytes[pos++].toInt() and 0xFF
                    res = res or ((b and 0x7F).toLong() shl shift)
                    if ((b and 0x80) == 0) return res
                    shift += 7
                }
                error("Truncated varint")
            }
            while (pos < bytes.size) {
                val key = readVarint()
                val field = (key ushr 3).toInt()
                val wireType = (key and 7L).toInt()
                when (wireType) {
                    0 -> out.add(Triple(field, wireType, readVarint()))
                    2 -> {
                        val len = readVarint().toInt()
                        val slice = bytes.copyOfRange(pos, pos + len)
                        pos += len
                        out.add(Triple(field, wireType, slice))
                    }
                    else -> error("Unexpected wire type $wireType")
                }
            }
            return out
        }

        val envFields = readFields(wire)
        val reqBytes = envFields.first { it.first == com.kaspakinetic.wallet.core.network.KaspadProtoCodec.TAG_REQ_SUBMIT_TX }.third as ByteArray
        val txBytes = readFields(reqBytes).first { it.first == 1 }.third as ByteArray
        val txFields = readFields(txBytes)

        assertEquals(1L, txFields.first { it.first == 1 }.third as Long) // version = 1
        assertEquals("4b4b435631", String(txFields.first { it.first == 8 }.third as ByteArray)) // payload = field 8

        val inpBytes = txFields.first { it.first == 2 }.third as ByteArray
        val inpFields = readFields(inpBytes)
        assertEquals("RpcTransactionInput.computeBudget (field 6) must be 12", 12L, inpFields.first { it.first == 6 }.third as Long)
        assertTrue("RpcTransactionInput.sigOpCount (field 5) must be omitted for v1", inpFields.none { it.first == 5 })

        val outBytes = txFields.first { it.first == 3 }.third as ByteArray
        val outFields = readFields(outBytes)
        val covBytes = outFields.first { it.first == 4 }.third as ByteArray
        val covFields = readFields(covBytes)
        assertEquals(dummyCovId, String(covFields.first { it.first == 2 }.third as ByteArray))
    }

    @Test
    fun covenantsFiveLaunchReminderRespectsDoNotShowAgainOptOut() {
        val repo = com.kaspakinetic.wallet.data.repository.WalletDataRepository
        // Non-5th launches never fire
        assertFalse(repo.shouldShowLaunchPopup(4, donationOptedOut = false, covenantsActivated = true, covenantsReminderOptOut = false))
        // 5th launch with donation muted, covenants active & NOT muted -> fires for Covenants reminder
        assertTrue(repo.shouldShowLaunchPopup(5, donationOptedOut = true, covenantsActivated = true, covenantsReminderOptOut = false))
        // 5th launch with donation muted AND covenants reminder muted -> suppressed!
        assertFalse(repo.shouldShowLaunchPopup(5, donationOptedOut = true, covenantsActivated = true, covenantsReminderOptOut = true))
        // 10th launch with donation NOT muted & covenants reminder muted -> still fires for donation step only
        assertTrue(repo.shouldShowLaunchPopup(10, donationOptedOut = false, covenantsActivated = true, covenantsReminderOptOut = true))
    }

    // =========================================================================
    // Stage 3.2 (v3.2) — Keyless tx.time Covenants, OP_NUM2BIN Self-Advancing
    //                    Subscription, 0-KAS Claiming, .kpass & Shared Tab,
    //                    Per-Wallet Isolation, Fresh-Install Defaults, & 11-Locale Reflection Audit
    // =========================================================================

    @Test
    fun keylessTimeBasedSubscriptionAdvancesCycleOnChainWithZeroKasAndOwnerCanCancelReclaim() {
        val ownerPriv = ByteArray(32) { (it + 11).toByte() }
        val ownerPub = KaspaCrypto.xOnlyPublicKey(ownerPriv)
        val ownerPubHex = KaspaCrypto.bytesToHex(ownerPub)
        val ownerAddr = KaspaAddress.encodePubKey(ownerPub, KaspaNetwork.TESTNET_10)

        val recPriv = ByteArray(32) { (it + 77).toByte() }
        val recPub = KaspaCrypto.xOnlyPublicKey(recPriv)
        val recAddr = KaspaAddress.encodePubKey(recPub, KaspaNetwork.TESTNET_10)

        val due1 = 1_795_400_000_000L
        val intervalMs = 7L * 86_400_000L
        val salt = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex()
        val covId = "ab".repeat(32)

        val params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = ownerPubHex,
            ownerAddress = ownerAddr,
            vaultSaltHex = salt,
            recipientAddress = recAddr,
            allowanceSompi = 5L * SOMPI_PER_KAS,
            nextDueTimeMs = due1,
            intervalMs = intervalMs,
        )
        val bundle1 = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            params,
            KaspaNetwork.TESTNET_10,
        )
        val tracked1 = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "kip20:testnet-10:$covId",
            walletId = "w1",
            network = KaspaNetwork.TESTNET_10,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            label = "Weekly Allowance",
            covenantIdHex = covId,
            currentP2shAddress = bundle1.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(bundle1.redeemScript),
            redeemScriptHashHex = bundle1.redeemScriptHashHex,
            silSource = bundle1.silSource,
            abiArtifact = bundle1.abiArtifact,
            params = params,
            liveBalanceSompi = 20L * SOMPI_PER_KAS,
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.ACTIVE,
        )
        val utxo1 = UtxoItem(
            address = bundle1.p2shAddress,
            transactionId = "11".repeat(32),
            index = 0,
            amountSompi = 20L * SOMPI_PER_KAS,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(bundle1.p2shScriptPubKey),
            covenantId = covId,
        )

        // 1. Before due1 -> NOT due yet!
        assertNull(
            com.kaspakinetic.wallet.core.covenants.CovenantKeylessExecutor.buildDueKeylessTx(
                tracked1,
                utxo1,
                pastMedianTimeMs = due1 - 10_000L,
            ),
        )

        // 2. At due1 -> Keyless 0-KAS withdraw succeeds, verifies in KaspaScriptVm, and advances nextDueTimeMs += intervalMs!
        val built1 = com.kaspakinetic.wallet.core.covenants.CovenantKeylessExecutor.buildDueKeylessTx(
            tracked1,
            utxo1,
            pastMedianTimeMs = due1 + 1_000L,
        )
        assertNotNull("Expected keyless withdraw tx at due1", built1)
        val (prep1, nextCov2) = built1!!
        assertTrue("VM must verify keyless OP_NUM2BIN self-advancing withdraw", prep1.vmResult?.success == true)
        assertEquals(2, prep1.rawTx.outputs.size)
        assertEquals(recAddr, prep1.rawTx.outputs[0].address)
        assertEquals(5L * SOMPI_PER_KAS, prep1.rawTx.outputs[0].valueSompi)
        assertEquals(due1 + intervalMs, nextCov2.params.nextDueTimeMs)
        assertEquals(covId, prep1.rawTx.outputs[1].covenant?.covenantIdHex)

        // Verify bit-for-bit script equality with direct compilation for Cycle 2!
        val directBundle2 = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            params.copy(nextDueTimeMs = due1 + intervalMs),
            KaspaNetwork.TESTNET_10,
        )
        assertEquals(directBundle2.p2shAddress, nextCov2.currentP2shAddress)
        assertEquals(KaspaCrypto.bytesToHex(directBundle2.redeemScript), nextCov2.currentRedeemScriptHex)

        // 3. Auto-advance reconciliation: if a Shared `.kpass` holder still holds cycle-1 metadata and chain moved to cycle 2!
        val utxo2 = UtxoItem(
            address = directBundle2.p2shAddress,
            transactionId = prep1.txId,
            index = 1,
            amountSompi = prep1.rawTx.outputs[1].valueSompi,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(directBundle2.p2shScriptPubKey),
            covenantId = covId,
        )
        val reconciled = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(tracked1.copy(isSharedPassImport = true)),
            liveUtxos = listOf(utxo2),
            currentVirtualDaaScore = 100_000L,
            verifiedAtMs = due1 + 2_000L,
        ).single()
        assertEquals("Shared pass should auto-advance to cycle 2 address", directBundle2.p2shAddress, reconciled.currentP2shAddress)
        assertEquals("Shared pass should auto-advance nextDueTimeMs", due1 + intervalMs, reconciled.params.nextDueTimeMs)

        // 4. Owner cancelReclaim() sweeps remaining pool back to owner anytime cleanly!
        val ownerSpk = KaspaAddress.decode(ownerAddr)!!.toScriptPublicKey()
        val cancelTx = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
            covenant = nextCov2,
            covenantUtxo = utxo2,
            entryName = "cancelReclaim",
            outputs = listOf(KaspaMassAndTx.TxOutput(utxo2.amountSompi - 350_000L, 0, ownerSpk, ownerAddr)),
            signerPrivateKeys = listOf(ownerPriv),
        )
        assertTrue("Owner cancelReclaim must verify cleanly in KaspaScriptVm", cancelTx.vmResult?.success == true)
    }

    @Test
    fun keylessDeadMansSwitchHeirClaimWorksWithZeroKasAndOwnerCheckInAdvancesDeadline() {
        val ownerPriv = ByteArray(32) { (it + 21).toByte() }
        val ownerPub = KaspaCrypto.xOnlyPublicKey(ownerPriv)
        val ownerPubHex = KaspaCrypto.bytesToHex(ownerPub)
        val ownerAddr = KaspaAddress.encodePubKey(ownerPub, KaspaNetwork.TESTNET_10)

        val heirPriv = ByteArray(32) { (it + 88).toByte() }
        val heirPub = KaspaCrypto.xOnlyPublicKey(heirPriv)
        val heirAddr = KaspaAddress.encodePubKey(heirPub, KaspaNetwork.TESTNET_10)

        val deadlineMs = 1_796_000_000_000L
        val durMs = 30L * 86_400_000L
        val covId = "cd".repeat(32)
        val params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = ownerPubHex,
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            heirAddress = heirAddr,
            inactivityDeadlineMs = deadlineMs,
            inactivityDurationMs = durMs,
        )
        val bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH,
            params,
            KaspaNetwork.TESTNET_10,
        )
        val tracked = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "kip20:testnet-10:$covId",
            walletId = "w1",
            network = KaspaNetwork.TESTNET_10,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH,
            label = "Family Will",
            covenantIdHex = covId,
            currentP2shAddress = bundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(bundle.redeemScript),
            redeemScriptHashHex = bundle.redeemScriptHashHex,
            silSource = bundle.silSource,
            abiArtifact = bundle.abiArtifact,
            params = params,
            liveBalanceSompi = 50L * SOMPI_PER_KAS,
        )
        val utxo = UtxoItem(
            address = bundle.p2shAddress,
            transactionId = "22".repeat(32),
            index = 0,
            amountSompi = 50L * SOMPI_PER_KAS,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(bundle.p2shScriptPubKey),
            covenantId = covId,
        )

        // Before deadline -> blocked
        assertNull(com.kaspakinetic.wallet.core.covenants.CovenantKeylessExecutor.buildDueKeylessTx(tracked, utxo, pastMedianTimeMs = deadlineMs - 1_000L))

        // After deadline -> keyless 0-KAS heirClaim succeeds with 0 private keys!
        val claim = com.kaspakinetic.wallet.core.covenants.CovenantKeylessExecutor.buildDueKeylessTx(tracked, utxo, pastMedianTimeMs = deadlineMs + 1_000L)
        assertNotNull(claim)
        assertTrue(claim!!.first.vmResult?.success == true)
        assertEquals(heirAddr, claim.first.rawTx.outputs[0].address)
        assertTrue("Fee must be <= MAX_KEYLESS_FEE_SOMPI", claim.first.feeSompi <= com.kaspakinetic.wallet.core.covenants.CovenantCompiler.MAX_KEYLESS_FEE_SOMPI)

        // Owner checkIn(sig) advances deadline into successor P2SH preserving covenant_id!
        val nextParams = params.copy(inactivityDeadlineMs = deadlineMs + durMs)
        val nextBundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH,
            nextParams,
            KaspaNetwork.TESTNET_10,
        )
        val checkInTx = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
            covenant = tracked,
            covenantUtxo = utxo,
            entryName = "checkIn",
            outputs = listOf(
                KaspaMassAndTx.TxOutput(
                    valueSompi = 50L * SOMPI_PER_KAS - 350_000L,
                    scriptPublicKeyVersion = 0,
                    scriptPublicKey = nextBundle.p2shScriptPubKey,
                    address = nextBundle.p2shAddress,
                    covenant = KaspaMassAndTx.CovenantBinding(0, covId),
                ),
            ),
            signerPrivateKeys = listOf(ownerPriv),
        )
        assertTrue(checkInTx.vmResult?.success == true)
    }

    @Test
    fun kpassExportRoundTripContainsZeroPrivateKeysAndCleanFilenames() {
        val ownerPriv = ByteArray(32) { (it + 3).toByte() }
        val ownerPubHex = KaspaCrypto.bytesToHex(KaspaCrypto.xOnlyPublicKey(ownerPriv))
        val ownerAddr = KaspaAddress.encodePubKey(KaspaCrypto.xOnlyPublicKey(ownerPriv), KaspaNetwork.TESTNET_10)
        val params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = ownerPubHex,
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            recipientAddress = ownerAddr,
            allowanceSompi = 2L * SOMPI_PER_KAS,
            nextDueTimeMs = 1_795_500_000_000L,
            intervalMs = 86_400_000L,
        )
        val bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            params,
            KaspaNetwork.TESTNET_10,
        )
        val pass = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.SharedCovenantPass(
            kind = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.SharedPassKind.ALLOWANCE_CLAIM_PASS,
            network = KaspaNetwork.TESTNET_10,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            label = "Kids Allowance",
            covenantIdHex = "ee".repeat(32),
            p2shAddress = bundle.p2shAddress,
            redeemScriptHex = KaspaCrypto.bytesToHex(bundle.redeemScript),
            abiArtifact = bundle.abiArtifact,
            params = params,
            createdAtMs = 1_795_358_400_000L, // 2026-11-23 UTC
        )
        val bytes = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.exportKpassBytes(pass)
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse("Must never contain private key hex", text.contains(KaspaCrypto.bytesToHex(ownerPriv), ignoreCase = true))
        assertFalse("Must never contain mnemonic field", text.contains("mnemonic", ignoreCase = true))
        val parsed = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.parseKpassBytes(bytes)
        assertEquals(pass.p2shAddress, parsed.p2shAddress)

        // Compact QR payload must ALSO parse cleanly and fit well under 1,000 bytes for instant low-density scanning!
        val qrPayload = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.exportCompactSharedPassQrJson(pass)
        assertTrue("Compact QR payload (${qrPayload.length} bytes) must fit cleanly (< 1,000 bytes)", qrPayload.length < 1_000)
        assertEquals(pass.p2shAddress, com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.parseSharedPassJson(qrPayload).p2shAddress)

        val imported = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.toTrackedCovenant(parsed, "recipient_wallet")
        assertTrue("Imported .kpass must be flagged isSharedPassImport = true", imported.isSharedPassImport)

        // Verify clean `.kpass` filenames
        val allowanceName = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.buildDefaultKpassFilename(
            com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.SharedPassKind.ALLOWANCE_CLAIM_PASS,
            "",
            1_795_358_400_000L,
        )
        assertTrue(allowanceName.startsWith("Allowance_Pass_") && allowanceName.endsWith(".kpass"))
        val heirName = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.buildDefaultKpassFilename(
            com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.SharedPassKind.HEIR_CLAIM_PASS,
            "",
            1_795_358_400_000L,
        )
        assertTrue(heirName.startsWith("Heir_Pass_") && heirName.endsWith(".kpass"))
    }

    @Test
    fun perWalletCovenantsIsolationFreshTransparencyDefaultAndPrivacyMaskedFilterCounts() {
        // 1. Fresh-install transparency default MUST be OFF (reduceTransparency = true) alongside progressive blur OFF
        assertTrue(com.kaspakinetic.wallet.data.preferences.KineticAeroDefaults.DEFAULT_REDUCE_TRANSPARENCY)
        assertTrue(com.kaspakinetic.wallet.data.preferences.KineticAeroDefaults.DEFAULT_DISABLE_PROGRESSIVE_BLUR)

        // 2. Per-wallet Covenants activation isolation: activating Wallet A does NOT activate Wallet B!
        val prefs = com.kaspakinetic.wallet.data.repository.WalletPreferencesState(
            activeWalletId = "wallet_b",
            activatedCovenantWalletIds = setOf("wallet_a"),
        )
        assertTrue(prefs.isCovenantsActivatedForWallet("wallet_a"))
        assertFalse(prefs.isCovenantsActivatedForWallet("wallet_b"))
        assertFalse(prefs.isCovenantsActivatedForWallet("wallet_new"))

        // 3. Privacy Mask hides `(X)` filter counts on BOTH Transactions & Covenants filter pills!
        assertEquals("Active (4)", com.kaspakinetic.wallet.ui.components.formatFilterChipLabel("Active", 4, hideBalances = false))
        assertEquals("Active", com.kaspakinetic.wallet.ui.components.formatFilterChipLabel("Active", 4, hideBalances = true))
        assertEquals("Received (12)", com.kaspakinetic.wallet.ui.components.formatFilterChipLabel("Received", 12, hideBalances = false))
        assertEquals("Received", com.kaspakinetic.wallet.ui.components.formatFilterChipLabel("Received", 12, hideBalances = true))
    }

    @Test
    fun reflectionAuditAllCovenantStringsTranslatedAndSafeAcrossAll11Locales() {
        val en = com.kaspakinetic.wallet.ui.locale.KaspaWalletStrings()
        val nonEnglishLocales = listOf(
            java.util.Locale.SIMPLIFIED_CHINESE,
            java.util.Locale.TRADITIONAL_CHINESE,
            java.util.Locale.JAPANESE,
            java.util.Locale.KOREAN,
            java.util.Locale("ar"),
            java.util.Locale("es"),
            java.util.Locale.FRENCH,
            java.util.Locale.GERMAN,
            java.util.Locale("pt"),
            java.util.Locale("ru"),
        )
        // Explicit technical-token & standard-cognate allowlist (network/protocol identifiers, "Backup" in DE/PT, "Minutes" in FR)
        val allowlistIdentical = setOf(
            "covMainnetBadge",
            "covTestnetBadge",
            "covMainnetBetaBadge",
            "covTabBackup",
            "covMinutesUnit",
            "covQuickAllowanceFmt",
            "covCovenantIdLabel",
        )
        val covGetters = com.kaspakinetic.wallet.ui.locale.KaspaWalletStrings::class.java.declaredMethods
            .filter { it.parameterCount == 0 && it.returnType == String::class.java }
            .filter { it.name.startsWith("getCov") || it.name.startsWith("getCovenants") }
            .sortedBy { it.name }

        assertTrue("Expected > 200 reflected covenant string properties", covGetters.size > 200)

        for (loc in nonEnglishLocales) {
            val localized = com.kaspakinetic.wallet.ui.locale.resolveKaspaWalletStrings(loc)
            val untranslated = ArrayList<String>()
            for (getter in covGetters) {
                val propName = getter.name.removePrefix("get").replaceFirstChar { it.lowercaseChar() }
                val enVal = getter.invoke(en) as String
                val locVal = getter.invoke(localized) as String
                if (propName !in allowlistIdentical && locVal == enVal) {
                    untranslated.add(propName)
                }
                // Also verify every single `*Fmt` covenant string formats cleanly without throwing!
                if (propName.endsWith("Fmt")) {
                    val sampleArgs = when (propName) {
                        "covActivationProceedCountdownFmt", "covQuickWhitelistFmt", "covInitialScanFoundFmt" -> arrayOf<Any>(5)
                        "covBackupMetaFmt" -> arrayOf<Any>(3, "Nov 23, 12:00")
                        "covRestoredBackupMsgFmt", "covChainScanResultFmt" -> arrayOf<Any>(4, 2)
                        "covQuickGoalPctFmt" -> arrayOf<Any>(50, "100", "KAS")
                        "covSummaryWhitelistFmt" -> arrayOf<Any>("10", "KAS", 2)
                        "covSummaryDailyFmt", "covSummaryRecurringFmt" -> arrayOf<Any>("10", "KAS", "2", "KAS", "24h", "Dec 1")
                        "covSummaryPiggyFmt" -> arrayOf<Any>("10", "KAS", "50", "KAS", "Dec 1")
                        "covSummaryDeadManFmt" -> arrayOf<Any>("10", "KAS", "30d", "Dec 23")
                        "covSummaryClawbackFmt", "covSummaryHodlFmt", "covQuickLimitFmt", "covQuickAllowanceFmt" -> arrayOf<Any>("10", "KAS", "24h")
                        "covSharedClaimAmountFmt", "covAvailableBalanceFmt" -> arrayOf<Any>("5", "KAS")
                        "covDailyAvailableNowFmt" -> arrayOf<Any>("1.00", "KAS", "1.00", "KAS")
                        else -> arrayOf<Any>("KAS")
                    }
                    val formatted = String.format(java.util.Locale.US, locVal, *sampleArgs)
                    assertTrue("Blank formatted value for $propName in $loc", formatted.isNotBlank())
                    assertFalse("Double %% remained for $propName in $loc: $formatted", formatted.contains("%%"))
                }
            }
            assertTrue(
                "Untranslated covenant strings in locale $loc (${untranslated.size}): ${untranslated.take(15)}",
                untranslated.isEmpty(),
            )
        }
    }

    @Test
    fun stage33ProtoCodecDecodesPastMedianTimeFromBlockDagInfo() {
        val expectedPmt = 1_795_512_345_678L
        val expectedDaa = 412_889_900L
        val dagInfoBody = concatBytes(
            encodeStringFieldForTest(1, "kaspa-testnet-10"),
            encodeVarintFieldForTest(6, expectedPmt),
            encodeVarintFieldForTest(9, expectedDaa),
        )
        // KaspadResponse: id = field 101, getBlockDagInfoResponse = field 1036 (TAG_RESP_GET_BLOCK_DAG_INFO)
        val responseBytes = concatBytes(
            encodeVarintFieldForTest(101, 42L),
            encodeLengthDelimitedFieldForTest(
                com.kaspakinetic.wallet.core.network.KaspadProtoCodec.TAG_RESP_GET_BLOCK_DAG_INFO,
                dagInfoBody,
            ),
        )
        val decoded = com.kaspakinetic.wallet.core.network.KaspadProtoCodec.decodeResponse(responseBytes)
        val payload = decoded.payload as? com.kaspakinetic.wallet.core.network.KaspadProtoCodec.Payload.BlockDagInfo
        assertNotNull("Expected BlockDagInfo payload", payload)
        assertEquals("kaspa-testnet-10", payload!!.networkName)
        assertEquals(expectedPmt, payload.pastMedianTime)
        assertEquals(expectedDaa, payload.virtualDaaScore)
    }

    @Test
    fun stage33TimeLockReadinessStrictlyUsesPastMedianTimeAndBlocksWallClockOnly() {
        val unlockMs = 1_795_600_000_000L
        // Strict rusty-kaspa finality: tx.lock_time < pastMedianTime!
        assertFalse("Equal pastMedianTime MUST NOT be finalized yet", com.kaspakinetic.wallet.core.covenants.CovenantCompiler.isTimeLockReady(unlockMs, unlockMs))
        assertFalse("Earlier pastMedianTime MUST NOT be finalized", com.kaspakinetic.wallet.core.covenants.CovenantCompiler.isTimeLockReady(unlockMs, unlockMs - 120_000L))
        assertTrue("Strictly greater pastMedianTime MUST be finalized", com.kaspakinetic.wallet.core.covenants.CovenantCompiler.isTimeLockReady(unlockMs, unlockMs + 1L))

        val ownerPriv = ByteArray(32) { (it + 31).toByte() }
        val ownerPub = KaspaCrypto.xOnlyPublicKey(ownerPriv)
        val ownerAddr = KaspaAddress.encodePubKey(ownerPub, KaspaNetwork.TESTNET_10)
        val params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = KaspaCrypto.bytesToHex(ownerPub),
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            unlockTimeMs = unlockMs,
        )
        val bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.HODL_TIMELOCK,
            params,
            KaspaNetwork.TESTNET_10,
        )
        val tracked = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "p2sh:testnet-10:${bundle.redeemScriptHashHex}",
            walletId = "w1",
            network = KaspaNetwork.TESTNET_10,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.HODL_TIMELOCK,
            label = "Diamond Hands",
            currentP2shAddress = bundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(bundle.redeemScript),
            redeemScriptHashHex = bundle.redeemScriptHashHex,
            silSource = bundle.silSource,
            abiArtifact = bundle.abiArtifact,
            params = params,
            liveBalanceSompi = 10L * SOMPI_PER_KAS,
        )
        val utxo = UtxoItem(
            address = bundle.p2shAddress,
            transactionId = "33".repeat(32),
            index = 0,
            amountSompi = 10L * SOMPI_PER_KAS,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(bundle.p2shScriptPubKey),
            blockDaaScore = 500_000L,
        )

        // Wall-clock (verifiedAtMs) is 60s PAST unlockMs, but consensus pastMedianTime is 60s BEFORE unlockMs -> MUST stay ACTIVE!
        val beforePmt = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(tracked),
            liveUtxos = listOf(utxo),
            currentVirtualDaaScore = 501_000L,
            verifiedAtMs = unlockMs + 60_000L,
            pastMedianTimeMs = unlockMs - 60_000L,
        ).single()
        assertEquals(com.kaspakinetic.wallet.core.covenants.CovenantStatus.ACTIVE, beforePmt.liveStatus)

        // VM MUST also reject unlock attempt when currentTimeMs (pastMedianTime) <= unlockMs!
        val destSpk = KaspaAddress.decode(ownerAddr)!!.toScriptPublicKey()
        var threwBeforePmt = false
        try {
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                covenant = beforePmt,
                covenantUtxo = utxo,
                entryName = "unlock",
                outputs = listOf(KaspaMassAndTx.TxOutput(9L * SOMPI_PER_KAS, 0, destSpk, ownerAddr)),
                lockTime = unlockMs,
                currentTimeMs = unlockMs, // Equal -> NOT finalized!
                signerPrivateKeys = listOf(ownerPriv),
            )
        } catch (e: IllegalStateException) {
            threwBeforePmt = e.message?.contains("not finalized", ignoreCase = true) == true
        }
        assertTrue("VM must reject CLTV spend when pastMedianTime == unlockTime", threwBeforePmt)

        // Once pastMedianTime crosses unlockMs + 1s -> status flips to READY and VM verifies cleanly!
        val afterPmt = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(tracked),
            liveUtxos = listOf(utxo),
            currentVirtualDaaScore = 502_000L,
            verifiedAtMs = unlockMs + 180_000L,
            pastMedianTimeMs = unlockMs + 1_000L,
        ).single()
        assertEquals(com.kaspakinetic.wallet.core.covenants.CovenantStatus.READY, afterPmt.liveStatus)

        val verified = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
            covenant = afterPmt,
            covenantUtxo = utxo,
            entryName = "unlock",
            outputs = listOf(KaspaMassAndTx.TxOutput(9L * SOMPI_PER_KAS, 0, destSpk, ownerAddr)),
            lockTime = unlockMs,
            currentTimeMs = unlockMs + 1_000L,
            signerPrivateKeys = listOf(ownerPriv),
        )
        assertTrue("VM must accept CLTV spend once pastMedianTime > unlockTime", verified.vmResult?.success == true)
    }

    @Test
    fun stage33MonotonicChainTimeAnchorAdvancesSmoothlyWithoutBackwardsDrift() {
        val basePmt = 1_795_700_000_000L
        val a0 = com.kaspakinetic.wallet.core.covenants.ChainTimeAnchor().mergeUpdate(
            newPastMedianTimeMs = basePmt,
            newVirtualDaaScore = 200_000L,
            nowElapsedRealtimeMs = 10_000L,
        )
        // 1s, 2s, 3s ticks advance monotonically
        assertEquals(basePmt + 1_000L, a0.estimatePastMedianTimeMs(11_000L))
        assertEquals(basePmt + 2_000L, a0.estimatePastMedianTimeMs(12_000L))
        assertEquals(200_020L, a0.estimateVirtualDaaScore(12_000L))

        // If a stale server snapshot arrives (< basePmt), real server pastMedianTimeMs MUST NEVER roll backwards!
        // And real server pastMedianTimeMs MUST NOT be artificially inflated by local elapsed extrapolation!
        val a1 = a0.mergeUpdate(
            newPastMedianTimeMs = basePmt - 2_000L,
            newVirtualDaaScore = 200_010L,
            nowElapsedRealtimeMs = 15_000L,
        )
        assertEquals("Real server pastMedianTimeMs must never drift backwards", basePmt, a1.pastMedianTimeMs)
        assertTrue("Merged DAA score must never go backwards", a1.estimateVirtualDaaScore(15_000L) >= 200_050L)

        // When a newer gRPC snapshot jumps ahead, it adopts the fresher real server value immediately
        val a2 = a1.mergeUpdate(
            newPastMedianTimeMs = basePmt + 25_000L,
            newVirtualDaaScore = 200_300L,
            nowElapsedRealtimeMs = 16_000L,
        )
        assertEquals(basePmt + 25_000L, a2.pastMedianTimeMs)
        assertEquals(basePmt + 25_000L, a2.estimatePastMedianTimeMs(16_000L))
        assertEquals(basePmt + 26_000L, a2.estimatePastMedianTimeMs(17_000L))
    }

    @Test
    fun stage33ColdWhitelistPreValidationRejectsNonWhitelistedAddressBeforeVm() {
        val ownerPub = KaspaCrypto.xOnlyPublicKey(ByteArray(32) { (it + 1).toByte() })
        val cold1 = KaspaAddress.encodePubKey(KaspaCrypto.xOnlyPublicKey(ByteArray(32) { (it + 2).toByte() }), KaspaNetwork.TESTNET_10)
        val cold2 = KaspaAddress.encodePubKey(KaspaCrypto.xOnlyPublicKey(ByteArray(32) { (it + 3).toByte() }), KaspaNetwork.TESTNET_10)
        val stranger = KaspaAddress.encodePubKey(KaspaCrypto.xOnlyPublicKey(ByteArray(32) { (it + 9).toByte() }), KaspaNetwork.TESTNET_10)

        val params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = KaspaCrypto.bytesToHex(ownerPub),
            ownerAddress = KaspaAddress.encodePubKey(ownerPub, KaspaNetwork.TESTNET_10),
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            whitelistAddresses = listOf(cold1, cold2),
        )
        val bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.COLD_WHITELIST,
            params,
            KaspaNetwork.TESTNET_10,
        )
        val cov = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "p2sh:testnet-10:${bundle.redeemScriptHashHex}",
            walletId = "w1",
            network = KaspaNetwork.TESTNET_10,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.COLD_WHITELIST,
            label = "Cold Chute",
            currentP2shAddress = bundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(bundle.redeemScript),
            redeemScriptHashHex = bundle.redeemScriptHashHex,
            silSource = bundle.silSource,
            abiArtifact = bundle.abiArtifact,
            params = params,
        )

        assertTrue(com.kaspakinetic.wallet.core.covenants.CovenantCompiler.isDestinationWhitelisted(cov, cold1))
        assertTrue(com.kaspakinetic.wallet.core.covenants.CovenantCompiler.isDestinationWhitelisted(cov, cold2.uppercase()))
        assertFalse("Unapproved address must be rejected cleanly before VM", com.kaspakinetic.wallet.core.covenants.CovenantCompiler.isDestinationWhitelisted(cov, stranger))
        assertFalse("Blank address must be rejected cleanly", com.kaspakinetic.wallet.core.covenants.CovenantCompiler.isDestinationWhitelisted(cov, "   "))
    }

    @Test
    fun stage33ClawbackKpassRejectedAndSharedPassRemovalPreservesOwnedVaults() {
        val ownerPub = KaspaCrypto.xOnlyPublicKey(ByteArray(32) { (it + 5).toByte() })
        val ownerAddr = KaspaAddress.encodePubKey(ownerPub, KaspaNetwork.TESTNET_10)
        val clawParams = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = KaspaCrypto.bytesToHex(ownerPub),
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            alarmPubKeyHex = KaspaCrypto.bytesToHex(ownerPub),
            recoveryAddress = ownerAddr,
            unvaultDelayDaa = 1_200L,
        )
        val clawBundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.CLAWBACK_VAULT,
            clawParams,
            KaspaNetwork.TESTNET_10,
        )
        val badPass = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.SharedCovenantPass(
            kind = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.SharedPassKind.ALARM_CLAWBACK_PASS,
            network = KaspaNetwork.TESTNET_10,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.CLAWBACK_VAULT,
            label = "Clawback",
            p2shAddress = clawBundle.p2shAddress,
            redeemScriptHex = KaspaCrypto.bytesToHex(clawBundle.redeemScript),
            abiArtifact = clawBundle.abiArtifact,
            params = clawParams,
        )
        var exportRejected = false
        try {
            com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.exportSharedPassJson(badPass)
        } catch (_: IllegalArgumentException) {
            exportRejected = true
        }
        assertTrue("Clawback Vault .kpass export must be rejected", exportRejected)

        var importRejected = false
        try {
            com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.parseSharedPassJson(
                """{"format":"kaspa-kinetic-pass-v1","kind":"ALARM_CLAWBACK_PASS","network":"TESTNET_10","templateId":"CLAWBACK_VAULT","label":"Claw","p2shAddress":"${clawBundle.p2shAddress}","redeemScriptHex":"${KaspaCrypto.bytesToHex(clawBundle.redeemScript)}","abiArtifact":{"schemaVersion":1,"compilerVersion":"1","contracts":{}},"params":{"ownerPubKeyHex":"${clawParams.ownerPubKeyHex}"}}""",
            )
        } catch (_: IllegalArgumentException) {
            importRejected = true
        }
        assertTrue("Clawback Vault .kpass import must be rejected", importRejected)

        // Shared pass removal removes ONLY the targeted isSharedPassImport == true item for active walletId
        val ownedVault = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "owned_key",
            walletId = "wallet_a",
            network = KaspaNetwork.TESTNET_10,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.CLAWBACK_VAULT,
            label = "My Clawback Vault",
            currentP2shAddress = clawBundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(clawBundle.redeemScript),
            redeemScriptHashHex = clawBundle.redeemScriptHashHex,
            silSource = clawBundle.silSource,
            abiArtifact = clawBundle.abiArtifact,
            params = clawParams,
            isSharedPassImport = false,
        )
        val sharedTarget = ownedVault.copy(
            canonicalKey = "shared_target",
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            label = "Kids Allowance",
            isSharedPassImport = true,
        )
        val sharedOther = sharedTarget.copy(canonicalKey = "shared_other", label = "Heir Will")
        val afterRemove = com.kaspakinetic.wallet.viewmodel.WalletViewModel.filterRemoveSharedPass(
            covenants = listOf(ownedVault, sharedTarget, sharedOther),
            walletId = "wallet_a",
            canonicalKey = "shared_target",
        )
        assertEquals(listOf("owned_key", "shared_other"), afterRemove.map { it.canonicalKey })

        // Attempting to remove an owned vault via filterRemoveSharedPass MUST NOT delete it!
        val afterAttemptOwned = com.kaspakinetic.wallet.viewmodel.WalletViewModel.filterRemoveSharedPass(
            covenants = listOf(ownedVault, sharedTarget),
            walletId = "wallet_a",
            canonicalKey = "owned_key",
        )
        assertEquals(2, afterAttemptOwned.size)
    }

    @Test
    fun stage34IdenticallyNamedWalletsIsolatedDiskStoresAndSingleSourceOfTruth() {
        val tempDir = java.io.File.createTempFile("kaspa_kinetic_v34_store_", "").apply {
            delete()
            mkdirs()
        }
        try {
            val diskStore = com.kaspakinetic.wallet.core.covenants.CovenantVaultDiskStore(tempDir)
            val walletAId = "wallet_id_alpha"
            val walletBId = "wallet_id_beta"
            val sharedDisplayName = "Main Kaspa Wallet"

            val pubA = KaspaCrypto.xOnlyPublicKey(ByteArray(32) { (it + 10).toByte() })
            val addrA = KaspaAddress.encodePubKey(pubA, KaspaNetwork.TESTNET_10)
            val keyA = ByteArray(32) { (it + 1).toByte() }
            val pubB = KaspaCrypto.xOnlyPublicKey(ByteArray(32) { (it + 20).toByte() })
            val addrB = KaspaAddress.encodePubKey(pubB, KaspaNetwork.TESTNET_10)
            val keyB = ByteArray(32) { (it + 2).toByte() }

            val paramsA = com.kaspakinetic.wallet.core.covenants.CovenantParams(
                ownerPubKeyHex = KaspaCrypto.bytesToHex(pubA),
                ownerAddress = addrA,
                vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
                unlockTimeMs = 1_796_100_000_000L,
            )
            val bundleA = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
                com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.HODL_TIMELOCK,
                paramsA,
                KaspaNetwork.TESTNET_10,
            )
            val covA = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
                canonicalKey = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.canonicalKeyFor(
                    network = KaspaNetwork.TESTNET_10,
                    templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.HODL_TIMELOCK,
                    covenantIdHex = null,
                    redeemScriptHashHex = bundleA.redeemScriptHashHex,
                ),
                walletId = walletAId,
                network = KaspaNetwork.TESTNET_10,
                templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.HODL_TIMELOCK,
                label = "Wallet A Hodl",
                currentP2shAddress = bundleA.p2shAddress,
                currentRedeemScriptHex = KaspaCrypto.bytesToHex(bundleA.redeemScript),
                redeemScriptHashHex = bundleA.redeemScriptHashHex,
                silSource = bundleA.silSource,
                abiArtifact = bundleA.abiArtifact,
                params = paramsA,
                liveBalanceSompi = 15L * SOMPI_PER_KAS,
                liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.ACTIVE,
                isSharedPassImport = false,
            )
            val sharedPassInB = covA.copy(
                canonicalKey = "kip20:testnet-10:${"fe".repeat(32)}",
                walletId = walletBId,
                templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH,
                label = "Imported Settled Shared Pass",
                liveBalanceSompi = 0L,
                liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.SETTLED,
                isSharedPassImport = true,
            )

            kotlinx.coroutines.runBlocking {
                // Save imported `.kinetic` covenant to Wallet A ("Main Kaspa Wallet")
                diskStore.mergeIncomingAndSave(
                    walletId = walletAId,
                    walletName = sharedDisplayName,
                    primaryAddressMainnet = addrA,
                    incoming = listOf(covA),
                    seedBackupKey32 = keyA,
                )
                // Save settled shared pass to Wallet B (ALSO named "Main Kaspa Wallet"!)
                diskStore.mergeIncomingAndSave(
                    walletId = walletBId,
                    walletName = sharedDisplayName,
                    primaryAddressMainnet = addrB,
                    incoming = listOf(sharedPassInB),
                    seedBackupKey32 = keyB,
                )

                // Verify internal `.kinetic` files & JSON stores are strictly separate paths keyed by walletId
                assertNotEquals(diskStore.storeFile(walletAId).name, diskStore.storeFile(walletBId).name)
                assertNotEquals(diskStore.internalKineticFile(walletAId).name, diskStore.internalKineticFile(walletBId).name)
                assertTrue(diskStore.internalKineticFile(walletAId).exists())
                assertTrue(diskStore.internalKineticFile(walletBId).exists())

                // Switch back to Wallet A -> Wallet A's imported covenant MUST be 100% preserved!
                val reloadedA = diskStore.loadVault(walletAId)
                assertEquals(1, reloadedA.covenants.size)
                assertEquals(covA.canonicalKey, reloadedA.covenants.single().canonicalKey)
                assertEquals(walletAId, reloadedA.covenants.single().walletId)

                // Switch to Wallet B -> Single source of truth filter returns IDENTICAL counts (0 owned) for BOTH Home & Hub -> Yours!
                val reloadedB = diskStore.loadVault(walletBId)
                val homeOwnedB = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.ownedCovenantsForNetwork(
                    reloadedB.covenants,
                    KaspaNetwork.TESTNET_10,
                    walletBId,
                )
                val hubYoursB = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.ownedCovenantsForNetwork(
                    reloadedB.covenants,
                    KaspaNetwork.TESTNET_10,
                    walletBId,
                )
                val hubSharedB = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.sharedCovenantsForNetwork(
                    reloadedB.covenants,
                    KaspaNetwork.TESTNET_10,
                    walletBId,
                )
                assertEquals(homeOwnedB, hubYoursB)
                assertEquals("Wallet B has 0 owned covenants (shared pass must not inflate Home counts!)", 0, homeOwnedB.size)
                assertEquals("Wallet B has 1 shared pass in Shared tab", 1, hubSharedB.size)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun stage34StrictServerPastMedianTimeReadyGateBlocksExtrapolatedReady() {
        val unlockMs = 1_795_800_000_000L
        // Server reported PMT is 5 seconds BEFORE unlockMs at elapsed=100_000L
        val anchor = com.kaspakinetic.wallet.core.covenants.ChainTimeAnchor().mergeUpdate(
            newPastMedianTimeMs = unlockMs - 5_000L,
            newVirtualDaaScore = 300_000L,
            nowElapsedRealtimeMs = 100_000L,
        )

        // 15 seconds pass locally -> extrapolated local PMT is 10 seconds PAST unlockMs!
        val elapsedPlus15s = 115_000L
        assertTrue(
            "Local extrapolated PMT exceeds unlockMs by 10s",
            anchor.estimatePastMedianTimeMs(elapsedPlus15s) == unlockMs + 10_000L,
        )
        // Strict server gate MUST STILL REFUSE READY -> returns FINALIZING instead!
        val evalBeforeServerConfirms = anchor.evaluateTimeLock(unlockMs, elapsedPlus15s)
        assertFalse("Server-confirmed ready must be FALSE until real gRPC PMT > unlockMs", evalBeforeServerConfirms.serverConfirmedReady)
        assertEquals(
            "Must show Finalizing on Kaspa L1... instead of premature Ready",
            com.kaspakinetic.wallet.core.covenants.TimeLockStage.FINALIZING,
            evalBeforeServerConfirms.stage,
        )

        // Even when server returns exact equality (pastMedianTime == unlockMs), rusty-kaspa requires strict `<` -> STILL FINALIZING!
        val anchorEqual = anchor.mergeUpdate(
            newPastMedianTimeMs = unlockMs,
            newVirtualDaaScore = 300_150L,
            nowElapsedRealtimeMs = elapsedPlus15s,
        )
        assertEquals(
            com.kaspakinetic.wallet.core.covenants.TimeLockStage.FINALIZING,
            anchorEqual.evaluateTimeLock(unlockMs, elapsedPlus15s).stage,
        )

        // Once real gRPC server returns pastMedianTime = unlockMs + 1_000L -> flips cleanly to READY!
        val anchorConfirmed = anchorEqual.mergeUpdate(
            newPastMedianTimeMs = unlockMs + 1_000L,
            newVirtualDaaScore = 300_170L,
            nowElapsedRealtimeMs = elapsedPlus15s + 2_000L,
        )
        val evalConfirmed = anchorConfirmed.evaluateTimeLock(unlockMs, elapsedPlus15s + 2_000L)
        assertTrue(evalConfirmed.serverConfirmedReady)
        assertEquals(com.kaspakinetic.wallet.core.covenants.TimeLockStage.READY, evalConfirmed.stage)
    }

    @Test
    fun stage34MempoolAlreadyInMempoolIdempotencyResolvesAsAccepted() {
        val myTxId = "a1b2c3d4".repeat(8)
        val otherTxId = "99887766".repeat(8)

        kotlinx.coroutines.runBlocking {
            // 1. Exact matching txid reporting "already in the mempool" resolves cleanly as accepted!
            val accepted = com.kaspakinetic.wallet.core.network.KaspaMempoolIdempotency.resolveIdempotentSubmit(myTxId) {
                throw IllegalStateException(
                    "Broadcast rejected: Rejected transaction $myTxId: transaction $myTxId is already in the mempool",
                )
            }
            assertEquals(myTxId, accepted)

            // 2. Different txid in error MUST NOT be swallowed!
            var otherFailed = false
            try {
                com.kaspakinetic.wallet.core.network.KaspaMempoolIdempotency.resolveIdempotentSubmit(myTxId) {
                    throw IllegalStateException(
                        "Broadcast rejected: Rejected transaction $otherTxId: transaction $otherTxId is already in the mempool",
                    )
                }
            } catch (_: IllegalStateException) {
                otherFailed = true
            }
            assertTrue("Mismatched txid mempool error must still throw", otherFailed)

            // 3. Real consensus/orphan rejection MUST still throw cleanly!
            var orphanFailed = false
            try {
                com.kaspakinetic.wallet.core.network.KaspaMempoolIdempotency.resolveIdempotentSubmit(myTxId) {
                    throw IllegalStateException("Broadcast rejected: transaction $myTxId is an orphan where orphan is disallowed")
                }
            } catch (_: IllegalStateException) {
                orphanFailed = true
            }
            assertTrue("Orphan rejection must still throw", orphanFailed)
        }
    }

    @Test
    fun stage34SubscriptionCleanupNoAutoSettleAndNoAutoSendWorker() {
        val ownerPub = KaspaCrypto.xOnlyPublicKey(ByteArray(32) { (it + 15).toByte() })
        val ownerAddr = KaspaAddress.encodePubKey(ownerPub, KaspaNetwork.TESTNET_10)
        val recAddr = KaspaAddress.encodePubKey(KaspaCrypto.xOnlyPublicKey(ByteArray(32) { (it + 25).toByte() }), KaspaNetwork.TESTNET_10)
        val params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = KaspaCrypto.bytesToHex(ownerPub),
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            recipientAddress = recAddr,
            allowanceSompi = 2L * SOMPI_PER_KAS,
            nextDueTimeMs = 1_796_200_000_000L,
            intervalMs = 86_400_000L,
        )
        val bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            params,
            KaspaNetwork.TESTNET_10,
        )
        val entries = bundle.abiArtifact.contracts["RecurringAllowance"]!!.entries.keys
        assertEquals(setOf("withdraw", "cancelReclaim", "topUp"), entries)
        assertFalse("SilverScript source must not contain autoSettle", bundle.silSource.contains("autoSettle", ignoreCase = true))

        // Confirm CovenantAutoExecutorWorker background worker class is completely removed!
        val workerExists = try {
            Class.forName("com.kaspakinetic.wallet.core.covenants.CovenantAutoExecutorWorker")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
        assertFalse("CovenantAutoExecutorWorker must be completely deleted", workerExists)
    }

    @Test
    fun stage35ColdRestoreFourWaysAll7CovenantsNonZeroIndexSignAndPassVm() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        val seed = Bip39.mnemonicToSeed(mnemonic, "")
        val backupKey = KaspaCrypto.deriveCovenantBackupKey(seed)
        val network = KaspaNetwork.MAINNET
        val walletId = "wallet_cold_restore_test"

        // Deploy using a NON-ZERO BIP-32 receive index (index 7) & alarm sub-key (index 2)!
        val ownerPriv = KaspaCrypto.derivePrivateKeyFromSeed(seed, chain = 0, index = 7)
        val ownerPub = KaspaCrypto.xOnlyPublicKey(ownerPriv)
        val ownerPubHex = KaspaCrypto.bytesToHex(ownerPub)
        val ownerAddr = KaspaAddress.encodePubKey(ownerPub, network)

        val (_, alarmPub) = KaspaCrypto.deriveCovenantSubKey(seed, 2)
        val alarmPubHex = KaspaCrypto.bytesToHex(alarmPub)

        val coldPriv = KaspaCrypto.derivePrivateKeyFromSeed(seed, chain = 1, index = 3)
        val coldPub = KaspaCrypto.xOnlyPublicKey(coldPriv)
        val coldAddr = KaspaAddress.encodePubKey(coldPub, network)
        val coldSpk = KaspaAddress.decode(coldAddr)!!.toScriptPublicKey()

        val nowMs = 1_796_200_000_000L
        val unlockTimeMs = nowMs + 86_400_000L
        val pastMedianAfterUnlock = unlockTimeMs + 60_000L
        val baseDaa = 100_000_000L

        fun baseParams(
            alarmHex: String = "",
            recovAddr: String = "",
            heirAddr: String = "",
            recipientAddr: String = "",
            whitelist: List<String> = emptyList(),
        ) = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = ownerPubHex,
            ownerAddress = ownerAddr,
            ownerDerivationPath = "m/44'/111111'/0'/0/7",
            ownerDerivationChain = 0,
            ownerDerivationIndex = 7,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            alarmPubKeyHex = alarmHex,
            recoveryAddress = recovAddr,
            unvaultDelayDaa = 1_200L,
            heirAddress = heirAddr,
            inactivityDeadlineMs = unlockTimeMs,
            inactivityDurationMs = 86_400_000L,
            dailyCapSompi = 3L * SOMPI_PER_KAS,
            windowDaa = 864_000L,
            fullWithdrawUnlockTimeMs = unlockTimeMs,
            whitelistAddresses = whitelist,
            unlockTimeMs = unlockTimeMs,
            targetGoalSompi = 10L * SOMPI_PER_KAS,
            fallbackUnlockTimeMs = unlockTimeMs,
            recipientAddress = recipientAddr,
            allowanceSompi = 2L * SOMPI_PER_KAS,
            nextDueTimeMs = unlockTimeMs,
            intervalMs = 86_400_000L,
        )

        val templatesAndParams = listOf(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.CLAWBACK_VAULT to baseParams(alarmHex = alarmPubHex, recovAddr = coldAddr),
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH to baseParams(heirAddr = coldAddr),
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DAILY_SPEND_LIMIT to baseParams(),
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.COLD_WHITELIST to baseParams(whitelist = listOf(coldAddr)),
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.HODL_TIMELOCK to baseParams(),
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.GOAL_PIGGY_BANK to baseParams(),
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE to baseParams(recipientAddr = coldAddr),
        )

        val deployed = ArrayList<com.kaspakinetic.wallet.core.covenants.TrackedCovenant>()
        val kkcv1Payloads = ArrayList<Pair<String, ByteArray>>()
        val liveUtxos = ArrayList<UtxoItem>()

        templatesAndParams.forEachIndexed { idx, (tpl, params) ->
            val bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(tpl, params, network)
            val txId = "${(idx + 1).toString().padStart(2, '0')}".repeat(32)
            val covId = if (tpl.isStatefulKip20) "${(idx + 17).toString(16).padStart(2, '0')}".repeat(32) else null
            val key = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.canonicalKeyFor(
                network, tpl, covId, bundle.redeemScriptHashHex,
            )
            val bal = if (tpl == com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.GOAL_PIGGY_BANK) {
                12L * SOMPI_PER_KAS
            } else {
                5L * SOMPI_PER_KAS
            }
            val cov = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
                canonicalKey = key,
                walletId = walletId,
                network = network,
                templateId = tpl,
                label = "Vault $idx",
                covenantIdHex = covId,
                currentP2shAddress = bundle.p2shAddress,
                currentRedeemScriptHex = KaspaCrypto.bytesToHex(bundle.redeemScript),
                redeemScriptHashHex = bundle.redeemScriptHashHex,
                silSource = bundle.silSource,
                abiArtifact = bundle.abiArtifact,
                params = params,
                knownRedeemScriptsHex = listOf(KaspaCrypto.bytesToHex(bundle.redeemScript)),
                deployTxId = txId,
                liveBalanceSompi = bal,
                liveUtxoTxId = txId,
                liveUtxoIndex = 0,
                liveUtxoBlockDaaScore = baseDaa,
                liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.ACTIVE,
            )
            deployed.add(cov)
            val enc = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.encodeKkcv1Payload(
                com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.Kkcv1Recipe(
                    t = tpl.id,
                    n = network.id,
                    l = cov.label,
                    c = covId,
                    p = params,
                ),
                seedBackupKey32 = backupKey,
            )
            kkcv1Payloads.add(txId to enc)
            liveUtxos.add(
                UtxoItem(
                    address = bundle.p2shAddress,
                    transactionId = txId,
                    index = 0,
                    amountSompi = bal,
                    scriptPublicKeyHex = KaspaCrypto.bytesToHex(bundle.p2shScriptPubKey),
                    scriptPublicKeyVersion = 0,
                    blockDaaScore = baseDaa,
                    covenantId = covId,
                ),
            )
        }

        // Export `.kinetic` backup
        val kineticBytes = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.encryptKineticBackup(
            payload = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.KineticVaultPayload(
                walletId = walletId,
                walletName = "Cold Restore Wallet",
                walletFingerprint = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.walletFingerprint(ownerAddr),
                createdAtMs = nowMs,
                lastUpdatedAtMs = nowMs,
                covenants = deployed,
            ),
            seedBackupKey32 = backupKey,
        )

        // Wipe and restore in ALL 4 ways!
        val fromKinetic = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.decryptKineticBackup(
            kineticBytes, seedBackupKey32 = backupKey,
        ).covenants
        val fromKkcv1 = kkcv1Payloads.mapNotNull { (txId, bytes) ->
            val rec = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.tryDecodeKkcv1Payload(bytes, backupKey)
                ?: return@mapNotNull null
            com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.reconstructFromKkcv1(rec, walletId, txId)
        }
        val restoreModes = listOf(
            "kinetic_only" to fromKinetic,
            "kkcv1_only" to fromKkcv1,
            "kinetic_then_kkcv1" to com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.mergeAdditive(
                fromKinetic, fromKkcv1, walletId,
            ).merged,
            "kkcv1_then_kinetic" to com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.mergeAdditive(
                fromKkcv1, fromKinetic, walletId,
            ).merged,
        )

        val ownerSpk = KaspaAddress.decode(ownerAddr)!!.toScriptPublicKey()
        for ((modeName, rawRestored) in restoreModes) {
            assertEquals("$modeName must recover all 7 covenants", 7, rawRestored.size)
            val reconciled = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
                covenants = rawRestored,
                liveUtxos = liveUtxos,
                currentVirtualDaaScore = baseDaa + 2_000L,
                pastMedianTimeMs = pastMedianAfterUnlock,
            )
            for (cov in reconciled) {
                assertTrue(
                    "$modeName ${cov.templateId}: script hash must verify",
                    com.kaspakinetic.wallet.core.covenants.CovenantCompiler.verifyRedeemScriptProof(
                        cov.currentRedeemScriptHex, cov.currentP2shAddress, network,
                    ),
                )
                val liveUtxo = liveUtxos.first { it.address == cov.currentP2shAddress }
                val resolvedOwner = KaspaCrypto.resolveSigningKeyForPubKey(
                    seed = seed,
                    targetXOnlyPubKeyHex = cov.params.ownerPubKeyHex,
                    hintPath = cov.params.ownerDerivationPath,
                    hintChain = cov.params.ownerDerivationChain,
                    hintIndex = cov.params.ownerDerivationIndex,
                )
                assertNotNull("$modeName ${cov.templateId}: owner signing key must resolve", resolvedOwner)
                val ownerKey = resolvedOwner!!.privateKey32

                when (cov.templateId) {
                    com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.CLAWBACK_VAULT -> {
                        // Branch 1: initiateUnvault (owner key)
                        val st1Params = cov.params.copy(vaultStage = 1, pendingUnvaultDestAddress = ownerAddr)
                        val st1Bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(cov.templateId, st1Params, network)
                        val initTx = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                            covenant = cov,
                            covenantUtxo = liveUtxo,
                            entryName = "initiateUnvault",
                            outputs = listOf(
                                KaspaMassAndTx.TxOutput(
                                    valueSompi = liveUtxo.amountSompi - 2_000_000L,
                                    scriptPublicKeyVersion = 0,
                                    scriptPublicKey = st1Bundle.p2shScriptPubKey,
                                    address = st1Bundle.p2shAddress,
                                    covenant = KaspaMassAndTx.CovenantBinding(0, cov.covenantIdHex!!),
                                ),
                            ),
                            signerPrivateKeys = listOf(ownerKey),
                        )
                        assertTrue("$modeName Clawback initiateUnvault VM", initTx.vmResult!!.success)

                        // Branch 2: alarmClawback (alarm sub-key index 2!)
                        val resolvedAlarm = KaspaCrypto.resolveSigningKeyForPubKey(seed, cov.params.alarmPubKeyHex)
                        assertNotNull("$modeName Clawback alarm key must resolve", resolvedAlarm)
                        val alarmTx = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                            covenant = cov,
                            covenantUtxo = liveUtxo,
                            entryName = "alarmClawback",
                            outputs = listOf(KaspaMassAndTx.TxOutput(liveUtxo.amountSompi - 2_000_000L, 0, coldSpk, coldAddr)),
                            signerPrivateKeys = listOf(resolvedAlarm!!.privateKey32),
                        )
                        assertTrue("$modeName Clawback alarmClawback VM", alarmTx.vmResult!!.success)
                    }

                    com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH -> {
                        val reclaimTx = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                            covenant = cov,
                            covenantUtxo = liveUtxo,
                            entryName = "ownerReclaim",
                            outputs = listOf(KaspaMassAndTx.TxOutput(liveUtxo.amountSompi - 2_000_000L, 0, ownerSpk, ownerAddr)),
                            signerPrivateKeys = listOf(ownerKey),
                        )
                        assertTrue("$modeName DMS ownerReclaim VM", reclaimTx.vmResult!!.success)

                        val keylessHeir = com.kaspakinetic.wallet.core.covenants.CovenantKeylessExecutor.buildDueKeylessTx(
                            covenant = cov,
                            covenantUtxo = liveUtxo,
                            pastMedianTimeMs = pastMedianAfterUnlock,
                            preferredEntry = "heirClaim",
                        )
                        assertNotNull("$modeName DMS keyless heirClaim must succeed", keylessHeir)
                        assertTrue("$modeName DMS keyless heirClaim VM", keylessHeir!!.first.vmResult!!.success)
                    }

                    com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DAILY_SPEND_LIMIT -> {
                        val spendAmt = 2L * SOMPI_PER_KAS
                        val nextParams = cov.params.copy(spentInWindowSompi = spendAmt)
                        val nextBundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(cov.templateId, nextParams, network)
                        val spendTx = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                            covenant = cov,
                            covenantUtxo = liveUtxo,
                            entryName = "spendWithinLimit",
                            outputs = listOf(
                                KaspaMassAndTx.TxOutput(spendAmt, 0, coldSpk, coldAddr),
                                KaspaMassAndTx.TxOutput(
                                    liveUtxo.amountSompi - spendAmt - 2_000_000L,
                                    0,
                                    nextBundle.p2shScriptPubKey,
                                    nextBundle.p2shAddress,
                                    covenant = KaspaMassAndTx.CovenantBinding(0, cov.covenantIdHex!!),
                                ),
                            ),
                            signerPrivateKeys = listOf(ownerKey),
                        )
                        assertTrue("$modeName DailyLimit spendWithinLimit VM", spendTx.vmResult!!.success)

                        val reclaimTx = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                            covenant = cov,
                            covenantUtxo = liveUtxo,
                            entryName = "ownerReclaim",
                            outputs = listOf(KaspaMassAndTx.TxOutput(liveUtxo.amountSompi - 2_000_000L, 0, ownerSpk, ownerAddr)),
                            lockTime = unlockTimeMs,
                            currentTimeMs = pastMedianAfterUnlock,
                            signerPrivateKeys = listOf(ownerKey),
                        )
                        assertTrue("$modeName DailyLimit ownerReclaim VM", reclaimTx.vmResult!!.success)
                    }

                    com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.COLD_WHITELIST -> {
                        val spendTx = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                            covenant = cov,
                            covenantUtxo = liveUtxo,
                            entryName = "spend",
                            outputs = listOf(KaspaMassAndTx.TxOutput(liveUtxo.amountSompi - 2_000_000L, 0, coldSpk, coldAddr)),
                            signerPrivateKeys = listOf(ownerKey),
                        )
                        assertTrue("$modeName ColdWhitelist spend VM", spendTx.vmResult!!.success)
                    }

                    com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.HODL_TIMELOCK -> {
                        val unlockTx = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                            covenant = cov,
                            covenantUtxo = liveUtxo,
                            entryName = "unlock",
                            outputs = listOf(KaspaMassAndTx.TxOutput(liveUtxo.amountSompi - 2_000_000L, 0, ownerSpk, ownerAddr)),
                            lockTime = unlockTimeMs,
                            currentTimeMs = pastMedianAfterUnlock,
                            signerPrivateKeys = listOf(ownerKey),
                        )
                        assertTrue("$modeName Hodl unlock VM", unlockTx.vmResult!!.success)
                    }

                    com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.GOAL_PIGGY_BANK -> {
                        val goalTx = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                            covenant = cov,
                            covenantUtxo = liveUtxo,
                            entryName = "unlockGoal",
                            outputs = listOf(KaspaMassAndTx.TxOutput(liveUtxo.amountSompi - 2_000_000L, 0, ownerSpk, ownerAddr)),
                            signerPrivateKeys = listOf(ownerKey),
                        )
                        assertTrue("$modeName GoalPiggy unlockGoal VM", goalTx.vmResult!!.success)
                    }

                    com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE -> {
                        val cancelTx = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                            covenant = cov,
                            covenantUtxo = liveUtxo,
                            entryName = "cancelReclaim",
                            outputs = listOf(KaspaMassAndTx.TxOutput(liveUtxo.amountSompi - 2_000_000L, 0, ownerSpk, ownerAddr)),
                            signerPrivateKeys = listOf(ownerKey),
                        )
                        assertTrue("$modeName Recurring cancelReclaim VM", cancelTx.vmResult!!.success)

                        val keylessPull = com.kaspakinetic.wallet.core.covenants.CovenantKeylessExecutor.buildDueKeylessTx(
                            covenant = cov,
                            covenantUtxo = liveUtxo,
                            pastMedianTimeMs = pastMedianAfterUnlock,
                            preferredEntry = "withdraw",
                        )
                        assertNotNull("$modeName Recurring keyless withdraw must succeed", keylessPull)
                        assertTrue("$modeName Recurring keyless withdraw VM", keylessPull!!.first.vmResult!!.success)
                    }
                }
            }
        }
    }

    @Test
    fun stage35MidLifecycleStatefulDriftRescueDailySpendClawbackSubscriptionDeadMan() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        val seed = Bip39.mnemonicToSeed(mnemonic, "")
        val network = KaspaNetwork.MAINNET
        val walletId = "wallet_drift_rescue"

        val ownerPriv = KaspaCrypto.derivePrivateKeyFromSeed(seed, chain = 0, index = 4)
        val ownerPub = KaspaCrypto.xOnlyPublicKey(ownerPriv)
        val ownerPubHex = KaspaCrypto.bytesToHex(ownerPub)
        val ownerAddr = KaspaAddress.encodePubKey(ownerPub, network)
        val ownerSpk = KaspaAddress.decode(ownerAddr)!!.toScriptPublicKey()

        val (alarmPriv, alarmPub) = KaspaCrypto.deriveCovenantSubKey(seed, 3)
        val alarmPubHex = KaspaCrypto.bytesToHex(alarmPub)
        val destPriv = KaspaCrypto.derivePrivateKeyFromSeed(seed, chain = 0, index = 9)
        val destAddr = KaspaAddress.encodePubKey(KaspaCrypto.xOnlyPublicKey(destPriv), network)
        val destSpk = KaspaAddress.decode(destAddr)!!.toScriptPublicKey()

        // --- A. Daily Spend Limit (exact Mainnet bug reproduction: 5.00 KAS -> 2.00 KAS spent -> 2.98811705 KAS live!) ---
        val dslSalt = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex()
        val dslCovId = "a1".repeat(32)
        val dslState0Params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = ownerPubHex,
            ownerAddress = ownerAddr,
            vaultSaltHex = dslSalt,
            dailyCapSompi = 3L * SOMPI_PER_KAS,
            windowDaa = 864_000L,
            spentInWindowSompi = 0L,
            fullWithdrawUnlockTimeMs = 1_797_000_000_000L,
        )
        val dslState0Bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DAILY_SPEND_LIMIT, dslState0Params, network,
        )
        val dslState1Params = dslState0Params.copy(spentInWindowSompi = 2L * SOMPI_PER_KAS)
        val dslState1Bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DAILY_SPEND_LIMIT, dslState1Params, network,
        )
        val staleDslState0 = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.canonicalKeyFor(
                network, com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DAILY_SPEND_LIMIT, dslCovId, dslState0Bundle.redeemScriptHashHex,
            ),
            walletId = walletId,
            network = network,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DAILY_SPEND_LIMIT,
            label = "Mainnet Daily Limit",
            covenantIdHex = dslCovId,
            currentP2shAddress = dslState0Bundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(dslState0Bundle.redeemScript),
            redeemScriptHashHex = dslState0Bundle.redeemScriptHashHex,
            silSource = dslState0Bundle.silSource,
            abiArtifact = dslState0Bundle.abiArtifact,
            params = dslState0Params,
        )
        val liveDslState1Utxo = UtxoItem(
            address = dslState1Bundle.p2shAddress,
            transactionId = "d1".repeat(32),
            index = 1,
            amountSompi = 298_811_705L, // Exact 2.98811705 KAS!
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(dslState1Bundle.p2shScriptPubKey),
            scriptPublicKeyVersion = 0,
            blockDaaScore = 50_000_000L,
            covenantId = dslCovId,
        )

        // Reconcile BOTH with REST candidate outputs[0] AND offline (empty candidates)!
        for (cands in listOf(mapOf(staleDslState0.canonicalKey to listOf(2L * SOMPI_PER_KAS)), emptyMap())) {
            val rescued = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
                covenants = listOf(staleDslState0),
                liveUtxos = listOf(liveDslState1Utxo),
                currentVirtualDaaScore = 50_000_100L,
                candidateSpentByKey = cands,
            ).single()
            assertEquals(2L * SOMPI_PER_KAS, rescued.params.spentInWindowSompi)
            assertTrue(
                "Verify On-Chain must pass after Daily Spend Limit self-heal",
                com.kaspakinetic.wallet.core.covenants.CovenantCompiler.verifyRedeemScriptProof(
                    rescued.currentRedeemScriptHex, rescued.currentP2shAddress, network,
                ),
            )
            // Immediately spend remaining 1.00 KAS and prove KaspaScriptVm passes!
            val nextSpend = 1L * SOMPI_PER_KAS
            val state2Bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
                rescued.templateId, rescued.params.copy(spentInWindowSompi = 3L * SOMPI_PER_KAS), network,
            )
            val spendTx = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                covenant = rescued,
                covenantUtxo = liveDslState1Utxo,
                entryName = "spendWithinLimit",
                outputs = listOf(
                    KaspaMassAndTx.TxOutput(nextSpend, 0, destSpk, destAddr),
                    KaspaMassAndTx.TxOutput(
                        liveDslState1Utxo.amountSompi - nextSpend - 2_000_000L,
                        0,
                        state2Bundle.p2shScriptPubKey,
                        state2Bundle.p2shAddress,
                        covenant = KaspaMassAndTx.CovenantBinding(0, dslCovId),
                    ),
                ),
                signerPrivateKeys = listOf(ownerPriv),
            )
            assertTrue("Follow-up spend on rescued Mainnet Daily Spend Limit must pass VM", spendTx.vmResult!!.success)
        }

        // --- B. Clawback Vault State-0 -> State-1 Unvaulting Drift Rescue ---
        val clawCovId = "b2".repeat(32)
        val clawSt0Params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = ownerPubHex,
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            alarmPubKeyHex = alarmPubHex,
            recoveryAddress = ownerAddr,
            unvaultDelayDaa = 1_000L,
            vaultStage = 0,
        )
        val clawSt0Bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.CLAWBACK_VAULT, clawSt0Params, network,
        )
        val clawSt1Params = clawSt0Params.copy(vaultStage = 1, pendingUnvaultDestAddress = destAddr)
        val clawSt1Bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.CLAWBACK_VAULT, clawSt1Params, network,
        )
        val staleClaw0 = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.canonicalKeyFor(
                network, com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.CLAWBACK_VAULT, clawCovId, clawSt0Bundle.redeemScriptHashHex,
            ),
            walletId = walletId,
            network = network,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.CLAWBACK_VAULT,
            label = "Clawback Vault",
            covenantIdHex = clawCovId,
            currentP2shAddress = clawSt0Bundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(clawSt0Bundle.redeemScript),
            redeemScriptHashHex = clawSt0Bundle.redeemScriptHashHex,
            silSource = clawSt0Bundle.silSource,
            abiArtifact = clawSt0Bundle.abiArtifact,
            params = clawSt0Params,
        )
        val liveClaw1Utxo = UtxoItem(
            address = clawSt1Bundle.p2shAddress,
            transactionId = "c1".repeat(32),
            index = 0,
            amountSompi = 5L * SOMPI_PER_KAS,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(clawSt1Bundle.p2shScriptPubKey),
            scriptPublicKeyVersion = 0,
            blockDaaScore = 50_000_000L,
            covenantId = clawCovId,
        )
        val rescuedClaw = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(staleClaw0),
            liveUtxos = listOf(liveClaw1Utxo),
            currentVirtualDaaScore = 50_002_000L,
            candidateDestAddresses = listOf(ownerAddr, destAddr),
        ).single()
        assertEquals(1, rescuedClaw.params.vaultStage)
        assertEquals(destAddr, rescuedClaw.params.pendingUnvaultDestAddress)
        assertTrue(
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                covenant = rescuedClaw,
                covenantUtxo = liveClaw1Utxo,
                entryName = "completeUnvault",
                outputs = listOf(KaspaMassAndTx.TxOutput(liveClaw1Utxo.amountSompi - 2_000_000L, 0, destSpk, destAddr)),
                sequence = 1_000L,
                virtualDaaScore = 50_002_000L,
                signerPrivateKeys = listOf(ownerPriv),
            ).vmResult!!.success,
        )
        assertTrue(
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                covenant = rescuedClaw,
                covenantUtxo = liveClaw1Utxo,
                entryName = "alarmClawback",
                outputs = listOf(KaspaMassAndTx.TxOutput(liveClaw1Utxo.amountSompi - 2_000_000L, 0, ownerSpk, ownerAddr)),
                signerPrivateKeys = listOf(alarmPriv),
            ).vmResult!!.success,
        )

        // --- C. Recurring Allowance after 3 `.kpass` claims -> Cycle-4 Claim & Owner Cancel ---
        val subCovId = "c3".repeat(32)
        val startDueMs = 1_796_200_000_000L
        val intervalMs = 86_400_000L
        val sub0Params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = ownerPubHex,
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            recipientAddress = destAddr,
            allowanceSompi = 1L * SOMPI_PER_KAS,
            nextDueTimeMs = startDueMs,
            intervalMs = intervalMs,
        )
        val sub0Bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE, sub0Params, network,
        )
        val sub3Bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            sub0Params.copy(nextDueTimeMs = startDueMs + 3L * intervalMs),
            network,
        )
        val staleSub0 = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.canonicalKeyFor(
                network, com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE, subCovId, sub0Bundle.redeemScriptHashHex,
            ),
            walletId = walletId,
            network = network,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            label = "Allowance",
            covenantIdHex = subCovId,
            currentP2shAddress = sub0Bundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(sub0Bundle.redeemScript),
            redeemScriptHashHex = sub0Bundle.redeemScriptHashHex,
            silSource = sub0Bundle.silSource,
            abiArtifact = sub0Bundle.abiArtifact,
            params = sub0Params,
        )
        val liveSub3Utxo = UtxoItem(
            address = sub3Bundle.p2shAddress,
            transactionId = "e3".repeat(32),
            index = 1,
            amountSompi = 5L * SOMPI_PER_KAS,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(sub3Bundle.p2shScriptPubKey),
            scriptPublicKeyVersion = 0,
            blockDaaScore = 50_000_000L,
            covenantId = subCovId,
        )
        val rescuedSub = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(staleSub0),
            liveUtxos = listOf(liveSub3Utxo),
            currentVirtualDaaScore = 50_000_100L,
            pastMedianTimeMs = startDueMs + 3L * intervalMs + 60_000L,
        ).single()
        assertEquals(startDueMs + 3L * intervalMs, rescuedSub.params.nextDueTimeMs)
        val cycle4Pull = com.kaspakinetic.wallet.core.covenants.CovenantKeylessExecutor.buildDueKeylessTx(
            covenant = rescuedSub,
            covenantUtxo = liveSub3Utxo,
            pastMedianTimeMs = startDueMs + 3L * intervalMs + 60_000L,
            preferredEntry = "withdraw",
        )
        assertNotNull("Cycle-4 keyless withdraw must succeed after 3-cycle drift rescue", cycle4Pull)
        assertTrue(cycle4Pull!!.first.vmResult!!.success)
        assertTrue(
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                covenant = rescuedSub,
                covenantUtxo = liveSub3Utxo,
                entryName = "cancelReclaim",
                outputs = listOf(KaspaMassAndTx.TxOutput(liveSub3Utxo.amountSompi - 2_000_000L, 0, ownerSpk, ownerAddr)),
                signerPrivateKeys = listOf(ownerPriv),
            ).vmResult!!.success,
        )

        // --- D. Dead Man's Switch after Check-In KKCV1 transition ---
        val dmsCovId = "d4".repeat(32)
        val dms0Params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = ownerPubHex,
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            heirAddress = destAddr,
            inactivityDeadlineMs = startDueMs,
            inactivityDurationMs = intervalMs,
        )
        val dms0Bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH, dms0Params, network,
        )
        val dms1Params = dms0Params.copy(inactivityDeadlineMs = startDueMs + 2L * intervalMs)
        val dms1Bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH, dms1Params, network,
        )
        val dmsKey = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.canonicalKeyFor(
            network, com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH, dmsCovId, dms0Bundle.redeemScriptHashHex,
        )
        val staleDms0 = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = dmsKey,
            walletId = walletId,
            network = network,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH,
            label = "Family Switch",
            covenantIdHex = dmsCovId,
            currentP2shAddress = dms0Bundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(dms0Bundle.redeemScript),
            redeemScriptHashHex = dms0Bundle.redeemScriptHashHex,
            silSource = dms0Bundle.silSource,
            abiArtifact = dms0Bundle.abiArtifact,
            params = dms0Params,
        )
        val checkInRecipe = staleDms0.copy(
            currentP2shAddress = dms1Bundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(dms1Bundle.redeemScript),
            redeemScriptHashHex = dms1Bundle.redeemScriptHashHex,
            params = dms1Params,
        )
        val liveDms1Utxo = UtxoItem(
            address = dms1Bundle.p2shAddress,
            transactionId = "f4".repeat(32),
            index = 0,
            amountSompi = 5L * SOMPI_PER_KAS,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(dms1Bundle.p2shScriptPubKey),
            scriptPublicKeyVersion = 0,
            blockDaaScore = 50_000_000L,
            covenantId = dmsCovId,
        )
        val rescuedDms = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(staleDms0),
            liveUtxos = listOf(liveDms1Utxo),
            currentVirtualDaaScore = 50_000_100L,
            candidateRecipesByKey = mapOf(dmsKey to listOf(checkInRecipe, staleDms0)),
        ).single()
        assertEquals(dms1Params.inactivityDeadlineMs, rescuedDms.params.inactivityDeadlineMs)
        assertTrue(
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                covenant = rescuedDms,
                covenantUtxo = liveDms1Utxo,
                entryName = "ownerReclaim",
                outputs = listOf(KaspaMassAndTx.TxOutput(liveDms1Utxo.amountSompi - 2_000_000L, 0, ownerSpk, ownerAddr)),
                signerPrivateKeys = listOf(ownerPriv),
            ).vmResult!!.success,
        )
    }

    @Test
    fun stage35LegacyKeyPathRescueVmDiagnosticsAndAntiFlickerGuard() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        val seed = Bip39.mnemonicToSeed(mnemonic, "")
        val network = KaspaNetwork.MAINNET

        // Legacy vault deployed at receive index 19 with NO saved ownerDerivationPath or ownerAddress!
        val realPriv = KaspaCrypto.derivePrivateKeyFromSeed(seed, chain = 0, index = 19)
        val realPub = KaspaCrypto.xOnlyPublicKey(realPriv)
        val realPubHex = KaspaCrypto.bytesToHex(realPub)
        val destAddr = KaspaAddress.encodePubKey(realPub, network)
        val destSpk = KaspaAddress.decode(destAddr)!!.toScriptPublicKey()

        val resolved = KaspaCrypto.resolveSigningKeyForPubKey(
            seed = seed,
            targetXOnlyPubKeyHex = realPubHex,
            hintPath = "",
            hintChain = -1,
            hintIndex = -1,
        )
        assertNotNull("Self-healing resolver must discover legacy index 19 key", resolved)
        assertEquals(0, resolved!!.chain)
        assertEquals(19, resolved.index)
        assertTrue(resolved.xOnlyPubKey32.contentEquals(realPub))

        // Unknown external pubkey must return null cleanly (NEVER silently fall back to index 0!)
        val foreignPub = KaspaCrypto.xOnlyPublicKey(ByteArray(32) { (it + 77).toByte() })
        assertNull(KaspaCrypto.resolveSigningKeyForPubKey(seed, KaspaCrypto.bytesToHex(foreignPub)))

        // Upgraded VM diagnostics: signing with WRONG key must produce explicit OP_CHECKSIG diagnostic!
        val params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = realPubHex,
            ownerAddress = "",
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            whitelistAddresses = listOf(destAddr),
        )
        val bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.COLD_WHITELIST, params, network,
        )
        val cov = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "p2sh:mainnet:cold_whitelist:${bundle.redeemScriptHashHex}",
            walletId = "w1",
            network = network,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.COLD_WHITELIST,
            label = "Legacy Whitelist",
            currentP2shAddress = bundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(bundle.redeemScript),
            redeemScriptHashHex = bundle.redeemScriptHashHex,
            silSource = bundle.silSource,
            abiArtifact = bundle.abiArtifact,
            params = params,
        )
        val utxo = UtxoItem(
            address = bundle.p2shAddress,
            transactionId = "11".repeat(32),
            index = 0,
            amountSompi = 3L * SOMPI_PER_KAS,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(bundle.p2shScriptPubKey),
            scriptPublicKeyVersion = 0,
            blockDaaScore = 10_000L,
        )
        val wrongPriv = KaspaCrypto.derivePrivateKeyFromSeed(seed, chain = 0, index = 0)
        var capturedErr = ""
        try {
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                covenant = cov,
                covenantUtxo = utxo,
                entryName = "spend",
                outputs = listOf(KaspaMassAndTx.TxOutput(2_900_000_000L, 0, destSpk, destAddr)),
                signerPrivateKeys = listOf(wrongPriv),
            )
        } catch (e: IllegalStateException) {
            capturedErr = e.message.orEmpty()
        }
        assertTrue(
            "VM error must clearly diagnose OP_CHECKSIG mismatch instead of generic 'Script evaluated to FALSE': $capturedErr",
            capturedErr.contains("OP_CHECKSIG returned FALSE"),
        )

        // Anti-flicker guard: transient gRPC failure (`covenantIdQuerySucceeded = false`) MUST NEVER mark ACTIVE stateful covenant as SETTLED!
        val activeStateful = cov.copy(
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DAILY_SPEND_LIMIT,
            covenantIdHex = "aa".repeat(32),
            liveBalanceSompi = 298_811_705L,
            liveUtxoTxId = "22".repeat(32),
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.ACTIVE,
        )
        val afterTransientRpcError = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(activeStateful),
            liveUtxos = emptyList(),
            currentVirtualDaaScore = 20_000L,
            covenantIdQuerySucceeded = false,
        ).single()
        assertEquals(com.kaspakinetic.wallet.core.covenants.CovenantStatus.ACTIVE, afterTransientRpcError.liveStatus)
        assertEquals(298_811_705L, afterTransientRpcError.liveBalanceSompi)
    }

    @Test
    fun stage35FullBalanceColdWhitelistAndDailySpendLimitSweepNoDustFeeExplosion() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        val seed = Bip39.mnemonicToSeed(mnemonic, "")
        val network = KaspaNetwork.MAINNET
        val priv = KaspaCrypto.derivePrivateKeyFromSeed(seed, chain = 0, index = 0)
        val pub = KaspaCrypto.xOnlyPublicKey(priv)
        val addr = KaspaAddress.encodePubKey(pub, network)
        val spk = KaspaAddress.decode(addr)!!.toScriptPublicKey()

        // Exact reproduction: 3.00 KAS Cold-Only Address Whitelist full-balance send!
        val cwParams = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = KaspaCrypto.bytesToHex(pub),
            ownerAddress = addr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            whitelistAddresses = listOf(addr),
        )
        val cwBundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.COLD_WHITELIST, cwParams, network,
        )
        val cwCov = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "p2sh:mainnet:cold_whitelist:${cwBundle.redeemScriptHashHex}",
            walletId = "w1",
            network = network,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.COLD_WHITELIST,
            label = "3 KAS Whitelist",
            currentP2shAddress = cwBundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(cwBundle.redeemScript),
            redeemScriptHashHex = cwBundle.redeemScriptHashHex,
            silSource = cwBundle.silSource,
            abiArtifact = cwBundle.abiArtifact,
            params = cwParams,
        )
        val cwUtxo = UtxoItem(
            address = cwBundle.p2shAddress,
            transactionId = "33".repeat(32),
            index = 0,
            amountSompi = 3L * SOMPI_PER_KAS,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(cwBundle.p2shScriptPubKey),
            scriptPublicKeyVersion = 0,
            blockDaaScore = 10_000L,
        )
        val cwPlan = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.computeSpendAndFeePlan(
            covenant = cwCov,
            covenantUtxo = cwUtxo,
            entryName = "spend",
            requestedSpendSompi = 3L * SOMPI_PER_KAS,
        )
        assertTrue("3.00 KAS send on 3.00 KAS vault must execute as 1-output full sweep", cwPlan.isFullSweep)
        assertEquals(0L, cwPlan.changeSompi)
        assertTrue("Miner fee must be normal (< 0.05 KAS), got ${cwPlan.feeSompi}", cwPlan.feeSompi < 5_000_000L)
        assertTrue("Swept output must be > 2.95 KAS", cwPlan.spendSompi > 295_000_000L)

        val sweepTx = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
            covenant = cwCov,
            covenantUtxo = cwUtxo,
            entryName = "spend",
            outputs = listOf(KaspaMassAndTx.TxOutput(cwPlan.spendSompi, 0, spk, addr)),
            signerPrivateKeys = listOf(priv),
        )
        assertTrue("Full-balance 3.00 KAS Cold Whitelist sweep must pass KaspaScriptVm", sweepTx.vmResult!!.success)
        assertEquals(1, sweepTx.rawTx.outputs.size)
    }

    @Test
    fun stage36LiveGrpcZeroUtxosImmediatelySettlesRestoredAndExternallyDrainedCovenants() {
        val pub = KaspaCrypto.xOnlyPublicKey(ByteArray(32) { (it + 31).toByte() })
        val ownerAddr = KaspaAddress.encodePubKey(pub, KaspaNetwork.MAINNET)
        val heirPub = KaspaCrypto.xOnlyPublicKey(ByteArray(32) { (it + 41).toByte() })
        val heirAddr = KaspaAddress.encodePubKey(heirPub, KaspaNetwork.MAINNET)
        val nowMs = 1_796_500_000_000L

        // 1. Chain-Scan restored settled vault (`reconstructFromKkcv1` -> `localBroadcastAtMs == 0L`)
        val dmsParams = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = KaspaCrypto.bytesToHex(pub),
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            heirAddress = heirAddr,
            inactivityDeadlineMs = nowMs - 60_000L,
            inactivityDurationMs = 86_400_000L,
        )
        val dmsCovId = "ab".repeat(32)
        val restoredFromScan = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.reconstructFromKkcv1(
            recipe = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.Kkcv1Recipe(
                t = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH.id,
                n = KaspaNetwork.MAINNET.id,
                l = "Restored Settled Switch",
                c = dmsCovId,
                p = dmsParams,
            ),
            walletId = "w1",
            deployTxId = "10".repeat(32),
        )!!.copy(lastUpdatedAtMs = nowMs) // Even if lastUpdatedAtMs == nowMs, localBroadcastAtMs == 0L!

        val reconciledRestored = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(restoredFromScan),
            liveUtxos = emptyList(),
            currentVirtualDaaScore = 200_000_000L,
            verifiedAtMs = nowMs,
            covenantIdQuerySucceeded = true,
            addressQuerySucceeded = true,
        ).single()
        assertEquals(
            "Chain-scan restored vault with 0 live UTXOs must immediately be SETTLED (never stuck Pending)",
            com.kaspakinetic.wallet.core.covenants.CovenantStatus.SETTLED,
            reconciledRestored.liveStatus,
        )
        assertEquals(0L, reconciledRestored.liveBalanceSompi)
        assertNull(reconciledRestored.liveUtxoTxId)

        // 2. Active Owner Dead Man's Switch (10.00 KAS READY) externally claimed by Heir
        val ownerDmsActive = restoredFromScan.copy(
            canonicalKey = "kip20:mainnet:${"cd".repeat(32)}",
            covenantIdHex = "cd".repeat(32),
            liveBalanceSompi = 10L * SOMPI_PER_KAS,
            liveUtxoTxId = "20".repeat(32),
            liveUtxoIndex = 0,
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.READY,
            lastChainVerifiedAtMs = nowMs,
        )
        // 3. Active Owner Recurring Allowance (5.00 KAS READY) externally drained by Recipient
        val subParams = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = KaspaCrypto.bytesToHex(pub),
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            recipientAddress = heirAddr,
            allowanceSompi = 5L * SOMPI_PER_KAS,
            nextDueTimeMs = nowMs - 60_000L,
            intervalMs = 86_400_000L,
        )
        val subBundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE, subParams, KaspaNetwork.MAINNET,
        )
        val subCovId = "ef".repeat(32)
        val ownerSubActive = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.canonicalKeyFor(
                KaspaNetwork.MAINNET, com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE, subCovId, subBundle.redeemScriptHashHex,
            ),
            walletId = "w1",
            network = KaspaNetwork.MAINNET,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            label = "Drained Subscription",
            covenantIdHex = subCovId,
            currentP2shAddress = subBundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(subBundle.redeemScript),
            redeemScriptHashHex = subBundle.redeemScriptHashHex,
            silSource = subBundle.silSource,
            abiArtifact = subBundle.abiArtifact,
            params = subParams,
            liveBalanceSompi = 5L * SOMPI_PER_KAS,
            liveUtxoTxId = "30".repeat(32),
            liveUtxoIndex = 0,
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.READY,
            lastChainVerifiedAtMs = nowMs,
        )

        val afterExternalDrain = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(ownerDmsActive, ownerSubActive),
            liveUtxos = emptyList(),
            currentVirtualDaaScore = 200_000_000L,
            verifiedAtMs = nowMs, // Same millisecond as prior verification -> monotonicVerifiedAt must still win merge!
            covenantIdQuerySucceeded = true,
            addressQuerySucceeded = true,
        )
        // Merge back against cached ACTIVE records (simulating WalletViewModel disk commit)
        val mergedAfterDrain = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.mergeAdditive(
            existing = listOf(ownerDmsActive, ownerSubActive),
            incoming = afterExternalDrain,
            targetWalletId = "w1",
        ).merged
        for (c in mergedAfterDrain) {
            assertEquals("${c.label} must immediately become SETTLED after external drain", com.kaspakinetic.wallet.core.covenants.CovenantStatus.SETTLED, c.liveStatus)
            assertEquals("${c.label} balance must clear to 0", 0L, c.liveBalanceSompi)
            assertNull("${c.label} cached outpoint must clear", c.liveUtxoTxId)
        }

        // Also settles cleanly when connected node explicitly lacks covenant index while address gRPC succeeds!
        val settledWithoutCovIndex = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(ownerDmsActive),
            liveUtxos = emptyList(),
            currentVirtualDaaScore = 200_000_000L,
            verifiedAtMs = nowMs,
            covenantIdQuerySucceeded = false,
            addressQuerySucceeded = true,
            covenantIndexUnsupportedOnNode = true,
        ).single()
        assertEquals(com.kaspakinetic.wallet.core.covenants.CovenantStatus.SETTLED, settledWithoutCovIndex.liveStatus)
        assertEquals(0L, settledWithoutCovIndex.liveBalanceSompi)
    }

    @Test
    fun stage36RpcExceptionPreservesActiveAndFreshLocalBroadcastStaysPending() {
        val pub = KaspaCrypto.xOnlyPublicKey(ByteArray(32) { (it + 53).toByte() })
        val ownerAddr = KaspaAddress.encodePubKey(pub, KaspaNetwork.MAINNET)
        val nowMs = 1_796_500_000_000L
        val params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = KaspaCrypto.bytesToHex(pub),
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            unlockTimeMs = nowMs + 86_400_000L,
        )
        val bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.HODL_TIMELOCK, params, KaspaNetwork.MAINNET,
        )
        val activeCov = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "p2sh:mainnet:hodl_timelock:${bundle.redeemScriptHashHex}",
            walletId = "w1",
            network = KaspaNetwork.MAINNET,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.HODL_TIMELOCK,
            label = "Active Hodl",
            currentP2shAddress = bundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(bundle.redeemScript),
            redeemScriptHashHex = bundle.redeemScriptHashHex,
            silSource = bundle.silSource,
            abiArtifact = bundle.abiArtifact,
            params = params,
            liveBalanceSompi = 8L * SOMPI_PER_KAS,
            liveUtxoTxId = "44".repeat(32),
            liveUtxoIndex = 0,
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.ACTIVE,
        )

        // Genuine transport timeout/error MUST NOT falsely flip Active -> Settled!
        val afterRpcError = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(activeCov),
            liveUtxos = emptyList(),
            currentVirtualDaaScore = 200_000_000L,
            verifiedAtMs = nowMs,
            covenantIdQuerySucceeded = false,
            addressQuerySucceeded = false,
            covenantIndexUnsupportedOnNode = false,
        ).single()
        assertEquals(com.kaspakinetic.wallet.core.covenants.CovenantStatus.ACTIVE, afterRpcError.liveStatus)
        assertEquals(8L * SOMPI_PER_KAS, afterRpcError.liveBalanceSompi)

        // Freshly broadcast local deploy (< 60s old) MUST remain UNCONFIRMED (Pending)
        val freshBroadcast = activeCov.copy(
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.UNCONFIRMED,
            localBroadcastAtMs = nowMs - 8_000L,
            lastUpdatedAtMs = nowMs - 8_000L,
        )
        val stillPending = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(freshBroadcast),
            liveUtxos = emptyList(),
            currentVirtualDaaScore = 200_000_000L,
            verifiedAtMs = nowMs,
            covenantIdQuerySucceeded = true,
            addressQuerySucceeded = true,
        ).single()
        assertEquals(com.kaspakinetic.wallet.core.covenants.CovenantStatus.UNCONFIRMED, stillPending.liveStatus)
        assertEquals(8L * SOMPI_PER_KAS, stillPending.liveBalanceSompi)

        // Once > 60s old with 0 UTXOs on healthy gRPC, settles cleanly!
        val expiredPending = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(freshBroadcast),
            liveUtxos = emptyList(),
            currentVirtualDaaScore = 200_000_000L,
            verifiedAtMs = nowMs + 65_000L,
            covenantIdQuerySucceeded = true,
            addressQuerySucceeded = true,
        ).single()
        assertEquals(com.kaspakinetic.wallet.core.covenants.CovenantStatus.SETTLED, expiredPending.liveStatus)
        assertEquals(0L, expiredPending.liveBalanceSompi)
    }

    @Test
    fun stage36CancellationExceptionDoesNotTriggerRestFallbackAndPostActivationScanStateMachine() {
        // 1. CancellationException MUST rethrow immediately out of runCatchingNonCancellation
        //    so cancelled refreshes NEVER fall through to REST fallback or flash the warning!
        var restFallbackCalled = false
        var cancellationRethrown = false
        try {
            val attempt = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation<String> {
                throw kotlinx.coroutines.CancellationException("Screen navigated")
            }
            if (attempt.isFailure) {
                restFallbackCalled = true
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancellationRethrown = true
        }
        assertTrue("CancellationException must be rethrown", cancellationRethrown)
        assertFalse("REST fallback must NEVER run on CancellationException", restFallbackCalled)

        // Genuine IOException IS caught cleanly
        val ioRes = com.kaspakinetic.wallet.core.explore.runCatchingNonCancellation<String> {
            throw java.io.IOException("Socket closed")
        }
        assertTrue(ioRes.isFailure)

        // 2. Post-Activation BlockDAG Covenant Scan state machine
        val idle = com.kaspakinetic.wallet.viewmodel.PostActivationScanState()
        assertFalse(idle.visible)
        assertFalse(idle.isScanning)
        assertTrue(idle.dismissAllowed)

        val scanning = com.kaspakinetic.wallet.viewmodel.PostActivationScanState.startScanning()
        assertTrue(scanning.visible)
        assertTrue(scanning.isScanning)
        assertFalse("Dialog MUST NOT be dismissible while scanning", scanning.dismissAllowed)

        val completed = com.kaspakinetic.wallet.viewmodel.PostActivationScanState.completed(foundCount = 3)
        assertTrue(completed.visible)
        assertFalse(completed.isScanning)
        assertTrue("Dialog MUST be dismissible once scan completes", completed.dismissAllowed)
        assertEquals(3, completed.foundCount)
        assertNull(completed.errorMessage)

        val dismissed = com.kaspakinetic.wallet.viewmodel.PostActivationScanState.dismissed()
        assertFalse(dismissed.visible)
    }

    @Test
    fun stage37NoCovenantIndexMultiStateSyncReconcilesCycle1AndCancelsCleanly() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        val seed = Bip39.mnemonicToSeed(mnemonic, "")
        val network = KaspaNetwork.MAINNET
        val ownerPriv = KaspaCrypto.derivePrivateKeyFromSeed(seed, chain = 0, index = 2)
        val ownerPub = KaspaCrypto.xOnlyPublicKey(ownerPriv)
        val ownerAddr = KaspaAddress.encodePubKey(ownerPub, network)
        val ownerSpk = KaspaAddress.decode(ownerAddr)!!.toScriptPublicKey()

        val recPriv = KaspaCrypto.derivePrivateKeyFromSeed(seed, chain = 0, index = 5)
        val recAddr = KaspaAddress.encodePubKey(KaspaCrypto.xOnlyPublicKey(recPriv), network)

        val cycle0DueMs = 1_796_600_000_000L
        val intervalMs = 86_400_000L
        val subCovId = "99".repeat(32)
        val cycle0Params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = KaspaCrypto.bytesToHex(ownerPub),
            ownerAddress = ownerAddr,
            ownerDerivationPath = "m/44'/111111'/0'/0/2",
            ownerDerivationChain = 0,
            ownerDerivationIndex = 2,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            recipientAddress = recAddr,
            allowanceSompi = 1L * SOMPI_PER_KAS,
            nextDueTimeMs = cycle0DueMs,
            intervalMs = intervalMs,
        )
        val cycle0Bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            cycle0Params,
            network,
        )
        val cycle1Params = cycle0Params.copy(nextDueTimeMs = cycle0DueMs + intervalMs)
        val cycle1Bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            cycle1Params,
            network,
        )

        val ownerStaleCycle0 = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.canonicalKeyFor(
                network,
                com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
                subCovId,
                cycle0Bundle.redeemScriptHashHex,
            ),
            walletId = "w_owner",
            network = network,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            label = "Allowance Pool",
            covenantIdHex = subCovId,
            currentP2shAddress = cycle0Bundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(cycle0Bundle.redeemScript),
            redeemScriptHashHex = cycle0Bundle.redeemScriptHashHex,
            silSource = cycle0Bundle.silSource,
            abiArtifact = cycle0Bundle.abiArtifact,
            params = cycle0Params,
            liveBalanceSompi = 5L * SOMPI_PER_KAS,
            liveUtxoTxId = "aa".repeat(32),
            liveUtxoIndex = 0,
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.READY,
        )

        // Batched lookahead probe generates Cycles 0..24 (25 addresses) containing BOTH Cycle 0 & Cycle 1!
        val lookaheadAddrs = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildMultiStateLookaheadAddresses(
            covenant = ownerStaleCycle0,
            subscriptionStartCycleOffset = 0,
            subscriptionCycleCount = 25,
        )
        assertTrue("Lookahead must cover 25 cycles", lookaheadAddrs.size >= 25)
        assertTrue("Lookahead must include Cycle 0 P2SH", cycle0Bundle.p2shAddress in lookaheadAddrs)
        assertTrue("Lookahead must include Cycle 1 P2SH", cycle1Bundle.p2shAddress in lookaheadAddrs)

        // Simulate standard `--utxoindex` node WITHOUT `--covenant-index`:
        // `getUtxosByCovenantIds` fails (`covenantIdQuerySucceeded = false`, `covenantIndexUnsupportedOnNode = true`),
        // while `getUtxosByAddresses(lookaheadAddrs)` succeeds returning ONLY Cycle 1's 3.9905 KAS UTXO!
        val liveCycle1Utxo = UtxoItem(
            address = cycle1Bundle.p2shAddress,
            transactionId = "bb".repeat(32),
            index = 1,
            amountSompi = 399_050_000L, // Exact 3.9905 KAS after external 1.00 KAS `.kpass` claim!
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(cycle1Bundle.p2shScriptPubKey),
            scriptPublicKeyVersion = 0,
            blockDaaScore = 250_000_000L,
            covenantId = subCovId,
        )
        val reconciled = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(ownerStaleCycle0),
            liveUtxos = listOf(liveCycle1Utxo),
            currentVirtualDaaScore = 250_000_050L,
            pastMedianTimeMs = cycle0DueMs + 30_000L,
            covenantIdQuerySucceeded = false,
            addressQuerySucceeded = true,
            covenantIndexUnsupportedOnNode = true,
            candidateLookaheadAddrsByKey = mapOf(ownerStaleCycle0.canonicalKey to lookaheadAddrs),
        ).single()

        assertEquals(399_050_000L, reconciled.liveBalanceSompi)
        assertEquals(cycle1Bundle.p2shAddress, reconciled.currentP2shAddress)
        assertEquals(KaspaCrypto.bytesToHex(cycle1Bundle.redeemScript), reconciled.currentRedeemScriptHex)
        assertEquals(cycle1Params.nextDueTimeMs, reconciled.params.nextDueTimeMs)
        assertEquals("bb".repeat(32), reconciled.liveUtxoTxId)
        assertEquals(1, reconciled.liveUtxoIndex)

        // Owner can immediately execute `Cancel & Reclaim Pool` (`cancelReclaim`) passing KaspaScriptVm!
        val cancelTx = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
            covenant = reconciled,
            covenantUtxo = liveCycle1Utxo,
            entryName = "cancelReclaim",
            outputs = listOf(KaspaMassAndTx.TxOutput(liveCycle1Utxo.amountSompi - 2_000_000L, 0, ownerSpk, ownerAddr)),
            signerPrivateKeys = listOf(ownerPriv),
        )
        assertTrue("Cancel & Reclaim Pool after Cycle-1 lookahead sync must pass KaspaScriptVm", cancelTx.vmResult!!.success)
    }

    @Test
    fun stage37NoCovenantIndexFullExternalDrainSettlesSubscriptionAndDeadMansSwitch() {
        val pub = KaspaCrypto.xOnlyPublicKey(ByteArray(32) { (it + 61).toByte() })
        val ownerAddr = KaspaAddress.encodePubKey(pub, KaspaNetwork.MAINNET)
        val otherPub = KaspaCrypto.xOnlyPublicKey(ByteArray(32) { (it + 71).toByte() })
        val otherAddr = KaspaAddress.encodePubKey(otherPub, KaspaNetwork.MAINNET)
        val nowMs = 1_796_600_000_000L

        val subParams = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = KaspaCrypto.bytesToHex(pub),
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            recipientAddress = otherAddr,
            allowanceSompi = 5L * SOMPI_PER_KAS,
            nextDueTimeMs = nowMs - 60_000L,
            intervalMs = 86_400_000L,
        )
        val subBundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE, subParams, KaspaNetwork.MAINNET,
        )
        val subCov = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "kip20:mainnet:${"77".repeat(32)}",
            walletId = "w1",
            network = KaspaNetwork.MAINNET,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            label = "Subscription",
            covenantIdHex = "77".repeat(32),
            currentP2shAddress = subBundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(subBundle.redeemScript),
            redeemScriptHashHex = subBundle.redeemScriptHashHex,
            silSource = subBundle.silSource,
            abiArtifact = subBundle.abiArtifact,
            params = subParams,
            liveBalanceSompi = 5L * SOMPI_PER_KAS,
            liveUtxoTxId = "cc".repeat(32),
            liveUtxoIndex = 0,
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.READY,
        )

        val dmsParams = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = KaspaCrypto.bytesToHex(pub),
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            heirAddress = otherAddr,
            inactivityDeadlineMs = nowMs - 60_000L,
            inactivityDurationMs = 86_400_000L,
        )
        val dmsBundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH, dmsParams, KaspaNetwork.MAINNET,
        )
        val dmsCov = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "kip20:mainnet:${"88".repeat(32)}",
            walletId = "w1",
            network = KaspaNetwork.MAINNET,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH,
            label = "Dead Man's Switch",
            covenantIdHex = "88".repeat(32),
            currentP2shAddress = dmsBundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(dmsBundle.redeemScript),
            redeemScriptHashHex = dmsBundle.redeemScriptHashHex,
            silSource = dmsBundle.silSource,
            abiArtifact = dmsBundle.abiArtifact,
            params = dmsParams,
            liveBalanceSompi = 10L * SOMPI_PER_KAS,
            liveUtxoTxId = "dd".repeat(32),
            liveUtxoIndex = 0,
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.READY,
        )

        val lookahead = mapOf(
            subCov.canonicalKey to com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildMultiStateLookaheadAddresses(subCov),
            dmsCov.canonicalKey to com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildMultiStateLookaheadAddresses(dmsCov),
        )
        // `getUtxosByCovenantIds` unavailable, `getUtxosByAddresses` across ALL candidate states returns 0 UTXOs
        val settled = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(subCov, dmsCov),
            liveUtxos = emptyList(),
            currentVirtualDaaScore = 250_000_050L,
            verifiedAtMs = nowMs,
            covenantIdQuerySucceeded = false,
            addressQuerySucceeded = true,
            covenantIndexUnsupportedOnNode = true,
            candidateLookaheadAddrsByKey = lookahead,
        )
        for (c in settled) {
            assertEquals("${c.label} must flip immediately to SETTLED", com.kaspakinetic.wallet.core.covenants.CovenantStatus.SETTLED, c.liveStatus)
            assertEquals(0L, c.liveBalanceSompi)
            assertNull(c.liveUtxoTxId)
        }
    }

    @Test
    fun stage37DailySpendWindowAvailabilityResetCountdownAndExactCapSpend() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        val seed = Bip39.mnemonicToSeed(mnemonic, "")
        val network = KaspaNetwork.MAINNET
        val ownerPriv = KaspaCrypto.derivePrivateKeyFromSeed(seed, chain = 0, index = 0)
        val ownerPub = KaspaCrypto.xOnlyPublicKey(ownerPriv)
        val ownerAddr = KaspaAddress.encodePubKey(ownerPub, network)
        val destAddr = KaspaAddress.encodePubKey(
            KaspaCrypto.xOnlyPublicKey(KaspaCrypto.derivePrivateKeyFromSeed(seed, chain = 0, index = 1)),
            network,
        )
        val destSpk = KaspaAddress.decode(destAddr)!!.toScriptPublicKey()

        val cap = 1L * SOMPI_PER_KAS // Exact 1.00 KAS daily limit
        val windowDaa = 1_800L // 3 minutes at 10 BPS
        val utxoDaa = 100_000_000L

        val stage0Params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = KaspaCrypto.bytesToHex(ownerPub),
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            dailyCapSompi = cap,
            windowDaa = windowDaa,
            spentInWindowSompi = 0L,
            fullWithdrawUnlockTimeMs = 1_797_000_000_000L,
        )
        // 1. Stage 0: full 1.00 KAS available immediately, not in cooldown
        val w0 = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.evaluateDailySpendWindow(
            params = stage0Params,
            liveBalanceSompi = 5L * SOMPI_PER_KAS,
            utxoBlockDaaScore = utxoDaa,
            virtualDaaScore = utxoDaa + 50L,
        )
        assertEquals(cap, w0.availableNowSompi)
        assertFalse(w0.isInCooldown)
        assertEquals(0L, w0.resetRemainingMs)

        // 2. Spend EXACT 1.00 KAS on a 1.00 KAS cap (must succeed cleanly with zero false cap rejections!)
        val stage0Bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DAILY_SPEND_LIMIT, stage0Params, network,
        )
        val stage0Cov = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "kip20:mainnet:${"55".repeat(32)}",
            walletId = "w1",
            network = network,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DAILY_SPEND_LIMIT,
            label = "1 KAS Daily Limit",
            covenantIdHex = "55".repeat(32),
            currentP2shAddress = stage0Bundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(stage0Bundle.redeemScript),
            redeemScriptHashHex = stage0Bundle.redeemScriptHashHex,
            silSource = stage0Bundle.silSource,
            abiArtifact = stage0Bundle.abiArtifact,
            params = stage0Params,
        )
        val utxo0 = UtxoItem(
            address = stage0Bundle.p2shAddress,
            transactionId = "12".repeat(32),
            index = 0,
            amountSompi = 5L * SOMPI_PER_KAS,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(stage0Bundle.p2shScriptPubKey),
            scriptPublicKeyVersion = 0,
            blockDaaScore = utxoDaa,
            covenantId = "55".repeat(32),
        )
        val plan = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.computeSpendAndFeePlan(
            covenant = stage0Cov,
            covenantUtxo = utxo0,
            entryName = "spendWithinLimit",
            requestedSpendSompi = cap,
            maxAllowedSpendSompi = cap,
            allowCovenantChangeOutput = true,
            payloadSizeBytes = 640,
        )
        assertEquals("Exact 1.00 KAS spend must not be trimmed", cap, plan.spendSompi)
        assertFalse(plan.isFullSweep)
        assertTrue(plan.changeSompi > 3L * SOMPI_PER_KAS)

        val stage1Params = stage0Params.copy(spentInWindowSompi = cap)
        val stage1Bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DAILY_SPEND_LIMIT, stage1Params, network,
        )
        val spendTx = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
            covenant = stage0Cov,
            covenantUtxo = utxo0,
            entryName = "spendWithinLimit",
            outputs = listOf(
                KaspaMassAndTx.TxOutput(plan.spendSompi, 0, destSpk, destAddr),
                KaspaMassAndTx.TxOutput(
                    plan.changeSompi,
                    0,
                    stage1Bundle.p2shScriptPubKey,
                    stage1Bundle.p2shAddress,
                    covenant = KaspaMassAndTx.CovenantBinding(0, "55".repeat(32)),
                ),
            ),
            signerPrivateKeys = listOf(ownerPriv),
        )
        assertTrue("Exact 1.00 KAS spend on 1.00 KAS cap must pass KaspaScriptVm", spendTx.vmResult!!.success)

        // 3. Stage 1 during cooldown: availableNow == 0, isInCooldown == true, live countdown > 0
        val wCooldown = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.evaluateDailySpendWindow(
            params = stage1Params,
            liveBalanceSompi = plan.changeSompi,
            utxoBlockDaaScore = utxoDaa + 100L,
            virtualDaaScore = utxoDaa + 560L, // 460 DAA elapsed -> 1,340 DAA remaining = 134s!
        )
        assertEquals(0L, wCooldown.availableNowSompi)
        assertTrue(wCooldown.isInCooldown)
        assertFalse(wCooldown.isWindowResetReady)
        assertEquals(1_340L, wCooldown.resetRemainingDaa)
        assertEquals(134_000L, wCooldown.resetRemainingMs)

        // 4. Once window elapses (ageDaa >= windowDaa): full 1.00 KAS available again, cooldown cleared!
        val wReset = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.evaluateDailySpendWindow(
            params = stage1Params,
            liveBalanceSompi = plan.changeSompi,
            utxoBlockDaaScore = utxoDaa + 100L,
            virtualDaaScore = utxoDaa + 100L + windowDaa,
        )
        assertEquals(cap, wReset.availableNowSompi)
        assertFalse(wReset.isInCooldown)
        assertTrue(wReset.isWindowResetReady)
        assertEquals(0L, wReset.resetRemainingMs)
    }

    @Test
    fun stage38DeadMansSwitchCsvCheckInPreservesP2shAndPermanentHeirKpass() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        val seed = Bip39.mnemonicToSeed(mnemonic, "")
        val network = KaspaNetwork.TESTNET_10
        val ownerPriv = KaspaCrypto.derivePrivateKeyFromSeed(seed, chain = 0, index = 0)
        val ownerPub = KaspaCrypto.xOnlyPublicKey(ownerPriv)
        val ownerAddr = KaspaAddress.encodePubKey(ownerPub, network)
        val heirPub = KaspaCrypto.xOnlyPublicKey(KaspaCrypto.derivePrivateKeyFromSeed(seed, chain = 0, index = 2))
        val heirAddr = KaspaAddress.encodePubKey(heirPub, network)
        val changeAddr = KaspaAddress.encodePubKey(
            KaspaCrypto.xOnlyPublicKey(KaspaCrypto.derivePrivateKeyFromSeed(seed, chain = 1, index = 0)),
            network,
        )

        val inactivityDaa = 1_200L // 2 minutes at 10 BPS
        val deployBlockDaa = 100_000L
        val covId = "ab".repeat(32)
        val vaultBalance = 5L * SOMPI_PER_KAS
        val params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = KaspaCrypto.bytesToHex(ownerPub),
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            heirAddress = heirAddr,
            inactivityDaa = inactivityDaa,
            inactivityDeadlineMs = 0L,
            inactivityDurationMs = 120_000L,
            keylessCsvHeir = true,
        )
        assertTrue(com.kaspakinetic.wallet.core.covenants.CovenantCompiler.isKeylessCsvDeadMansSwitch(params))
        assertFalse(com.kaspakinetic.wallet.core.covenants.CovenantCompiler.isKeylessTimeBasedDeadMansSwitch(params))

        val bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH,
            params,
            network,
        )
        val ownerCov = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "kip20:${network.id}:$covId",
            walletId = "owner_wallet",
            network = network,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH,
            label = "Family Inheritance",
            covenantIdHex = covId,
            currentP2shAddress = bundle.p2shAddress,
            currentRedeemScriptHex = KaspaCrypto.bytesToHex(bundle.redeemScript),
            redeemScriptHashHex = bundle.redeemScriptHashHex,
            silSource = bundle.silSource,
            abiArtifact = bundle.abiArtifact,
            params = params,
            liveBalanceSompi = vaultBalance,
            liveUtxoTxId = "11".repeat(32),
            liveUtxoIndex = 0,
            liveUtxoBlockDaaScore = deployBlockDaa,
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.ACTIVE,
        )

        // 1. Export heir `.kpass` ONCE and import into heir's Shared tab
        val exportedPass = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.SharedCovenantPass(
            kind = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.SharedPassKind.HEIR_CLAIM_PASS,
            network = network,
            templateId = ownerCov.templateId,
            label = ownerCov.label,
            covenantIdHex = covId,
            p2shAddress = ownerCov.currentP2shAddress,
            redeemScriptHex = ownerCov.currentRedeemScriptHex,
            abiArtifact = ownerCov.abiArtifact,
            params = params,
        )
        val compactQr = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.exportCompactSharedPassQrJson(exportedPass)
        val parsedPass = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.parseSharedPassJson(compactQr)
        val heirSharedInitial = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.toTrackedCovenant(parsedPass, "heir_wallet")
        assertEquals(bundle.p2shAddress, heirSharedInitial.currentP2shAddress)

        // 2. Advance past inactivityDaa (> 1,200 blocks) -> heir Shared pass flips to READY
        val initialUtxo = UtxoItem(
            address = bundle.p2shAddress,
            transactionId = "11".repeat(32),
            index = 0,
            amountSompi = vaultBalance,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(bundle.p2shScriptPubKey),
            blockDaaScore = deployBlockDaa,
            covenantId = covId,
        )
        val heirReadyBeforeCheckIn = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(heirSharedInitial),
            liveUtxos = listOf(initialUtxo),
            currentVirtualDaaScore = deployBlockDaa + inactivityDaa + 50L,
        ).single()
        assertEquals(com.kaspakinetic.wallet.core.covenants.CovenantStatus.READY, heirReadyBeforeCheckIn.liveStatus)

        // 3. Owner taps Check-In EVEN AFTER timer hit 0, paying fee from owner's normal funding UTXO so 5.00 KAS stays intact!
        val fundingUtxo = UtxoItem(
            address = ownerAddr,
            transactionId = "22".repeat(32),
            index = 0,
            amountSompi = 2L * SOMPI_PER_KAS,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(KaspaAddress.decode(ownerAddr)!!.toScriptPublicKey()),
            blockDaaScore = deployBlockDaa,
        )
        val checkInFee = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.estimateSpendFeeSompi(
            covenant = ownerCov,
            covenantUtxo = initialUtxo,
            entryName = "checkIn",
            outputAmountsSompi = listOf(vaultBalance, fundingUtxo.amountSompi - 350_000L),
            hasCovenantOutput = true,
            extraFundingUtxos = listOf(fundingUtxo),
        )
        val checkInTx = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
            covenant = ownerCov,
            covenantUtxo = initialUtxo,
            entryName = "checkIn",
            outputs = listOf(
                KaspaMassAndTx.TxOutput(
                    valueSompi = vaultBalance, // Full 5.00 KAS preserved intact!
                    scriptPublicKeyVersion = 0,
                    scriptPublicKey = bundle.p2shScriptPubKey,
                    address = bundle.p2shAddress,
                    covenant = KaspaMassAndTx.CovenantBinding(0, covId),
                ),
                KaspaMassAndTx.TxOutput(
                    valueSompi = fundingUtxo.amountSompi - checkInFee,
                    scriptPublicKeyVersion = 0,
                    scriptPublicKey = KaspaAddress.decode(changeAddr)!!.toScriptPublicKey(),
                    address = changeAddr,
                ),
            ),
            virtualDaaScore = deployBlockDaa + inactivityDaa + 50L,
            signerPrivateKeys = listOf(ownerPriv.copyOf()),
            extraFundingUtxos = listOf(fundingUtxo),
            extraKeyLookup = { addr -> if (addr == ownerAddr) ownerPriv.copyOf() else null },
        )
        assertTrue("Owner checkIn must pass local Kaspa VM even after timer hit 0", checkInTx.vmResult?.success == true)
        assertEquals("Vault balance must remain full 5.00 KAS intact", vaultBalance, checkInTx.rawTx.outputs[0].valueSompi)
        assertTrue(
            "Check-In must preserve byte-for-byte identical P2SH scriptPubKey",
            checkInTx.rawTx.outputs[0].scriptPublicKey.contentEquals(bundle.p2shScriptPubKey),
        )
        assertEquals("Check-In must preserve exact P2SH address", bundle.p2shAddress, checkInTx.rawTx.outputs[0].address)
        assertEquals("Check-In must preserve exact KIP-20 covenant_id", covId, checkInTx.rawTx.outputs[0].covenant?.covenantIdHex)

        // 4. Heir's ORIGINAL `.kpass` (NEVER re-shared!) syncs new UTXO at newBlockDaa -> flips READY -> ACTIVE & locks!
        val postCheckInBlockDaa = deployBlockDaa + inactivityDaa + 100L
        val postCheckInUtxo = UtxoItem(
            address = bundle.p2shAddress,
            transactionId = checkInTx.txId,
            index = 0,
            amountSompi = vaultBalance,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(bundle.p2shScriptPubKey),
            blockDaaScore = postCheckInBlockDaa,
            covenantId = covId,
        )
        val heirAfterCheckIn = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(heirReadyBeforeCheckIn),
            liveUtxos = listOf(postCheckInUtxo),
            currentVirtualDaaScore = postCheckInBlockDaa + 50L,
            covenantIdQuerySucceeded = false,
            addressQuerySucceeded = true,
            covenantIndexUnsupportedOnNode = true,
        ).single()
        assertEquals("Heir screen must flip from READY back to ACTIVE (never SETTLED)", com.kaspakinetic.wallet.core.covenants.CovenantStatus.ACTIVE, heirAfterCheckIn.liveStatus)
        assertEquals(postCheckInBlockDaa, heirAfterCheckIn.liveUtxoBlockDaaScore)
        assertEquals(vaultBalance, heirAfterCheckIn.liveBalanceSompi)
        assertTrue(
            "Original .kpass redeemScript must still match post-check-in UTXO BLAKE2b hash",
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.matchesLiveUtxoScriptHash(
                heirAfterCheckIn.currentRedeemScriptHex, network, postCheckInUtxo,
            ),
        )
        assertNull(
            "Keyless heirClaim must be blocked before newBlockDaa + inactivityDaa",
            com.kaspakinetic.wallet.core.covenants.CovenantKeylessExecutor.buildDueKeylessTx(
                covenant = heirAfterCheckIn,
                covenantUtxo = postCheckInUtxo,
                pastMedianTimeMs = 0L,
                virtualDaaScore = postCheckInBlockDaa + inactivityDaa - 1L,
            ),
        )
        // Confirm VM itself rejects early CSV heirClaim too!
        var earlyVmRejected = false
        try {
            com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildSignAndVerifySpendTxV1(
                covenant = heirAfterCheckIn,
                covenantUtxo = postCheckInUtxo,
                entryName = "heirClaim",
                outputs = listOf(
                    KaspaMassAndTx.TxOutput(
                        valueSompi = vaultBalance - 300_000L,
                        scriptPublicKeyVersion = 0,
                        scriptPublicKey = KaspaAddress.decode(heirAddr)!!.toScriptPublicKey(),
                        address = heirAddr,
                    ),
                ),
                sequence = inactivityDaa,
                virtualDaaScore = postCheckInBlockDaa + inactivityDaa - 1L,
                signerPrivateKeys = emptyList(),
            )
        } catch (_: IllegalStateException) {
            earlyVmRejected = true
        }
        assertTrue("Local VM must reject CSV heirClaim before inactivityDaa elapses", earlyVmRejected)

        // 5. Once server virtualDaaScore reaches newBlockDaa + inactivityDaa -> ORIGINAL .kpass claims with 0.00 KAS!
        val maturedDaa = postCheckInBlockDaa + inactivityDaa
        val heirMatured = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(heirAfterCheckIn),
            liveUtxos = listOf(postCheckInUtxo),
            currentVirtualDaaScore = maturedDaa,
        ).single()
        assertEquals(com.kaspakinetic.wallet.core.covenants.CovenantStatus.READY, heirMatured.liveStatus)

        val keylessClaim = com.kaspakinetic.wallet.core.covenants.CovenantKeylessExecutor.buildDueKeylessTx(
            covenant = heirMatured,
            covenantUtxo = postCheckInUtxo,
            pastMedianTimeMs = 0L,
            virtualDaaScore = maturedDaa,
        )
        assertNotNull("Original .kpass must build valid 0-KAS keyless CSV heirClaim once matured", keylessClaim)
        assertTrue("Keyless CSV heirClaim must pass local KaspaScriptVm", keylessClaim!!.first.vmResult?.success == true)
        assertEquals(heirAddr, keylessClaim.first.rawTx.outputs[0].address)
        assertEquals(inactivityDaa, keylessClaim.first.rawTx.inputs[0].sequence)
        assertTrue("Keyless fee must stay within anti-griefing bound", keylessClaim.first.feeSompi <= com.kaspakinetic.wallet.core.covenants.CovenantCompiler.MAX_KEYLESS_FEE_SOMPI)
    }

    @Test
    fun stage38StrictServerVirtualDaaReadyGateBlocksExtrapolatedReady() {
        val utxoBlockDaa = 100_000L
        val requiredDaa = 1_200L // 120s at 10 BPS -> ready only when server virtualDaaScore >= 101_200
        val ownerPub = "11".repeat(32)
        val ownerAddr = KaspaAddress.encodePubKey(KaspaCrypto.hexToBytes(ownerPub), KaspaNetwork.TESTNET_10)

        val dmsCov = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "kip20:testnet-10:${"a1".repeat(32)}",
            walletId = "w1",
            network = KaspaNetwork.TESTNET_10,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH,
            label = "DMS",
            currentP2shAddress = ownerAddr,
            currentRedeemScriptHex = "51",
            redeemScriptHashHex = "00".repeat(32),
            silSource = "",
            abiArtifact = com.kaspakinetic.wallet.core.covenants.SilAbiArtifact(contracts = emptyMap()),
            params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
                ownerPubKeyHex = ownerPub,
                ownerAddress = ownerAddr,
                heirAddress = ownerAddr,
                inactivityDaa = requiredDaa,
                inactivityDeadlineMs = 0L,
                keylessCsvHeir = true,
            ),
            liveBalanceSompi = 5L * SOMPI_PER_KAS,
            liveUtxoBlockDaaScore = utxoBlockDaa,
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.ACTIVE,
        )
        val clawCov = dmsCov.copy(
            canonicalKey = "kip20:testnet-10:${"b2".repeat(32)}",
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.CLAWBACK_VAULT,
            params = dmsCov.params.copy(vaultStage = 1, unvaultDelayDaa = requiredDaa),
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.UNVAULTING,
        )
        val dailyCov = dmsCov.copy(
            canonicalKey = "kip20:testnet-10:${"c3".repeat(32)}",
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DAILY_SPEND_LIMIT,
            params = dmsCov.params.copy(dailyCapSompi = 1L * SOMPI_PER_KAS, spentInWindowSompi = 1L * SOMPI_PER_KAS, windowDaa = requiredDaa),
        )

        // Server reported virtualDaaScore is 50 blocks (5s) BEFORE target at elapsed=100_000L
        val anchor = com.kaspakinetic.wallet.core.covenants.ChainTimeAnchor().mergeUpdate(
            newPastMedianTimeMs = 1_798_000_000_000L,
            newVirtualDaaScore = utxoBlockDaa + requiredDaa - 50L, // 101_150
            nowElapsedRealtimeMs = 100_000L,
        )

        // 15 seconds pass locally -> extrapolated DAA is 101_300 (10s PAST 101_200!), while server is STILL 101_150!
        val elapsedPlus15s = 115_000L
        assertTrue(anchor.estimateVirtualDaaScore(elapsedPlus15s) > utxoBlockDaa + requiredDaa)

        val eval = anchor.evaluateRelativeDaaLock(utxoBlockDaa, requiredDaa, elapsedPlus15s)
        assertFalse("Must NOT be Ready from local DAA extrapolation alone", eval.serverConfirmedReady)
        assertEquals(com.kaspakinetic.wallet.core.covenants.TimeLockStage.FINALIZING, eval.stage)
        assertTrue(anchor.isCovenantFinalizing(dmsCov, elapsedPlus15s))
        assertTrue(anchor.isCovenantFinalizing(clawCov, elapsedPlus15s))
        assertTrue(anchor.isCovenantFinalizing(dailyCov, elapsedPlus15s))

        val dailyStatus = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.evaluateDailySpendWindow(
            params = dailyCov.params,
            liveBalanceSompi = dailyCov.liveBalanceSompi,
            utxoBlockDaaScore = utxoBlockDaa,
            virtualDaaScore = anchor.virtualDaaScore,
            extrapolatedVirtualDaaScore = anchor.estimateVirtualDaaScore(elapsedPlus15s),
        )
        assertFalse("Daily Spend reset must stay blocked until server virtualDaaScore confirms", dailyStatus.isWindowResetReady)
        assertTrue("Daily Spend must enter finalizing state when local countdown <= 10s", dailyStatus.isFinalizing)

        // Server reports 101_199 (1 block short!) -> real virtualDaaScore must NOT be inflated by local 101_300!
        val anchorStillShort = anchor.mergeUpdate(
            newPastMedianTimeMs = 1_798_000_014_000L,
            newVirtualDaaScore = utxoBlockDaa + requiredDaa - 1L,
            nowElapsedRealtimeMs = elapsedPlus15s,
        )
        assertEquals(utxoBlockDaa + requiredDaa - 1L, anchorStillShort.virtualDaaScore)
        assertEquals(
            com.kaspakinetic.wallet.core.covenants.TimeLockStage.FINALIZING,
            anchorStillShort.evaluateRelativeDaaLock(utxoBlockDaa, requiredDaa, elapsedPlus15s).stage,
        )

        // Once real server reports virtualDaaScore == 101_200 -> flips cleanly to READY!
        val anchorConfirmed = anchorStillShort.mergeUpdate(
            newPastMedianTimeMs = 1_798_000_016_000L,
            newVirtualDaaScore = utxoBlockDaa + requiredDaa,
            nowElapsedRealtimeMs = elapsedPlus15s + 2_000L,
        )
        val evalConfirmed = anchorConfirmed.evaluateRelativeDaaLock(utxoBlockDaa, requiredDaa, elapsedPlus15s + 2_000L)
        assertTrue(evalConfirmed.serverConfirmedReady)
        assertEquals(com.kaspakinetic.wallet.core.covenants.TimeLockStage.READY, evalConfirmed.stage)
    }

    @Test
    fun stage38DeterministicKpassQrAndHighCycleSubscriptionCatchUp() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        val seed = Bip39.mnemonicToSeed(mnemonic, "")
        val network = KaspaNetwork.MAINNET
        val ownerPub = KaspaCrypto.xOnlyPublicKey(KaspaCrypto.derivePrivateKeyFromSeed(seed, chain = 0, index = 0))
        val ownerAddr = KaspaAddress.encodePubKey(ownerPub, network)
        val recipientAddr = KaspaAddress.encodePubKey(
            KaspaCrypto.xOnlyPublicKey(KaspaCrypto.derivePrivateKeyFromSeed(seed, chain = 0, index = 3)),
            network,
        )

        val baseDueMs = 1_798_000_000_000L
        val intervalMs = 120_000L // 2 minutes per cycle
        val covId = "ef".repeat(32)
        val cycle0Params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
            ownerPubKeyHex = KaspaCrypto.bytesToHex(ownerPub),
            ownerAddress = ownerAddr,
            vaultSaltHex = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.generateVaultSaltHex(),
            recipientAddress = recipientAddr,
            allowanceSompi = 1L * SOMPI_PER_KAS,
            nextDueTimeMs = baseDueMs,
            intervalMs = intervalMs,
        )
        val cycle0Bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            cycle0Params,
            network,
        )

        // 1. Deterministic .kpass QR & JSON: two exports separated in wall-clock time must be byte-for-byte identical!
        val passA = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.SharedCovenantPass(
            kind = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.SharedPassKind.ALLOWANCE_CLAIM_PASS,
            network = network,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            label = "Allowance",
            covenantIdHex = covId,
            p2shAddress = cycle0Bundle.p2shAddress,
            redeemScriptHex = KaspaCrypto.bytesToHex(cycle0Bundle.redeemScript),
            abiArtifact = cycle0Bundle.abiArtifact,
            params = cycle0Params,
        )
        Thread.sleep(15L)
        val passB = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.SharedCovenantPass(
            kind = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.SharedPassKind.ALLOWANCE_CLAIM_PASS,
            network = network,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            label = "Allowance",
            covenantIdHex = covId,
            p2shAddress = cycle0Bundle.p2shAddress,
            redeemScriptHex = KaspaCrypto.bytesToHex(cycle0Bundle.redeemScript),
            abiArtifact = cycle0Bundle.abiArtifact,
            params = cycle0Params,
        )
        assertEquals(
            "Compact .kpass QR payload must be 100% deterministic across exports",
            com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.exportCompactSharedPassQrJson(passA),
            com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.exportCompactSharedPassQrJson(passB),
        )
        assertEquals(
            "Full .kpass JSON payload must be 100% deterministic across exports",
            com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.exportSharedPassJson(passA),
            com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.exportSharedPassJson(passB),
        )

        // 2. High-cycle Subscription catch-up (Cycle 0 .kpass -> live UTXO at Cycle 30 on `--utxoindex`-only node!)
        val sharedCycle0 = com.kaspakinetic.wallet.core.covenants.KineticBackupEngine.toTrackedCovenant(passA, "recipient_wallet")
        assertTrue(sharedCycle0.isSharedPassImport)

        val cycle30DueMs = baseDueMs + 30L * intervalMs
        val cycle30Params = cycle0Params.copy(nextDueTimeMs = cycle30DueMs)
        val cycle30Bundle = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.compile(
            com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.RECURRING_ALLOWANCE,
            cycle30Params,
            network,
        )
        val cycle30Utxo = UtxoItem(
            address = cycle30Bundle.p2shAddress,
            transactionId = "30".repeat(32),
            index = 1,
            amountSompi = 20L * SOMPI_PER_KAS,
            scriptPublicKeyHex = KaspaCrypto.bytesToHex(cycle30Bundle.p2shScriptPubKey),
            blockDaaScore = 300_000L,
            covenantId = covId,
        )

        // Batched lookahead (0..24 + 25..49) discovers Cycle 30 without `--covenant-index`!
        val batch1 = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildMultiStateLookaheadAddresses(
            sharedCycle0, subscriptionStartCycleOffset = 0, subscriptionCycleCount = 25,
        )
        val batch2 = com.kaspakinetic.wallet.core.covenants.CovenantCompiler.buildMultiStateLookaheadAddresses(
            sharedCycle0, subscriptionStartCycleOffset = 25, subscriptionCycleCount = 25,
        )
        assertTrue("Batch 2 (25..49) must include Cycle 30 P2SH address", cycle30Bundle.p2shAddress in batch2)

        val livePmtMs = cycle30DueMs + 5_000L
        val reconciled = com.kaspakinetic.wallet.core.covenants.CovenantMergeEngine.reconcileWithChainUtxos(
            covenants = listOf(sharedCycle0),
            liveUtxos = listOf(cycle30Utxo),
            currentVirtualDaaScore = 300_100L,
            pastMedianTimeMs = livePmtMs,
            covenantIdQuerySucceeded = false,
            addressQuerySucceeded = true,
            covenantIndexUnsupportedOnNode = true,
            candidateLookaheadAddrsByKey = mapOf(sharedCycle0.canonicalKey to (batch1 + batch2)),
        ).single()

        assertEquals("Shared .kpass must catch up to exact Cycle 30 timestamp", cycle30DueMs, reconciled.params.nextDueTimeMs)
        assertEquals(cycle30Bundle.p2shAddress, reconciled.currentP2shAddress)
        assertEquals(com.kaspakinetic.wallet.core.covenants.CovenantStatus.READY, reconciled.liveStatus)

        // Keyless withdraw from Cycle 30 -> Cycle 31 passes local KaspaScriptVm with 0 private keys!
        val claimRes = com.kaspakinetic.wallet.core.covenants.CovenantKeylessExecutor.buildDueKeylessTx(
            covenant = reconciled,
            covenantUtxo = cycle30Utxo,
            pastMedianTimeMs = livePmtMs,
        )
        assertNotNull(claimRes)
        assertTrue("Cycle-30 keyless withdraw must pass KaspaScriptVm", claimRes!!.first.vmResult?.success == true)
        assertEquals(cycle30DueMs + intervalMs, claimRes.second.params.nextDueTimeMs)
    }

    @Test
    fun stage39UiSentinelSyncingGuardAndAll7RealWorldScenariosAcrossAll11Locales() {
        val en = com.kaspakinetic.wallet.ui.locale.KaspaWalletStrings()

        // 1. Sentinel Long.MAX_VALUE / >100y countdowns NEVER format as "106751991167d 7h"
        assertEquals(
            en.covSyncingStatus,
            com.kaspakinetic.wallet.ui.covenants.formatDurationMsShort(Long.MAX_VALUE, en.covStatusReady, en.covSyncingStatus),
        )
        assertEquals(
            en.covSyncingStatus,
            com.kaspakinetic.wallet.ui.covenants.formatDurationMsShort(Long.MAX_VALUE / 2L, en.covStatusReady, en.covSyncingStatus),
        )
        assertEquals(
            en.covSyncingStatus,
            com.kaspakinetic.wallet.ui.covenants.formatDaaDurationShort(Long.MAX_VALUE, en.covStatusReady, en.covSyncingStatus),
        )

        // Freshly imported Shared .kpass Dead Man's Switch before initial gRPC sync (liveUtxoTxId == null, blockDaa == 0)
        val ownerPub = "11".repeat(32)
        val ownerAddr = KaspaAddress.encodePubKey(KaspaCrypto.hexToBytes(ownerPub), KaspaNetwork.TESTNET_10)
        val freshSharedDms = com.kaspakinetic.wallet.core.covenants.TrackedCovenant(
            canonicalKey = "kip20:testnet-10:${"aa".repeat(32)}",
            walletId = "heir_wallet",
            network = KaspaNetwork.TESTNET_10,
            templateId = com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.DEAD_MANS_SWITCH,
            label = "Family Will",
            covenantIdHex = "aa".repeat(32),
            currentP2shAddress = ownerAddr,
            currentRedeemScriptHex = "51",
            redeemScriptHashHex = "00".repeat(32),
            silSource = "",
            abiArtifact = com.kaspakinetic.wallet.core.covenants.SilAbiArtifact(contracts = emptyMap()),
            params = com.kaspakinetic.wallet.core.covenants.CovenantParams(
                ownerPubKeyHex = ownerPub,
                ownerAddress = ownerAddr,
                heirAddress = ownerAddr,
                inactivityDaa = 1_200L,
                inactivityDeadlineMs = 0L,
                keylessCsvHeir = true,
            ),
            liveBalanceSompi = 0L,
            liveUtxoTxId = null,
            liveUtxoBlockDaaScore = 0L,
            liveStatus = com.kaspakinetic.wallet.core.covenants.CovenantStatus.UNCONFIRMED,
            isSharedPassImport = true,
        )
        val liveAnchor = com.kaspakinetic.wallet.core.covenants.ChainTimeAnchor().mergeUpdate(
            newPastMedianTimeMs = 1_798_000_000_000L,
            newVirtualDaaScore = 500_000L,
            nowElapsedRealtimeMs = 10_000L,
        )
        val quickStatus = com.kaspakinetic.wallet.ui.covenants.formatCovenantQuickStatus(
            cov = freshSharedDms,
            virtualDaa = 500_000L,
            hideBalances = false,
            masked = en.maskedAmount,
            unit = en.tkasUnit,
            s = en,
            chainTimeAnchor = liveAnchor,
            nowElapsedRealtimeMs = 10_000L,
        )
        assertEquals("Fresh uninitialized Shared DMS must display Syncing...", en.covSyncingStatus, quickStatus)
        assertFalse("Must never flash sentinel day count", quickStatus.contains("106751991167"))

        // 2. All 7 covenants expose non-blank localized Real-World Scenario copy across all 11 locales
        val allLocales = listOf(
            java.util.Locale.ENGLISH,
            java.util.Locale.SIMPLIFIED_CHINESE,
            java.util.Locale.TRADITIONAL_CHINESE,
            java.util.Locale.JAPANESE,
            java.util.Locale.KOREAN,
            java.util.Locale("ar"),
            java.util.Locale("es"),
            java.util.Locale.FRENCH,
            java.util.Locale.GERMAN,
            java.util.Locale("pt"),
            java.util.Locale("ru"),
        )
        for (loc in allLocales) {
            val strings = com.kaspakinetic.wallet.ui.locale.resolveKaspaWalletStrings(loc)
            assertTrue(strings.covRealWorldScenarioLabel.isNotBlank())
            assertTrue(strings.covSyncingStatus.isNotBlank())
            assertTrue(strings.settingsAboutApp.isNotBlank())
            assertTrue(strings.settingsAboutLicenseValue.contains("GPLv3"))
            for (tpl in com.kaspakinetic.wallet.core.covenants.CovenantTemplateId.entries) {
                val guide = com.kaspakinetic.wallet.ui.covenants.covenantIntroGuideFor(tpl, strings)
                assertTrue(
                    "Locale ${loc.toLanguageTag()} template ${tpl.id} must have non-blank Real-World Scenario",
                    guide.realWorldScenario.isNotBlank(),
                )
            }
        }
    }

    // =========================================================================
    // v1.0.0-rc1 Surgical Hotfix — Multi-Wallet Switch Isolation, Anti-Ghost
    //                              Filtering, & Post-Switch Transaction Sync
    // =========================================================================

    @Test
    fun rc1HotfixCrossWalletSwitchRaceDropsStaleFetchAndEliminatesZeroKasGhosts() {
        val addrA1 = KaspaAddress.encodePubKey(ByteArray(32) { 0xA1.toByte() }, KaspaNetwork.MAINNET)
        val addrA2 = KaspaAddress.encodePubKey(ByteArray(32) { 0xA2.toByte() }, KaspaNetwork.MAINNET)
        val addrB1 = KaspaAddress.encodePubKey(ByteArray(32) { 0xB1.toByte() }, KaspaNetwork.MAINNET)
        val walletBAddrs = setOf(addrB1)
        val burnAddr = KaspaAddress.encodePubKey(ByteArray(32) { 0x00.toByte() }, KaspaNetwork.MAINNET)

        // 1. parseWalletTx MUST return null (never `Received 0.00 KAS`) when a foreign Wallet A
        //    REST transaction touches zero inputs and zero outputs of Wallet B!
        val gateway = com.kaspakinetic.wallet.core.network.OkHttpKaspaNetworkGateway()
        val foreignTxJson = kotlinx.serialization.json.Json.parseToJsonElement(
            """
            {
              "transaction_id": "${"a1".repeat(32)}",
              "block_time": 1764000000000,
              "is_accepted": true,
              "accepting_block_blue_score": 90000000,
              "mass": 2036,
              "inputs": [
                {
                  "previous_outpoint_hash": "${"11".repeat(32)}",
                  "previous_outpoint_index": 0,
                  "previous_outpoint_address": "$addrA1",
                  "previous_outpoint_amount": 10000000000
                }
              ],
              "outputs": [
                {
                  "index": 0,
                  "script_public_key_address": "$addrA2",
                  "amount": 9999900000,
                  "script_public_key": "20${"aa".repeat(32)}ac"
                }
              ]
            }
            """.trimIndent(),
        ) as kotlinx.serialization.json.JsonObject

        assertNull(
            "Foreign Wallet A tx parsed against Wallet B addresses must be dropped (not Received 0.00 KAS)",
            gateway.parseWalletTx(foreignTxJson, walletBAddrs, burnAddr),
        )
        assertNull(
            "parseWalletTx with an empty walletAddressSet must always return null",
            gateway.parseWalletTx(foreignTxJson, emptySet(), burnAddr),
        )

        // 2. filterTransactionsForWallet MUST scrub all 27 previously cached Wallet A ghost rows
        //    (plus any 0-sompi ghost placeholder) while preserving Wallet B's real 50 KAS deposit!
        val realTxB = WalletTx(
            transactionId = "b1".repeat(32),
            blockTimeMs = 1_764_000_500_000L,
            isAccepted = true,
            direction = TxDirection.RECEIVED,
            netAmountSompi = 50L * SOMPI_PER_KAS,
            feeSompi = 2_036L,
            counterpartyAddress = addrA1,
            inputs = listOf(com.kaspakinetic.wallet.core.model.TxInputSummary("22".repeat(32), 0, addrA1, 100L * SOMPI_PER_KAS)),
            outputs = listOf(
                com.kaspakinetic.wallet.core.model.TxOutputSummary(0, addrB1, 50L * SOMPI_PER_KAS, ""),
                com.kaspakinetic.wallet.core.model.TxOutputSummary(1, addrA2, 50L * SOMPI_PER_KAS - 2_036L, ""),
            ),
        )
        val poisoned27Ghosts = (1..27).map { idx ->
            WalletTx(
                transactionId = idx.toString(16).padStart(64, '0'),
                blockTimeMs = 1_763_000_000_000L + idx * 1_000L,
                isAccepted = true,
                direction = TxDirection.RECEIVED,
                netAmountSompi = 0L,
                feeSompi = 2_000L,
                counterpartyAddress = addrA1,
                inputs = listOf(com.kaspakinetic.wallet.core.model.TxInputSummary("33".repeat(32), 0, addrA1, 5L * SOMPI_PER_KAS)),
                outputs = listOf(com.kaspakinetic.wallet.core.model.TxOutputSummary(0, addrA2, 5L * SOMPI_PER_KAS - 2_000L, "")),
            )
        }
        val zeroOptimisticGhost = WalletTx(
            transactionId = "cc".repeat(32),
            blockTimeMs = 1_763_999_999_000L,
            isAccepted = false,
            direction = TxDirection.RECEIVED,
            netAmountSompi = 0L,
            feeSompi = 0L,
            counterpartyAddress = addrA1,
        )

        val cleaned = com.kaspakinetic.wallet.viewmodel.WalletViewModel.filterTransactionsForWallet(
            txs = poisoned27Ghosts + zeroOptimisticGhost + realTxB,
            ownedAddresses = walletBAddrs,
        )
        assertEquals("Only Wallet B's real 50 KAS tx must survive ghost scrub", listOf(realTxB), cleaned)
        assertTrue(
            "Empty ownedAddresses must never let foreign txs through",
            com.kaspakinetic.wallet.viewmodel.WalletViewModel.filterTransactionsForWallet(
                txs = listOf(realTxB),
                ownedAddresses = emptySet(),
            ).isEmpty(),
        )

        // 3. Monotonic wallet-switch epoch guard drops late async responses from Wallet A even
        //    across rapid A -> B -> A switches or network toggles.
        val epoch = java.util.concurrent.atomic.AtomicLong(1L)
        val capturedEpochA1 = epoch.get()
        assertTrue(
            com.kaspakinetic.wallet.viewmodel.WalletViewModel.isScopeValid(
                capturedEpoch = capturedEpochA1,
                currentEpoch = epoch.get(),
                capturedWalletId = "wallet-A",
                activeWalletId = "wallet-A",
                capturedNetwork = KaspaNetwork.MAINNET,
                activeNetwork = KaspaNetwork.MAINNET,
            ),
        )
        // Switch Wallet A -> Wallet B
        val epochB = epoch.incrementAndGet()
        assertFalse(
            "Late Wallet A response must be dropped after switching to Wallet B",
            com.kaspakinetic.wallet.viewmodel.WalletViewModel.isScopeValid(
                capturedEpoch = capturedEpochA1,
                currentEpoch = epochB,
                capturedWalletId = "wallet-A",
                activeWalletId = "wallet-B",
                capturedNetwork = KaspaNetwork.MAINNET,
                activeNetwork = KaspaNetwork.MAINNET,
            ),
        )
        // Switch back Wallet B -> Wallet A (new epoch!) — stale first-visit Wallet A response STILL dropped!
        val epochA2 = epoch.incrementAndGet()
        assertFalse(
            "Stale pre-switch Wallet A fetch must remain rejected even after switching back to Wallet A",
            com.kaspakinetic.wallet.viewmodel.WalletViewModel.isScopeValid(
                capturedEpoch = capturedEpochA1,
                currentEpoch = epochA2,
                capturedWalletId = "wallet-A",
                activeWalletId = "wallet-A",
                capturedNetwork = KaspaNetwork.MAINNET,
                activeNetwork = KaspaNetwork.MAINNET,
            ),
        )
    }

    @Test
    fun rc1HotfixPostSwitchTransactionSyncDetectsNewIncoming50KasUtxoAndClearsStaleThrottle() {
        val addrA = KaspaAddress.encodePubKey(ByteArray(32) { 0x1A.toByte() }, KaspaNetwork.MAINNET)
        val addrB = KaspaAddress.encodePubKey(ByteArray(32) { 0x2B.toByte() }, KaspaNetwork.MAINNET)
        val walletBAddrs = setOf(addrB)
        val burnAddr = KaspaAddress.encodePubKey(ByteArray(32) { 0x00.toByte() }, KaspaNetwork.MAINNET)
        val incomingTxId = "50".repeat(32)
        val fiftyKasSompi = 50L * SOMPI_PER_KAS

        // 1. After switching back to Wallet B, live gRPC UTXOs immediately include the 50 KAS UTXO
        //    while Wallet B's cached transaction history does NOT yet have incomingTxId.
        val liveWalletBUtxos = listOf(
            UtxoItem(
                address = addrB,
                transactionId = incomingTxId,
                index = 0,
                amountSompi = fiftyKasSompi,
                scriptPublicKeyHex = "20${"2b".repeat(32)}ac",
                blockDaaScore = 91_234_567L,
            ),
        )
        val unindexedBefore = com.kaspakinetic.wallet.viewmodel.WalletViewModel.findUnindexedUtxoTxIds(
            utxos = liveWalletBUtxos,
            existingTxs = emptyList(),
        )
        assertEquals(listOf(incomingTxId), unindexedBefore)

        // 2. Immediate UTXO reconciliation synthesizes a +50 KAS Received row so Recent Transactions
        //    updates in the exact same sync pass as Total Balance (never frozen until force-quit!).
        val optimistic = com.kaspakinetic.wallet.viewmodel.WalletViewModel.synthesizeMissingUtxoTxs(
            utxos = liveWalletBUtxos,
            existingTxIds = emptySet(),
            nowMs = 1_764_001_000_000L,
        )
        assertEquals(1, optimistic.size)
        assertEquals(incomingTxId, optimistic.single().transactionId)
        assertEquals(TxDirection.RECEIVED, optimistic.single().direction)
        assertEquals(fiftyKasSompi, optimistic.single().netAmountSompi)

        // Even with the optimistic row present, findUnindexedUtxoTxIds STILL requests confirmed
        // details until the full accepted tx lands!
        assertEquals(
            listOf(incomingTxId),
            com.kaspakinetic.wallet.viewmodel.WalletViewModel.findUnindexedUtxoTxIds(
                utxos = liveWalletBUtxos,
                existingTxs = optimistic,
            ),
        )

        // 3. When REST returns the full confirmed 50 KAS tx, parseWalletTx classifies it accurately
        //    and mergeTransactions upgrades the optimistic placeholder cleanly.
        val gateway = com.kaspakinetic.wallet.core.network.OkHttpKaspaNetworkGateway()
        val confirmedJson = kotlinx.serialization.json.Json.parseToJsonElement(
            """
            {
              "transaction_id": "$incomingTxId",
              "block_time": 1764001000500,
              "is_accepted": true,
              "accepting_block_blue_score": 91234568,
              "mass": 2036,
              "inputs": [
                {
                  "previous_outpoint_hash": "${"99".repeat(32)}",
                  "previous_outpoint_index": 0,
                  "previous_outpoint_address": "$addrA",
                  "previous_outpoint_amount": 8000000000
                }
              ],
              "outputs": [
                {
                  "index": 0,
                  "script_public_key_address": "$addrB",
                  "amount": $fiftyKasSompi,
                  "script_public_key": "20${"2b".repeat(32)}ac"
                },
                {
                  "index": 1,
                  "script_public_key_address": "$addrA",
                  "amount": 2999997964,
                  "script_public_key": "20${"1a".repeat(32)}ac"
                }
              ]
            }
            """.trimIndent(),
        ) as kotlinx.serialization.json.JsonObject

        val confirmedTx = gateway.parseWalletTx(confirmedJson, walletBAddrs, burnAddr)
        assertNotNull("Confirmed 50 KAS deposit to Wallet B must parse cleanly", confirmedTx)
        assertEquals(TxDirection.RECEIVED, confirmedTx!!.direction)
        assertEquals(fiftyKasSompi, confirmedTx.netAmountSompi)
        assertTrue(confirmedTx.isAccepted)

        val upgraded = com.kaspakinetic.wallet.viewmodel.WalletViewModel.mergeTransactions(
            existing = optimistic,
            incoming = listOf(confirmedTx),
        )
        assertEquals(1, upgraded.size)
        assertTrue("Optimistic row must upgrade to confirmed tx", upgraded.single().isAccepted)
        assertTrue(
            "Once confirmed full tx lands, findUnindexedUtxoTxIds must be empty",
            com.kaspakinetic.wallet.viewmodel.WalletViewModel.findUnindexedUtxoTxIds(
                utxos = liveWalletBUtxos,
                existingTxs = upgraded,
            ).isEmpty(),
        )

        // 4. Manual refresh / wallet switch MUST clear a tripped REST 429 backoff so transaction
        //    history never stays locked out until force-quit!
        val backoffField = gateway.javaClass.getDeclaredField("restBackoffUntilMs").apply { isAccessible = true }
        (backoffField.get(gateway) as java.util.concurrent.atomic.AtomicLong)
            .set(System.currentTimeMillis() + 60_000L)
        assertTrue("Simulated REST 429 backoff should report rate-limited", gateway.isRestRateLimited())
        gateway.clearRestBackoffOnUserRefresh()
        assertFalse("clearRestBackoffOnUserRefresh must immediately clear stale 429 lockout", gateway.isRestRateLimited())
    }

    private fun writeVarintForTest(out: java.io.ByteArrayOutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
        out.write(v.toInt() and 0x7F)
    }

    private fun encodeVarintFieldForTest(fieldNumber: Int, value: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        writeVarintForTest(out, (fieldNumber.toLong() shl 3) or 0L)
        writeVarintForTest(out, value)
        return out.toByteArray()
    }

    private fun encodeLengthDelimitedFieldForTest(fieldNumber: Int, payload: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        writeVarintForTest(out, (fieldNumber.toLong() shl 3) or 2L)
        writeVarintForTest(out, payload.size.toLong())
        out.write(payload)
        return out.toByteArray()
    }

    private fun encodeStringFieldForTest(fieldNumber: Int, text: String): ByteArray =
        encodeLengthDelimitedFieldForTest(fieldNumber, text.toByteArray(Charsets.UTF_8))

    private fun concatBytes(vararg parts: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}
