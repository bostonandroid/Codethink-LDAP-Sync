Codethink LDAP Sync Tool
========================

This app provides Android 2.x devices the ability to synchronize contacts
with an LDAP database.  *This tool is a work in progress.*  Using it with
real contact data is not recommended at this point.

Limitations
-----------

Limitations of the current iteration:

* Only supports a syncing with a single source, which is called "Testing Account".
* Synchronization is one-way (contacts from LDAP can't be edited)

Both of these limitations will be addressed eventually.

Code Structure
--------------

All the code is in the info.codethink.ldapsync_ package.  There are three main
components: the app, which is started via the ``AccountList`` activity.  It's
currently mostly used to edit account settings by launching the
``LDAPAuthneticatorActivity`` but will do more in the future.

The other two pieces are hooked into the Android account/synchronization
system.  The account management component consists of the
``LDAPAuthenticator*`` classes.  ``LDAPAuthenticatorService`` just hooks the
``LDAPAuthenticator`` into Android's centralized account management code.
There's some magic in the manifest to make that happen.  ``LDAPAuthenticator``
implements the account management protocol so that LDAP accounts show up in the
"Accounts & Sync" settings panel and the option to create them is presented
when you go to create a new account from there.  Whenever the authenticator
needs to talk to the user it returns an intent to launch the
``LDAPAuthenticatorActivity``; currently the only times Android will actually
make that happen are for new accounts and when authentication fails on an
existing account.

The last piece is the ``LDAPSyncService``/``LDAPSyncAdapter`` pair (again,
the service is just Android's entry point into the adapter).  These do the
actual sync of contacts between the LDAP server and the phone's contact
database.  Android will call into the adapter when a sync is manually requested
from the Accounts & Sync panel, or automatically every so often if automatic
sync is enabled.  The adapter uses ``LDAPSyncMapping`` and
``LDAPContactSource`` to do a lot of the heavy lifting.  The ``LDAPSyncMapping``
class parses the ``res/raw/basicmapping.xml`` file and does some reflection
on the Android contact DB interface classes to determine how to map LDAP
attributes to rows in the android contact DB.  The ``LDAPContactSource``
class handles setting up the actual connection to the LDAP server
and setting up the parameters for the LDAP query to get contacts.

Resources
---------

As mentioned above, the ``res/raw/basicmapping.xml`` is a custom XML
microformat for mapping LDAP to Android contact-ese.  It's in ``raw``
because I couldn't figure out how to hook an Android XML resource up to
the ``android.sax``-based parser I wrote in ``LDAPSyncMapping.Parser``.  Go
figure.

The only ``drawable`` resource is the default Android app icon.  I'll swap it
for something better later.

``layout`` has:
  * ``accountlist`` and ``accountlistentry`` for the ``AccountList``
  * ``editaccount`` which is used to edit LDAP account settings and test
                    the connections.  (There's a separate landscape version
                    in ``layout-land`` to test orientation switching.)
  * ``login`` which would theoretically be used for authentication failures
              but hasn't been tested.
  * ``pickerlist`` and ``pickerlistentry`` which are used for the LDAP entry
                   picker activity, which can be used to browse for the query
                   base DN in the account settings view.

``xml`` has a bunch of XML files referenced from the manifest:
  * ``ldapauthenticator`` which is referenced from the manifest and gives
                          Android the icon and label for the LDAP account type.
  * ``ldapcontactsource`` which describes the LDAP contact source, and may
                          eventually include some markup to tell the contacts
                          app how to render custom contact data items.
  * ``ldapsyncadapter`` which tells Android that LDAP accounts can sync contact
                        data (and also that the adapter is currently read-only
                        sync).
                        
``values`` has ``strings`` (for future i18n) and ``misc`` which has the list
of values currently in the security types dropdown.

Misc TODOs
----------
Improve the way SSL/TLS certs are handled, to allow prompting the user for
certificate trust.  Also need to change the way the dropdown values are set up
so that the entries are translatable.  As is, adding a ``misc.xml`` in another
languate would break things.

.. _info.codethink.ldapsync: http://github.com/bostonandroid/Codethink-LDAP-Sync/tree/master/src/info/codethink/ldapsync/
