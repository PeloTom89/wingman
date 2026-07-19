package com.pelotom89.wingman.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Trampoline activity that makes Wingman a system-registered handler for the RC-N3's
 * USB (AOA) accessory attach event. Mirrors `UsbAttachActivity` in DJI's official V5
 * sample (`dji.sampleV5.aircraft.UsbAttachActivity`), which is declared with an
 * `android.hardware.usb.action.USB_ACCESSORY_ATTACHED` intent-filter plus the
 * `@xml/accessory_filter` meta-data that ships inside the `dji-sdk-v5-aircraft` AAR
 * (models T600 / HG210 / WM160 / com.dji.logiclink / com.dji.link, manufacturer DJI).
 *
 * Why this matters beyond "which app auto-launches on plug-in":
 *  1. An app chosen via the accessory-attach chooser is granted USB-accessory
 *     permission *implicitly* — no runtime "Allow Wingman to access…?" dialog, and no
 *     dependence on MSDK's internal `UsbManager.requestPermission` fallback (see
 *     `dji.sdk.datalink.usb.DJIUsbAccessoryReceiver`, disassembled from the real
 *     5.18.0 jar: it polls `getAccessoryList()` and fires `requestPermission` itself
 *     only as a fallback when it lacks permission).
 *  2. Without this filter, DJI Fly is the *only* matching handler on the phone, so
 *     Android auto-launches DJI Fly (with implicit permission) on every physical
 *     plug-in. A DJI Fly process that has touched the RC leaves the RC firmware in a
 *     state where an MSDK app cannot bring the aircraft's flight-controller link up
 *     itself — DJI's own support calls this out (Mobile-SDK-Android-V5 issue #427:
 *     "the port of MSDK will be preempted by the DJI [Fly/Pilot] app … caused by the
 *     design of the remote control firmware"; their own sample exhibits it too).
 *     Installing a second matching handler also makes Android drop DJI Fly's
 *     "open by default" association for the accessory, so DJI Fly stops being
 *     silently launched into the middle of every Wingman session.
 *
 * DJI's sample forwards with NEW_TASK|CLEAR_TASK; we deliberately use
 * CLEAR_TOP|SINGLE_TOP instead so a cable blip mid-session re-delivers to the
 * existing singleTop MainActivity rather than tearing the whole task down.
 */
class UsbAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        finish()
    }
}
