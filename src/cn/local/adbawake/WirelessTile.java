package cn.local.adbawake;
import android.app.PendingIntent;
import android.content.Intent;
import android.service.quicksettings.*;
public final class WirelessTile extends TileService {
    public void onStartListening(){Tile t=getQsTile();if(t!=null){t.setState(Tile.STATE_ACTIVE);t.updateTile();}}
    public void onClick(){startActivityAndCollapse(PendingIntent.getActivity(this,2,new Intent(this,MainActivity.class).putExtra("shortcut","WIFI").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));}
}
