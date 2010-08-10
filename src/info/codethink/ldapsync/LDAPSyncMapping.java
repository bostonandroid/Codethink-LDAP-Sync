package info.codethink.ldapsync;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.Data;
import android.sax.Element;
import android.sax.RootElement;
import android.sax.StartElementListener;
import android.util.Log;
import android.util.Xml;
import android.util.Xml.Encoding;

import com.unboundid.ldap.sdk.SearchResultEntry;

class LDAPSyncMapping {
	private static final String TAG = "LDAPSyncMapping";
	
	private static class Value {
		public String columnName;
		public boolean isLiteral;
		public String value;
		public Value(String columnName, boolean isLiteral, String value) {
			this.columnName = columnName;
			this.isLiteral = isLiteral;
			this.value = value;
		}
	}
	
	public static class Parser {
		boolean parseError = false;
		
		public List<LDAPSyncMapping> read(InputStream mappingXml)
		{
			final ArrayList<LDAPSyncMapping> rows = new ArrayList<LDAPSyncMapping>();

			RootElement root = new RootElement("ldapsyncmapping");
			Element row = root.getChild("row");
			row.setStartElementListener(new StartElementListener() {
				public void start(Attributes attributes) {
					final LDAPSyncMapping newRow = new LDAPSyncMapping();
					final String type = attributes.getValue("type");
					if (parseError || type == null) {
						parseError = true;
						return;
					}
					try {
						newRow.setType(type);
					} catch (ClassNotFoundException e) {
						Log.e(TAG, "Can't find CommonDataKind type " + type, e);
						parseError = true; return;
					} catch (IllegalAccessException e) {
						Log.e(TAG, "Can't find CommonDataKind type " + type, e);
						parseError = true; return;
					} catch (NoSuchFieldException e) {
						Log.e(TAG, "Can't find CommonDataKind type " + type, e);
						parseError = true; return;
					}
					rows.add(newRow);
				}
			});
			row.getChild("field").setStartElementListener(new StartElementListener() {
				public void start(Attributes attributes) {
					if (parseError)
						return;
					LDAPSyncMapping curRow = rows.get(rows.size()-1);
					String column = attributes.getValue("column");
					String ldapattr = attributes.getValue("ldapattr");
					String typeattr = attributes.getValue("typeattr");
					if (ldapattr == null && typeattr == null) {
						Log.e(TAG, "<field .../> must have either an ldapattr or a typeattr attribute");
						parseError = true; return;
					}
					try {
						if (ldapattr != null) {
							curRow.addLDAPField(column, ldapattr);
						} else {
							curRow.addTypeAttrField(column, typeattr);
						}
					} catch (IllegalAccessException e) {
						Log.e(TAG, "Can't set column " + column + " to " + curRow.mType + "." + typeattr, e);
						parseError = true; return;
					} catch (NoSuchFieldException e) {
						Log.e(TAG, "Can't set column " + column + " to " + curRow.mType + "." + typeattr, e);
						parseError = true; return;
					}
				}
			});
			
			try {
				Xml.parse(mappingXml, Encoding.UTF_8, root.getContentHandler());
			} catch (IOException e) {
				Log.e(TAG, "Can't read LDAP sync mapping file", e);
			} catch (SAXException e) {
				Log.e(TAG, "Can't read LDAP sync mapping file", e);
			}
			
			return rows;
		}
	}

	private Class<?> mType;
	private String mMimeType;
	private final ArrayList<LDAPSyncMapping.Value> mValues = new ArrayList<LDAPSyncMapping.Value>();
	
	public void setType(String type) throws ClassNotFoundException, IllegalAccessException, NoSuchFieldException
	{
		mType = Class.forName(CommonDataKinds.class.getName() + "$" + type);
		mMimeType = mType.getField("CONTENT_ITEM_TYPE").get(null).toString();
	}
	private String resolveColumnName(String column)	throws IllegalAccessException, NoSuchFieldException {
		column = mType.getField(column).get(null).toString();
		return column;
	}
	public void addLDAPField(String column, String ldapattr) throws IllegalAccessException, NoSuchFieldException {
		column = resolveColumnName(column);
		mValues.add(new Value(column, false, ldapattr));
	}
	public void addTypeAttrField(String column, String typeattr) throws IllegalAccessException, NoSuchFieldException {
		column = resolveColumnName(column);
		String value = mType.getField(typeattr).get(null).toString();
		mValues.add(new Value(column, true, value));
	}
	public void buildInsert(ArrayList<ContentProviderOperation> ops, int rawIdReference, SearchResultEntry data)
	{
		ContentProviderOperation.Builder builder = ContentProviderOperation.newInsert(Utils.syncURI(Data.CONTENT_URI));
		builder.withValueBackReference(Data.RAW_CONTACT_ID, rawIdReference);
		fillBuilder(ops, data, builder);
	}
	public void buildReplace(ArrayList<ContentProviderOperation> ops, long rawContactId, SearchResultEntry data)
	{
		ContentProviderOperation.Builder builder = ContentProviderOperation.newInsert(Utils.syncURI(Data.CONTENT_URI));
		builder.withValue(Data.RAW_CONTACT_ID, rawContactId);
		fillBuilder(ops, data, builder);
	}
	private void fillBuilder(ArrayList<ContentProviderOperation> ops,
			SearchResultEntry data, ContentProviderOperation.Builder builder) {
		int attribsFound = 0, attribsMissing = 0;
		builder.withValue(Data.MIMETYPE, mMimeType);
		StringBuilder msg = new StringBuilder("Adding " + mMimeType + " record with ");
		for (LDAPSyncMapping.Value val: mValues) {
			if (val.isLiteral) {
				builder.withValue(val.columnName, val.value);
				msg.append(val.columnName + "=" + val.value + ", ");
			} else if (data.hasAttribute(val.value)) {
				builder.withValue(val.columnName, data.getAttribute(val.value).getValue()); // TODO: handle multiple values
				msg.append(val.columnName + "=" + data.getAttribute(val.value).getValue() + ", ");
				attribsFound++;
			} else {
				attribsMissing++;
			}
		}
		if (attribsFound > 0 || attribsMissing == 0) {
			Log.d(TAG, msg.toString());
			ops.add(builder.build());
		}
	}
}
