package info.codethink.ldapsync;

import java.lang.ref.WeakReference;
import java.util.HashMap;

import android.accounts.Account;
import android.accounts.AccountAuthenticatorActivity;
import android.accounts.AccountManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.Spinner;

import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.ResultCode;

public class LDAPAuthenticatorActivity extends AccountAuthenticatorActivity {
	public static final String ACTION_CONFIRM = "info.codethink.ldapsync.action.confirm";
	public static final String ACTION_LOGIN = "info.codethink.ldapsync.action.login";
	
	private static final String KEY_ACCOUNT = "codethink.account";
	private static final String KEY_WANTSAUTHTOKEN = "codethink.wantsauthtoken";
	private static final String KEY_INSTANCE_ID = "codethink.instanceid";
	private static final String KEY_SETTINGS = "codethink.settings";
	private static final String KEY_TEST_IN_PROGRESS = "codethink.testinprogress";
	private static final String KEY_TEST_RESULT_COUNT = "codethink.testresultcount";
	private static final String KEY_TEST_FAIL_MESSAGE = "codethink.testfailuremessage";

	private static final String TAG = "LDAPAuthenticatorActivity";
	
	private static final int DIALOG_TEST_PASSED = 0;
	private static final int DIALOG_TEST_FAILED = 1;
	public static final int DIALOG_TEST_PROGRESS = 2;
	private static final int REQUEST_PICK_BASEDN = 0;
	
	private static int nextInstanceId = 0;
	private static HashMap<Integer, WeakReference<LDAPAuthenticatorActivity>> instanceMap = new HashMap<Integer, WeakReference<LDAPAuthenticatorActivity>>();
	
	private Account mAccount;
	private AccountManager mMgr;
	private boolean mWantsAuthToken;
	private int mTestResultCount;
	private String mTestFailureMessage;
	private int mInstanceId;
	private ConnectionTestTask mConnectionTestTask;
	private boolean mConnectionTestInProgress = false;
	private boolean mSavedInstanceState = false;

	private void setupLogin(Bundle icicle)
	{
		setContentView(R.layout.login);
	}
	
	private void setupEditAccount(Bundle icicle)
	{
		setContentView(R.layout.editaccount);
		if (icicle == null && mAccount != null) {
		    applySettings(Utils.getSavedSettngs(mMgr, mAccount));
		}
	}
	
	@Override
	public void onCreate(Bundle icicle)
	{
		Log.d(TAG, "onCreate(" + icicle + ")");
		super.onCreate(icicle);
		
        requestWindowFeature(Window.FEATURE_LEFT_ICON);
        
        mMgr = AccountManager.get(this);
    	
		final Intent intent = getIntent();
		Bundle settings = null;
		if (icicle == null) {
			mInstanceId = nextInstanceId++;
			instanceMap.put(mInstanceId, new WeakReference<LDAPAuthenticatorActivity>(this));
			final String accountName = intent.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
			if (accountName != null)
				mAccount = new Account(accountName, LDAPAuthenticator.ACCOUNT_TYPE);
        	if (intent.hasExtra(AccountManager.KEY_AUTHTOKEN))
        		mWantsAuthToken = true;
		} else {
			mAccount = icicle.getParcelable(KEY_ACCOUNT);
			mWantsAuthToken = icicle.getBoolean(KEY_WANTSAUTHTOKEN);
			mInstanceId = icicle.getInt(KEY_INSTANCE_ID);
			settings = icicle.getBundle(KEY_SETTINGS);
			mConnectionTestInProgress = icicle.getBoolean(KEY_TEST_IN_PROGRESS);
			mConnectionTestTask = ConnectionTestTask.get(mInstanceId);
			mTestResultCount = icicle.getInt(KEY_TEST_RESULT_COUNT);
			mTestFailureMessage = icicle.getString(KEY_TEST_FAIL_MESSAGE);
			instanceMap.put(mInstanceId, new WeakReference<LDAPAuthenticatorActivity>(this));
			if (mConnectionTestTask == null && mConnectionTestInProgress) {
				// connection test was killed part-way through, restart it
				mConnectionTestTask = new ConnectionTestTask(mInstanceId, settings);
				mConnectionTestTask.execute();
			}
			Log.d(TAG, "Restored state with instance id " + mInstanceId + " and task " + mConnectionTestTask);
		}

		final String action = intent.getAction();
    	if (Intent.ACTION_INSERT.equals(action)) {
        	setupEditAccount(icicle);
		} else if (Intent.ACTION_EDIT.equals(action)) {
			setupEditAccount(icicle);
		} else if (ACTION_LOGIN.equals(action) || ACTION_CONFIRM.equals(action)) {
			setupLogin(null);
		} else {
			Log.e(TAG, "Unknown action " + action);
			setAccountAuthenticatorResult(Utils.bundleError(AccountManager.ERROR_CODE_BAD_REQUEST, "Unknown action " + action));
			finish();
			return;
		}
		
        getWindow().setFeatureDrawableResource(Window.FEATURE_LEFT_ICON, android.R.drawable.ic_dialog_alert);
        if (icicle != null)
			applySettings(settings);
	}
	
	@Override
	protected void onResume() {
		super.onResume();
		mSavedInstanceState = false;
	}
	
	@Override
	protected Dialog onCreateDialog(int id) {
		OnClickListener okClickListener = new OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		};
		if (id == DIALOG_TEST_PASSED) {
			return new AlertDialog.Builder(this)
				.setMessage(getString(R.string.testpassed, mTestResultCount))
				.setPositiveButton(R.string.ok, okClickListener)
				.setCancelable(true)
				.create();
		} else if (id == DIALOG_TEST_FAILED) {
			return new AlertDialog.Builder(this)
				.setMessage(getString(R.string.testfailed, mTestFailureMessage))
				.setPositiveButton(R.string.ok, okClickListener)
				.setCancelable(true)
				.create();
		} else if (id == DIALOG_TEST_PROGRESS) {
			ProgressDialog pd = new ProgressDialog(this);
			pd.setMessage(getString(R.string.testingconnection));
			pd.setCancelable(true);
			pd.setIndeterminate(true);
			pd.setOnCancelListener(new OnCancelListener() {
				public void onCancel(DialogInterface dialog) {
					mConnectionTestTask.cancel(false);
				}
			});
			return pd;
		}
		return super.onCreateDialog(id);
	}
	
	@Override
	protected void onPrepareDialog(int id, Dialog dialog) {
		super.onPrepareDialog(id, dialog);
		if (id == DIALOG_TEST_PASSED) {
			AlertDialog ad = (AlertDialog)dialog;
			ad.setMessage(getString(R.string.testpassed, mTestResultCount));
		} else if (id == DIALOG_TEST_FAILED) {
			AlertDialog ad = (AlertDialog)dialog;
			ad.setMessage(getString(R.string.testfailed, mTestFailureMessage));
		} else if (id == DIALOG_TEST_PROGRESS) {
			// do nothing
		}
	}
	
	@Override
	protected void onSaveInstanceState(Bundle outState)
	{
		super.onSaveInstanceState(outState);
		outState.putParcelable(KEY_ACCOUNT, mAccount);
		outState.putBoolean(KEY_WANTSAUTHTOKEN, mWantsAuthToken);
		outState.putInt(KEY_INSTANCE_ID, mInstanceId);
		outState.putBoolean(KEY_TEST_IN_PROGRESS, mConnectionTestInProgress);
		outState.putBundle(KEY_SETTINGS, getVisibleSettings());
		outState.putInt(KEY_TEST_RESULT_COUNT, mTestResultCount);
		outState.putString(KEY_TEST_FAIL_MESSAGE, mTestFailureMessage);
		mSavedInstanceState = true;
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		
		if (requestCode == REQUEST_PICK_BASEDN && resultCode == RESULT_OK) {
			EditText baseInput = (EditText) findViewById(R.id.basedninput);
			baseInput.setText(data.getStringExtra(LDAPEntryPicker.KEY_DN));
		}
	}
	
	private void saveDataFromView(Bundle settings, String field, int viewId)
	{
		EditText view = (EditText)findViewById(viewId);
		settings.putString(field, view.getText().toString());
	}
	
	private Bundle getVisibleSettings()
	{
		Bundle settings = new Bundle();
		saveDataFromView(settings, "password", R.id.passwordinput);
		saveDataFromView(settings, "server", R.id.serverinput);
		saveDataFromView(settings, "binddn", R.id.binddninput);
		saveDataFromView(settings, "basedn", R.id.basedninput);
		String security = (String)((Spinner)findViewById(R.id.securityinput)).getSelectedItem();
		settings.putString("security", security);
		return settings;
	}
	
	private void applySettingToView(Bundle settings, String key, int viewId)
	{
		EditText view = (EditText)findViewById(viewId);
		if (view == null) throw new NullPointerException("view " + viewId + " not found");
		if (settings.containsKey(key)) {
			view.setText(settings.getString(key));
		} else {
			view.setText("");
		}
	}

	private void applySettings(Bundle settings)
	{
		if (settings == null) throw new NullPointerException("can't apply null settings");
		applySettingToView(settings, "password", R.id.passwordinput);
		applySettingToView(settings, "server", R.id.serverinput);
		applySettingToView(settings, "binddn", R.id.binddninput);
		applySettingToView(settings, "basedn", R.id.basedninput);
		String security = settings.containsKey("security") ? settings.getString("security") : "None";
		Spinner secView = (Spinner)findViewById(R.id.securityinput);
		for (int i = 0; i < secView.getCount(); i++)
			if (secView.getItemAtPosition(i).equals(security))
				secView.setSelection(i);
	}
	
	public void handleSave(View view)
	{
		String action = getIntent().getAction();
		String authToken = null;

		if (mAccount == null)
			mAccount = new Account("Testing Account", LDAPAuthenticator.ACCOUNT_TYPE);
		
		final Bundle settings = getVisibleSettings();
		
		if (action.equals(Intent.ACTION_INSERT)) {
			Log.i(TAG, "Creating account" + mAccount.name + " with type " + mAccount.type);
			if (!mMgr.addAccountExplicitly(mAccount, settings.getString("password"), null)) {
				Log.w(TAG, "Failed to create account!");
				setAccountAuthenticatorResult(Utils.bundleError(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "Account creation failed"));
				// TODO: display some kind of error to the user here
				finish();
				return;
			}
			if (mWantsAuthToken)
				authToken = "testing_token";
		}
		Utils.saveSettings(mMgr, mAccount, settings);

		Bundle result = Utils.bundleAccount(mAccount.type, mAccount.name, authToken);
		setAccountAuthenticatorResult(result);
		finish();
	}

    public void handlePickBase(View view) {
    	Bundle settings = getVisibleSettings();
    	
    	Intent pickerIntent = new Intent(this, LDAPEntryPicker.class);
    	pickerIntent.putExtra(LDAPEntryPicker.KEY_SETTINGS, settings);
    	pickerIntent.putExtra(LDAPEntryPicker.KEY_DN, settings.getString("basedn"));
    	startActivityForResult(pickerIntent, REQUEST_PICK_BASEDN);
    }
	
	private static class ConnectionTestTask extends AsyncTask<Void, Void, Boolean> {
		private static HashMap<Integer, WeakReference<ConnectionTestTask>> taskMap = new HashMap<Integer, WeakReference<ConnectionTestTask>>();

		int mActivityInstanceId;
		String mFailureMessage;
		Bundle mSettings;
		int mRecordsFound;
		public ConnectionTestTask(int instanceId, Bundle settings) {
			mActivityInstanceId = instanceId;
			mSettings = settings;
			taskMap.put(instanceId, new WeakReference<ConnectionTestTask>(this));
		}
		public static ConnectionTestTask get(int instanceId) {
			WeakReference<ConnectionTestTask> ref = taskMap.get(instanceId);
			if (ref == null) return null;
			return ref.get();
		}
		@Override
		protected Boolean doInBackground(Void... paramsUnused) {
			try {
				final LDAPContactSource src = new LDAPContactSource(mSettings);
				mRecordsFound = src.test();
				Log.d(TAG, "Connection test successful, " + mRecordsFound + " result returned");
				return true;
			} catch (LDAPSearchException e) {
				Log.i(TAG, "Connection test search failed", e);
				if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
					mFailureMessage = "invalid base dn"; // TODO: expand and localize
				} else {
					mFailureMessage = e.getLocalizedMessage();
				}
				return false;
			} catch (Exception e) {
				Log.i(TAG, "Connection test failed", e);
				mFailureMessage = e.getLocalizedMessage();
				return false;
			}
		}
		@Override
		protected void onPostExecute(Boolean result) {
			super.onPostExecute(result);
			
			taskMap.remove(mActivityInstanceId);
			WeakReference<LDAPAuthenticatorActivity> activityRef = instanceMap.get(mActivityInstanceId);
			if (activityRef == null || activityRef.get() == null) {
				Log.d(TAG, "ConnectionTestTask has no activity in onPostExecute");
				return; // our activity died while we were working
			}
			LDAPAuthenticatorActivity activity = activityRef.get();
			activity.mConnectionTestTask = null;
			activity.mConnectionTestInProgress = false;

			if (result == null || isCancelled()) {
				Log.d(TAG, "ConnectionTestTask cancelled, not messing with dialogs");
				return; // Canceled, just quit
			}
			
			Log.d(TAG, "ConnectionTestTask returned " + result + ", showing result dialog.");
			activity.removeDialog(DIALOG_TEST_PROGRESS); // dismissDialog doesn't seem to work if the app was killed and restored
			if (result) {
				activity.mTestResultCount = mRecordsFound;
				activity.showDialog(DIALOG_TEST_PASSED);
			} else {
				activity.mTestFailureMessage = mFailureMessage;
				activity.showDialog(DIALOG_TEST_FAILED);
			}
		}
		@Override
		protected void onCancelled() {
			super.onCancelled();
			taskMap.remove(mActivityInstanceId);
		}
	}
	
	public void handleTest(View view)
	{
		mConnectionTestInProgress = true;
		showDialog(DIALOG_TEST_PROGRESS);
		mConnectionTestTask = new ConnectionTestTask(mInstanceId, getVisibleSettings());
		mConnectionTestTask.execute();
	}
	
	@Override
	protected void onDestroy() {
		Log.d(TAG, "onDestroy");
		instanceMap.remove(mInstanceId);
		if (!mSavedInstanceState && mConnectionTestTask != null)
			mConnectionTestTask.cancel(false);
		super.onDestroy();
	}
}
