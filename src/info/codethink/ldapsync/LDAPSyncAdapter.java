package info.codethink.ldapsync;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.SyncResult;
import android.content.res.Resources;
import android.database.Cursor;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.util.Log;

import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.RootDSE;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchResultListener;
import com.unboundid.ldap.sdk.SearchResultReference;
import com.unboundid.ldap.sdk.SearchScope;

public class LDAPSyncAdapter extends AbstractThreadedSyncAdapter {
	private final static String TAG = "LDAPSyncAdapter";
	
	private final Context mContext;
	
	private class SyncSearchListener implements SearchResultListener {
		private static final long serialVersionUID = 1L; // why is SearchResultListener serializable?
		private final ContentProviderClient mProvider;
		private final List<LDAPSyncMapping> mMapping;
		private final Account mAccount;
		private final SyncResult mSyncResult;

		private SyncSearchListener(ContentProviderClient provider,
				List<LDAPSyncMapping> mapping, Account account, SyncResult syncResult) {
			this.mProvider = provider;
			this.mMapping = mapping;
			this.mAccount = account;
			this.mSyncResult = syncResult;
		}

		// references unsupported, ignore
		public void searchReferenceReturned(SearchResultReference searchReference) {}

		public void searchEntryReturned(SearchResultEntry searchEntry) {
			String dn = searchEntry.getDN();
			String[] columns = new String[] { RawContacts._ID };
			String conditions = RawContacts.ACCOUNT_TYPE + " = '" + LDAPAuthenticator.ACCOUNT_TYPE + "' AND " +
				RawContacts.SOURCE_ID + " = ?";
			long rawContactId = -1;
			Log.v(TAG, "Syncing contact with DN " + dn);
			try {
				Cursor result = mProvider.query(RawContacts.CONTENT_URI, columns, conditions, new String[]{dn}, null);
				try {
					if (result.moveToFirst())
						rawContactId = result.getLong(0);
				} finally {
					result.close();
				}
			} catch (RemoteException e) {
				mSyncResult.databaseError = true;
				mSyncResult.stats.numSkippedEntries++;
				Log.i(TAG, "query for local contact failed", e);
				return;
			}
			ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
			if (rawContactId == -1) {
				ContentProviderOperation.Builder builder = ContentProviderOperation.newInsert(Utils.syncURI(RawContacts.CONTENT_URI));
				builder.withValue(RawContacts.ACCOUNT_NAME, mAccount.name);
				builder.withValue(RawContacts.ACCOUNT_TYPE, mAccount.type);
				builder.withValue(RawContacts.SOURCE_ID, dn);
				ops.add(builder.build());
				
				for (LDAPSyncMapping m: mMapping) {
					m.buildInsert(ops, 0, searchEntry);
				}
				mSyncResult.stats.numInserts++;
			} else {
				// drop all data from existing row and replace
				ContentProviderOperation.Builder builder = ContentProviderOperation.newDelete(Utils.syncURI(Data.CONTENT_URI));
				builder.withSelection(Data.RAW_CONTACT_ID + " = ?", new String[]{""+rawContactId});
				ops.add(builder.build());

				for (LDAPSyncMapping m: mMapping) {
					m.buildReplace(ops, rawContactId, searchEntry);
				}
				mSyncResult.stats.numUpdates++;
			}
			try {
				mProvider.applyBatch(ops);
			} catch (RemoteException e) {
				Log.e(TAG, "Could not sync contact", e);
				mSyncResult.stats.numSkippedEntries++;
			} catch (OperationApplicationException e) {
				Log.e(TAG, "Could not sync contact", e);
				mSyncResult.databaseError = true;
				mSyncResult.stats.numSkippedEntries++;
			}
		}
	}
	
	public LDAPSyncAdapter(Context ctx)
	{
		super(ctx, true);
		
		mContext = ctx;
	}
	
	@Override
	public void onPerformSync(final Account account, Bundle extras, String authority,
			final ContentProviderClient provider, final SyncResult syncResult) {
		
		Log.i(TAG, "Syncing account " + account.type + "/" + account.name + " for authority " + authority + "...");
		
		if (!LDAPAuthenticator.ACCOUNT_TYPE.equals(account.type))
			throw new IllegalArgumentException("Account type must be " + LDAPAuthenticator.ACCOUNT_TYPE);
		if (!authority.equals("com.android.contacts"))
			throw new IllegalArgumentException("Can't sync authority " + authority);
		
		final List<LDAPSyncMapping> mapping;
		InputStream mappingXml = null;
		try {
			Resources r = mContext.getResources();
			mappingXml = r.openRawResource(R.raw.basicmapping);
			mapping = new LDAPSyncMapping.Parser().read(mappingXml);
		} catch (Exception ex) {
			Log.e(TAG, "Could not load mapping config", ex);
			syncResult.databaseError = true;
			return;
		} finally {
			if (mappingXml != null)
				try { mappingXml.close();
				} catch (IOException e) {}
		}
		if (mapping.size() == 0) {
			syncResult.databaseError = true;
			return;
		}

		LDAPContactSource src = new LDAPContactSource(mContext, account);
		LDAPConnection connection = null;
		try {
			connection = src.connect();
		} catch (LDAPException e) {
			Log.e(TAG, "Failed to connect to LDAP server for sync", e);
			if (e.getResultCode() == ResultCode.INVALID_CREDENTIALS) {
				syncResult.stats.numAuthExceptions++;
			} else {
				syncResult.stats.numIoExceptions++;
			}
			return;
		}
		
  		try {
  			RootDSE root = connection.getRootDSE();
  			String baseDN = root.getNamingContextDNs()[0]; // TODO: allow user to customize search parameters!

  			SearchResultListener listener = new SyncSearchListener(provider, mapping, account, syncResult);
			SearchRequest request = new SearchRequest(listener,	baseDN, SearchScope.SUB, "(objectClass=inetOrgPerson)");
			SearchResult result = connection.search(request);
			if (result.getResultCode() != ResultCode.SUCCESS) {
				syncResult.stats.numIoExceptions++;
				Log.w(TAG, "LDAP search result returned: " + result.getDiagnosticMessage());
			}
		} catch (LDAPException e)  {
			syncResult.stats.numIoExceptions++;
		} finally {
			connection.close();
		}
	}
}
