# Kaspa Kinetic `.kinetic` Backup & `KKCV1` On-Chain Recovery Format Specification

- **Status:** Open Format Specification — Version `1` (`KINETIC1` / `KKCV1`)
- **Maintainer:** Kaspa Kinetic (Pure Kaspa Layer-1 Native Wallet)
- **Purpose:** Guarantee that any user or open-source developer can independently decrypt, inspect, and recover Kaspa Layer-1 SilverScript covenants years from now—even if **zero** full archival Kaspa nodes exist in the future.

---

## 1. Why `.kinetic` Exists Alongside On-Chain `KKCV1` Payloads

Kaspa is a high-throughput BlockDAG where standard full nodes prune old transaction bodies after ~30 hours. While Kaspa Kinetic attaches a compact, seed-encrypted `KKCV1` recovery payload to covenant deployment transactions (enabling automatic restoration via archival nodes/APIs), that convenience assumes archival nodes remain online indefinitely.

`.kinetic` is Kaspa Kinetic's open, self-contained **AES-256-GCM** encrypted backup archive wrapping official portable **`SilAbiArtifact`** JSON + covenant parameters + redeem script preimages. Because Kaspa's P2SH covenant UTXOs only publish the 32-byte BLAKE2b script hash on-chain until spent, keeping a `.kinetic` backup guarantees 100% sovereign recovery with zero reliance on archival nodes or third-party servers.

---

## 2. Default Export Filename Convention

When exported via Android Storage Access Framework (`CreateDocument`), Kaspa Kinetic pre-fills:

```text
<SanitizedWalletName>_<CreatedYYYY-MM-DD>_updated-<LastUpdatedYYYY-MM-DD>.kinetic
```

Example:
```text
Main_Kaspa_Wallet_2026-11-22_updated-2026-11-25.kinetic
```

- Dates are formatted in UTC (`YYYY-MM-DD`).
- `<SanitizedWalletName>` trims whitespace, strips filesystem-illegal characters (`/ \ : * ? " < > | ( ) [ ] { }`), replaces spaces with `_`, and collapses consecutive underscores.

---

## 3. `.kinetic` File Envelope & Encryption Layout

A `.kinetic` file is a UTF-8 JSON document (`KineticEnvelope`) with an unauthenticated clear metadata header (bound via AES-GCM AAD) and an authenticated ciphertext hex payload:

```json
{
  "magic": "KINETIC1",
  "format_version": 1,
  "cipher": "AES-256-GCM",
  "kdf": "wallet-seed-v1 | pbkdf2-hmac-sha256",
  "kdf_iterations": 0,
  "salt_hex": "",
  "iv_hex": "<24 lowercase hex chars (12 bytes)>",
  "wallet_fingerprint": "<16 lowercase hex chars>",
  "wallet_name": "Main Kaspa Wallet",
  "created_at_ms": 1795300000000,
  "last_updated_at_ms": 1795560000000,
  "covenant_count": 3,
  "ciphertext_hex": "<AES-256-GCM ciphertext || 16-byte GCM auth tag>"
}
```

### Key Derivation Modes (`kdf`)
1. **`wallet-seed-v1` (Automatic Internal Snapshots & Seed-Locked Exports):**
   - Derive BIP-32 hardened key at isolated internal path:
     `m/44'/111111'/99'/0'/0'`
   - Compute 32-byte AES key via keyed **BLAKE3-256**:
     `key32 = BLAKE3_keyed(key = "KaspaKineticCovenantBackupV1\0\0\0\0", data = leaf_privkey_32B)`
2. **`pbkdf2-hmac-sha256` (Optional User Passphrase Exports):**
   - `salt`: 16 cryptographically random bytes (`salt_hex`).
   - `iterations`: `100,000` (`kdf_iterations`).
   - `key32 = PBKDF2-HMAC-SHA256(passphrase_utf8, salt, iterations, 256 bits)`.

### AES-256-GCM Parameters
- **Cipher:** `AES/GCM/NoPadding`
- **IV / Nonce:** 12 random bytes (`iv_hex`)
- **Authentication Tag:** 128-bit (16 bytes), appended to ciphertext (`ciphertext_hex`)
- **AAD (Associated Authenticated Data):** UTF-8 bytes of `"KINETIC1:1:<wallet_fingerprint>"`
- **`wallet_fingerprint`:** First 16 lowercase hex characters of unkeyed `BLAKE3(primary_mainnet_address.lowercase().utf8)`

### Decrypted Plaintext Schema (`KineticVaultPayload`)
UTF-8 JSON containing:
- `schema_version`: `1`
- `wallet_id`, `wallet_name`, `wallet_fingerprint`, `created_at_ms`, `last_updated_at_ms`
- `covenants`: array of `TrackedCovenant` objects, each carrying:
  - `canonicalKey` (`kip20:<network>:<covenant_id>` or `p2sh:<network>:<template_id>:<redeem_script_hash>`)
  - `network` (`MAINNET` / `TESTNET_10`), `templateId`, `label`, `note`
  - `covenantIdHex` (64-char KIP-20 lineage ID for stateful covenants)
  - `currentP2shAddress`, `currentRedeemScriptHex`, `redeemScriptHashHex`, `knownRedeemScriptsHex`
  - `silSource` (SilverScript `.sil` source)
  - `abiArtifact` (portable `SilAbiArtifact` v1 JSON matching `kaspanet/silverscript`'s `silverscript-abi` schema: `schema_version`, `compiler_version`, `contracts`, `entries` with 4-byte KCC-01 BLAKE3 `dispatch_tag`, `bytecode_hex`, `template_hash_hex`, `state_span`)
  - `params` (constructor & state parameters)

---

## 4. On-Chain `KKCV1` Seed-Encrypted Payload Format

Attached to Transaction v1 `payload` on covenant deployment transactions:

| Offset | Length | Description |
| :--- | :--- | :--- |
| `0` | `5` bytes | ASCII Magic: `"KKCV1"` (`4b 4b 43 56 31`) |
| `5` | `1` byte | Schema Version: `0x01` |
| `6` | `12` bytes | Random AES-GCM IV |
| `18` | `N + 16` bytes | AES-256-GCM ciphertext of compact UTF-8 JSON `Kkcv1Recipe` + 16-byte GCM tag |

- **Key:** Identical `wallet-seed-v1` 32-byte key derived from `m/44'/111111'/99'/0'/0'`.
- **AAD:** The 6-byte header (`"KKCV1" || 0x01`).
- **Recipe JSON (`Kkcv1Recipe`):**
  `{"v":1,"t":"<template_id>","n":"<network_id>","l":"<label>","c":"<64-char covenant_id or null>","p":{...CovenantParams...}}`

---

## 5. Shareable `.kpass` Format (`kaspa-kinetic-pass-v1`)

A **`.kpass`** file is a lightweight, **public-safe** UTF-8 JSON pass designed to be shared with a **Recurring Allowance / Subscription Recipient** or **Dead Man's Switch Heir** via Android Share Sheet, SAF file save, or compact QR code (`kaspa-kinetic-pass-qr-v1`):

- **Zero Private Keys:** Contains **zero** owner private keys, **zero** mnemonic seed words, and **zero** wallet secrets. (`Clawback Vault` is intentionally excluded from `.kpass` sharing because Alarm Clawback requires a private Alarm signing key.)
- **Default Filenames:** Clean, Android-safe filenames such as:
  - `Allowance_Pass_2026-11-23.kpass` (Recurring Allowance / Subscription recipient claim pass)
  - `Heir_Pass_2026-11-23.kpass` (Dead Man's Switch heir claim pass)
- **Top-Level JSON Fields:**
  - `format`: `"kaspa-kinetic-pass-v1"` (or `"kaspa-kinetic-pass-qr-v1"` for compact low-density QR codes)
  - `kind`: `"ALLOWANCE_CLAIM_PASS"` | `"HEIR_CLAIM_PASS"`
  - `network`, `templateId`, `label`, `covenantIdHex`, `p2shAddress`, `redeemScriptHex`, `abiArtifact`, `params`, `createdAtMs`
- **0-KAS Claiming:** For both `ALLOWANCE_CLAIM_PASS` and `HEIR_CLAIM_PASS`, the recipient or heir can import the `.kpass` into the **Shared** tab on a wallet with `0.00 KAS` and claim once Kaspa's consensus `pastMedianTime` surpasses the on-chain `tx.time` lock — miner fees are deducted directly from the covenant UTXO.

---

## 6. Conflict-Free Merge & Live UTXO Single Source of Truth

When combining local covenants, imported `.kinetic` archives, and recovered `KKCV1` on-chain payloads:
1. Records are deduplicated strictly by `canonicalKey` (`kip20:<network>:<covenant_id>` or `p2sh:<network>:<template_id>:<redeem_script_hash>`).
2. Merging is **additive-only** and **idempotent** — restoring an older `.kinetic` file never deletes newer covenants and never duplicates existing records.
3. **Live blockchain UTXOs are always the single source of truth** for active balance, current outpoint, DAA countdown/progress, and `ACTIVE` vs `SETTLED` status.
