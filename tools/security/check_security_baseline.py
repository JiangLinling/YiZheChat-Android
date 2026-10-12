#!/usr/bin/env python3
"""Fail CI when the YiZheChat security baseline silently regresses."""

from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "security/data-protection/impl/src/main/kotlin/io/element/android/security/dataprotection/impl/DefaultSensitiveAssetCatalog.kt"
MODELS = ROOT / "security/data-protection/api/src/main/kotlin/io/element/android/security/dataprotection/api/DataProtectionModels.kt"
INVENTORY = ROOT / "docs/security/sensitive-data-inventory.md"
APP_BUILD = ROOT / "app/build.gradle.kts"
FEATURE_FLAGS = ROOT / "libraries/featureflag/api/src/main/kotlin/io/element/android/libraries/featureflag/api/FeatureFlags.kt"
HTTP_LOGGING = ROOT / "libraries/network/src/main/kotlin/io/element/android/libraries/network/interceptors/DynamicHttpLoggingInterceptor.kt"
PATH_MANIFEST = ROOT / "tools/security/sensitive_paths.json"
APP_BINDINGS = ROOT / "app/src/main/kotlin/io/element/android/x/di/AppBindings.kt"
SECURE_STORE_API = ROOT / "security/secure-storage/api/src/main/kotlin/io/element/android/security/securestorage/api/SecureStorageContracts.kt"
SECURE_STORE = ROOT / "security/secure-storage/impl/src/main/kotlin/io/element/android/security/securestorage/impl/DefaultSensitiveFileStore.kt"
SECURE_FORMAT = ROOT / "security/secure-storage/impl/src/main/kotlin/io/element/android/security/securestorage/impl/SecureFileFormat.kt"
KEY_ENVELOPE_SERVICE = ROOT / "security/secure-storage/impl/src/main/kotlin/io/element/android/security/securestorage/impl/ObjectKeyEnvelopeService.kt"
DEVICE_ROOT_KEY_API = ROOT / "security/secure-storage/api/src/main/kotlin/io/element/android/security/securestorage/api/DeviceRootKeyService.kt"
DEVICE_ROOT_KEY_BACKEND = ROOT / "security/secure-storage/impl/src/main/kotlin/io/element/android/security/securestorage/impl/DeviceRootKeyBackend.kt"
DEVICE_ROOT_KEY_SERVICE = ROOT / "security/secure-storage/impl/src/main/kotlin/io/element/android/security/securestorage/impl/DefaultDeviceRootKeyService.kt"
DEVICE_ROOT_KEY_RECORDS = ROOT / "security/secure-storage/impl/src/main/kotlin/io/element/android/security/securestorage/impl/DeviceRootKeyRecords.kt"
ATOMIC_SECURE_RECORD_IO = ROOT / "security/secure-storage/impl/src/main/kotlin/io/element/android/security/securestorage/impl/AtomicSecureRecordIO.kt"
VAULT_KEY_ENVELOPE_STORE = ROOT / "security/secure-storage/impl/src/main/kotlin/io/element/android/security/securestorage/impl/VaultKeyEnvelopeStore.kt"
VAULT_KEY_API = ROOT / "security/secure-storage/api/src/main/kotlin/io/element/android/security/securestorage/api/VaultKeyService.kt"
ACCOUNT_VAULT_RECORDS = ROOT / "security/secure-storage/impl/src/main/kotlin/io/element/android/security/securestorage/impl/AccountVaultRecordStore.kt"
ACCOUNT_VAULT_SERVICE = ROOT / "security/secure-storage/impl/src/main/kotlin/io/element/android/security/securestorage/impl/DefaultVaultKeyService.kt"
ACCOUNT_VAULT_INITIALIZER = ROOT / "app/src/main/kotlin/io/element/android/x/initializer/AccountVaultInitializer.kt"
ACCOUNT_VAULT_LIFECYCLE = ROOT / "app/src/main/kotlin/io/element/android/x/security/AccountVaultLifecycleCoordinator.kt"
ACCOUNT_VAULT_TEST = ROOT / "security/secure-storage/impl/src/test/kotlin/io/element/android/security/securestorage/impl/DefaultVaultKeyServiceTest.kt"
DEVICE_ROOT_KEY_INITIALIZER = ROOT / "app/src/main/kotlin/io/element/android/x/initializer/DeviceRootKeyInitializer.kt"
DEVICE_ROOT_KEY_TEST = ROOT / "security/secure-storage/impl/src/test/kotlin/io/element/android/security/securestorage/impl/DefaultDeviceRootKeyServiceTest.kt"
DEVICE_TRUST_API = ROOT / "security/secure-storage/api/src/main/kotlin/io/element/android/security/securestorage/api/DeviceTrustService.kt"
DEVICE_ATTESTATION_BACKEND = ROOT / "security/secure-storage/impl/src/main/kotlin/io/element/android/security/securestorage/impl/DeviceAttestationBackend.kt"
DEVICE_TRUST_SERVICE = ROOT / "security/secure-storage/impl/src/main/kotlin/io/element/android/security/securestorage/impl/DefaultDeviceTrustService.kt"
DEVICE_TRUST_RECORDS = ROOT / "security/secure-storage/impl/src/main/kotlin/io/element/android/security/securestorage/impl/DeviceTrustRecords.kt"
DEVICE_TRUST_INITIALIZER = ROOT / "app/src/main/kotlin/io/element/android/x/initializer/DeviceTrustInitializer.kt"
DEVICE_TRUST_TEST = ROOT / "security/secure-storage/impl/src/test/kotlin/io/element/android/security/securestorage/impl/DefaultDeviceTrustServiceTest.kt"
APP_MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
BACKUP_RULES = ROOT / "app/src/main/res/xml/backup_rules.xml"
DATA_EXTRACTION_RULES = ROOT / "app/src/main/res/xml/data_extraction_rules.xml"
SECURE_STORE_TEST = ROOT / "security/secure-storage/impl/src/test/kotlin/io/element/android/security/securestorage/impl/DefaultSensitiveFileStoreTest.kt"
SECURE_MEDIA_MANIFEST = ROOT / "security/secure-storage/impl/src/main/AndroidManifest.xml"
SECURE_MEDIA_PROVIDER = ROOT / "security/secure-storage/impl/src/main/kotlin/io/element/android/security/securestorage/impl/SecureMediaContentProvider.kt"
SECURE_MEDIA_LOADER = ROOT / "libraries/matrix/impl/src/main/kotlin/io/element/android/libraries/matrix/impl/media/SecureMatrixMediaLoader.kt"
MATRIX_PROTECTED_CONTENT_ADAPTER = ROOT / "libraries/matrix/impl/src/main/kotlin/io/element/android/libraries/matrix/impl/timeline/MatrixProtectedContentAdapter.kt"
MATRIX_CLIENT_FACTORY = ROOT / "libraries/matrix/impl/src/main/kotlin/io/element/android/libraries/matrix/impl/RustMatrixClientFactory.kt"
MATRIX_IMAGE_LOADER = ROOT / "libraries/matrixmedia/impl/src/main/kotlin/io/element/android/libraries/matrix/ui/media/ImageLoaderFactories.kt"
VOICE_MEDIA_REPO = ROOT / "libraries/voiceplayer/impl/src/main/kotlin/io/element/android/libraries/voiceplayer/impl/VoiceMessageMediaRepo.kt"
NOTIFICATION_MEDIA_REPO = ROOT / "libraries/push/impl/src/main/kotlin/io/element/android/libraries/push/impl/notifications/NotificationMediaRepo.kt"
NOTIFICATION_RESOLVER = ROOT / "libraries/push/impl/src/main/kotlin/io/element/android/libraries/push/impl/notifications/DefaultNotifiableEventResolver.kt"
NOTIFICATION_SANITIZER = ROOT / "libraries/push/impl/src/main/kotlin/io/element/android/libraries/push/impl/notifications/NotificationPrivacySanitizer.kt"
NOTIFIABLE_MESSAGE = ROOT / "libraries/push/impl/src/main/kotlin/io/element/android/libraries/push/impl/notifications/model/NotifiableMessageEvent.kt"
LOG_CONFIGURATION = ROOT / "features/rageshake/api/src/main/kotlin/io/element/android/features/rageshake/api/logs/WriteToFilesConfigurationFactory.kt"
BUG_REPORTER = ROOT / "features/rageshake/impl/src/main/kotlin/io/element/android/features/rageshake/impl/reporter/DefaultBugReporter.kt"
CRASH_INITIALIZER = ROOT / "app/src/main/kotlin/io/element/android/x/initializer/CrashInitializer.kt"
PLATFORM_INITIALIZER = ROOT / "app/src/main/kotlin/io/element/android/x/initializer/PlatformInitializer.kt"
DATABASE_SECURITY = ROOT / "libraries/encrypted-db/src/main/kotlin/io/element/encrypteddb/DatabaseSecurityMaintenance.kt"
SQLCIPHER_FACTORY = ROOT / "libraries/encrypted-db/src/main/kotlin/io/element/encrypteddb/SqlCipherDriverFactory.kt"
DELETION_COORDINATOR = ROOT / "security/data-protection/impl/src/main/kotlin/io/element/android/security/dataprotection/impl/DefaultDataDeletionCoordinator.kt"
DELETION_JOURNAL = ROOT / "security/data-protection/impl/src/main/kotlin/io/element/android/security/dataprotection/impl/DeletionJournal.kt"
DELETION_SESSION_CLEANER = ROOT / "security/data-protection/impl/src/main/kotlin/io/element/android/security/dataprotection/impl/DeletionSessionCleaner.kt"
DELETION_RECOVERY_INITIALIZER = ROOT / "app/src/main/kotlin/io/element/android/x/initializer/DeletionRecoveryInitializer.kt"
ELEMENT_APPLICATION = ROOT / "app/src/main/kotlin/io/element/android/x/ElementXApplication.kt"
RUST_MATRIX_CLIENT = ROOT / "libraries/matrix/impl/src/main/kotlin/io/element/android/libraries/matrix/impl/RustMatrixClient.kt"
PLAINTEXT_MIGRATION_COORDINATOR = ROOT / "security/data-protection/impl/src/main/kotlin/io/element/android/security/dataprotection/impl/DefaultPlaintextMigrationCoordinator.kt"
PLAINTEXT_MIGRATION_JOURNAL = ROOT / "security/data-protection/impl/src/main/kotlin/io/element/android/security/dataprotection/impl/PlaintextMigrationJournal.kt"
PLAINTEXT_CLEANER = ROOT / "security/data-protection/impl/src/main/kotlin/io/element/android/security/dataprotection/impl/LegacyPlaintextCleaner.kt"
PLAINTEXT_MIGRATION_INITIALIZER = ROOT / "app/src/main/kotlin/io/element/android/x/initializer/PlaintextMigrationInitializer.kt"
CONTENT_PROTECTION_CONTRACTS = ROOT / "security/content-protection/api/src/main/kotlin/io/element/android/security/contentprotection/api/ContentProtectionContracts.kt"
CONTENT_PROTECTION_API = ROOT / "security/content-protection/api/src/main/kotlin/io/element/android/security/contentprotection/api/ContentProtectionService.kt"
STAGE_THREE_GATE_API = ROOT / "security/content-protection/api/src/main/kotlin/io/element/android/security/contentprotection/api/StageThreeSecurityGate.kt"
OFFLINE_LICENSE_STORE = ROOT / "security/content-protection/impl/src/main/kotlin/io/element/android/security/contentprotection/impl/OfflineLicenseStore.kt"
OFFLINE_LICENSE_SERVICE = ROOT / "security/content-protection/impl/src/main/kotlin/io/element/android/security/contentprotection/impl/DefaultOfflineLicenseService.kt"
REMOTE_REVOCATION_SERVICE = ROOT / "security/content-protection/impl/src/main/kotlin/io/element/android/security/contentprotection/impl/DefaultRemoteRevocationService.kt"
HTTP_KMS_CLIENT = ROOT / "security/content-protection/impl/src/main/kotlin/io/element/android/security/contentprotection/impl/HttpKmsShareClient.kt"
DEFAULT_CONTENT_PROTECTION_SERVICE = ROOT / "security/content-protection/impl/src/main/kotlin/io/element/android/security/contentprotection/impl/DefaultContentProtectionService.kt"
STAGE_THREE_GATE = ROOT / "security/content-protection/impl/src/main/kotlin/io/element/android/security/contentprotection/impl/DefaultStageThreeSecurityGate.kt"
CONTENT_PROTECTION_TEST = ROOT / "security/content-protection/impl/src/test/kotlin/io/element/android/security/contentprotection/impl/DefaultContentProtectionServiceTest.kt"
OFFLINE_LICENSE_TEST = ROOT / "security/content-protection/impl/src/test/kotlin/io/element/android/security/contentprotection/impl/OfflineLicenseServiceTest.kt"
HTTP_KMS_TEST = ROOT / "security/content-protection/impl/src/test/kotlin/io/element/android/security/contentprotection/impl/HttpKmsShareClientTest.kt"
MANAGEMENT_CONFIGURATION = ROOT / "security/data-protection/impl/src/main/kotlin/io/element/android/security/dataprotection/impl/ManagementControlConfiguration.kt"
MANAGEMENT_STATE = ROOT / "security/data-protection/impl/src/main/kotlin/io/element/android/security/dataprotection/impl/SignedManagementStateRepository.kt"
MANAGEMENT_SERVICE = ROOT / "security/data-protection/impl/src/main/kotlin/io/element/android/security/dataprotection/impl/HttpManagementControlService.kt"
MANAGEMENT_PROVIDER = ROOT / "security/data-protection/impl/src/main/kotlin/io/element/android/security/dataprotection/impl/ManagedDataProtectionPolicyProvider.kt"
MANAGEMENT_WIRE_MODELS = ROOT / "security/data-protection/impl/src/main/kotlin/io/element/android/security/dataprotection/impl/ManagementWireModels.kt"
CALL_PROTECTION_SERVICE = ROOT / "features/call/impl/src/main/kotlin/io/element/android/features/call/impl/security/CallProtectionService.kt"
RUNTIME_POLICY = ROOT / "security/data-protection/api/src/main/kotlin/io/element/android/security/dataprotection/api/RuntimeDataProtectionPolicy.kt"
CLIPBOARD_HELPER = ROOT / "libraries/androidutils/src/main/kotlin/io/element/android/libraries/androidutils/clipboard/AndroidClipboardHelper.kt"
SYSTEM_CLIPBOARD = ROOT / "libraries/androidutils/src/main/kotlin/io/element/android/libraries/androidutils/system/CopyToClipboardUseCase.kt"
SYSTEM_UTILS = ROOT / "libraries/androidutils/src/main/kotlin/io/element/android/libraries/androidutils/system/SystemUtils.kt"
MEDIA_ACTIONS = ROOT / "libraries/mediaviewer/impl/src/main/kotlin/io/element/android/libraries/mediaviewer/impl/local/AndroidLocalMediaActions.kt"
FILE_SHARE = ROOT / "features/viewfolder/impl/src/main/kotlin/io/element/android/features/viewfolder/impl/file/FileShare.kt"
FILE_SAVE = ROOT / "features/viewfolder/impl/src/main/kotlin/io/element/android/features/viewfolder/impl/file/FileSave.kt"
LOCK_SCREEN_SERVICE = ROOT / "features/lockscreen/api/src/main/kotlin/io/element/android/features/lockscreen/api/LockScreenService.kt"
MANAGEMENT_COORDINATOR = ROOT / "app/src/main/kotlin/io/element/android/x/security/ManagementControlCoordinator.kt"

EXPECTED_SECURITY_FLAGS = {
    "security.secure_file_store",
    "security.hardware_backed_vault",
    "security.content_protection",
    "security.remote_deletion",
}
EXPECTED_APP_MODULES = {
    "projects.security.contentProtection.impl",
    "projects.security.dataProtection.impl",
    "projects.security.secureStorage.impl",
}
STORAGE_SOURCE_ROOTS = ("app", "features", "libraries", "services", "security")
STATIC_PATH_PATTERNS = (
    re.compile(r"File\([^,\n]*(?:cacheDir|filesDir|noBackupFilesDir)[^,\n]*,\s*\"([^\"]+)\""),
    re.compile(r"const val\s+[A-Z0-9_]*(?:_DIR_|_DIRECTORY_|_SUBDIR|DIRECTORY_NAME)[A-Z0-9_]*\s*=\s*\"([^\"]+)\""),
    re.compile(r"cacheSubdir\s*=\s*\"([^\"]+)\""),
)


def production_kotlin_files():
    for root_name in STORAGE_SOURCE_ROOTS:
        source_root = ROOT / root_name
        for directory, child_directories, file_names in os.walk(source_root):
            child_directories[:] = [name for name in child_directories if name not in {"build", ".gradle"}]
            directory_path = Path(directory)
            if "src" not in directory_path.parts or "main" not in directory_path.parts:
                continue
            if "test" in directory_path.parts[: directory_path.parts.index("src")]:
                continue
            for file_name in file_names:
                if file_name.endswith(".kt"):
                    yield directory_path / file_name


def fail(messages: list[str]) -> None:
    for message in messages:
        print(f"ERROR | {message}")
    raise SystemExit(1)


def ids_from_inventory() -> set[str]:
    return set(re.findall(r"^\|\s*([LMS]-\d{3})\s*\|", INVENTORY.read_text(encoding="utf-8"), re.MULTILINE))


def enum_entries(source: str, enum_name: str) -> set[str]:
    match = re.search(rf"enum class {enum_name}\s*\{{(?P<body>.*?)\n\}}", source, re.DOTALL)
    if match is None:
        return set()
    return set(re.findall(r"^\s*([A-Z][A-Z0-9_]*)\s*,", match.group("body"), re.MULTILINE))


def check_catalog(errors: list[str]) -> None:
    documented_ids = ids_from_inventory()
    catalog_source = CATALOG.read_text(encoding="utf-8")
    catalog_ids = set(re.findall(r"asset\(\s*\"([LMS]-\d{3})\"", catalog_source))
    owner_block = catalog_source.split("private fun ownerFor", maxsplit=1)[-1]
    owned_ids = set(re.findall(r"\"([LMS]-\d{3})\"", owner_block))
    if len(documented_ids) != 41:
        errors.append(f"sensitive inventory must contain 41 unique ids, found {len(documented_ids)}")
    if catalog_ids != documented_ids:
        errors.append(f"runtime catalog mismatch: missing={sorted(documented_ids - catalog_ids)}, extra={sorted(catalog_ids - documented_ids)}")
    if owned_ids != documented_ids:
        errors.append(f"asset ownership mismatch: missing={sorted(documented_ids - owned_ids)}, extra={sorted(owned_ids - documented_ids)}")


def check_wiring_and_flags(errors: list[str]) -> None:
    app_build = APP_BUILD.read_text(encoding="utf-8")
    missing_modules = sorted(module for module in EXPECTED_APP_MODULES if module not in app_build)
    if missing_modules:
        errors.append(f"App is missing security module wiring: {missing_modules}")

    flags_source = FEATURE_FLAGS.read_text(encoding="utf-8")
    present_flags = set(re.findall(r'key\s*=\s*\"(security\.[^\"]+)\"', flags_source))
    if present_flags != EXPECTED_SECURITY_FLAGS:
        errors.append(f"security feature flag mismatch: expected={sorted(EXPECTED_SECURITY_FLAGS)}, actual={sorted(present_flags)}")
    expected_flags = {
        "security.secure_file_store": ("defaultValue = { true }", "isFinished = true"),
        "security.hardware_backed_vault": ("defaultValue = { true }", "isFinished = true"),
        "security.content_protection": ("defaultValue = { true }", "isFinished = true"),
        "security.remote_deletion": ("defaultValue = { true }", "isFinished = true"),
    }
    for flag, (expected_default, expected_status) in expected_flags.items():
        block_match = re.search(
            rf'key\s*=\s*\"{re.escape(flag)}\"(?P<body>.*?isFinished\s*=\s*(?:true|false))',
            flags_source,
            re.DOTALL,
        )
        if block_match is None or expected_default not in block_match.group("body") or expected_status not in block_match.group("body"):
            errors.append(f"security feature flag {flag} must use {expected_default} and {expected_status}")


def check_http_logging(errors: list[str]) -> None:
    source = HTTP_LOGGING.read_text(encoding="utf-8")
    required_fragments = (
        "buildType == BuildType.RELEASE",
        "return Level.NONE",
        "productionSafeHttpLoggingLevel",
    )
    for fragment in required_fragments:
        if fragment not in source:
            errors.append(f"release HTTP logging guard is missing {fragment!r}")

    assignment = re.compile(r"(?:\.level\s*=|setLevel\s*\().*?(?:Level\.)?BODY")
    for kotlin_file in production_kotlin_files():
        relative = kotlin_file.relative_to(ROOT).as_posix()
        if kotlin_file == HTTP_LOGGING:
            continue
        for line_number, line in enumerate(kotlin_file.read_text(encoding="utf-8").splitlines(), start=1):
            if assignment.search(line):
                errors.append(f"HTTP BODY logging assignment outside guarded interceptor: {relative}:{line_number}")


def check_secure_file_store(errors: list[str]) -> None:
    sources = {
        "API": SECURE_STORE_API.read_text(encoding="utf-8"),
        "store": SECURE_STORE.read_text(encoding="utf-8"),
        "format": SECURE_FORMAT.read_text(encoding="utf-8"),
        "key envelope": KEY_ENVELOPE_SERVICE.read_text(encoding="utf-8"),
        "tests": SECURE_STORE_TEST.read_text(encoding="utf-8"),
        "App bindings": APP_BINDINGS.read_text(encoding="utf-8"),
    }
    required_fragments = {
        "API": ("interface SensitiveFileStore", "deleteVault", "runMaintenance", "plaintextLength", "associatedDataSha256"),
        "store": (
            "OBJECT_KEY_SIZE_BITS = 256",
            "publish(dataPartial, dataFile)",
            "publish(keyEnvelopePartial, keyEnvelopeFile)",
            "val keyDestroyed = tombstoneWritten && objectKeyEnvelopeService.destroyVaultKey(vaultId)",
        ),
        "format": ("SECURE_FILE_FORMAT_VERSION = 1", "SECURE_FILE_CHUNK_SIZE = 64 * 1024", '"YZSF0001"', '"YZKE0001"'),
        "key envelope": (
            "DEVICE_ROOT_VAULT",
            "ACCOUNT_VAULT_DOMAIN",
            "vaultKeyService.wrapKey",
            "vaultKeyService.isUnlocked",
            "legacyVaultKeyService.unwrap",
            "retireLegacyVaultKey",
        ),
        "tests": ("published files never contain the plaintext canary", "tampered terminal record", "fresh installation key"),
        "App bindings": ("fun sensitiveFileStore(): SensitiveFileStore",),
    }
    for source_name, fragments in required_fragments.items():
        for fragment in fragments:
            if fragment not in sources[source_name]:
                errors.append(f"secure file store {source_name} is missing required invariant {fragment!r}")

    store_source = sources["store"]
    if store_source.index("publish(dataPartial, dataFile)") > store_source.index("publish(keyEnvelopePartial, keyEnvelopeFile)"):
        errors.append("secure file store must publish ciphertext before its key envelope")


def check_device_root_key(errors: list[str]) -> None:
    sources = {
        "API": DEVICE_ROOT_KEY_API.read_text(encoding="utf-8"),
        "backend": DEVICE_ROOT_KEY_BACKEND.read_text(encoding="utf-8"),
        "service": DEVICE_ROOT_KEY_SERVICE.read_text(encoding="utf-8"),
        "records": DEVICE_ROOT_KEY_RECORDS.read_text(encoding="utf-8"),
        "atomic records": ATOMIC_SECURE_RECORD_IO.read_text(encoding="utf-8"),
        "vault envelopes": VAULT_KEY_ENVELOPE_STORE.read_text(encoding="utf-8"),
        "initializer": DEVICE_ROOT_KEY_INITIALIZER.read_text(encoding="utf-8"),
        "application": ELEMENT_APPLICATION.read_text(encoding="utf-8"),
        "App bindings": APP_BINDINGS.read_text(encoding="utf-8"),
        "tests": DEVICE_ROOT_KEY_TEST.read_text(encoding="utf-8"),
    }
    required_fragments = {
        "API": ("interface DeviceRootKeyService", "wrapKey", "unwrapKey", "hardwareSecurityLevel", "suspend fun destroy"),
        "backend": (
            "AndroidKeyStore",
            "ROOT_KEY_SIZE_BITS = 256",
            "setRandomizedEncryptionRequired(true)",
            "setIsStrongBoxBacked",
            "SECURITY_LEVEL_STRONGBOX",
            "SECURITY_LEVEL_TRUSTED_ENVIRONMENT",
        ),
        "service": (
            "ROOT_KEY_ALIAS_PREFIX",
            "installationIdentityStore.loadOrCreate()",
            "backend.load(alias) ?: throw SecureStorageKeyUnavailableException()",
            "MessageDigest.isEqual",
            "recordStore.markDestroyed",
        ),
        "records": ('DEVICE_ROOT_DIRECTORY = "device-root"', '"YZID0001"', '"YZDR0001"', "MessageDigest.isEqual"),
        "atomic records": ("output.fd.sync()", 'BACKUP_SUFFIX = ".replace-backup"', "recover(destination)"),
        "vault envelopes": ('VAULT_KEYS_DIRECTORY = "vault-keys"', '"YZVK0001"', "MessageDigest.isEqual", "AtomicSecureRecordIO.create"),
        "initializer": ("deviceRootKeyService().ensureReady()",),
        "application": ("initializeComponent(DeviceRootKeyInitializer::class.java)",),
        "App bindings": ("fun deviceRootKeyService(): DeviceRootKeyService",),
        "tests": (
            "wrapped keys are installation and purpose bound",
            "copied no-backup records without the old keystore key fail closed",
            "tampered installation identity is rejected",
            "destroy writes an irreversible local tombstone",
        ),
    }
    for source_name, fragments in required_fragments.items():
        for fragment in fragments:
            if fragment not in sources[source_name]:
                errors.append(f"device root key {source_name} is missing required invariant {fragment!r}")


def check_account_vault(errors: list[str]) -> None:
    sources = {
        "API": VAULT_KEY_API.read_text(encoding="utf-8"),
        "records": ACCOUNT_VAULT_RECORDS.read_text(encoding="utf-8"),
        "service": ACCOUNT_VAULT_SERVICE.read_text(encoding="utf-8"),
        "format": SECURE_FORMAT.read_text(encoding="utf-8"),
        "key envelope": KEY_ENVELOPE_SERVICE.read_text(encoding="utf-8"),
        "factory": MATRIX_CLIENT_FACTORY.read_text(encoding="utf-8"),
        "lifecycle": ACCOUNT_VAULT_LIFECYCLE.read_text(encoding="utf-8"),
        "initializer": ACCOUNT_VAULT_INITIALIZER.read_text(encoding="utf-8"),
        "application": ELEMENT_APPLICATION.read_text(encoding="utf-8"),
        "App bindings": APP_BINDINGS.read_text(encoding="utf-8"),
        "tests": ACCOUNT_VAULT_TEST.read_text(encoding="utf-8"),
    }
    required_fragments = {
        "API": (
            "enum class VaultState",
            "enum class VaultKeyDomain",
            "CREDENTIAL(1)",
            "MATRIX_STORE(2)",
            "DATABASE(3)",
            "MEDIA(4)",
            "CACHE(5)",
            "LOG(6)",
            "suspend fun wrapKey",
            "suspend fun unwrapKey",
            "deriveAccountVaultId",
        ),
        "records": (
            'ACCOUNT_VAULTS_DIRECTORY = "account-vaults"',
            'MANIFEST_MAGIC = "YZAV0001"',
            'DOMAIN_KEY_MAGIC = "YZDK0001"',
            "MessageDigest.isEqual",
        ),
        "service": (
            "class DefaultVaultKeyService",
            "ensureDomainKeys",
            "replaceCachedAccountKey",
            "clearCachedAccountKey",
            "throw SecureStorageKeyUnavailableException()",
            "DurableVaultState.DESTROYED",
            "VAULT_WRAPPED_KEY_FORMAT_VERSION = 2",
        ),
        "format": (
            "KEY_ENVELOPE_FORMAT_VERSION = 3",
            "ACCOUNT_VAULT_DOMAIN(3)",
            "vaultKeyVersion",
        ),
        "key envelope": (
            "VaultKeyDomain.MEDIA",
            "ObjectKeyWrappingScheme.ACCOUNT_VAULT_DOMAIN",
            "formatVersion = 1",
            "formatVersion = 2",
        ),
        "factory": (
            "vaultKeyService.provision",
            "vaultKeyService.unlock",
            "FeatureFlags.HardwareBackedVault",
        ),
        "lifecycle": (
            "ProcessLifecycleOwner",
            "lockScreenService.lockState",
            "sessionStore.sessionsFlow()",
            "vaultKeyService.lock",
            "onMemoryPressure",
        ),
        "initializer": ("accountVaultLifecycleCoordinator().start()", "DeletionRecoveryInitializer::class.java"),
        "application": (
            "initializeComponent(AccountVaultInitializer::class.java)",
            "accountVaultLifecycleCoordinator().onMemoryPressure()",
        ),
        "App bindings": ("fun vaultKeyService(): VaultKeyService", "fun accountVaultLifecycleCoordinator()"),
        "tests": (
            "lock releases the account key",
            "a restarted process must explicitly unlock",
            "rotation advances the active domain version",
            "batch six direct account key envelopes remain readable",
            "destroy tombstones the vault",
        ),
    }
    for source_name, fragments in required_fragments.items():
        for fragment in fragments:
            if fragment not in sources[source_name]:
                errors.append(f"account Vault {source_name} is missing invariant {fragment!r}")


def check_device_trust_and_uninstall(errors: list[str]) -> None:
    sources = {
        "API": DEVICE_TRUST_API.read_text(encoding="utf-8"),
        "backend": DEVICE_ATTESTATION_BACKEND.read_text(encoding="utf-8"),
        "service": DEVICE_TRUST_SERVICE.read_text(encoding="utf-8"),
        "records": DEVICE_TRUST_RECORDS.read_text(encoding="utf-8"),
        "initializer": DEVICE_TRUST_INITIALIZER.read_text(encoding="utf-8"),
        "application": ELEMENT_APPLICATION.read_text(encoding="utf-8"),
        "lifecycle": ACCOUNT_VAULT_LIFECYCLE.read_text(encoding="utf-8"),
        "Vault service": ACCOUNT_VAULT_SERVICE.read_text(encoding="utf-8"),
        "App bindings": APP_BINDINGS.read_text(encoding="utf-8"),
        "tests": DEVICE_TRUST_TEST.read_text(encoding="utf-8"),
        "manifest": APP_MANIFEST.read_text(encoding="utf-8"),
        "backup rules": BACKUP_RULES.read_text(encoding="utf-8"),
        "data extraction rules": DATA_EXTRACTION_RULES.read_text(encoding="utf-8"),
    }
    required_fragments = {
        "API": (
            "data class DeviceAttestationEvidence",
            "enum class DeviceRiskSignal",
            "blocksLocalVaultAccess",
            "allowsLocalVaultAccess",
            "suspend fun initialize",
            "suspend fun applyRemoteTrust",
            "suspend fun signControlStatement",
            "suspend fun destroy",
        ),
        "backend": (
            "AndroidKeyStore",
            "PURPOSE_SIGN",
            "setAttestationChallenge",
            "setIsStrongBoxBacked",
            'SIGNATURE_ALGORITHM = "SHA256withECDSA"',
            "getCertificateChain",
            "ROOT_ARTIFACT_DETECTED",
            "APP_SIGNING_IDENTITY_UNAVAILABLE",
        ),
        "service": (
            'DAK_ALIAS_PREFIX = "yizhe.device-attestation.v1."',
            "DeviceTrustState.PENDING",
            "DeviceTrustState.INTEGRITY_WARNING",
            "Only a server that validates",
            "APP_SIGNING_IDENTITY_CHANGED",
            "recordStore.write(lost)",
            "attestationBackend.destroy(alias)",
        ),
        "records": ('TRUST_RECORD_FILE_NAME = "device-trust.yzdt"', 'TRUST_RECORD_MAGIC = "YZDT0001"', "MessageDigest.isEqual"),
        "initializer": ("deviceTrustService().initialize()", "DeviceRootKeyInitializer::class.java"),
        "application": ("initializeComponent(DeviceTrustInitializer::class.java)",),
        "lifecycle": ("deviceTrust.allowsLocalVaultAccess", "deviceTrustService.markLocked()"),
        "Vault service": ("trust.allowsLocalVaultAccess", "root.hardwareBacked", "trust.hardwareSecurityLevel.isTrustedHardware()"),
        "App bindings": ("fun deviceTrustService(): DeviceTrustService",),
        "tests": (
            "remains pending server verification",
            "blocking platform signal fails local Vault access closed",
            "changed application signing identity",
            "copied trust records without the old DAK",
            "destroy writes LOST before deleting the DAK",
            "tampered durable trust state is rejected",
            "server approval promotes only the current hardware attestation version",
            "server approval cannot promote a device without a hardware attestation chain",
        ),
        "manifest": ('android:allowBackup="false"', 'android:dataExtractionRules="@xml/data_extraction_rules"'),
        "backup rules": ('<exclude domain="root" path="."', '<exclude domain="database" path="."'),
        "data extraction rules": ("<device-transfer>", '<exclude domain="root" path="."', '<exclude domain="database" path="."'),
    }
    for source_name, fragments in required_fragments.items():
        for fragment in fragments:
            if fragment not in sources[source_name]:
                errors.append(f"device trust/uninstall {source_name} is missing invariant {fragment!r}")

    trusted_assignment_count = sources["service"].count("trustState = DeviceTrustState.TRUSTED")
    remote_trust_guards = (
        "override suspend fun applyRemoteTrust",
        "existing.attestationVersion != attestationVersion",
        "ATTESTATION_CERTIFICATE_CHAIN_UNAVAILABLE in existing.riskSignals",
        "HARDWARE_ATTESTATION_UNAVAILABLE in existing.riskSignals",
        "existing.attestationPublicKeySha256",
    )
    if trusted_assignment_count != 1 or any(guard not in sources["service"] for guard in remote_trust_guards):
        errors.append("device trust may become TRUSTED only in the guarded verified-server verdict path")


def check_offline_license_and_remote_revocation(errors: list[str]) -> None:
    sources = {
        "contracts": CONTENT_PROTECTION_CONTRACTS.read_text(encoding="utf-8"),
        "API": CONTENT_PROTECTION_API.read_text(encoding="utf-8"),
        "Vault API": VAULT_KEY_API.read_text(encoding="utf-8"),
        "store": OFFLINE_LICENSE_STORE.read_text(encoding="utf-8"),
        "license service": OFFLINE_LICENSE_SERVICE.read_text(encoding="utf-8"),
        "revocation service": REMOTE_REVOCATION_SERVICE.read_text(encoding="utf-8"),
        "KMS client": HTTP_KMS_CLIENT.read_text(encoding="utf-8"),
        "content service": DEFAULT_CONTENT_PROTECTION_SERVICE.read_text(encoding="utf-8"),
        "App bindings": APP_BINDINGS.read_text(encoding="utf-8"),
        "license tests": OFFLINE_LICENSE_TEST.read_text(encoding="utf-8"),
        "KMS tests": HTTP_KMS_TEST.read_text(encoding="utf-8"),
        "trust tests": DEVICE_TRUST_TEST.read_text(encoding="utf-8"),
        "feature flags": FEATURE_FLAGS.read_text(encoding="utf-8"),
    }
    required_fragments = {
        "contracts": (
            "data class OfflineLease",
            "val accountIdHash: String",
            "val refreshAfterEpochMillis: Long",
            "val revocationSequence: Long",
            "val maximumUses: Int",
            "data class RemoteRevocationSnapshot",
            "val revokedLeaseIds: Set<String>",
            "val revokedContentIds: Set<ContentId>",
        ),
        "API": (
            "interface OfflineLicenseService",
            "interface RemoteRevocationService",
            "suspend fun issueOfflineLease",
            "suspend fun fetchRevocations",
        ),
        "Vault API": ("OFFLINE_LICENSE(7)",),
        "store": (
            'STORE_DIRECTORY = "offline-licenses"',
            "VaultKeyDomain.OFFLINE_LICENSE",
            "AES/GCM/NoPadding",
            "output.fd.sync()",
            "snapshot.sequence < existing.sequence",
            "existing.revokedLeaseIds + snapshot.revokedLeaseIds",
            "existing.revokedContentIds + snapshot.revokedContentIds",
        ),
        "license service": (
            "SystemClock.elapsedRealtime()",
            "private val mutex = Mutex()",
            "requireEligibleDevice",
            "synchronizeIfDue",
            "clockRollbackToleranceMillis",
            "runtimeBindingProvider.bootCount()",
            "remainingUses = record.remainingUses - 1",
            "val deadline = minOf",
            "MAXIMUM_OFFLINE_LEASE_MILLIS = 24L * 60 * 60 * 1_000",
            "MAXIMUM_CLOCK_ROLLBACK_TOLERANCE_MILLIS = 5L * 60 * 1_000",
        ),
        "revocation service": (
            "val merged = store.applySnapshot",
            "applyRemoteRestriction(DeviceTrustState.STALE",
            "applyRemoteRestriction(DeviceTrustState.REVOKED",
            "vaultKeyService.destroy",
            "deviceShareKeyService.destroy()",
            "store.clearVault(vaultId)",
        ),
        "KMS client": (
            "KMS_PROTOCOL_VERSION = 2",
            '@POST("v1/revocations/snapshot")',
            "verifySignature(requireConfiguration(), revocationResponsePayload(body)",
            "body.sequence < request.knownSequence",
            "MessageDigest.isEqual(nonce, request.requestNonce)",
        ),
        "content service": (
            "OfflineLeaseMode.BOUNDED_OFFLINE -> offlineLicenseService.releaseOrAcquire",
            "offlineLicenseService.revokeContent",
        ),
        "App bindings": (
            "fun offlineLicenseService(): OfflineLicenseService",
            "fun remoteRevocationService(): RemoteRevocationService",
        ),
        "license tests": (
            "survives process service recreation",
            "tampered persisted license fails authentication",
            "use limit is exhausted",
            "wall clock rollback",
            "signed remote device revocation",
        ),
        "KMS tests": (
            "signed bounded offline lease",
            "signed revocation snapshot",
        ),
        "trust tests": ("remote restrictions persist and a revoked device cannot be promoted locally",),
        "feature flags": (
            "ContentProtection(",
            'key = "security.content_protection"',
            "defaultValue = { false }",
            "isFinished = false",
        ),
    }
    for source_name, fragments in required_fragments.items():
        for fragment in fragments:
            if fragment not in sources[source_name]:
                errors.append(f"batch 11 {source_name} is missing invariant {fragment!r}")

    revocation_source = sources["revocation service"]
    if revocation_source.find("store.applySnapshot") > revocation_source.find("deviceTrustService.applyRemoteRestriction"):
        errors.append("batch 11 must persist the signed revocation snapshot before applying device key destruction")


def check_sensitive_paths(errors: list[str]) -> None:
    manifest = json.loads(PATH_MANIFEST.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1:
        errors.append("sensitive path manifest schemaVersion must be 1")
        return

    documented_ids = ids_from_inventory()
    owners = enum_entries(MODELS.read_text(encoding="utf-8"), "SensitiveAssetOwner")
    entries = manifest.get("paths", [])
    registered_tokens = {entry.get("token") for entry in entries}
    if len(registered_tokens) != len(entries):
        errors.append("sensitive path manifest contains duplicate path tokens")

    for entry in entries:
        token = entry.get("token", "")
        source_path = ROOT / entry.get("source", "")
        asset_ids = set(entry.get("assetIds", []))
        if not token:
            errors.append("sensitive path manifest contains an empty token")
        if not asset_ids or not asset_ids.issubset(documented_ids):
            errors.append(f"sensitive path {token!r} has invalid asset ids {sorted(asset_ids)}")
        if entry.get("owner") not in owners:
            errors.append(f"sensitive path {token!r} has unknown owner {entry.get('owner')!r}")
        if not source_path.is_file():
            errors.append(f"sensitive path {token!r} source does not exist: {source_path.relative_to(ROOT)}")
        elif token not in source_path.read_text(encoding="utf-8"):
            errors.append(f"sensitive path {token!r} is no longer declared by {source_path.relative_to(ROOT)}")

    discovered_tokens: set[str] = set()
    for kotlin_file in production_kotlin_files():
        source = kotlin_file.read_text(encoding="utf-8")
        for pattern in STATIC_PATH_PATTERNS:
            discovered_tokens.update(pattern.findall(source))

    unregistered = sorted(discovered_tokens - registered_tokens)
    if unregistered:
        errors.append(f"new static storage paths must be registered in sensitive_paths.json: {unregistered}")


def check_stage_three_security_gate(errors: list[str]) -> None:
    sources = {
        "gate API": STAGE_THREE_GATE_API.read_text(encoding="utf-8"),
        "gate": STAGE_THREE_GATE.read_text(encoding="utf-8"),
        "content API": CONTENT_PROTECTION_API.read_text(encoding="utf-8"),
        "content service": DEFAULT_CONTENT_PROTECTION_SERVICE.read_text(encoding="utf-8"),
        "offline service": OFFLINE_LICENSE_SERVICE.read_text(encoding="utf-8"),
        "recovery tests": CONTENT_PROTECTION_TEST.read_text(encoding="utf-8"),
        "App bindings": APP_BINDINGS.read_text(encoding="utf-8"),
    }
    required_fragments = {
        "gate API": (
            "REMOTE_POLICY_UNAVAILABLE",
            "DEVICE_NOT_TRUSTED",
            "KMS_TRANSPORT_NOT_PINNED",
            "MATRIX_MAPPING_NOT_READY",
            "interface StageThreeSecurityGate",
            "interface MatrixContentProtectionBinding",
        ),
        "gate": (
            "DataProtectionPolicySource.VERIFIED_REMOTE",
            "trust.trustState != DeviceTrustState.TRUSTED",
            "vaultKeyService.isUnlocked",
            "configuration.certificatePinsSha256.isEmpty()",
            "authorizationProvider.authorization() == null",
            "MatrixContentProtectionStatus.NOT_CONNECTED",
            "matrixBinding.recipientsApproved",
        ),
        "content API": (
            "val vaultId: AccountVaultId",
            "suspend fun requireAccessAllowed",
        ),
        "content service": (
            "operation = StageThreeOperation.PROTECT",
            "operation = StageThreeOperation.RELEASE",
            "offlineLicenseService.requireAccessAllowed",
        ),
        "offline service": ("override suspend fun requireAccessAllowed",),
        "recovery tests": (
            "restoring an old KMS backup cannot resurrect locally revoked content",
            "assertThat(kms.releaseCount).isEqualTo(0)",
        ),
        "App bindings": (
            "fun stageThreeSecurityGate(): StageThreeSecurityGate",
            "fun matrixContentProtectionBinding(): MatrixContentProtectionBinding",
        ),
    }
    for source_name, fragments in required_fragments.items():
        for fragment in fragments:
            if fragment not in sources[source_name]:
                errors.append(f"stage-three gate {source_name} is missing invariant {fragment!r}")


def check_management_control(errors: list[str]) -> None:
    sources = {
        "configuration": MANAGEMENT_CONFIGURATION.read_text(encoding="utf-8"),
        "state": MANAGEMENT_STATE.read_text(encoding="utf-8"),
        "service": MANAGEMENT_SERVICE.read_text(encoding="utf-8"),
        "provider": MANAGEMENT_PROVIDER.read_text(encoding="utf-8"),
        "runtime policy": RUNTIME_POLICY.read_text(encoding="utf-8"),
        "clipboard helper": CLIPBOARD_HELPER.read_text(encoding="utf-8"),
        "system clipboard": SYSTEM_CLIPBOARD.read_text(encoding="utf-8"),
        "system share": SYSTEM_UTILS.read_text(encoding="utf-8"),
        "media actions": MEDIA_ACTIONS.read_text(encoding="utf-8"),
        "file share": FILE_SHARE.read_text(encoding="utf-8"),
        "file save": FILE_SAVE.read_text(encoding="utf-8"),
        "screen capture": LOCK_SCREEN_SERVICE.read_text(encoding="utf-8"),
        "coordinator": MANAGEMENT_COORDINATOR.read_text(encoding="utf-8"),
        "manifest": APP_MANIFEST.read_text(encoding="utf-8"),
    }
    required_fragments = {
        "configuration": ('startsWith("https://")', 'certificatePin.startsWith("sha256/")'),
        "state": (
            "Management policy replay or rollback was rejected",
            "Management command replay or rollback was rejected",
            "trustedNow <= safeAdd(state.trustedTimeEpochMillis, policy.maximumOfflineMillis)",
        ),
        "service": ("CertificatePinner.Builder()", "X-YiZhe-Nonce", "signControlStatement"),
        "provider": ("RuntimeDataProtectionPolicy.install(this)", "DataProtectionPolicySource.VERIFIED_REMOTE"),
        "runtime policy": ("AtomicReference<DataProtectionPolicyProvider?>", "allowsClipboard", "allowsExternalShare"),
        "clipboard helper": ("RuntimeDataProtectionPolicy.allowsClipboard()",),
        "system clipboard": ("RuntimeDataProtectionPolicy.allowsClipboard()",),
        "system share": ("RuntimeDataProtectionPolicy.allowsExternalShare()",),
        "media actions": ("allowContentExport", "allowExternalShare"),
        "file share": ("allowExternalShare",),
        "file save": ("allowContentExport",),
        "screen capture": (
            "ScreenCapturePolicy.BLOCK",
            "combine(policyRevision)",
            "WindowManager.LayoutParams.FLAG_SECURE",
            "setRecentsScreenshotEnabled(!blockCapture)",
        ),
        "coordinator": ("ManagementCommandType.LOCK", "ManagementCommandType.REVOKE", "ManagementCommandType.CLEAR_ACCOUNT"),
        "manifest": (
            "io.element.android.yizhe.control.BASE_URL",
            "io.element.android.yizhe.control.SIGNING_KEY_DER_B64",
            "io.element.android.yizhe.control.CERTIFICATE_PIN",
        ),
    }
    for source_name, fragments in required_fragments.items():
        for fragment in fragments:
            if fragment not in sources[source_name]:
                errors.append(f"management control {source_name} is missing invariant {fragment!r}")

    approved_clipboard_boundaries = {CLIPBOARD_HELPER.resolve(), SYSTEM_CLIPBOARD.resolve()}
    approved_share_boundaries = {SYSTEM_UTILS.resolve(), MEDIA_ACTIONS.resolve(), FILE_SHARE.resolve()}
    approved_export_boundaries = {MEDIA_ACTIONS.resolve(), FILE_SAVE.resolve()}
    for kotlin_file in production_kotlin_files():
        source = kotlin_file.read_text(encoding="utf-8")
        if ("setPrimaryClip" in source or "LocalClipboard.current" in source) and kotlin_file.resolve() not in approved_clipboard_boundaries:
            errors.append(f"clipboard access bypasses the managed fail-closed boundary: {kotlin_file.relative_to(ROOT)}")
        if "Intent(Intent.ACTION_SEND)" in source and kotlin_file.resolve() not in approved_share_boundaries:
            errors.append(f"external sharing bypasses the managed fail-closed boundary: {kotlin_file.relative_to(ROOT)}")
        if "MediaStore.Downloads.EXTERNAL_CONTENT_URI" in source and kotlin_file.resolve() not in approved_export_boundaries:
            errors.append(f"content export bypasses the managed fail-closed boundary: {kotlin_file.relative_to(ROOT)}")


def check_secure_media_pipeline(errors: list[str]) -> None:
    sources = {
        "manifest": SECURE_MEDIA_MANIFEST.read_text(encoding="utf-8"),
        "provider": SECURE_MEDIA_PROVIDER.read_text(encoding="utf-8"),
        "loader": SECURE_MEDIA_LOADER.read_text(encoding="utf-8"),
        "factory": MATRIX_CLIENT_FACTORY.read_text(encoding="utf-8"),
        "Coil loader": MATRIX_IMAGE_LOADER.read_text(encoding="utf-8"),
        "voice repo": VOICE_MEDIA_REPO.read_text(encoding="utf-8"),
        "notification repo": NOTIFICATION_MEDIA_REPO.read_text(encoding="utf-8"),
    }
    required_fragments = {
        "manifest": ('android:exported="false"', 'android:grantUriPermissions="true"'),
        "provider": ("createReliablePipe", "read(lease.reference) { Unit }", 'mode != "r"'),
        "loader": (
            "delegate.loadMediaContent(source)",
            "sensitiveFileStore.write",
            "downloaded.fill(0)",
            "protectedMediaService.decrypt",
            "override fun persist(path: String): Boolean = false",
        ),
        "factory": ("maxCacheSize = if (secureFileStoreEnabled) 0uL", '"temp/media"', '"temp/voice"', '"temp/notif"'),
        "Coil loader": (".diskCache(null)",),
    }
    for source_name, fragments in required_fragments.items():
        for fragment in fragments:
            if fragment not in sources[source_name]:
                errors.append(f"secure media pipeline {source_name} is missing invariant {fragment!r}")

    for source_name in ("voice repo", "notification repo"):
        if "downloadMediaFile" not in sources[source_name] or "useCache = false" not in sources[source_name]:
            errors.append(f"secure media pipeline {source_name} must use a non-cached media handle")
        if "temp/voice" in sources[source_name] or "temp/notif" in sources[source_name]:
            errors.append(f"secure media pipeline {source_name} reintroduced a plaintext temp directory")


def check_high_security_downgrade_gate(errors: list[str]) -> None:
    sources = {
        "policy": MODELS.read_text(encoding="utf-8"),
        "policy wire": MANAGEMENT_WIRE_MODELS.read_text(encoding="utf-8"),
        "Matrix adapter": MATRIX_PROTECTED_CONTENT_ADAPTER.read_text(encoding="utf-8"),
        "media loader": SECURE_MEDIA_LOADER.read_text(encoding="utf-8"),
        "Matrix client": RUST_MATRIX_CLIENT.read_text(encoding="utf-8"),
        "call protection": CALL_PROTECTION_SERVICE.read_text(encoding="utf-8"),
    }
    required_fragments = {
        "policy": (
            "val requireContentEnvelope: Boolean = true",
            "val allowRtc: Boolean = false",
            "const val SCHEMA_VERSION = 2",
        ),
        "policy wire": (
            '@SerialName("require_content_envelope")',
            '@SerialName("allow_rtc")',
        ),
        "Matrix adapter": (
            "policyRequiresProtection || featureFlagService.isFeatureEnabled",
            "decryptOrRejectMessage",
            "blockedItem",
            "classifyStrictMatrixMessage",
            "StrictMatrixMessageDecision.Block",
        ),
        "media loader": (
            "requireInnerProtection() && !protected",
            "Matrix media did not contain a YiZhe protected-media frame.",
        ),
        "Matrix client": (
            "requireInnerProtection = { dataProtectionPolicyProvider.current().policy.requireContentEnvelope }",
        ),
        "call protection": (
            "RTC_E2EE_NOT_VERIFIED",
            "if (!policy.allowRtc)",
        ),
    }
    for source_name, fragments in required_fragments.items():
        for fragment in fragments:
            if fragment not in sources[source_name]:
                errors.append(f"high-security downgrade gate {source_name} is missing invariant {fragment!r}")


def check_notifications_logs_and_databases(errors: list[str]) -> None:
    sources = {
        "notification resolver": NOTIFICATION_RESOLVER.read_text(encoding="utf-8"),
        "notification sanitizer": NOTIFICATION_SANITIZER.read_text(encoding="utf-8"),
        "notifiable message": NOTIFIABLE_MESSAGE.read_text(encoding="utf-8"),
        "log configuration": LOG_CONFIGURATION.read_text(encoding="utf-8"),
        "bug reporter": BUG_REPORTER.read_text(encoding="utf-8"),
        "crash initializer": CRASH_INITIALIZER.read_text(encoding="utf-8"),
        "platform initializer": PLATFORM_INITIALIZER.read_text(encoding="utf-8"),
        "database security": DATABASE_SECURITY.read_text(encoding="utf-8"),
        "SQLCipher factory": SQLCIPHER_FACTORY.read_text(encoding="utf-8"),
        "deletion coordinator": DELETION_COORDINATOR.read_text(encoding="utf-8"),
        "deletion journal": DELETION_JOURNAL.read_text(encoding="utf-8"),
        "deletion session cleaner": DELETION_SESSION_CLEANER.read_text(encoding="utf-8"),
        "deletion recovery": DELETION_RECOVERY_INITIALIZER.read_text(encoding="utf-8"),
        "application startup": ELEMENT_APPLICATION.read_text(encoding="utf-8"),
        "App bindings": APP_BINDINGS.read_text(encoding="utf-8"),
        "Matrix client": RUST_MATRIX_CLIENT.read_text(encoding="utf-8"),
        "plaintext migration coordinator": PLAINTEXT_MIGRATION_COORDINATOR.read_text(encoding="utf-8"),
        "plaintext migration journal": PLAINTEXT_MIGRATION_JOURNAL.read_text(encoding="utf-8"),
        "plaintext cleaner": PLAINTEXT_CLEANER.read_text(encoding="utf-8"),
        "plaintext migration initializer": PLAINTEXT_MIGRATION_INITIALIZER.read_text(encoding="utf-8"),
    }
    required_fragments = {
        "notification resolver": (
            "notificationPrivacySanitizer.allowsContent()",
            ".map(notificationPrivacySanitizer::sanitize)",
        ),
        "notification sanitizer": (
            "currentExposure() == NotificationExposure.CONTENT_ALLOWED",
            "exposure == NotificationExposure.SENDER_ONLY",
            "description = genericBody",
        ),
        "notifiable message": (
            "imageUriString = null",
            "imageMimeType = null",
            "roomAvatarPath = null",
            "senderAvatarPath = null",
        ),
        "log configuration": (
            "if (!allowPlaintextDebugLogs) return WriteToFilesConfiguration.Disabled",
            "numberOfFiles = 24",
            "DEBUG_LOG_RETENTION_MILLIS",
        ),
        "bug reporter": (
            "buildMeta.isDebuggable && (withCrashLogs || withDevicesLogs)",
            "buildMeta.isDebuggable && sendPushRules",
            "buildMeta.isDebuggable && withDevicesLogs",
        ),
        "crash initializer": (
            "persistCrashDetails = BuildConfig.DEBUG",
            "if (!BuildConfig.DEBUG)",
            "crashDataStore.reset()",
        ),
        "platform initializer": (
            "writesToLogcat = BuildConfig.DEBUG",
            "allowPlaintextDebugLogs = BuildConfig.DEBUG",
            'if (BuildConfig.DEBUG) "1" else "0"',
        ),
        "database security": (
            "PRAGMA secure_delete = ON",
            "PRAGMA cipher_memory_security = ON",
            "PRAGMA temp_store = MEMORY",
            "PRAGMA wal_checkpoint(TRUNCATE)",
            "cursor.getInt(0) == 0",
        ),
        "SQLCipher factory": ("override fun onOpen", "registerAndHarden(name, db)"),
        "deletion coordinator": (
            "deletionJournal.begin(request)",
            "deletionJournal.update",
            "deletionJournal.complete(entry)",
            "sensitiveFileStore.deleteVault",
            "databaseSecurityMaintenance.checkpointAndTruncate",
            "deletionNotificationCleaner.cancelAll()",
            "resumePending(coldStart: Boolean)",
        ),
        "deletion journal": (
            'JOURNAL_DIRECTORY = "deletion-journal"',
            "output.fd.sync()",
            "MessageDigest.isEqual",
            "WROTE_NON_CONTENT_AUDIT",
            "MAX_COMPLETED_AUDITS = 128",
        ),
        "deletion session cleaner": (
            "sessionStore.removeSession(scope.localSessionId)",
            "candidate.parentFile == parent",
            "file.deleteRecursively()",
        ),
        "deletion recovery": (
            "dataDeletionCoordinator().resumePending(coldStart = true)",
            "PlatformInitializer::class.java",
        ),
        "application startup": (
            "initializeComponent(PlaintextMigrationInitializer::class.java)",
            "initializeComponent(DeletionRecoveryInitializer::class.java)",
        ),
        "App bindings": (
            "fun dataDeletionCoordinator(): DataDeletionCoordinator",
            "fun plaintextMigrationCoordinator(): PlaintextMigrationCoordinator",
        ),
        "Matrix client": (
            'childScope(dispatchers.main, "Matrix session")',
            "deriveAccountVaultId(sessionId.value, deviceId.value)",
            "prepareProtectedAccountDeletion(DeletionReason.ACCOUNT_LOGOUT)",
            "completeProtectedAccountDeletion(requireNotNull(deletionRequest))",
            "accountIdHash = hashAccountId(sessionId.value)",
            "vaultId = accountVaultId.value",
            "confirmedPreconditions = setOf(",
            "DeletionStep.PURGED_MEMORY",
            "dataDeletionCoordinator.prepare(request)",
            "localSessionId = sessionId.value",
            "sessionFilePath = sessionPaths.fileDirectory.path",
        ),
        "plaintext migration coordinator": (
            "LegacyPlaintextTarget.entries",
            "sensitiveFileStore.runMaintenance()",
            "journal.complete(state)",
            "PlaintextMigrationStep.SECURE_MEDIA_CUTOVER",
        ),
        "plaintext migration journal": (
            'JOURNAL_DIRECTORY = "plaintext-migration"',
            "output.fd.sync()",
            "MessageDigest.isEqual",
            'COMPLETED_FILE_NAME = "complete.yzpm"',
        ),
        "plaintext cleaner": (
            '"temp/media"',
            '"temp/voice"',
            '"temp/notif"',
            '"uploads"',
            '"edited-media"',
            '"voice_recordings"',
            '"logs"',
            '"screenshot.png"',
            "target.toPath().startsWith(safeRoot.toPath())",
        ),
        "plaintext migration initializer": (
            "plaintextMigrationCoordinator().migrate()",
            "check(!report.retryRequired)",
        ),
    }
    for source_name, fragments in required_fragments.items():
        for fragment in fragments:
            if fragment not in sources[source_name]:
                errors.append(f"batch 3 {source_name} is missing invariant {fragment!r}")

    if 'database.execSQL("PRAGMA' in sources["database security"]:
        errors.append("SQLCipher PRAGMAs must use the query path; execSQL crashes for row-returning PRAGMAs")
    if 'CoroutineName("Session-$sessionId")' in sources["Matrix client"] or '"Session-$sessionId"' in sources["Matrix client"]:
        errors.append("Matrix session coroutine names must not expose the account identifier to Android crash logs")


def main() -> None:
    errors: list[str] = []
    check_catalog(errors)
    check_wiring_and_flags(errors)
    check_http_logging(errors)
    check_secure_file_store(errors)
    check_device_root_key(errors)
    check_account_vault(errors)
    check_device_trust_and_uninstall(errors)
    check_offline_license_and_remote_revocation(errors)
    check_stage_three_security_gate(errors)
    check_management_control(errors)
    check_sensitive_paths(errors)
    check_secure_media_pipeline(errors)
    check_high_security_downgrade_gate(errors)
    check_notifications_logs_and_databases(errors)
    if errors:
        fail(errors)
    print("OK | security modules, management control/DLP, stage-three fail-closed gate, device root/account Vault keys, device trust/uninstall binding, offline licenses/revocation, secure storage/media, plaintext migration, notification privacy, release logging, databases, deletion, flags, ownership, and paths")


if __name__ == "__main__":
    main()
