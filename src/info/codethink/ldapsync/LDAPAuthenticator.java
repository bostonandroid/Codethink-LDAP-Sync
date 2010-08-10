package info.codethink.ldapsync;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.NetworkErrorException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class LDAPAuthenticator extends AbstractAccountAuthenticator {
	private final Context mContext;
	
	private static final String TAG = "LDAPAuthenticator";

	public final static String AUTH_TOKEN_TYPE = "info.codethink.ldapsync.authtoken";
	public final static String ACCOUNT_TYPE = "info.codethink.ldapsync.account";
	
	public static final String KEY_PASSWORD = "password";
	
	public LDAPAuthenticator(Context context)
	{
		super(context);
		Log.i(TAG, "LDAPAuthenticator created");
		mContext = context;
	}
	
	private Bundle bundleIntent(String action, AccountAuthenticatorResponse response, Account acct, String authTokenType)
	{
		final Intent intent = new Intent(mContext, LDAPAuthenticatorActivity.class);
		intent.setAction(action);
		intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response);
		if (acct != null)
			intent.putExtra(AccountManager.KEY_ACCOUNT_NAME, acct.name);
		if (authTokenType != null)
			intent.putExtra(AccountManager.KEY_AUTHTOKEN, authTokenType);
		
		final Bundle intentBundle = new Bundle();
		intentBundle.putParcelable(AccountManager.KEY_INTENT, intent);
		return intentBundle;
	}
	
	@Override
	public Bundle addAccount(AccountAuthenticatorResponse response, String accountType,
			String authTokenType, String[] requiredFeatures, Bundle options)
			throws NetworkErrorException {
		
		Log.i(TAG, "LDAPAuthenticator addAccount called");

		if (authTokenType != null && !authTokenType.equals(AUTH_TOKEN_TYPE))
			return Utils.bundleError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION, "Unsupported authTokenType");
		if (!accountType.equals(ACCOUNT_TYPE))
			return Utils.bundleError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION, "Unsupported accountType");
		if (requiredFeatures != null && requiredFeatures.length != 0)
			return Utils.bundleError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION, "Unrecognized features requested");
		
		return bundleIntent(Intent.ACTION_INSERT, response, null, authTokenType);
	}

	@Override
	public Bundle confirmCredentials(AccountAuthenticatorResponse response,
			Account account, Bundle options) throws NetworkErrorException {
		if (!account.type.equals(ACCOUNT_TYPE))
			return Utils.bundleError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION, "Unsupported account type");
		if (options.containsKey(AccountManager.KEY_PASSWORD)) {
			// TODO: authenticate account
			return Utils.bundleResult(false);
		}
		return bundleIntent(LDAPAuthenticatorActivity.ACTION_CONFIRM, response, account, null);
	}

	@Override
	public Bundle editProperties(AccountAuthenticatorResponse response,
			String accountType) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Bundle getAuthToken(AccountAuthenticatorResponse response,
			Account account, String authTokenType, Bundle options)
			throws NetworkErrorException {
		if (!account.type.equals(ACCOUNT_TYPE))
			return Utils.bundleError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION, "Unsupported account type");
		if (options.containsKey(AccountManager.KEY_PASSWORD)) {
			// TODO: authenticate account
			return Utils.bundleResult(false);
		}
		return bundleIntent(LDAPAuthenticatorActivity.ACTION_LOGIN, response, account, null);
	}

	@Override
	public String getAuthTokenLabel(String authTokenType) {
		if (authTokenType != AUTH_TOKEN_TYPE)
			return null;

		return mContext.getString(R.string.ldapaccount);
	}

	@Override
	public Bundle hasFeatures(AccountAuthenticatorResponse response,
			Account account, String[] features) throws NetworkErrorException {
		if (!account.type.equals(ACCOUNT_TYPE))
			return Utils.bundleError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION, "Unsupported account type");
		if (features == null || features.length == 0)
			return Utils.bundleResult(true);
		
		// we don't support any authenticator-specific features
		return Utils.bundleResult(false);
	}

	@Override
	public Bundle updateCredentials(AccountAuthenticatorResponse response,
			Account account, String authTokenType, Bundle options)
			throws NetworkErrorException {
		if (!account.type.equals(ACCOUNT_TYPE))
			return Utils.bundleError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION, "Unsupported account type");
		if (authTokenType != null && !authTokenType.equals(AUTH_TOKEN_TYPE))
			return Utils.bundleError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION, "Unsupported authTokenType");
		
		return bundleIntent(Intent.ACTION_EDIT, response, account, null);
	}

}
