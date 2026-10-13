package cn.local.adbawake;
import android.service.quicksettings.*;
import android.os.*;
import org.json.JSONObject;
public final class AwakeTile extends TileService {
    public void onStartListening(){refresh(false);}
    public void onClick(){if(isLocked()){unlockAndRun(()->refresh(true));}else refresh(true);}
    private void refresh(boolean toggle){new Thread(()->{
        try{
            JSONObject s=Client.request(this,"GET");
            if(toggle)s=Client.mode(this,"off".equals(s.optString("mode"))?"auto":"off");
            final JSONObject result=s;
            new Handler(Looper.getMainLooper()).post(()->{Tile t=getQsTile();if(t==null)return;
                t.setState("off".equals(result.optString("mode"))?Tile.STATE_INACTIVE:Tile.STATE_ACTIVE);
                t.setLabel("ADB 常亮");t.setSubtitle(result.optBoolean("held")?"常亮中":"off".equals(result.optString("mode"))?"已关闭":"等待连接");t.updateTile();});
        }catch(Exception e){new Handler(Looper.getMainLooper()).post(()->{Tile t=getQsTile();if(t!=null){t.setState(Tile.STATE_INACTIVE);t.setSubtitle("需启动辅助进程");t.updateTile();}});}
    },"tile").start();}
}
