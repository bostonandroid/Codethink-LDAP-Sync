package info.codethink.ldapsync;

import java.io.InputStream;
import java.util.ArrayList;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentProviderOperation.Builder;
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

import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchResultListener;
import com.unboundid.ldap.sdk.SearchResultReference;

public class LDAPSyncAdapter extends AbstractThreadedSyncAdapter {
	private final static String TAG = "LDAPSyncAdapter";
	
	private final Context mContext;
	
	public interface BuilderBuilder {
		ContentProviderOperation.Builder newInsert();
	}
	
	private class SyncSearchListener implements SearchResultListener {
		private static final long serialVersionUID = 1L; // why is SearchResultListener serializable?
		private final ContentProviderClient mProvider;
		private final LDAPSyncMapping mMapping;
		private final Account mAccount;
		private final SyncResult mSyncResult;
		private final ArrayList<ContentProviderOperation> mBatch;

		private SyncSearchListener(ContentProviderClient provider,
				LDAPSyncMapping mapping, Account account, SyncResult syncResult) {
			this.mProvider = provider;
			this.mMapping = mapping;
			this.mAccount = account;
			this.mSyncResult = syncResult;
			this.mBatch = new ArrayList<ContentProviderOperation>();
		}

		// references unsupported, ignore
		public void searchReferenceReturned(SearchResultReference searchReference) {}

		public void searchEntryReturned(SearchResultEntry searchEntry) {
			String dn = searchEntry.getDN();
			String[] columns = new String[] { RawContacts._ID };
			String conditions = RawContacts.ACCOUNT_TYPE + " = '" + LDAPAuthenticator.ACCOUNT_TYPE + "' AND " +
				RawContacts.SOURCE_ID + " = ?";
			final long rawContactId;
			Log.v(TAG, "Syncing contact with DN " + dn);
			try {
				Cursor result = mProvider.query(RawContacts.CONTENT_URI, columns, conditions, new String[]{dn}, null);
				try {
					if (result.moveToFirst())
						rawContactId = result.getLong(0);
					else
						rawContactId = -1;
				} finally {
					result.close();
				}
			} catch (RemoteException e) {
				mSyncResult.databaseError = true;
				mSyncResult.stats.numSkippedEntries++;
				Log.i(TAG, "query for local contact failed", e);
				return;
			}
			if (rawContactId == -1) {
				ContentProviderOperation.Builder builder = ContentProviderOperation.newInsert(Utils.syncURI(RawContacts.CONTENT_URI));
				builder.withValue(RawContacts.ACCOUNT_NAME, mAccount.name);
				builder.withValue(RawContacts.ACCOUNT_TYPE, mAccount.type);
				builder.withValue(RawContacts.SOURCE_ID, dn);
				mBatch.add(builder.build());
				final int rawContactRef = mBatch.size() - 1;
				mMapping.buildData(mBatch, searchEntry, new BuilderBuilder() {
					public Builder newInsert() {
						Builder result = ContentProviderOperation.newInsert(Utils.syncURI(Data.CONTENT_URI)); 
						result.withValueBackReference(Data.RAW_CONTACT_ID, rawContactRef);
						return result;
					}
				});
				mSyncResult.stats.numInserts++;
			} else {
				// drop all data from existing row and replace
				ContentProviderOperation.Builder builder = ContentProviderOperation.newDelete(Utils.syncURI(Data.CONTENT_URI));
				builder.withSelection(Data.RAW_CONTACT_ID + " = ?", new String[]{""+rawContactId});
				mBatch.add(builder.build());
				mMapping.buildData(mBatch, searchEntry, new BuilderBuilder() {
					public Builder newInsert() {
						Builder result = ContentProviderOperation.newInsert(Utils.syncURI(Data.CONTENT_URI)); 
						result.withValue(Data.RAW_CONTACT_ID, rawContactId);
						return result;
					}
				});
				mSyncResult.stats.numUpdates++;
			}
			
			if (mBatch.size() >= 50) {
				applyChanges();
			}
		}

		public void applyChanges() {
			try {
				Log.v(TAG, "Applying " + mBatch.size() + " operations to contacts DB...");
				mProvider.applyBatch(mBatch);
				mBatch.clear();
			} catch (RemoteException e) {
				Log.e(TAG, "Could not sync contacts", e);
				mSyncResult.databaseError = true;
			} catch (OperationApplicationException e) {
				Log.e(TAG, "Could not sync contact", e);
				mSyncResult.databaseError = true;
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
		
		final LDAPSyncMapping mapping;
		InputStream mappingXml = null;
		try {
			Resources r = mContext.getResources();
			mappingXml = r.openRawResource(R.raw.basicmapping);
			mapping = new LDAPSyncMapping(mappingXml);
		} catch (Exception ex) {
			Log.e(TAG, "Could not load mapping config", ex);
			syncResult.databaseError = true;
			// TODO: log errors somewhere where the UI can get at them
			return;
		} finally {
			if (mappingXml != null)
				try { mappingXml.close(); } catch (Exception e) {}
		}

		LDAPContactSource src = new LDAPContactSource(mContext, account);
		try {
			src.connect();
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
  			SyncSearchListener listener = new SyncSearchListener(provider, mapping, account, syncResult);
			src.search(listener);
			Log.v(TAG, "Search complete, applying remaining changes...");
			listener.applyChanges();
			Log.v(TAG, "...sync complete.");
		} catch (LDAPException e)  {
			Log.e(TAG, "LDAP search failed", e);
			syncResult.stats.numIoExceptions++;
		} catch (Exception e) {
			Log.e(TAG, "Unrecognized error occurred, aborting sync", e);
			syncResult.databaseError = true;
		} finally {
			src.close();
		}
	}
}
