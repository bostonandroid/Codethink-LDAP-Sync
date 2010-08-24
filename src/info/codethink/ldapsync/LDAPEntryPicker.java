package info.codethink.ldapsync;

import java.util.ArrayList;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.RDN;

public class LDAPEntryPicker extends ListActivity {
	private static final String TAG = "LDAPEntryPicker";
	
	public static final String KEY_DN = "codethink.dn";
	public static final String KEY_SETTINGS = "codethink.settings";
	public static final String KEY_CHILDREN = "codethink.children";
	private static final String KEY_HAS_PARENT = "codethink.hasparent";

	private static final int DIALOG_LOAD_FAILED = 0;

	private static final int RESULT_PICK_AGAIN = RESULT_FIRST_USER;

	Bundle mSettings;
	String mDN;
	private RetrieveEntriesTask mTask;
	private ArrayList<String> mEntries;
	private ArrayAdapter<String> mAdapter;
	private boolean mHasParent;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Log.v(TAG, "onCreate(" + savedInstanceState + ")");

		Intent intent = getIntent();
		mSettings = intent.getBundleExtra(KEY_SETTINGS);
		mDN = intent.getStringExtra(KEY_DN);
		mHasParent = intent.getBooleanExtra(KEY_HAS_PARENT, false);

		setContentView(R.layout.pickerlist);
		mAdapter = new ArrayAdapter<String>(this, R.layout.pickerlistentry);
		setListAdapter(mAdapter);
		
		if (!mHasParent && getParentDN() == null) {
			Button parentButton = (Button)findViewById(R.id.parentbutton);
			parentButton.setEnabled(false);
		}
		
		TextView dnView = (TextView)findViewById(R.id.dn);
		dnView.setText(mDN);

		if (savedInstanceState == null) {
			retrieveChildren();
		} else {
			mEntries = savedInstanceState.getStringArrayList(KEY_CHILDREN);
			if (mEntries == null)
				retrieveChildren();
			else
				fillAdapter();
		}
	}
	
	private String getParentDN()
	{
		try {
			return DN.getParentString(mDN);
		} catch (LDAPException e) {
			return null; // DN parse error, treat entry as a root
		}
	}
	
	@Override
	protected void onListItemClick(ListView l, View v, int position, long id) {
		super.onListItemClick(l, v, position, id);

			Log.v(TAG, "Invoking child picker");
			try {
				RDN rdn = new RDN(mAdapter.getItem(position));
				DN childDN = new DN(rdn, new DN(mDN));
				pickFromDN(childDN.toString(), true);
			} catch (LDAPException e) {
				Log.d(TAG, "Error building child DN", e);
				// TODO: show message, just in case
			}
	}
	
	public void handleUseThisClick(View view)
	{
		Log.v(TAG, "Choosing this DN");
		Intent data = new Intent();
		data.putExtra(KEY_DN, mDN);
		setResult(RESULT_OK, data);
		finish();
	}
	
	public void handleParentClick(View view)
	{
		if (mHasParent) {
			setResult(RESULT_PICK_AGAIN);
			finish();
		} else {
			Log.v(TAG, "Following parent link by activating new parent activity");
			pickFromDN(getParentDN(), false);
		}
	}

	private void pickFromDN(String newDN, boolean isChild) {
		Intent pickChildIntent = new Intent(this, getClass());
		pickChildIntent.putExtra(KEY_SETTINGS, mSettings);
		pickChildIntent.putExtra(KEY_HAS_PARENT, isChild);
		pickChildIntent.putExtra(KEY_DN, newDN);
		startActivityForResult(pickChildIntent, 0);
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		
		Log.v(TAG, "onActivityResult got result code " + resultCode);

		if (resultCode == RESULT_OK) { // propagate selections back to original requester
			setResult(RESULT_OK, data);
			finish();
		}
		// ignore CANCELED children to allow the user to pick another or cancel here
	}
		
	private void fillAdapter() {
		mAdapter = new ArrayAdapter<String>(this, R.layout.pickerlistentry, mEntries);
		setListAdapter(mAdapter);
		
		TextView emptyView = (TextView)findViewById(android.R.id.empty);
		emptyView.setText(R.string.nochildentries);
	}

	private void retrieveChildren() {
		mTask = new RetrieveEntriesTask();
		mTask.execute(mSettings, mDN);
		// TODO: show loading indicator
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putStringArrayList(KEY_CHILDREN, mEntries);
	}
	
	@Override
	protected void onDestroy() {
		if (mTask != null && mTask.mFailureMessage == null)
			mTask.cancel(true);
		super.onDestroy();
	}
	
	@Override
	protected Dialog onCreateDialog(int id) {
		if (id == DIALOG_LOAD_FAILED)
			return new AlertDialog.Builder(this)
				.setMessage(mTask.mFailureMessage)
				.setPositiveButton(R.string.ok, new OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss(); setResult(RESULT_CANCELED); finish();
					}
				})
				.create();
		return super.onCreateDialog(id);
	}
	
	@Override
	protected void onPrepareDialog(int id, Dialog dialog) {
		if (id == DIALOG_LOAD_FAILED)
			((AlertDialog)dialog).setMessage(mTask.mFailureMessage);
		super.onPrepareDialog(id, dialog);
	}

	private class RetrieveEntriesTask extends AsyncTask<Object, Void, ArrayList<String>> {
		String mFailureMessage;

		@Override
		protected ArrayList<String> doInBackground(Object... params) {
			Log.v(TAG, "RetrieveEntriesTask downloading children of " + params[1] + "...");
			LDAPContactSource src = new LDAPContactSource((Bundle)params[0]);
			try {
				src.connect();
			} catch (LDAPException e) {
				Log.d(TAG, "Browser LDAP connection failed", e);
				mFailureMessage = "Could not connect to LDAP server: " + e.getLocalizedMessage();
				return null;
			}
			Log.v(TAG, "RetrieveEntriesTask connected to LDAP server.");
			try {
				String searchDN = (String)params[1];
				if (searchDN.length() == 0) searchDN = src.getRootDN();

				ArrayList<String> children = new ArrayList<String>();
				src.browse(searchDN, children);
				Log.v(TAG, "RetrieveEntriesTask returning " + children.size() + " entries...");
				
				for (int i = 0; i < children.size(); i++)
					children.set(i, new DN(children.get(i)).getRDNString());
				
				return children;
			} catch (LDAPException e) {
				Log.d(TAG, "Browser LDAP search failed", e);
				mFailureMessage = "Could not find entries in " + mDN + ": " + e.getLocalizedMessage();
				return null;
			} finally {
				src.close();
			}
		}
		@Override
		protected void onPostExecute(ArrayList<String> result) {
			super.onPostExecute(result);
			if (isCancelled()) return;
			if (result == null) {
				showDialog(DIALOG_LOAD_FAILED);
				return;
			}
			mTask = null;
			mEntries = result;
			fillAdapter();
		}
	}
}
