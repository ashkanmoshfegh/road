import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import org.osmdroid.tileprovider.IRegisterReceiver

class CustomRegisterReceiver(private val context: Context) : IRegisterReceiver {
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): android.content.Intent? {
        return context.registerReceiver(receiver, filter)
    }

    override fun unregisterReceiver(receiver: BroadcastReceiver?) {
        context.unregisterReceiver(receiver)
    }

    override fun destroy() {
        // No resources to clean up in this simple wrapper.
        // If you had any, release them here.
    }
}