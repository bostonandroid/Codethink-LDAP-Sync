package info.codethink.ldapsync;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.SearchResultEntry;

import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentProviderOperation.Builder;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.Data;
import android.sax.Element;
import android.sax.RootElement;
import android.sax.StartElementListener;
import android.util.Log;
import android.util.Xml;
import android.util.Xml.Encoding;


class LDAPSyncMapping {
	static final String TAG = "LDAPSyncMapping";
	
	private static final Object MIME_LDAP_ATTRIBUTE = "vnd.android.cursor.item/vnd.info.codethink.ldap.contactldifentry";
	private static final String COLUMN_ATTRIB_NAME = Data.DATA1;
	private static final String COLUMN_ATTRIB_INDEX = Data.DATA2;
	private static final String COLUMN_ATTRIB_DATA = Data.DATA15;

	public static class Value {
		public String columnName;
		public boolean isLiteral;
		public String value;
		public boolean isBlob;
		
		public Value(String columnName, boolean isLiteral, String value, boolean isBlob) {
			this.columnName = columnName;
			this.isLiteral = isLiteral;
			this.value = value;
			this.isBlob = isBlob;
		}
	}
	
	public static class RowBuilder {

		private Class<?> mType;
		private String mMimeType;
		private final ArrayList<LDAPSyncMapping.Value> mValues = new ArrayList<LDAPSyncMapping.Value>();

		public void setType(String type) throws ClassNotFoundException,
				IllegalAccessException, NoSuchFieldException {
			mType = Class.forName(CommonDataKinds.class.getName() + "$" + type);
			mMimeType = mType.getField("CONTENT_ITEM_TYPE").get(null).toString();
		}

		private String resolveColumnName(String column)
				throws IllegalAccessException, NoSuchFieldException {
			column = mType.getField(column).get(null).toString();
			return column;
		}

		public void addLDAPField(String column, String ldapattr, boolean isBlob)
				throws IllegalAccessException, NoSuchFieldException {
			column = resolveColumnName(column);
			mValues.add(new Value(column, false, ldapattr, isBlob));
		}

		public void addTypeAttrField(String column, String typeattr)
				throws IllegalAccessException, NoSuchFieldException {
			column = resolveColumnName(column);
			String value = mType.getField(typeattr).get(null).toString();
			mValues.add(new Value(column, true, value, false));
		}

		public void buildInsert(ArrayList<ContentProviderOperation> ops, int rawIdReference, SearchResultEntry data, Set<String> mappedAttribs) {
			List<Builder> rows = buildRows(data, mappedAttribs);
			for (Builder builder: rows) {
				builder.withValueBackReference(Data.RAW_CONTACT_ID, rawIdReference);
				ops.add(builder.build());
			}
		}

		public void buildReplace(ArrayList<ContentProviderOperation> ops, long rawContactId, SearchResultEntry data, Set<String> mappedAttribs) {
			List<Builder> rows = buildRows(data, mappedAttribs);
			for (Builder builder: rows) {
				builder.withValue(Data.RAW_CONTACT_ID, rawContactId);
				ops.add(builder.build());
			}
		}

		private List<ContentProviderOperation.Builder> buildRows(SearchResultEntry data, Set<String> mappedAttribs) {
			int numDynamicAttribs = 0; // TODO: make this a member boolean

			ArrayList<Builder> builders = new ArrayList<Builder>(5);
			StringBuilder msg = new StringBuilder("Adding " + mMimeType + " records with ");
			for (LDAPSyncMapping.Value val: mValues) {
				if (val.isLiteral) continue;
				numDynamicAttribs++;
				if (!data.hasAttribute(val.value)) continue;

				mappedAttribs.add(val.value);
				if (val.isBlob) {
					byte[][] values = data.getAttributeValueByteArrays(val.value);
					addBuilders(builders, values.length);
					for (int i = 0; i < values.length; i++) {
						builders.get(i).withValue(val.columnName, values[i]);
						msg.append(val.columnName + "[" + i + "] = <" + values[i].length + " bytes>, ");
					}
				} else {
					String values[] = data.getAttributeValues(val.value);
					addBuilders(builders, values.length);
					for (int i = 0; i < values.length; i++) {
						builders.get(i).withValue(val.columnName, values[i]);
						msg.append(val.columnName + "[" + i + "] =" + values[i] + ", ");
					}
				}
			}
			
			if (numDynamicAttribs == 0) addBuilders(builders, 1);
			
			if (builders.isEmpty())
				return builders;
				
			Log.d(TAG, msg.toString());

			for (Builder builder: builders) { // fill in literal values and MIME type
				builder.withValue(Data.MIMETYPE, mMimeType);
				for (Value val: mValues) {
					if (!val.isLiteral) continue;
					builder.withValue(val.columnName, val.value);
				}
			}
			
			return builders;
		}

		private void addBuilders(ArrayList<Builder> builders, int length) {
			while (builders.size() < length)
				builders.add(ContentProviderOperation.newInsert(Utils.syncURI(Data.CONTENT_URI)));
		}

		public void buildLDIFEntry(ContentProviderClient provider,
				long rawContactId, Entry entry) {
			// TODO Auto-generated method stub
			
		}
	}
	
	public static class ParseError extends Exception {
		public ParseError(String message, Throwable cause) {super(message, cause);}
		private static final long serialVersionUID = 1L;
	}

	public static class Parser {
		Throwable parseErrorCause = null;
		String parseErrorMessage = null;
		
		private static class UncheckedParseError extends RuntimeException {
			// this is used internally to get exceptions out of listeners
			public UncheckedParseError(String message, Throwable cause) {super(message, cause);}
			public UncheckedParseError(String message) {super(message);}
			private static final long serialVersionUID = 1L;
		}
		
		public List<RowBuilder> read(InputStream mappingXml) throws ParseError
		{
			Log.d(TAG, "New LDAPSyncMapping.Parser...");
			final ArrayList<RowBuilder> rows = new ArrayList<RowBuilder>();

			RootElement root = new RootElement("ldapsyncmapping");
			Element row = root.getChild("row");
			row.setStartElementListener(new StartElementListener() {
				public void start(Attributes attributes) {
					final RowBuilder newRow = new RowBuilder();
					final String type = attributes.getValue("type");
					Log.d(TAG, "  <row type='" + type + "'>");
					if (type == null) {
						throw new UncheckedParseError("row tag with no type attribute");
					}
					try {
						newRow.setType(type);
					} catch (ClassNotFoundException e) {
						throw new UncheckedParseError("Can't find CommonDataKind type " + type, e);
					} catch (IllegalAccessException e) {
						throw new UncheckedParseError("Can't find CommonDataKind type " + type, e);
					} catch (NoSuchFieldException e) {
						throw new UncheckedParseError("Can't find CommonDataKind type " + type, e);
					}
					rows.add(newRow);
				}
			});
			row.getChild("field").setStartElementListener(new StartElementListener() {
				public void start(Attributes attributes) {
					RowBuilder curRow = rows.get(rows.size()-1);
					String column = attributes.getValue("column");
					String ldapattr = attributes.getValue("ldapattr");
					String typeattr = attributes.getValue("typeattr");
					boolean isBlob = attributes.getValue("blob") != null;
					Log.d(TAG, "    <field column='" + column + "' ldapattr='" + ldapattr + "'>");
					if (ldapattr == null && typeattr == null) {
						throw new UncheckedParseError("<field .../> must have either an ldapattr or a typeattr attribute");
					}
					if (isBlob && typeattr != null) {
						throw new UncheckedParseError("<field typeattr=\"...\"/> can't have isBlob attribute");
					}
					try {
						if (ldapattr != null) {
							curRow.addLDAPField(column, ldapattr, isBlob);
						} else {
							curRow.addTypeAttrField(column, typeattr);
						}
					} catch (IllegalAccessException e) {
						throw new UncheckedParseError("Can't set column " + column + " to " + curRow.mType + "." + typeattr, e);
					} catch (NoSuchFieldException e) {
						throw new UncheckedParseError("Can't set column " + column + " to " + curRow.mType + "." + typeattr, e);
					}
				}
			});
			
			try {
				Xml.parse(mappingXml, Encoding.UTF_8, root.getContentHandler());
			} catch (IOException e) {
				throw new ParseError("Can't read LDAP sync mapping file", e);
			} catch (SAXException e) {
				throw new ParseError("Can't read LDAP sync mapping file", e);
			} catch (UncheckedParseError e) {
				throw new ParseError(e.getMessage(), e);
			}
			
			return rows;
		}
	}
	
	List<RowBuilder> mRows;
	LDAPSyncMapping(InputStream mappingXml) throws ParseError
	{
		mRows = new Parser().read(mappingXml);
	}
	
	public void addUnmappedAttributes(
			ArrayList<ContentProviderOperation> ops,
			SearchResultEntry data, Set<String> mappedAttribs) {
		for (Attribute attrib: data.getAttributes()) {
			if (mappedAttribs.contains(attrib.getName()))
				continue;
			int i = 0;
			for (byte[] value: attrib.getValueByteArrays()) {
				Builder b = ContentProviderOperation.newInsert(Utils.syncURI(Data.CONTENT_URI));
				b.withValue(Data.MIMETYPE, MIME_LDAP_ATTRIBUTE);
				b.withValue(COLUMN_ATTRIB_NAME, attrib.getName());
				b.withValue(COLUMN_ATTRIB_INDEX, ""+(i++));
				b.withValue(COLUMN_ATTRIB_DATA, value);
				ops.add(b.build());
			}
		}
	}
	
	public void buildInsertOps(ArrayList<ContentProviderOperation> ops, int rawIdReference, SearchResultEntry entry)
	{
		HashSet<String> mappedAttribs = new HashSet<String>();
		for (RowBuilder row: mRows) {
			row.buildInsert(ops, rawIdReference, entry, mappedAttribs);
		}
		addUnmappedAttributes(ops, entry, mappedAttribs);
	}
	public void buildReplaceOps(ArrayList<ContentProviderOperation> ops, long rawContactId, SearchResultEntry entry)
	{
		HashSet<String> mappedAttribs = new HashSet<String>();
		for (RowBuilder row: mRows) {
			row.buildReplace(ops, rawContactId, entry, mappedAttribs);
		}
		addUnmappedAttributes(ops, entry, mappedAttribs);
	}
	
	public Entry buildLDIFEntry(ContentProviderClient provider, long rawContactId)
	{
		String dn = ""; // TODO: get DN
		Entry result = new Entry(dn);
		for (RowBuilder row: mRows) {
			row.buildLDIFEntry(provider, rawContactId, result);
		}
		// TODO: get unmapped LDAP attribs
		// even once we have this, we have to delta it against an older buildLDIFEntry result, since some data from LDAP doesn't exist here
		return result;
	}
}
