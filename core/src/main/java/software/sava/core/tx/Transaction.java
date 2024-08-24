package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.lookup.AccountIndexLookupTableEntry;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.encoding.Base58;
import software.sava.core.encoding.CompactU16Encoding;

import java.util.*;
import java.util.function.BiFunction;

import static software.sava.core.accounts.meta.AccountMeta.ACCOUNT_META_ARRAY_GENERATOR;
import static software.sava.core.encoding.CompactU16Encoding.getByteLen;
import static software.sava.core.encoding.CompactU16Encoding.signedByte;
import static software.sava.core.tx.TransactionRecord.NO_TABLES;

public interface Transaction {

  int MAX_SERIALIZED_LENGTH = 1232;
  int SIGNATURE_LENGTH = 64;
  int BLOCK_HASH_LENGTH = 32;

  BiFunction<AccountMeta, AccountMeta, AccountMeta> MERGE_ACCOUNT_META = (prev, add) -> prev == null ? add : prev.merge(add);

  // fee payer, sign, write, read
  Comparator<AccountMeta> LEGACY_META_COMPARATOR = (am1, am2) -> {
    if (am1.feePayer()) {
      return -1;
    } else if (am2.feePayer()) {
      return 1;
    } else if (am1.signer() == am2.signer()) {
      if (am1.write() == am2.write()) {
        return 0;
      } else {
        return am1.write() ? -1 : 1;
      }
    } else {
      return am1.signer() ? -1 : 1;
    }
  };
  Comparator<AccountMeta> VO_META_COMPARATOR = (am1, am2) -> {
    if (am1.feePayer()) {
      return -1;
    } else if (am2.feePayer()) {
      return 1;
    } else if (am1.signer() == am2.signer()) {
      if (am1.write() == am2.write()) {
        return am1.invoked() == am2.invoked() ? 0 : am1.invoked() ? -1 : 1;
      } else {
        return am1.write() ? -1 : 1;
      }
    } else {
      return am1.signer() ? -1 : 1;
    }
  };

  int MSG_HEADER_LENGTH = 3;
  int VERSIONED_MSG_HEADER_LENGTH = 1 + MSG_HEADER_LENGTH;
  byte MESSAGE_VERSION_0_PREFIX = (byte) (1 << 7);

  static String getBase58Id(final byte[] signedTransaction) {
    if (signedTransaction[0] == 0) {
      throw new IllegalStateException("Transaction has not been signed yet.");
    } else {
      return Base58.encode(signedTransaction, 1, 1 + Transaction.SIGNATURE_LENGTH);
    }
  }

  static byte[] getId(final byte[] signedTransaction) {
    if (signedTransaction[0] == 0) {
      throw new IllegalStateException("Transaction has not been signed yet.");
    } else {
      return Arrays.copyOfRange(signedTransaction, 1, 1 + Transaction.SIGNATURE_LENGTH);
    }
  }

  static AccountMeta[] sortLegacyAccounts(final Map<PublicKey, AccountMeta> mergedAccounts) {
    final AccountMeta[] accountMetas = mergedAccounts.values().toArray(ACCOUNT_META_ARRAY_GENERATOR);
    Arrays.sort(accountMetas, Transaction.LEGACY_META_COMPARATOR);
    return accountMetas;
  }

  static Transaction createTx(final PublicKey feePayer, final List<Instruction> instructions) {
    return createTx(feePayer == null ? null : AccountMeta.createFeePayer(feePayer), instructions);
  }

  private static int mergeAccounts(final AccountMeta feePayer,
                                   final Map<PublicKey, AccountMeta> accounts,
                                   final List<Instruction> instructions) {
    final int numInstructions = instructions.size();
    if (numInstructions == 0) {
      throw new IllegalArgumentException("No instructions provided");
    }
    if (feePayer != null) {
      accounts.put(feePayer.publicKey(), feePayer);
    }
    int serializedInstructionLength = 0;
    for (final var instruction : instructions) {
      serializedInstructionLength += instruction.serializedLength();
      for (final var meta : instruction.accounts()) {
        accounts.merge(meta.publicKey(), meta, Transaction.MERGE_ACCOUNT_META);
      }
      final var programMeta = instruction.programId();
      accounts.merge(programMeta.publicKey(), programMeta, Transaction.MERGE_ACCOUNT_META);
    }
    return serializedInstructionLength;
  }

  static Transaction createTx(final AccountMeta feePayer, final List<Instruction> instructions) {
    final var accounts = HashMap.<PublicKey, AccountMeta>newHashMap(256);
    final int serializedInstructionLength = mergeAccounts(feePayer, accounts, instructions);
    return createTx(instructions, serializedInstructionLength, sortLegacyAccounts(accounts));
  }


  static Transaction createTx(final AccountMeta feePayer, final Instruction instruction) {
    return createTx(feePayer, List.of(instruction));
  }

  static Transaction createTx(final List<Instruction> instructions) {
    return createTx((AccountMeta) null, instructions);
  }

  static Transaction createTx(final AccountMeta feePayer,
                              final List<Instruction> instructions,
                              final AddressLookupTable lookupTable) {
    if (lookupTable == null) {
      return createTx(feePayer, instructions);
    }
    final var accounts = HashMap.<PublicKey, AccountMeta>newHashMap(256);
    final int serializedInstructionLength = mergeAccounts(feePayer, accounts, instructions);
    return createTx(instructions, serializedInstructionLength, sortV0Accounts(accounts), lookupTable);
  }

  static Transaction createTx(final PublicKey feePayer,
                              final List<Instruction> instructions,
                              final AddressLookupTable lookupTable) {
    return createTx(feePayer == null ? null : AccountMeta.createFeePayer(feePayer), instructions, lookupTable);
  }

  static Transaction createTx(final AccountMeta feePayer,
                              final List<Instruction> instructions,
                              final AddressLookupTable lookupTable,
                              final LookupTableAccountMeta[] tableAccountMetas) {
    if (tableAccountMetas == null || tableAccountMetas.length == 0) {
      return createTx(feePayer, instructions, lookupTable);
    } else if (lookupTable != null) {
      throw new IllegalStateException("Use either a single lookup table or multiple account metas, not both");
    } else {
      return createTx(feePayer, instructions, tableAccountMetas);
    }
  }

  static Transaction createTx(final List<Instruction> instructions, final AddressLookupTable lookupTable) {
    return createTx((AccountMeta) null, instructions, lookupTable);
  }

  static Transaction createTx(final PublicKey feePayer,
                              final List<Instruction> instructions,
                              final Instruction... push) {
    final var pushed = new ArrayList<Instruction>(instructions.size() + push.length);
    Collections.addAll(pushed, push);
    pushed.addAll(instructions);
    return createTx(feePayer, pushed);
  }

  static Transaction createTx(final List<Instruction> instructions, final Instruction... push) {
    return createTx(null, instructions, push);
  }

  static Transaction createTx(final PublicKey feePayer, final Instruction instruction) {
    return createTx(feePayer, List.of(instruction));
  }

  static Transaction createTx(final Instruction instruction) {
    return createTx((AccountMeta) null, instruction);
  }

  static Transaction createTx(final List<Instruction> instructions, final AccountMeta[] sortedAccountKeys) {
    if (instructions.isEmpty()) {
      throw new IllegalArgumentException("No instructions provided");
    }
    final int serializedInstructionLength = instructions.stream().mapToInt(Instruction::serializedLength).sum();
    return createTx(instructions, serializedInstructionLength, sortedAccountKeys);
  }

  static Transaction createTx(final Instruction instruction, final AccountMeta[] sortedAccountKeys) {
    return createTx(List.of(instruction), instruction.serializedLength(), sortedAccountKeys);
  }

  static Transaction createTx(final Instruction[] instructions,
                              final int serializedInstructionLength,
                              final Map<PublicKey, AccountMeta> mergedAccounts) {
    return createTx(Arrays.asList(instructions), serializedInstructionLength, sortV0Accounts(mergedAccounts));
  }

  static Transaction createTx(final List<Instruction> instructions,
                              final int serializedInstructionLength,
                              final AccountMeta[] sortedAccounts) {
    final int numAccounts = sortedAccounts.length;
    final var accountIndexLookupTable = new AccountIndexLookupTableEntry[numAccounts];

    int numRequiredSignatures = 0;
    int numReadonlySignedAccounts = 0;
    int numReadonlyUnsignedAccounts = 0;

    AccountMeta feePayer = null;
    for (int i = 0; i < numAccounts; ++i) {
      final var accountMeta = sortedAccounts[i];
      accountIndexLookupTable[i] = new AccountIndexLookupTableEntry(accountMeta.publicKey().toByteArray(), i);

      if (accountMeta.signer()) {
        if (accountMeta.feePayer()) {
          feePayer = accountMeta;
        }
        ++numRequiredSignatures;
        if (!accountMeta.write()) {
          ++numReadonlySignedAccounts;
        }
      } else if (!accountMeta.write()) {
        ++numReadonlyUnsignedAccounts;
      }
    }
    Arrays.sort(accountIndexLookupTable);

    final int sigLen = 1 + (numRequiredSignatures << 6);
    final int numInstructions = instructions.size();
    final int bufferSize = sigLen
        + Transaction.MSG_HEADER_LENGTH
        + getByteLen(numAccounts) + (numAccounts << 5)
        + Transaction.BLOCK_HASH_LENGTH
        + getByteLen(numInstructions) + serializedInstructionLength;

    final byte[] out = new byte[bufferSize];
    out[0] = (byte) numRequiredSignatures;

    int i = sigLen;

    // Message Header
    out[i] = (byte) numRequiredSignatures;
    out[++i] = (byte) numReadonlySignedAccounts;
    out[++i] = (byte) numReadonlyUnsignedAccounts;
    ++i;

    i += CompactU16Encoding.encodeLength(out, i, numAccounts);
    for (final var accountMeta : sortedAccounts) {
      i += accountMeta.publicKey().write(out, i);
    }

    final int recentBlockHashIndex = i;
    i += Transaction.BLOCK_HASH_LENGTH;

    i += CompactU16Encoding.encodeLength(out, i, numInstructions);
    for (final var instruction : instructions) {
      i = instruction.serialize(out, i, accountIndexLookupTable);
    }

    return new TransactionRecord(feePayer, instructions, null, NO_TABLES, out, numRequiredSignatures, sigLen, recentBlockHashIndex);
  }

  static AccountMeta[] sortV0Accounts(final Map<PublicKey, AccountMeta> mergedAccounts) {
    final AccountMeta[] accountMetas = mergedAccounts.values().toArray(ACCOUNT_META_ARRAY_GENERATOR);
    Arrays.sort(accountMetas, Transaction.VO_META_COMPARATOR);
    return accountMetas;
  }

  static Transaction createTx(final List<Instruction> instructions,
                              final int serializedInstructionLength,
                              final Map<PublicKey, AccountMeta> mergedAccounts,
                              final AddressLookupTable lookupTable) {
    if (lookupTable == null) {
      return createTx(instructions, serializedInstructionLength, sortLegacyAccounts(mergedAccounts));
    } else {
      return createTx(instructions, serializedInstructionLength, sortV0Accounts(mergedAccounts), lookupTable);
    }
  }

  static Transaction createTx(final List<Instruction> instructions,
                              final int serializedInstructionLength,
                              final AccountMeta[] sortedAccounts,
                              final AddressLookupTable lookupTable) {
    if (lookupTable == null) {
      return createTx(instructions, serializedInstructionLength, sortedAccounts);
    }
    final int numAccounts = sortedAccounts.length;
    final AccountIndexLookupTableEntry[] accountIndexLookupTable = new AccountIndexLookupTableEntry[numAccounts];

    int numRequiredSignatures = 0;
    int numReadonlySignedAccounts = 0;
    int numReadonlyUnsignedAccounts = 0;

    AccountMeta feePayer = null;
    int numLookupReads = 0;
    int numLookupWrites = 0;
    int numIncludedAccounts = 0;
    for (int i = 0, len; i < numAccounts; ++i) {
      final var account = sortedAccounts[i];
      if (account.signer()) {
        if (account.feePayer()) {
          feePayer = account;
        }
        if (!account.write()) {
          ++numReadonlySignedAccounts;
        }
        ++numRequiredSignatures;
      } else if (account.invoked() || lookupTable.indexOf(account.publicKey()) < 0) {
        if (!account.write()) {
          ++numReadonlyUnsignedAccounts;
        }
        if (i > numIncludedAccounts) {
          len = i - numIncludedAccounts;
          if (len == 1) {
            sortedAccounts[i] = sortedAccounts[numIncludedAccounts];
          } else {
            System.arraycopy(sortedAccounts, numIncludedAccounts, sortedAccounts, numIncludedAccounts + 1, len);
          }
          sortedAccounts[numIncludedAccounts] = account;
        }
      } else {
        if (account.write()) {
          ++numLookupWrites;
        } else {
          ++numLookupReads;
        }
        continue;
      }
      accountIndexLookupTable[numIncludedAccounts] = new AccountIndexLookupTableEntry(account.publicKey().toByteArray(), numIncludedAccounts);
      ++numIncludedAccounts;
    }
    for (int a = numIncludedAccounts; a < numAccounts; ++a) {
      accountIndexLookupTable[a] = new AccountIndexLookupTableEntry(sortedAccounts[a].publicKey().toByteArray(), a);
    }
    Arrays.sort(accountIndexLookupTable);

    final int sigLen = 1 + (numRequiredSignatures << 6);
    final int bufferSize = sigLen
        + VERSIONED_MSG_HEADER_LENGTH
        + getByteLen(numIncludedAccounts) + (numIncludedAccounts << 5)
        + Transaction.BLOCK_HASH_LENGTH
        + getByteLen(instructions.size()) + serializedInstructionLength
        + (1 + PublicKey.PUBLIC_KEY_LENGTH + 2 + numLookupWrites + numLookupReads);

    final byte[] out = new byte[bufferSize];
    out[0] = (byte) numRequiredSignatures;

    int i = sigLen;

    // Version
    out[i] = MESSAGE_VERSION_0_PREFIX;

    // Message Header
    out[++i] = (byte) numRequiredSignatures;
    out[++i] = (byte) numReadonlySignedAccounts;
    out[++i] = (byte) numReadonlyUnsignedAccounts;
    ++i;

    i += CompactU16Encoding.encodeLength(out, i, numIncludedAccounts);
    for (int a = 0; a < numIncludedAccounts; ++a) {
      i += sortedAccounts[a].publicKey().write(out, i);
    }

    final int recentBlockHashIndex = i;
    i += Transaction.BLOCK_HASH_LENGTH;

    i += CompactU16Encoding.encodeLength(out, i, instructions.size());
    for (final var instruction : instructions) {
      i = instruction.serialize(out, i, accountIndexLookupTable);
    }

    // Address Lookup Table
    i += CompactU16Encoding.encodeLength(out, i, 1);
    i += lookupTable.address().write(out, i);
    i += CompactU16Encoding.encodeLength(out, i, numLookupWrites);
    int a = numIncludedAccounts;
    for (final int to = numIncludedAccounts + numLookupWrites; a < to; ++a, ++i) {
      out[i] = lookupTable.indexOfOrThrow(sortedAccounts[a].publicKey());
    }
    i += CompactU16Encoding.encodeLength(out, i, numLookupReads);
    for (; a < numAccounts; ++a, ++i) {
      out[i] = lookupTable.indexOfOrThrow(sortedAccounts[a].publicKey());
    }

    return new TransactionRecord(feePayer, instructions, lookupTable, NO_TABLES, out, numRequiredSignatures, sigLen, recentBlockHashIndex);
  }

  static Transaction createTx(final AccountMeta feePayer,
                              final List<Instruction> instructions,
                              final LookupTableAccountMeta[] tableAccountMetas) {
    final var accounts = HashMap.<PublicKey, AccountMeta>newHashMap(256);
    final int serializedInstructionLength = mergeAccounts(feePayer, accounts, instructions);
    return createTx(instructions, serializedInstructionLength, accounts, tableAccountMetas);
  }

  static Transaction createTx(final PublicKey feePayer,
                              final List<Instruction> instructions,
                              final LookupTableAccountMeta[] tableAccountMetas) {
    return createTx(feePayer == null ? null : AccountMeta.createFeePayer(feePayer), instructions, tableAccountMetas);
  }

  static Transaction createTx(final List<Instruction> instructions,
                              final int serializedInstructionLength,
                              final Map<PublicKey, AccountMeta> mergedAccounts,
                              final LookupTableAccountMeta[] tableAccountMetas) {
    if (tableAccountMetas == null || tableAccountMetas.length == 0) {
      return createTx(instructions, serializedInstructionLength, sortLegacyAccounts(mergedAccounts));
    } else {
      return createTx(instructions, serializedInstructionLength, sortV0Accounts(mergedAccounts), tableAccountMetas);
    }
  }

  static Transaction createTx(final List<Instruction> instructions,
                              final int serializedInstructionLength,
                              final AccountMeta[] sortedAccounts,
                              final LookupTableAccountMeta[] tableAccountMetas) {
    final int numLookupTables = tableAccountMetas.length;
    if (numLookupTables == 0) {
      return createTx(instructions);
    } else if (numLookupTables == 1) {
      return createTx(instructions, serializedInstructionLength, sortedAccounts, tableAccountMetas[0].lookupTable());
    }

    final int numAccounts = sortedAccounts.length;
    final AccountIndexLookupTableEntry[] accountIndexLookupTable = new AccountIndexLookupTableEntry[numAccounts];

    int numRequiredSignatures = 0;
    int numReadonlySignedAccounts = 0;
    int numReadonlyUnsignedAccounts = 0;

    int numIncludedAccounts = 0;

    AccountMeta feePayer = null;
    NEXT_ACCOUNT:
    for (int i = 0, len; i < numAccounts; ++i) {
      final var account = sortedAccounts[i];
      if (account.signer()) {
        if (account.feePayer()) {
          feePayer = account;
        }
        if (!account.write()) {
          ++numReadonlySignedAccounts;
        }
        ++numRequiredSignatures;
      } else {
        if (!account.invoked()) {
          for (final var tableAccountMeta : tableAccountMetas) {
            if (tableAccountMeta.indexOf(account.publicKey()) >= 0) {
              tableAccountMeta.addAccount(account);
              continue NEXT_ACCOUNT;
            }
          }
        }
        if (!account.write()) {
          ++numReadonlyUnsignedAccounts;
        }
        if (i > numIncludedAccounts) {
          len = i - numIncludedAccounts;
          if (len == 1) {
            sortedAccounts[i] = sortedAccounts[numIncludedAccounts];
          } else {
            System.arraycopy(sortedAccounts, numIncludedAccounts, sortedAccounts, numIncludedAccounts + 1, len);
          }
          sortedAccounts[numIncludedAccounts] = account;
        }
      }
      accountIndexLookupTable[numIncludedAccounts] = new AccountIndexLookupTableEntry(account.publicKey().toByteArray(), numIncludedAccounts);
      ++numIncludedAccounts;
    }

    int lookupTablesSerializationSize = 1;
    for (int t = 0, a = numIncludedAccounts; t < numLookupTables; ++t) {
      final var tableAccountMeta = tableAccountMetas[t];
      a = tableAccountMeta.addReverseLookupEntries(accountIndexLookupTable, a);
      lookupTablesSerializationSize += tableAccountMeta.serializationLength();
    }
    Arrays.sort(accountIndexLookupTable);

    final int sigLen = 1 + (numRequiredSignatures << 6);
    final int bufferSize = sigLen
        + VERSIONED_MSG_HEADER_LENGTH
        + getByteLen(numIncludedAccounts) + (numIncludedAccounts << 5)
        + Transaction.BLOCK_HASH_LENGTH
        + getByteLen(instructions.size()) + serializedInstructionLength
        + lookupTablesSerializationSize;

    final byte[] out = new byte[bufferSize];
    out[0] = (byte) numRequiredSignatures;

    int i = sigLen;

    // Version
    out[i] = MESSAGE_VERSION_0_PREFIX;

    // Message Header
    out[++i] = (byte) numRequiredSignatures;
    out[++i] = (byte) numReadonlySignedAccounts;
    out[++i] = (byte) numReadonlyUnsignedAccounts;
    ++i;

    // Accounts
    i += CompactU16Encoding.encodeLength(out, i, numIncludedAccounts);
    for (int a = 0; a < numIncludedAccounts; ++a) {
      i += sortedAccounts[a].publicKey().write(out, i);
    }

    final int recentBlockHashIndex = i;
    i += Transaction.BLOCK_HASH_LENGTH;

    // Instructions
    i += CompactU16Encoding.encodeLength(out, i, instructions.size());
    for (final var instruction : instructions) {
      i = instruction.serialize(out, i, accountIndexLookupTable);
    }

    // Address Lookup Tables
    i += CompactU16Encoding.encodeLength(out, i, numLookupTables);
    for (final var tableAccountMeta : tableAccountMetas) {
      i = tableAccountMeta.serialize(out, i);
    }

    return new TransactionRecord(feePayer, instructions, null, tableAccountMetas, out, numRequiredSignatures, sigLen, recentBlockHashIndex);
  }

  static void setBlockHash(final byte[] data, final byte[] recentBlockHash) {
    final int numSigners = Byte.toUnsignedInt(data[0]);
    final int versionOffset = 1 + (numSigners * Transaction.SIGNATURE_LENGTH);
    final int accountMetaOffset = versionOffset + (signedByte(data[versionOffset]) ? 4 : 3);
    final int accountMetaByteLen = CompactU16Encoding.getByteLen(data, accountMetaOffset);
    final int accountMetaLen = CompactU16Encoding.decode(data, accountMetaOffset) * PublicKey.PUBLIC_KEY_LENGTH;
    final int recentBlockHashOffset = accountMetaOffset + accountMetaByteLen + accountMetaLen;
    System.arraycopy(recentBlockHash, 0, data, recentBlockHashOffset, Transaction.BLOCK_HASH_LENGTH);
  }

  static void sign(final Signer signer,
                   final byte[] out,
                   final int msgOffset,
                   final int msgLen,
                   int offset) {
    signer.sign(out, msgOffset, msgLen, offset);
  }

  static void sign(final Signer signer, final byte[] out) {
    out[0] = 1;
    final int sigLen = 1 + Transaction.SIGNATURE_LENGTH;
    final int msgLen = out.length - sigLen;
    Transaction.sign(signer, out, sigLen, msgLen, 1);
  }

  static String signAndBase64Encode(final Signer signer, final byte[] out) {
    sign(signer, out);
    return Base64.getEncoder().encodeToString(out);
  }

  static void sign(final List<Signer> signers,
                   final byte[] out,
                   final int msgOffset,
                   final int msgLen,
                   int offset) {
    for (final var signer : signers) {
      offset = signer.sign(out, msgOffset, msgLen, offset);
    }
  }

  static void sign(final List<Signer> signers, final byte[] out) {
    final int numSigners = signers.size();
    out[0] = (byte) numSigners;
    final int sigLen = 1 + (numSigners * Transaction.SIGNATURE_LENGTH);
    final int msgLen = out.length - sigLen;
    Transaction.sign(signers, out, sigLen, msgLen, 1);
  }

  static String signAndBase64Encode(final List<Signer> signers, final byte[] out) {
    sign(signers, out);
    return Base64.getEncoder().encodeToString(out);
  }

  List<Instruction> instructions();

  AddressLookupTable lookupTable();

  LookupTableAccountMeta[] tableAccountMetas();

  void setRecentBlockHash(final byte[] recentBlockHash);

  void setRecentBlockHash(final String recentBlockHash);

  byte[] serialized();

  default String base64EncodeToString() {
    return Base64.getEncoder().encodeToString(serialized());
  }

  void sign(final Signer signer);

  void sign(final int index, final Signer signer);

  default String signAndBase64Encode(final Signer signer) {
    sign(signer);
    return base64EncodeToString();
  }

  default void sign(final byte[] recentBlockHash, final Signer signer) {
    setRecentBlockHash(recentBlockHash);
    sign(signer);
  }

  default void sign(final String recentBlockHash, final Signer signer) {
    sign(Base58.decode(recentBlockHash), signer);
  }

  default String signAndBase64Encode(final byte[] recentBlockHash, final Signer signer) {
    sign(recentBlockHash, signer);
    return base64EncodeToString();
  }

  default String signAndBase64Encode(final String recentBlockHash, final Signer signer) {
    return signAndBase64Encode(Base58.decode(recentBlockHash), signer);
  }

  void sign(final List<Signer> signers);

  default String signAndBase64Encode(final List<Signer> signers) {
    sign(signers);
    return base64EncodeToString();
  }

  default void sign(final byte[] recentBlockHash, final List<Signer> signers) {
    setRecentBlockHash(recentBlockHash);
    sign(signers);
  }

  default void sign(final String recentBlockHash, final List<Signer> signers) {
    setRecentBlockHash(recentBlockHash);
    sign(signers);
  }

  default String signAndBase64Encode(final byte[] recentBlockHash, final List<Signer> signers) {
    sign(recentBlockHash, signers);
    return base64EncodeToString();
  }

  default String signAndBase64Encode(final String recentBlockHash, final List<Signer> signers) {
    sign(recentBlockHash, signers);
    return base64EncodeToString();
  }

  String getBase58Id();

  byte[] getId();

  Transaction prependIx(final Instruction ix);

  Transaction prependInstructions(final Instruction ix1, final Instruction ix2);

  Transaction prependInstructions(final List<Instruction> instructions);

  AccountMeta feePayer();
}
