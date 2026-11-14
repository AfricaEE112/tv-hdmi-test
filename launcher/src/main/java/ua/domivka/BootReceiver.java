package ua.domivka;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Повне ввімкнення телевізора (холодний старт): ті самі перевірки, що й після режиму очікування. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pending = goAsync();
        App.schedule(context, pending::finish);
    }
}
