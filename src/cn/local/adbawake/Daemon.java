package cn.local.adbawake;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import org.json.JSONObject;

/** Runs as shell, independently of the ADB connection and of the APK process. */
public final class Daemon {
    private static final Uri BOOTSTRAP=Uri.parse("content://cn.local.adbawake.bootstrap");
    private final Context context;
    private final Object adb;
    private final Method pairs;
    private final PowerManager power;
    private final PowerManager.WakeLock lock;
    private final Method userActivity;
    private final String token;
    private final long installTime;
    private final File cache;
    private final ServerSocket server;
    private String mode;
    private boolean connected, healthy;
    private volatile boolean running=true;
    private long disconnectedAt, renewedAt, activityAt, manualWakeUntil;
    private boolean activityHealthy;
    private String message="";
    private Boolean lastInteractive=null;
    private String lastLog="";

    private Daemon() throws Exception {
        // Bind first: launching a second copy must never acquire a second lock.
        server=new ServerSocket();server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),Client.PORT));
        Object at=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context system=(Context)at.getClass().getMethod("getSystemContext").invoke(at);
        context=system.createPackageContext("com.android.shell",0);
        installTime=context.getPackageManager().getPackageInfo("cn.local.adbawake",0).firstInstallTime;
        cache=new File("/data/local/tmp/adbawake-bootstrap");
        String boot=exec("/system/bin/content","call","--uri",BOOTSTRAP.toString(),"--method","bootstrap");
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("token=([0-9a-f]{64})").matcher(boot);
        if(!match.find()) {
            // ColorOS may prevent launching a background app's provider while locked.
            // A previous successful bootstrap can restart this same installation.
            try(BufferedReader reader=new BufferedReader(new FileReader(cache))) {
                if(!Long.toString(installTime).equals(reader.readLine()))throw new IOException("New installation requires bootstrap");
                boot=reader.readLine();
            }
            match=java.util.regex.Pattern.compile("token=([0-9a-f]{64})").matcher(boot==null?"":boot);
            if(!match.find())throw new IOException("Bootstrap provider unavailable; open the app once");
        } else {
            cache.createNewFile();cache.setReadable(false,false);cache.setWritable(false,false);
            cache.setReadable(true,true);cache.setWritable(true,true);
            try(FileWriter writer=new FileWriter(cache)){writer.write(installTime+"\n"+boot);}
        }
        token=match.group(1);
        match=java.util.regex.Pattern.compile("mode=(auto|on|off)").matcher(boot);mode=match.find()?match.group(1):"auto";
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"adb");
        adb=Class.forName("android.debug.IAdbManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        pairs=Class.forName("android.debug.IAdbManager").getMethod("getPairedDevices");
        power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
        lock=power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,"AdbAwake:Wireless");lock.setReferenceCounted(false);
        userActivity=power.getClass().getMethod("userActivity",long.class,int.class,int.class);
    }
    private boolean wirelessConnected() throws Exception {
        Object[] devices=(Object[])pairs.invoke(adb);
        for(Object entry:devices) {
            Object d=entry.getClass().getField("device").get(entry);
            if(d!=null && d.getClass().getField("connected").getBoolean(d))return true;
        }
        return false;
    }
    private void wakeOnce() {
        try {
            power.getClass().getMethod("wakeUp",long.class,int.class,String.class)
                .invoke(power,SystemClock.uptimeMillis(),2,"AdbAwake:Connected");
            log("WAKE requested");
        } catch(Exception e) {message="已保持常亮；首次点亮请按电源键";log("WAKE unavailable: "+e.getClass().getSimpleName());}
    }
    private synchronized void tick() {
        // The CPU may suspend immediately after the display goes off. Observe the
        // display before waking on reconnect, so the off transition is not lost.
        boolean before=power.isInteractive();
        if(lastInteractive!=null&&lastInteractive!=before) {
            log("DISPLAY interactive="+before+" previousConnected="+connected+" held="+lock.isHeld());
            lastInteractive=before;
        }
        long now=SystemClock.elapsedRealtime();boolean old=connected;
        try {connected=wirelessConnected();healthy=true;message="";}
        catch(Exception e){connected=false;healthy=false;message="连接检测失败，已释放亮屏锁";log("DETECTION_ERROR "+e.getClass().getSimpleName());}
        if(connected)disconnectedAt=0;
        else if(disconnectedAt==0)disconnectedAt=now;
        boolean wanted="on".equals(mode)||("auto".equals(mode)&&healthy&&(connected||lock.isHeld()&&now-disconnectedAt<3000));
        if(!healthy && !"on".equals(mode))wanted=false;
        if(wanted) {
            if(!lock.isHeld()||now-renewedAt>=5000){lock.acquire(15000);renewedAt=now;}
            if((!old&&connected&&"auto".equals(mode))||manualWakeUntil>now) {manualWakeUntil=0;wakeOnce();}
            // ColorOS lockscreen can explicitly sleep despite a bright wake lock.
            // Refresh only an already-on display: this never undoes a power-key sleep.
            if(power.isInteractive()&&now-activityAt>=5000) {
                try{userActivity.invoke(power,SystemClock.uptimeMillis(),0,0);activityHealthy=true;activityAt=now;}
                catch(Exception e){activityHealthy=false;message="系统拒绝增强常亮";log("USER_ACTIVITY_ERROR "+e.getClass().getSimpleName());}
            }
        } else if(lock.isHeld()) {lock.release();renewedAt=0;}
        String transition="mode="+mode+" connected="+connected+" healthy="+healthy+" held="+lock.isHeld();
        boolean interactive=power.isInteractive();
        if(!transition.equals(lastLog)||lastInteractive==null||lastInteractive!=interactive) {
            log("STATE "+transition+" interactive="+interactive);
            lastLog=transition;lastInteractive=interactive;
            try{refresh();}catch(Exception e){log("TILE_REFRESH unavailable");}
        }
    }
    private synchronized JSONObject status() throws Exception {
        return new JSONObject().put("version",1).put("mode",mode).put("connected",connected)
            .put("healthy",healthy).put("held",lock.isHeld()).put("interactive",power.isInteractive())
            .put("activityHealthy",activityHealthy)
            .put("message",message).put("pid",android.os.Process.myPid()).put("elapsed",SystemClock.elapsedRealtime());
    }
    private static String exec(String... args) throws Exception {
        java.lang.Process p=new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
        try(InputStream in=p.getInputStream()){while((n=in.read(b))!=-1){if(out.size()<16384)out.write(b,0,n);}}
        int code=p.waitFor();String result=out.toString("UTF-8");
        if(code!=0)throw new IOException("Command failed: "+args[0]);return result;
    }
    private void refresh() throws Exception {
        exec("/system/bin/content","call","--uri",BOOTSTRAP.toString(),"--method","refresh","--arg",mode);
    }
    private void saveBootstrap() throws IOException {
        File temp=new File(cache.getPath()+".new");temp.createNewFile();
        temp.setReadable(false,false);temp.setWritable(false,false);
        temp.setReadable(true,true);temp.setWritable(true,true);
        try(FileWriter writer=new FileWriter(temp)){writer.write(installTime+"\nmode="+mode+", token="+token+"\n");}
        if(!temp.renameTo(cache))throw new IOException("Cannot persist helper mode");
    }
    private void developer() throws Exception {
        exec("/system/bin/settings","put","global","development_settings_enabled","1");
        exec("/system/bin/am","start","-a",Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,"-f","0x14000000");
    }
    private void wireless() throws Exception {
        // ColorOS 17 keeps the wireless fragment inside a non-exported activity.
        // Navigate the real Settings row as shell; never toggle the adjacent switch.
        HandlerThread thread=new HandlerThread("settings-navigation");thread.start();
        android.app.UiAutomation automation=null;
        try {
            Class<?> connection=Class.forName("android.app.IUiAutomationConnection");
            Object binder=Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            automation=(android.app.UiAutomation)android.app.UiAutomation.class.getConstructor(Looper.class,connection).newInstance(thread.getLooper(),binder);
            automation.getClass().getMethod("connect",int.class).invoke(automation,1);
            developer();
            long deadline=SystemClock.elapsedRealtime()+12000;
            int scrolls=0;long lastScroll=0;
            while(SystemClock.elapsedRealtime()<deadline) {
                android.view.accessibility.AccessibilityNodeInfo root=automation.getRootInActiveWindow();
                if(root!=null && "com.android.settings".contentEquals(root.getPackageName()==null?"":root.getPackageName())) {
                    java.util.List<android.view.accessibility.AccessibilityNodeInfo> matches=root.findAccessibilityNodeInfosByText("无线调试");
                    for(android.view.accessibility.AccessibilityNodeInfo node:matches) {
                        if(!"无线调试".contentEquals(node.getText()==null?"":node.getText()))continue;
                        android.view.accessibility.AccessibilityNodeInfo row=node;
                        while(row!=null&&!row.isClickable())row=row.getParent();
                        if(row!=null&&row.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)){log("WIFI_SETTINGS row opened");return;}
                    }
                    long now=SystemClock.elapsedRealtime();
                    if(now-lastScroll>1000&&scrolls<4&&scroll(root)){lastScroll=now;scrolls++;}
                }
                Thread.sleep(350);
            }
            throw new IOException("未找到无线调试入口，请在开发者选项中打开");
        } finally {
            if(automation!=null)try{automation.getClass().getMethod("disconnect").invoke(automation);}catch(Exception ignored){}
            thread.quitSafely();
        }
    }
    private static boolean scroll(android.view.accessibility.AccessibilityNodeInfo node) {
        if(node.isScrollable()&&node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true;
        for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo child=node.getChild(i);if(child!=null&&scroll(child))return true;}
        return false;
    }
    private synchronized JSONObject command(String command) throws Exception {
        if(command.startsWith("MODE:")) {
            String next=command.substring(5);
            if(!next.equals("auto")&&!next.equals("on")&&!next.equals("off"))throw new IllegalArgumentException("Invalid mode");
            if(!next.equals(mode)&&next.equals("on"))manualWakeUntil=SystemClock.elapsedRealtime()+3000;
            mode=next;saveBootstrap();tick();
            refresh();
        }else if("DEV".equals(command))developer();
        else if("WIFI".equals(command))wireless();
        else if("STOP".equals(command)){running=false;if(lock.isHeld())lock.release();}
        else if(!"GET".equals(command))throw new IllegalArgumentException("Unknown command");
        return status();
    }
    private void serve() {
        while(running) {
            try(Socket s=server.accept()) {
                s.setSoTimeout(2000);
                Reader reader=new InputStreamReader(s.getInputStream(),"UTF-8");StringBuilder b=new StringBuilder();int ch;
                while((ch=reader.read())!=-1&&ch!='\n'){if(b.length()>=256)throw new IOException("Request too long");b.append((char)ch);}
                String line=b.toString();JSONObject response;
                if(!line.startsWith(token+" "))response=new JSONObject().put("error","Unauthorized");
                else try{response=command(line.substring(token.length()+1));}
                    catch(Exception e){response=new JSONObject().put("error",e.toString());}
                Writer w=new OutputStreamWriter(s.getOutputStream(),"UTF-8");w.write(response.toString()+"\n");w.flush();
            }catch(Exception e){if(running)log("RPC_ERROR "+e.getClass().getSimpleName());}
        }
    }
    private static synchronized void log(String text) {
        String line=System.currentTimeMillis()+" elapsed="+SystemClock.elapsedRealtime()+" "+text;
        System.out.println(line);
        try {
            File f=new File("/data/local/tmp/adbawake-state.log");
            if(f.length()>256000){File old=new File(f.getPath()+".previous");old.delete();f.renameTo(old);}
            try(FileWriter w=new FileWriter(f,true)){w.write(line+"\n");}
        }catch(Exception ignored){}
    }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();Daemon daemon;
        try{daemon=new Daemon();}catch(BindException e){System.out.println("Already running");return;}
        log("START version=1 pid="+android.os.Process.myPid());
        Thread rpc=new Thread(daemon::serve,"rpc");rpc.setDaemon(true);rpc.start();
        try{while(daemon.running){daemon.tick();Thread.sleep(1000);}}
        finally{if(daemon.lock.isHeld())daemon.lock.release();daemon.server.close();log("STOP");}
    }
}
