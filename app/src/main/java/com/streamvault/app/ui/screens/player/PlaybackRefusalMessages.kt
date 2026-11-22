package com.streamvault.app.ui.screens.player

import com.streamvault.domain.model.ContentType
import com.streamvault.player.PlayerError

/**
 * Messages affichés quand le fournisseur refuse un flux (sonde pré-lecture ou
 * erreur moteur portant un statut HTTP).
 *
 * 403 : le nginx du fournisseur répond 403 quand la limite de connexions
 * simultanées de la ligne est atteinte (autre box, onglet web, lecteur resté
 * ouvert). C'est la cause la plus fréquente et la seule que l'utilisateur peut
 * régler lui-même, donc on la nomme en premier — l'ancien message affirmait
 * « votre abonnement n'inclut probablement pas les séries », souvent faux.
 */
object PlaybackRefusalMessages {

    fun forHttpCode(httpCode: Int, contentType: ContentType): String = when (httpCode) {
        403 -> {
            val what = when (contentType) {
                ContentType.SERIES_EPISODE -> "cet épisode"
                ContentType.MOVIE -> "ce film"
                else -> "ce flux"
            }
            "Le fournisseur refuse $what (HTTP 403). Cause la plus fréquente : la limite de " +
                "connexions simultanées de votre abonnement est atteinte. Fermez les autres " +
                "lecteurs qui utilisent ce compte (autre appareil, onglet web) puis réessayez. " +
                "Si rien d'autre ne lit, contactez votre fournisseur."
        }
        401 ->
            "Le fournisseur refuse vos identifiants (HTTP 401) : abonnement expiré ou " +
                "suspendu. Contactez votre fournisseur."
        404 ->
            "Ce contenu est introuvable chez le fournisseur (HTTP 404), " +
                "y compris sous les formats alternatifs."
        else ->
            "Le serveur du fournisseur est en erreur pour ce flux (HTTP $httpCode). " +
                "Réessayez plus tard."
    }

    /** Message de refus si [error] porte un 401/403 amont, sinon null (mapping générique). */
    fun forError(error: PlayerError, contentType: ContentType): String? {
        val code = (error as? PlayerError.NetworkError)?.httpCode ?: return null
        return if (code == 401 || code == 403) forHttpCode(code, contentType) else null
    }
}
