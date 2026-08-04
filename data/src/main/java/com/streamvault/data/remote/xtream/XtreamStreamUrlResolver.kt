package com.streamvault.data.remote.xtream

import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedStreamUrl(
    val url: String,
    val expirationTime: Long? = null
)

@Singleton
class XtreamStreamUrlResolver @Inject constructor(
    private val providerDao: ProviderDao,
    private val credentialCrypto: CredentialCrypto
) {
    fun isInternalStreamUrl(url: String?): Boolean = XtreamUrlFactory.isInternalStreamUrl(url)

    suspend fun resolve(
        url: String,
        fallbackProviderId: Long? = null,
        fallbackStreamId: Long? = null,
        fallbackContentType: ContentType? = null,
        fallbackContainerExtension: String? = null
    ): String? = resolveWithMetadata(
        url = url,
        fallbackProviderId = fallbackProviderId,
        fallbackStreamId = fallbackStreamId,
        fallbackContentType = fallbackContentType,
        fallbackContainerExtension = fallbackContainerExtension
    )?.url

    suspend fun resolveWithMetadata(
        url: String,
        fallbackProviderId: Long? = null,
        fallbackStreamId: Long? = null,
        fallbackContentType: ContentType? = null,
        fallbackContainerExtension: String? = null
    ): ResolvedStreamUrl? {
        if (url.isNotBlank() && !XtreamUrlFactory.isInternalStreamUrl(url)) {
            return ResolvedStreamUrl(
                url = url,
                expirationTime = extractStreamExpirationTime(url)
            )
        }

        val token = XtreamUrlFactory.parseInternalStreamUrl(url)
        val providerId = token?.providerId ?: fallbackProviderId?.takeIf { it > 0 } ?: return null
        val provider = providerDao.getById(providerId) ?: return null
        if (provider.type != ProviderType.XTREAM_CODES) {
            return url.takeIf { it.isNotBlank() }?.let { passthroughUrl ->
                ResolvedStreamUrl(
                    url = passthroughUrl,
                    expirationTime = extractStreamExpirationTime(passthroughUrl)
                )
            }
        }

        val kind = token?.kind ?: fallbackContentType?.let(XtreamUrlFactory::kindForContentType) ?: return null
        val streamId = token?.streamId ?: fallbackStreamId?.takeIf { it > 0 } ?: return null
        // Pour le LIVE, on force le conteneur MPEG-TS et on ignore le directSource HLS.
        // Les endpoints HLS (.m3u8) Xtream sont régulièrement dégénérés (manifest figé,
        // séquence à 0, segments manquants) → Media3 stall sur l'écran vert. Le TS
        // direct est un flux continu fiable, c'est ce qu'utilisent les clients IPTV
        // de référence (TiviMate, MyTVOnline, etc.).
        val ext = if (kind == XtreamStreamKind.LIVE) {
            "ts"
        } else {
            token?.containerExtension ?: fallbackContainerExtension
        }
        val directSource = if (kind == XtreamStreamKind.LIVE) {
            null
        } else {
            token?.directSource?.takeIf(UrlSecurityPolicy::isAllowedStreamEntryUrl)
        }
        val decryptedPassword = credentialCrypto.decryptIfNeeded(provider.password)

        val fallbackResolvedUrl = XtreamUrlFactory.buildPlaybackUrl(
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = decryptedPassword,
            kind = kind,
            streamId = streamId,
            containerExtension = ext
        )
        val resolvedUrl = directSource ?: fallbackResolvedUrl

        return ResolvedStreamUrl(
            url = resolvedUrl,
            expirationTime = extractStreamExpirationTime(resolvedUrl)
        )
    }
}

internal fun extractStreamExpirationTime(url: String): Long? {
    val query = runCatching { URI(url).rawQuery }.getOrNull()
        ?: url.substringAfter('?', missingDelimiterValue = "").takeIf { it.isNotBlank() }
        ?: return null

    val expirationKeys = setOf(
        "expire",
        "expires",
        "expiry",
        "expiration",
        "expires_at",
        "exp",
        "token_exp",
        "token_expires",
        "token_expiry"
    )

    return query.split('&')
        .asSequence()
        .mapNotNull { part ->
            val key = part.substringBefore('=', missingDelimiterValue = "")
                .lowercase()
                .takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (key !in expirationKeys) return@mapNotNull null

            val rawValue = part.substringAfter('=', missingDelimiterValue = "")
            val decodedValue = XtreamUrlCodec.decode(rawValue)
            parseXtreamExpirationDate(decodedValue)
        }
        .firstOrNull()
}
