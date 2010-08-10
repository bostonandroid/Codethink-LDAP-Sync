package info.codethink.ldapsync;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class LDAPAuthenticatorService extends Service {

	private LDAPAuthenticator mAuthenticator;
	private static final String TAG = "LDAPAuthenticatorService";

	@Override
	public void onCreate() {
		Log.i(TAG, "created");
		mAuthenticator = new LDAPAuthenticator(this);
	}
	
	@Override
	public IBinder onBind(Intent intent) {
		Log.i(TAG, "bound");
		return mAuthenticator.getIBinder();
	}
	
	@Override
	public void onDestroy() {
		Log.i(TAG, "destroyed");
	}

}
