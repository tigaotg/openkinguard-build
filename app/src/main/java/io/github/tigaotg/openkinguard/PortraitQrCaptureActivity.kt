package io.github.tigaotg.openkinguard

import android.content.pm.ActivityInfo
import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/** The default scanner activity can become landscape on some large-screen devices. */
class PortraitQrCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)
    }

    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_portrait_qr_capture)
        return findViewById(R.id.zxing_barcode_scanner)
    }
}
