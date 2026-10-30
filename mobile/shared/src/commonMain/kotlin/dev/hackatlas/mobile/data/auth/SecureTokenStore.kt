package dev.hackatlas.mobile.data.auth

/** One normalized API base URL, never settings or guest storage. Failures must throw SecureStorageException. */
interface SecureTokenStore {
    val apiBaseUrl: String
    suspend fun read(): StoredSession?
    suspend fun write(session: StoredSession)
    suspend fun clear()
}
