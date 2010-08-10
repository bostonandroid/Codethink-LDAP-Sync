package info.codethink.ldapsync;

import java.security.GeneralSecurityException;

import javax.net.SocketFactory;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.util.Log;

import com.unboundid.ldap.sdk.ExtendedResult;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.RootDSE;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
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
	
	private AccountManager mMgr;

	private String mBindDN;
	private String mPassword;

	private boolean mUseTLS;
	private boolean mUseSSL;
	private boolean mTrustAnyCert;
	private String mHost;
	private int mPort;
	
	public LDAPContactSource(Context ctx, Account ldapAccount) {
		if (ctx == null) // this will happen eventually anyway; throw here to simplify debugging
			throw new NullPointerException("Context cannot be null");
		if (ldapAccount == null || !ldapAccount.type.equals(LDAPAuthenticator.ACCOUNT_TYPE))
			throw new IllegalArgumentException("ldapAccount must be non-null and of type " + LDAPAuthenticator.ACCOUNT_TYPE);
		
		mMgr = AccountManager.get(ctx);
		
		String server = mMgr.getUserData(ldapAccount, "server");
		mBindDN = mMgr.getUserData(ldapAccount, "binddn");
		mPassword = mMgr.getPassword(ldapAccount);
		String security = mMgr.getUserData(ldapAccount, "security");
		
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

	public LDAPConnection connect() throws LDAPException {
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
		
		return connection;
	}
	
	public void validate() throws LDAPException
	{
		LDAPConnection connection = connect();
		connection.close();
	}
	
	public void test() throws LDAPException
	{
		LDAPConnection connection = connect();
		RootDSE root = connection.getRootDSE();
		
		String[] baseDNs = root.getNamingContextDNs();
		for (String baseDN: baseDNs) {
			SearchResult sr = connection.search(baseDN, SearchScope.SUB, "(objectClass=inetOrgPerson)", "dn");
			if (sr.getResultCode() != ResultCode.SUCCESS) {
				Log.e(TAG, "Search failed:" + sr.getDiagnosticMessage());
				continue;
			}
			for (SearchResultEntry entry: sr.getSearchEntries()) {
				Log.i(TAG, "Found " + entry.getDN());
			}
		}
	}
	
	
}
