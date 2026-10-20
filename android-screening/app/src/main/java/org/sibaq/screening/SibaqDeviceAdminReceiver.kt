package org.sibaq.screening

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class SibaqDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "UNINSTALL PROTECTION ACTIVE: Deactivating Device Admin or uninstalling SIBAQ Lab Screen Monitor requires entering the secret Admin AppLock PIN inside the app."
    }
}
