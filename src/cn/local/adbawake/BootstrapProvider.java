package cn.local.adbawake;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;

public final class BootstrapProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    public Bundle call(String method, String arg, Bundle extras) {
        // The provider permission alone is not applied to call() on every Android release.
        if (Binder.getCallingUid() != 2000 && Binder.getCallingUid() != android.os.Process.myUid())
            throw new SecurityException("Only ADB shell may bootstrap");
        if ("refresh".equals(method)) {
            long identity = Binder.clearCallingIdentity();
            try {
                if (arg != null) Client.prefs(getContext()).edit().putString("mode", arg).apply();
                android.service.quicksettings.TileService.requestListeningState(getContext(), new ComponentName(getContext(), AwakeTile.class));
            } finally { Binder.restoreCallingIdentity(identity); }
            return Bundle.EMPTY;
        }
        if (!"bootstrap".equals(method)) throw new IllegalArgumentException("Unknown method");
        Bundle b = new Bundle(); b.putString("token", Client.token(getContext()));
        b.putString("mode", Client.prefs(getContext()).getString("mode", "auto")); return b;
    }
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    public String getType(Uri u) { return null; }
    public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
