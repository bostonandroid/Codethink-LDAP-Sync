package info.codethink.ldapsync;

import java.security.GeneralSecurityException;
import java.util.List;

import javax.net.SocketFactory;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.unboundid.ldap.sdk.ExtendedResult;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.RootDSE;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchResultListener;
import com.unboundid.ldap.sdk.SearchResultReference;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.extensions.StartTLSExtendedRequest;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;

public class LDAPContactSource {
	private static final String TAG = "LDAPContactSource"; // for logging

	//private static final String SEC_NONE = "None"; // unused
	private static final String SEC_TLS = "TLS";
	private static final String SEC_TLS_ANYCERT = "TLS (any certificate)";
	private static final String SEC_SSL = "SSL";
	private static final String SEC_SSL_ANYCERT = "SSL (any certificate)";
	
	private String mBindDN;
	private String mPassword;

	private boolean mUseTLS;
	private boolean mUseSSL;
	private boolean mTrustAnyCert;
	private String mHost;
	private int mPort;
	
	private String mSearchBase;
	
	private LDAPConnection mConnection;
	
	public LDAPContactSource(Context ctx, Account ldapAccount) {
			if (ldapAccount == null || !ldapAccount.type.equals(LDAPAuthenticator.ACCOUNT_TYPE))
			throw new IllegalArgumentException("ldapAccount must be non-null and of type " + LDAPAuthenticator.ACCOUNT_TYPE);
		
		AccountManager mgr = AccountManager.get(ctx);
		Bundle settings = Utils.getSavedSettngs(mgr, ldapAccount);
		initFromSettings(settings);
	}

	public LDAPContactSource(Bundle accountSettings)
	{
		initFromSettings(accountSettings);
	}

	private void initFromSettings(Bundle settings) {
		String server = settings.getString("server");
		mBindDN = settings.getString("binddn");
		mPassword = settings.getString("password");
		String security = settings.getString("security");
		mSearchBase = settings.getString("basedn");
		
		mUseTLS = security.equals(SEC_TLS) || security.equals(SEC_TLS_ANYCERT);
		mUseSSL = security.equals(SEC_SSL) || security.equals(SEC_SSL_ANYCERT);
		mTrustAnyCert = security.equals(SEC_TLS_ANYCERT) || security.equals(SEC_SSL_ANYCERT);
		
		if (server.contains(":")) {
			String[] hostport = server.split(":", 2);
			mHost = hostport[0];
			mPort = Integer.parseInt(hostport[1]);
		} else {
			mHost = server;
			mPort = mUseSSL ? 636 : 389;
		}
	}

	public void connect() throws LDAPException {
		LDAPConnectionOptions options = new LDAPConnectionOptions();
		options.setAutoReconnect(true);
		
		SocketFactory socketFactory = null;
		SSLUtil sslutil = null;
		if (mUseSSL || mUseTLS)
			if (mTrustAnyCert)
				sslutil = new SSLUtil(new TrustAllTrustManager());
			else
				sslutil = new SSLUtil();
		if (mUseSSL) {
			try {
				socketFactory = sslutil.createSSLSocketFactory();
			} catch (GeneralSecurityException e) {
				throw new LDAPException(ResultCode.LOCAL_ERROR, "Can't get socket factory for SSL connection", e);
			}
		}
		
		
		LDAPConnection connection = new LDAPConnection(socketFactory, options, mHost, mPort);
		if (mUseTLS) {
			boolean tlsEstablished = false;
			try {
				final ExtendedResult tlsresult = connection.processExtendedOperation(
						new StartTLSExtendedRequest(sslutil.createSSLContext()));
				if (tlsresult.getResultCode() != ResultCode.SUCCESS)
					throw new LDAPException(tlsresult);
				tlsEstablished = true;
			} catch (GeneralSecurityException e) {
				throw new LDAPException(ResultCode.LOCAL_ERROR, "Can't get SSL context for TLS connection", e);
			} finally {
				if (!tlsEstablished) {
					connection.close();
					connection = null;
				}
			}
		}
		
		if (mBindDN.length() > 0) {
			boolean bound = false;
			try {
				connection.bind(mBindDN, mPassword);
				bound = true;
			} finally {
				if (!bound) {
					connection.close();
					connection = null;
				}
			}
		}
		
		mConnection = connection;
	}
	
	public void browse(String dn, List<String> outChildren) throws LDAPException
	{
		SearchResult sr = mConnection.search(dn, SearchScope.ONE, "(objectClass=*)");
		for (SearchResultEntry entry: sr.getSearchEntries()) {
			outChildren.add(entry.getDN());
		}
	}
	
	public void search(SearchResultListener listener) throws LDAPException
	{
		String baseDN = mSearchBase;
		if (baseDN == null) {
			baseDN = getRootDN();
		}
		SearchResult sr = mConnection.search(listener, baseDN, SearchScope.SUB, "(objectClass=inetOrgPerson)");
		if (sr.getResultCode() != ResultCode.SUCCESS) {
			throw new LDAPException(sr);
		}
	}

	public String getRootDN() throws LDAPException {
		RootDSE root = mConnection.getRootDSE();
		return root.getNamingContextDNs()[0];
	}
	
	public int test() throws LDAPException
	{
		final int[] resultCount = new int[]{0}; // wrap in array so inner SearchResultListener can update
		connect();
		try {
			search(new SearchResultListener() {
				private static final long serialVersionUID = 1L;
				public void searchReferenceReturned(SearchResultReference ref) {
					Log.i(TAG, "Found reference " + ref.getReferralURLs());
				}
				public void searchEntryReturned(SearchResultEntry entry) {
					Log.i(TAG, "Found " + entry.getDN());
					resultCount[0]++;
				}
			});
		} finally {
			close();
		}
		return resultCount[0];
	}
	
	public void close() {
		if (mConnection != null) mConnection.close();
	}	
}