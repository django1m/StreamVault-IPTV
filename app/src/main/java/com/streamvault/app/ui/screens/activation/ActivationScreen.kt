package com.streamvault.app.ui.screens.activation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import com.streamvault.app.ui.design.TvInitialFocus
import com.streamvault.app.vpn.VpnDisplayPhase
import com.streamvault.app.work.HeartbeatWorker
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.ui.design.AppColors

@Composable
fun ActivationScreen(
    onActivated: () -> Unit,
    viewModel: ActivationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.start() }

    LaunchedEffect(state) {
        if (state is ActivationState.Ready) {
            HeartbeatWorker.schedule(context)
            onActivated()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.22f),
                        AppColors.HeroTop,
                        AppColors.HeroBottom,
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            colors = SurfaceDefaults.colors(containerColor = AppColors.Surface.copy(alpha = 0.92f)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (val s = state) {
                    is ActivationState.Loading -> {
                        CircularProgressIndicator(color = AppColors.Brand)
                        Spacer(Modifier.height(18.dp))
                        Text("Initialisation…", color = AppColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
                    }

                    is ActivationState.WaitingForCode -> {
                        Text(
                            "Activation requise",
                            color = AppColors.TextPrimary,
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = s.code,
                            color = AppColors.Brand,
                            fontSize = 64.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 6.sp,
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "Saisissez ce code dans la console\nFuzionTV pour activer cet appareil.",
                            color = AppColors.TextSecondary,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        CircularProgressIndicator(color = AppColors.Brand)
                    }

                    is ActivationState.Syncing -> {
                        CircularProgressIndicator(color = AppColors.Brand)
                        Spacer(Modifier.height(18.dp))
                        Text("Synchronisation des chaînes…", color = AppColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
                    }

                    is ActivationState.Ready -> {
                        Text("Prêt !", color = AppColors.Success, style = MaterialTheme.typography.headlineMedium)
                    }

                    is ActivationState.Disabled -> {
                        Text("Appareil désactivé", color = AppColors.Live, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            s.reason ?: "Contactez l'administrateur pour le réactiver.",
                            color = AppColors.TextSecondary,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                    }

                    is ActivationState.VpnPending -> {
                        CircularProgressIndicator(color = AppColors.Brand)
                        Spacer(Modifier.height(18.dp))
                        Text(
                            if (s.phase == VpnDisplayPhase.VERIFYING) "Vérification du tunnel sécurisé…"
                            else "Connexion au VPN sécurisé…",
                            color = AppColors.TextPrimary,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Votre connexion est en cours de sécurisation.\nCela peut prendre jusqu'à deux minutes.",
                            color = AppColors.TextSecondary,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                    }

                    is ActivationState.VpnConsent -> {
                        Text(
                            if (s.denied) "Autorisation VPN refusée" else "Autorisation VPN requise",
                            color = AppColors.Live,
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (s.denied)
                                "FuzionTV a besoin du VPN pour fonctionner sur cette box.\nAppuyez sur Réessayer, puis acceptez la demande Android."
                            else
                                "Android demande votre autorisation pour activer la connexion sécurisée FuzionTV.\nSélectionnez OK dans la fenêtre affichée.",
                            color = AppColors.TextSecondary,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        val consentFocus = remember { FocusRequester() }
                        TvInitialFocus(consentFocus)
                        Button(
                            onClick = { viewModel.retryVpn() },
                            modifier = Modifier.focusRequester(consentFocus),
                        ) {
                            Text(if (s.denied) "Réessayer" else "Afficher la demande")
                        }
                    }

                    is ActivationState.VpnFailed -> {
                        Text(
                            if (s.terminal) "VPN bloqué — intervention requise" else "Connexion VPN impossible",
                            color = AppColors.Live,
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (s.terminal)
                                "La connexion sécurisée a échoué de façon répétée.\nContactez votre revendeur pour débloquer cet appareil."
                            else
                                "Le tunnel sécurisé n'a pas pu être établi.\nUne nouvelle tentative automatique aura lieu dans quelques minutes.",
                            color = AppColors.TextSecondary,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        val vpnRetryFocus = remember { FocusRequester() }
                        TvInitialFocus(vpnRetryFocus)
                        Row {
                            Button(
                                onClick = { viewModel.retryVpn() },
                                modifier = Modifier.focusRequester(vpnRetryFocus),
                            ) {
                                Text("Réessayer")
                            }
                            if (!s.terminal) {
                                Spacer(Modifier.width(16.dp))
                                Button(onClick = { viewModel.continueWithoutVpn() }) {
                                    Text("Continuer sans VPN")
                                }
                            }
                        }
                        if (!s.terminal) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Certains contenus risquent d'être indisponibles sans VPN.",
                                color = AppColors.TextTertiary,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    is ActivationState.Failed -> {
                        val title = when (s.kind) {
                            ActivationFailureKind.NETWORK -> "Pas de connexion Internet"
                            ActivationFailureKind.SERVER -> "Service momentanément indisponible"
                            ActivationFailureKind.SUBSCRIPTION_EXPIRED -> "Abonnement expiré"
                            ActivationFailureKind.TOKEN_RESET_NEEDED -> "Appareil à réassocier"
                            ActivationFailureKind.UNKNOWN -> "Erreur"
                        }
                        // Le compte à rebours n'est affiché que si le ViewModel en a
                        // réellement programmé un : les échecs qui exigent un humain
                        // (abonnement expiré, réassociation) ne repartent jamais seuls
                        // et ne doivent pas promettre une nouvelle tentative.
                        val countdown = s.retryInSeconds?.let { "\nNouvelle tentative dans $it s…" }.orEmpty()
                        val message = when (s.kind) {
                            ActivationFailureKind.NETWORK ->
                                "La box n'arrive pas à joindre Internet.\nVérifiez le câble réseau ou le Wi-Fi." + countdown
                            ActivationFailureKind.SERVER ->
                                "Le service FuzionTV est momentanément indisponible." + countdown
                            ActivationFailureKind.SUBSCRIPTION_EXPIRED ->
                                "Votre abonnement a expiré ou cet appareil n'est pas associé.\nContactez votre revendeur."
                            ActivationFailureKind.TOKEN_RESET_NEEDED ->
                                "Cet appareil doit être réassocié.\nContactez votre revendeur pour une réinitialisation."
                            ActivationFailureKind.UNKNOWN ->
                                "Une erreur inattendue s'est produite.\nContactez votre revendeur si le problème persiste." + countdown
                        }
                        Text(title, color = AppColors.Live, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            message,
                            color = AppColors.TextSecondary,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        val retryFocus = remember { FocusRequester() }
                        TvInitialFocus(retryFocus)
                        Button(
                            onClick = { viewModel.retry() },
                            modifier = Modifier.focusRequester(retryFocus),
                        ) {
                            Text("Réessayer")
                        }
                        // Technical detail kept small for phone support.
                        s.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                            Spacer(Modifier.height(16.dp))
                            Text(
                                detail.take(120),
                                color = AppColors.TextTertiary,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}
