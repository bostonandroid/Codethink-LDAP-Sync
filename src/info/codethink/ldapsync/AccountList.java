package info.codethink.ldapsync;

import java.io.IOException;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

public class AccountList extends ListActivity {
	private static final String TAG = "LDAPAccountList";
	private static final int DIALOG_DELETE_FAILED = 0;
	
	private AccountManager mMgr;
	private ArrayAdapter<String> mAccountList;

	/** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
    	Log.v(TAG, "AccountList onCreate(" + savedInstanceState + ")");
        super.onCreate(savedInstanceState);
        
        setContentView(R.layout.accountlist);
        registerForContextMenu(getListView());
               
        mAccountList = new ArrayAdapter<String>(this, R.layout.accountlistentry, R.id.accountname);
        setListAdapter(mAccountList);
        
    }
    
    @Override
    public void onResume()
    {
    	super.onResume();
    	loadAccountData();
    }
    
    @Override
    public Dialog onCreateDialog(int id)
    {
    	if (id == DIALOG_DELETE_FAILED) {
    		return new AlertDialog.Builder(this)
    			.setMessage(R.string.deletefailed)
    			.setCancelable(true)
    			.setPositiveButton(R.string.ohwell, new OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
					}
				}).create();
    	}
    	return super.onCreateDialog(id);
    }

	private void loadAccountData() {
		mAccountList.setNotifyOnChange(false);
		mAccountList.clear();
		
		mMgr = AccountManager.get(this);
        Account[] accounts = mMgr.getAccountsByType(LDAPAuthenticator.ACCOUNT_TYPE);
        for (int i = 0; i < accounts.length; i++) {
        	Log.v(TAG, "Loaded LDAP account " + accounts[i].name);
        	mAccountList.add(accounts[i].name);
        }
        if (accounts.length == 0)
        	Log.v(TAG, "No accounts found");
        
        mAccountList.setNotifyOnChange(true);
        mAccountList.notifyDataSetChanged();
	}
    
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo)
    {
    	super.onCreateContextMenu(menu, v, menuInfo);
    	getMenuInflater().inflate(R.menu.accountlistcontext, menu);
    }
       
    @Override
    public boolean onContextItemSelected(MenuItem item)
    {
    	AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
    	
    	switch (item.getItemId()) {
    	case R.id.delete: doDeleteAccount(info.targetView); return true;
    	case R.id.edit: doEditAccount(info.targetView); return true;
    	default: return super.onContextItemSelected(item);
    	}
    }
    
    @Override
    public void onListItemClick(ListView lv, View clickedView, int position, long id)
    {
    	doEditAccount(clickedView);
    }

    private void doDeleteAccount(View listItem)
    {
    	final String accountName = extractAccountName(listItem);

		final AlertDialog confirmDialog = new AlertDialog.Builder(this)
		.setMessage(R.string.reallydeleteaccount)
		.setCancelable(true)
		.setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
		    	dialog.dismiss();
		    	
		    	final ProgressDialog progress = ProgressDialog.show(AccountList.this, null, getString(R.string.deletingaccount), true);

		    	Account acct = new Account(accountName, LDAPAuthenticator.ACCOUNT_TYPE);
		    	mMgr.removeAccount(acct, new AccountManagerCallback<Boolean>() {
					public void run(AccountManagerFuture<Boolean> result) {
						progress.dismiss();
						
						try {
							if (result.getResult()) {
								loadAccountData();
							} else {
								showDialog(DIALOG_DELETE_FAILED);
							}
						} catch (OperationCanceledException e) {
							showDialog(DIALOG_DELETE_FAILED);
							Log.e(TAG, "Failed to delete account " + accountName, e);
						} catch (AuthenticatorException e) {
							showDialog(DIALOG_DELETE_FAILED);
							Log.e(TAG, "Failed to delete account " + accountName, e);
						} catch (IOException e) {
							showDialog(DIALOG_DELETE_FAILED);
							Log.e(TAG, "Failed to delete account " + accountName, e);
						}
					}
		    	}, null);
			}
		})
		.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				dialog.cancel();
			}
		})
		.create();
		confirmDialog.setOwnerActivity(this);
		confirmDialog.show();
    }
    
	private void doEditAccount(View listItem) {
		String accountName = extractAccountName(listItem);
    	Intent editIntent = new Intent(this, LDAPAuthenticatorActivity.class);
    	editIntent.setAction(Intent.ACTION_EDIT);
    	editIntent.putExtra(AccountManager.KEY_ACCOUNT_NAME, accountName);
    	startActivity(editIntent);
	}

	private static String extractAccountName(View listItem) {
		TextView nameView = (TextView) listItem.findViewById(R.id.accountname);
    	
    	return nameView.getText().toString();
	}
    
    public void onCreateAccountClicked(View view)
    {
    	Intent insertIntent = new Intent(this, LDAPAuthenticatorActivity.class);
    	insertIntent.setAction(Intent.ACTION_INSERT);
    	startActivity(insertIntent);
    }
}
