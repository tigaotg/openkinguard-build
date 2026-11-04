package io.github.tigaotg.openkinguard

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.tigaotg.openkinguard.security.protocol.PairingQrProtocol

// Legacy QR fallback only. Callbacks intentionally format messages with their Activity's
// current Context; the active app uses S1CompactSection rather than this fallback screen.
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun PairingSection() {
    val context = LocalContext.current
    val manager = remember { LocalPairingManager(context.applicationContext) }
    var snapshot by remember { mutableStateOf(runCatching { manager.snapshot() }.getOrDefault(PairingSnapshot())) }
    var result by remember { mutableStateOf("") }

    fun execute(success: String, action: () -> PairingSnapshot) {
        try {
            snapshot = action()
            runCatching { S1CaseStore(context.applicationContext).recordHandshake(
                "扫码备用", snapshot.session?.phase?.name ?: "修改关系", "",
                "本机操作", success) }
            result = success
        } catch (error: Exception) {
            result = context.getString(R.string.pairing_error, error.message ?: error.javaClass.simpleName)
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { scan ->
        if (scan.contents != null) execute(context.getString(R.string.pairing_scanned)) {
            manager.scan(scan.contents)
        }
    }
    val session = snapshot.session
    val nextQr = when (session?.phase) {
        PairingPhase.WAIT_RESPONSE -> session.invite
        PairingPhase.WAIT_GRANT -> session.response
        PairingPhase.WAIT_ACK -> session.grant
        PairingPhase.ACK_TO_SHOW -> session.ack
        else -> null
    }
    val peerQr = when (session?.phase) {
        PairingPhase.REVIEW_INVITE, PairingPhase.REVIEW_GRANT -> session.invite
        PairingPhase.REVIEW_RESPONSE -> session.response
        else -> null
    }
    val peerFingerprint = remember(peerQr) {
        peerQr?.let { runCatching {
            PairingQrProtocol.decodeAndVerify(it, System.currentTimeMillis())
                .frame.senderFingerprint.take(24)
        }.getOrNull() }
    }

    Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.pairing_warning), color = MaterialTheme.colorScheme.error)
    Text(stringResource(R.string.pairing_direction))
    if (session == null) {
        Button(onClick = { execute(context.getString(R.string.pairing_invite_created), manager::createInvitation) }) {
            Text(stringResource(R.string.pairing_create_invite))
        }
        Button(onClick = {
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setCaptureActivity(PortraitQrCaptureActivity::class.java)
                .setOrientationLocked(true)
                .setPrompt(context.getString(R.string.pairing_scan_prompt)).setBeepEnabled(false))
        }) { Text(stringResource(R.string.pairing_scan_invite)) }
    } else {
        Text("当前绑定步骤：${pairingPhaseName(session.phase)}")
        val promptId = when (session.phase) {
            PairingPhase.WAIT_RESPONSE -> R.string.pairing_wait_response
            PairingPhase.REVIEW_INVITE -> R.string.pairing_review_invite
            PairingPhase.WAIT_GRANT -> R.string.pairing_wait_grant
            PairingPhase.REVIEW_RESPONSE -> R.string.pairing_review_response
            PairingPhase.WAIT_ACK -> R.string.pairing_wait_ack
            PairingPhase.REVIEW_GRANT -> R.string.pairing_review_grant
            PairingPhase.ACK_TO_SHOW -> R.string.pairing_ack_to_show
            PairingPhase.COMPLETE -> R.string.pairing_complete
        }
        Text(stringResource(promptId))
        if (peerFingerprint != null) Text(stringResource(R.string.pairing_peer_fingerprint, peerFingerprint))
        if (nextQr != null) {
            val bitmap = remember(nextQr) { runCatching { qrBitmap(nextQr) }.getOrNull() }
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), stringResource(R.string.pairing_qr_description),
                    modifier = Modifier.size(285.dp))
            } else Text(stringResource(R.string.pairing_qr_error))
        }
        if (session.phase in listOf(PairingPhase.WAIT_RESPONSE, PairingPhase.WAIT_GRANT,
                PairingPhase.WAIT_ACK)) {
            Button(onClick = {
                scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setCaptureActivity(PortraitQrCaptureActivity::class.java)
                    .setOrientationLocked(true)
                    .setPrompt(context.getString(R.string.pairing_scan_prompt)).setBeepEnabled(false))
            }) { Text(stringResource(R.string.pairing_scan_reply)) }
        }
        if (session.phase in listOf(PairingPhase.REVIEW_INVITE, PairingPhase.REVIEW_RESPONSE,
                PairingPhase.REVIEW_GRANT)) {
            key(session.phase) {
                var verifiedTogether by remember { mutableStateOf(false) }
                Row {
                    Checkbox(checked = verifiedTogether, onCheckedChange = { verifiedTogether = it })
                    Text(stringResource(R.string.pairing_fingerprint_verified))
                }
                Button(enabled = verifiedTogether,
                    onClick = { execute(context.getString(R.string.pairing_confirmed), manager::confirm) }) {
                    Text(stringResource(R.string.pairing_confirm))
                }
            }
        }
        if (session.phase == PairingPhase.ACK_TO_SHOW) {
            Button(onClick = { execute(context.getString(R.string.pairing_complete),
                manager::finishHelperAfterAckShown) }) {
                Text(stringResource(R.string.pairing_subject_finished))
            }
        }
        OutlinedButton(onClick = { execute(context.getString(R.string.pairing_cancelled),
            manager::cancelPending) }) { Text(stringResource(R.string.pairing_reset)) }
    }
    if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider()
    Text(stringResource(R.string.pairing_saved_peers, snapshot.peers.count { it.active }))
    snapshot.peers.forEach { peer ->
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${if (peer.role == PairingRole.SUBJECT) "我是被救助者 · 这是我绑定的救助者" else "我是救助者 · 这是我帮助的被救助者"} · ${peer.peerFingerprint.take(24)}…")
                Text(if (peer.active) stringResource(R.string.pairing_local_record)
                else stringResource(R.string.pairing_revoked))
                if (peer.active) OutlinedButton(onClick = {
                    execute(context.getString(R.string.pairing_revoked_notice)) {
                        manager.revoke(peer.relationId)
                    }
                }) { Text(stringResource(R.string.pairing_revoke)) }
            }
        }
    }
}

private fun qrBitmap(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 520, 520)
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
        pixels[y * matrix.width + x] = if (matrix.get(x, y)) android.graphics.Color.BLACK
            else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
