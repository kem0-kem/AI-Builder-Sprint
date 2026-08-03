# Authentication session security

SlowTalk stores the access and refresh token pair as one immutable session value. The
production store uses AndroidX Security Crypto `EncryptedSharedPreferences` with an
AES-256-GCM Android Keystore master key. This choice keeps the storage API small while
ensuring token values are not written as plain application preferences. The former plain
preference file is cleared during migration, and Android application backup is disabled so
credentials cannot be copied into device or cloud backups.

The process restores encrypted storage once in `SlowTalkApplication` and then serves token
reads from an in-memory `StateFlow`; OkHttp interceptors never perform disk I/O. Debug HTTP
logging uses BASIC level, which excludes request and response bodies and all headers.

The signup screen currently has no separate nickname field. Until one is added, the
normalized username is sent as both `username` and `nickname`. Username validation follows
the backend contract: lowercase ASCII letters, digits, and underscore, 3 to 30 characters.
