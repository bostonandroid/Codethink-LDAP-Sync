package info.codethink.ldapsync;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class LDAPSyncService extends Service {
	final LDAPSyncAdapter mSyncAdapter = new LDAPSyncAdapter(this);
	
	@Override
	public IBinder onBind(Intent intent) {
		return mSyncAdapter.getSyncAdapterBinder();
	}

}
