package cn.local.adbawake;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;

final class Client {
    static final int PORT = 47687;
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("state", 0); }
    static synchronized String token(Context c) {
        String t = prefs(c).getString("token", null);
        if (t == null) {
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            StringBuilder b = new StringBuilder(); for(byte v: bytes) b.append(String.format("%02x", v & 255));
            t = b.toString(); prefs(c).edit().putString("token", t).commit();
        }
        return t;
    }
    static JSONObject request(Context c, String command) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 600); s.setSoTimeout(command.equals("WIFI")?20000:4000);
            Writer w = new OutputStreamWriter(s.getOutputStream(), "UTF-8");
            w.write(token(c) + " " + command + "\n"); w.flush();
            String line = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8")).readLine();
            if (line == null) throw new IOException("辅助进程没有响应");
            JSONObject result = new JSONObject(line);
            if (result.has("error")) throw new IOException(result.getString("error"));
            return result;
        }
    }
    static JSONObject mode(Context c, String mode) throws Exception {
        JSONObject result = request(c, "MODE:" + mode);
        prefs(c).edit().putString("mode", mode).apply();
        android.service.quicksettings.TileService.requestListeningState(c,
                new android.content.ComponentName(c, AwakeTile.class));
        return result;
    }
}
