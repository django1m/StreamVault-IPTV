package com.streamvault.app.vpn

/**
 * Canal par lequel le PLAN DE CONTRÔLE (activation, /credentials, login panel)
 * signale au superviseur VPN que le fournisseur a refusé la requête en 403.
 *
 * Les signaux de santé VPN existants ([VpnManager.reportEnforcedOnDemand403],
 * [VpnManager.reportLive403], [VpnManager.reportLivePlaybackSuccess]) viennent
 * TOUS de la lecture : ils n'existent qu'une fois la box activée et un contenu
 * lancé. Une box dont le login panel est refusé n'en produit aucun — elle boucle
 * sur l'écran d'activation sans jamais demander la rotation d'IP de sortie qui la
 * sauverait. Cette interface ferme ce trou sans faire dépendre l'écran
 * d'activation du [VpnManager] concret (7 tests JVM le construisent à la main).
 */
interface VpnPanelBlockReporter {
    /**
     * Le login `player_api` du fournisseur a répondu 403.
     *
     * À n'appeler QUE pour un refus du PANEL : un 403 du backend FuzionTV
     * (appareil désactivé, abonnement expiré) ne dit rien de l'IP de sortie et ne
     * doit jamais déclencher de rotation. L'implémentation ignore le signal si le
     * tunnel n'est pas vérifié — sans tunnel, un 403 est le blocage résidentiel
     * attendu, pas une sortie brûlée.
     */
    fun reportPanelLogin403()
}
