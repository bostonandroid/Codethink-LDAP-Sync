package info.codethink.ldapsync;

import android.accounts.Account;
import android.accounts.AccountAuthenticatorActivity;
import android.accounts.AccountManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.Spinner;

public class LDAPAuthenticatorActivity extends AccountAuthenticatorActivity {
	public static final String ACTION_CONFIRM = "info.codethink.ldapsync.action.confirm";
	public static final String ACTION_LOGIN = "info.codethink.ldapsync.action.login";
	
	public static final String KEY_ACCOUNT = "codethink.account";
	public static final String KEY_WANTSAUTHTOKEN = "codethink.wantsauthtoken";

	private static final String TAG = "LDAPAuthenticatorActivity";
	
	private Account mAccount;
	private AccountManager mMgr;
	private boolean mWantsAuthToken;

	private void setupLogin(Bundle icicle)
	{
		setContentView(R.layout.login);
	}
	
	private void setupEditAccount(Bundle icicle)
	{
		setContentView(R.layout.editaccount);
		if (icicle == null && mAccount != null) {
			loadDataToView("server", R.id.serverinput);
			loadDataToView("binddn", R.id.binddninput);
			EditText passwordView = (EditText)findViewById(R.id.passwordinput);
			passwordView.setText(mMgr.getPassword(mAccount));
			
			String security = mMgr.getUserData(mAccount, "security");
			Spinner secView = (Spinner)findViewById(R.id.securityinput);
			for (int i = 0; i < secView.getCount(); i++)
				if (secView.getItemAtPosition(i).equals(security))
					secView.setSelection(i);
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
		if (icicle == null) {
			final String accountName = intent.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
			if (accountName != null)
				mAccount = new Account(accountName, LDAPAuthenticator.ACCOUNT_TYPE);
        	if (intent.hasExtra(AccountManager.KEY_AUTHTOKEN))
        		mWantsAuthToken = true;
		} else {
			mAccount = icicle.getParcelable(KEY_ACCOUNT);
			mWantsAuthToken = icicle.getBoolean(KEY_WANTSAUTHTOKEN);
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
	}
	
	@Override
	protected void onSaveInstanceState(Bundle outState)
	{
		super.onSaveInstanceState(outState);
		outState.putParcelable(KEY_ACCOUNT, mAccount);
		outState.putBoolean(KEY_WANTSAUTHTOKEN, mWantsAuthToken);
	}
	
	private void setDataFromView(String field, int viewId)
	{
		EditText view = (EditText)findViewById(viewId);
		mMgr.setUserData(mAccount, field, view.getText().toString());
	}
	
	private void loadDataToView(String field, int viewId)
	{
		EditText view = (EditText)findViewById(viewId);
		view.setText(mMgr.getUserData(mAccount, field));
	}
	
	public void handleSave(View view)
	{
		String action = getIntent().getAction();
		String authToken = null;

		if (mAccount == null)
			mAccount = new Account("Testing Account", LDAPAuthenticator.ACCOUNT_TYPE);
		
		EditText passwordInput = (EditText)findViewById(R.id.passwordinput);
		String password = passwordInput.getText().toString();

		if (action.equals(Intent.ACTION_INSERT)) {
			Log.i(TAG, "Creating account" + mAccount.name + " with type " + mAccount.type);
			if (!mMgr.addAccountExplicitly(mAccount, password, null)) {
				Log.w(TAG, "Failed to create account!");
				setAccountAuthenticatorResult(Utils.bundleError(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "Account creation failed"));
				// TODO: display some kind of error to the user here
				finish();
				return;
			}
			if (mWantsAuthToken)
				authToken = "testing_token";
		} else {
			if (!"".equals(password))
				mMgr.setPassword(mAccount, password);
		}
		setDataFromView("server", R.id.serverinput);
		setDataFromView("binddn", R.id.binddninput);
		String security = (String)((Spinner)findViewById(R.id.securityinput)).getSelectedItem();
		mMgr.setUserData(mAccount, "security", security);

		Bundle result = Utils.bundleAccount(mAccount.type, mAccount.name, authToken);
		setAccountAuthenticatorResult(result);
		finish();
	}
	
	public void handleTest(View view)
	{
		try {
			LDAPContactSource src = new LDAPContactSource(this, mAccount);
			src.test();
			// TODO: show 'test passed' message
		} catch (Exception e) {
			// TODO: show error
			Log.e(TAG, "Connection test failed", e);
		}
	}
}
