package cn.local.adbawake;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView state, detail;
    private boolean visible;
    private int dp(int n) { return (int)(getResources().getDisplayMetrics().density*n); }
    private TextView text(String s,int size,int color) {
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(8),0,dp(8));return t;
    }
    private void button(LinearLayout parent,String label,Runnable action) {
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(Color.rgb(15,30,40));
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(113,226,206));bg.setCornerRadius(dp(14));b.setBackground(bg);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));p.topMargin=dp(10);parent.addView(b,p);b.setOnClickListener(v->action.run());
    }
    public void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(24),dp(18),dp(24),dp(30));scroll.addView(root);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;
        });
        root.addView(text("ADB 常亮",30,Color.WHITE));
        root.addView(text("电脑连上，屏幕保持亮着。",16,0xffa6b9ca));
        state=text("正在读取状态…",23,0xff71e2ce);root.addView(state);
        detail=text("",14,0xffc3d0dc);root.addView(detail);
        button(root,"自动 · 仅无线 ADB 连接时常亮",()->setMode("auto"));
        button(root,"强制常亮 · 与连接无关",()->setMode("on"));
        button(root,"关闭常亮 · 恢复正常熄屏",()->setMode("off"));
        root.addView(text("快捷入口",20,Color.WHITE));
        button(root,"开发者选项 / 一键启用",()->command("DEV"));
        button(root,"无线调试设置",()->command("WIFI"));
        button(root,"添加「ADB 常亮」磁贴",()->addTile(AwakeTile.class,"ADB 常亮",R.drawable.ic_sun));
        button(root,"添加「开发者选项」磁贴",()->addTile(DeveloperTile.class,"开发者选项",R.drawable.ic_tools));
        button(root,"添加「无线调试」磁贴",()->addTile(WirelessTile.class,"无线调试",R.drawable.ic_wifi));
        root.addView(text("常亮磁贴：点击切换自动 / 关闭，长按进入应用。\n手动按电源键仍可锁屏；不会替你解锁。\n手机重启后，电脑工具会在 ADB 重连时启动辅助进程。",13,0xffa6b9ca));
        String shortcut=getIntent().getStringExtra("shortcut");if(shortcut!=null) command(shortcut);
    }
    private void addTile(Class<?> type,String label,int icon) {
        if(Build.VERSION.SDK_INT>=33) {
            StatusBarManager m=getSystemService(StatusBarManager.class);
            m.requestAddTileService(new ComponentName(this,type),label,android.graphics.drawable.Icon.createWithResource(this,icon),getMainExecutor(),result->{
                Toast.makeText(this,result==2?"磁贴已添加":"可在快捷设置编辑页面查看磁贴",Toast.LENGTH_SHORT).show();
            });
        }
    }
    private void setMode(String mode) {
        io.execute(()->{try {JSONObject s=Client.mode(this,mode);ui.post(()->render(s));}catch(Exception e){ui.post(()->unavailable(e));}});
    }
    private void command(String command) {
        io.execute(()->{
            if(("DEV".equals(command)||"WIFI".equals(command)) && checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")==android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    if(!Settings.Global.putInt(getContentResolver(),"development_settings_enabled",1))throw new IllegalStateException("开发者选项未能启用");
                    if("DEV".equals(command)) {
                        ui.post(()->startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)));
                        return;
                    }
                }catch(Exception e){ui.post(()->Toast.makeText(this,"启用开发者选项失败："+e.getMessage(),Toast.LENGTH_LONG).show());return;}
            }
            try {Client.request(this,command);}catch(Exception e){ui.post(()->{
            if("DEV".equals(command)||"WIFI".equals(command)) {
                try {
                    if(Settings.Global.getInt(getContentResolver(),"development_settings_enabled",0)==0) {
                        Toast.makeText(this,"缺少一次性 ADB 设置授权，暂时不能自动启用开发者选项",Toast.LENGTH_LONG).show();return;
                    }
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    if("WIFI".equals(command))Toast.makeText(this,"辅助进程未连接，请在此点击无线调试",Toast.LENGTH_LONG).show();
                }
                catch(Exception ex) {unavailable(e);}
            } else unavailable(e);
        });}});
    }
    private void unavailable(Exception e) {
        state.setText("辅助进程未就绪");detail.setText("请让电脑通过无线 ADB 连接，并运行配套启动工具。\n"+e.getMessage());
    }
    private void render(JSONObject s) {
        String mode=s.optString("mode");boolean connected=s.optBoolean("connected"),held=s.optBoolean("held"),awake=s.optBoolean("interactive");
        String title=held?(awake?"屏幕常亮中":"已手动锁屏"):("off".equals(mode)?"常亮已关闭":"等待无线 ADB 连接");
        state.setText(title);
        detail.setText("模式："+("auto".equals(mode)?"自动":"on".equals(mode)?"强制常亮":"关闭")+"\n无线 ADB："+(connected?"已连接":"未连接")+"\n亮屏锁："+(held?"已持有":"已释放")+"\n"+s.optString("message",""));
    }
    private final Runnable poll=new Runnable(){public void run(){if(!visible)return;io.execute(()->{
        try{JSONObject s=Client.request(MainActivity.this,"GET");ui.post(()->render(s));}catch(Exception e){ui.post(()->unavailable(e));}
        ui.postDelayed(this,2000);
    });}};
    protected void onResume(){super.onResume();visible=true;ui.removeCallbacks(poll);ui.post(poll);}
    protected void onPause(){visible=false;ui.removeCallbacks(poll);super.onPause();}
    protected void onDestroy(){visible=false;ui.removeCallbacks(poll);io.shutdownNow();super.onDestroy();}
}
