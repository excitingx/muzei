/*
 * Copyright 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.muzei.provider;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiContract;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Provides access to a the most recent artwork
 */
public class MuzeiProvider extends ContentProvider {
    private static final String TAG = MuzeiProvider.class.getSimpleName();
    /**
     * Shared Preference key for the current artwork location used in openFile
     */
    private static final String CURRENT_ARTWORK_LOCATION = "CURRENT_ARTWORK_LOCATION";
    /**
     * The incoming URI matches the ARTWORK URI pattern
     */
    private static final int ARTWORK = 1;
    /**
     * The incoming URI matches the SOURCE URI pattern
     */
    private static final int SOURCES = 2;
    /**
     * The incoming URI matches the SOURCE ID URI pattern
     */
    private static final int SOURCE_ID = 3;
    /**
     * The database that the provider uses as its underlying data store
     */
    private static final String DATABASE_NAME = "muzei.db";
    /**
     * The database version
     */
    private static final int DATABASE_VERSION = 3;
    /**
     * A UriMatcher instance
     */
    private static final UriMatcher uriMatcher = MuzeiProvider.buildUriMatcher();
    /**
     * An identity all column projection mapping for Artwork
     */
    private final HashMap<String, String> allArtworkColumnProjectionMap =
            MuzeiProvider.buildAllArtworkColumnProjectionMap();
    /**
     * An identity all column projection mapping for Sources
     */
    private final HashMap<String, String> allSourcesColumnProjectionMap =
            MuzeiProvider.buildAllSourcesColumnProjectionMap();
    /**
     * Handle to a new DatabaseHelper.
     */
    private DatabaseHelper databaseHelper;
    /**
     * Whether we should hold notifyChange() calls due to an ongoing applyBatch operation
     */
    private boolean holdNotifyChange = false;
    /**
     * Set of Uris that should be applied when the ongoing applyBatch operation finishes
     */
    private LinkedHashSet<Uri> pendingNotifyChange = new LinkedHashSet<>();

    /**
     * Save the current artwork's local location so that third parties can use openFile to retrieve the already
     * downloaded artwork rather than re-download it
     *
     * @param context        Any valid Context
     * @param currentArtwork File pointing to the current artwork
     */
    public static boolean saveCurrentArtworkLocation(Context context, File currentArtwork) {
        if (currentArtwork == null || !currentArtwork.exists()) {
            Log.w(TAG, "File " + currentArtwork + " is not valid");
            return false;
        }
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        sharedPreferences.edit().putString(CURRENT_ARTWORK_LOCATION, currentArtwork.getAbsolutePath()).commit();
        return true;
    }

    /**
     * Creates and initializes a column project for all columns for Artwork
     *
     * @return The all column projection map for Artwork
     */
    private static HashMap<String, String> buildAllArtworkColumnProjectionMap() {
        final HashMap<String, String> allColumnProjectionMap = new HashMap<>();
        allColumnProjectionMap.put(BaseColumns._ID, BaseColumns._ID);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME,
                MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI,
                MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_TITLE,
                MuzeiContract.Artwork.COLUMN_NAME_TITLE);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_BYLINE,
                MuzeiContract.Artwork.COLUMN_NAME_BYLINE);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_ATTRIBUTION,
                MuzeiContract.Artwork.COLUMN_NAME_ATTRIBUTION);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_TOKEN,
                MuzeiContract.Artwork.COLUMN_NAME_TOKEN);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT,
                MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT);
        allColumnProjectionMap.put(MuzeiContract.Artwork.COLUMN_NAME_META_FONT,
                MuzeiContract.Artwork.COLUMN_NAME_META_FONT);
        return allColumnProjectionMap;
    }

    /**
     * Creates and initializes a column project for all columns for Sources
     *
     * @return The all column projection map for Sources
     */
    private static HashMap<String, String> buildAllSourcesColumnProjectionMap() {
        final HashMap<String, String> allColumnProjectionMap = new HashMap<>();
        allColumnProjectionMap.put(BaseColumns._ID, BaseColumns._ID);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME,
                MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED,
                MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION,
                MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE,
                MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND,
                MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND);
        allColumnProjectionMap.put(MuzeiContract.Sources.COLUMN_NAME_COMMANDS,
                MuzeiContract.Sources.COLUMN_NAME_COMMANDS);
        return allColumnProjectionMap;
    }

    /**
     * Creates and initializes the URI matcher
     *
     * @return the URI Matcher
     */
    private static UriMatcher buildUriMatcher() {
        final UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);
        matcher.addURI(MuzeiContract.AUTHORITY, MuzeiContract.Artwork.TABLE_NAME,
                MuzeiProvider.ARTWORK);
        matcher.addURI(MuzeiContract.AUTHORITY, MuzeiContract.Sources.TABLE_NAME,
                MuzeiProvider.SOURCES);
        matcher.addURI(MuzeiContract.AUTHORITY, MuzeiContract.Sources.TABLE_NAME + "/#",
                MuzeiProvider.SOURCE_ID);
        return matcher;
    }

    @NonNull
    @Override
    public ContentProviderResult[] applyBatch(@NonNull final ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        holdNotifyChange = true;
        try {
            return super.applyBatch(operations);
        } finally {
            holdNotifyChange = false;
            boolean broadcastSourceChanged = false;
            ContentResolver contentResolver = getContext().getContentResolver();
            Iterator<Uri> iterator = pendingNotifyChange.iterator();
            while (iterator.hasNext()) {
                Uri uri = iterator.next();
                contentResolver.notifyChange(uri, null);
                if (MuzeiContract.Artwork.CONTENT_URI.equals(uri)) {
                    getContext().sendBroadcast(new Intent(MuzeiContract.Artwork.ACTION_ARTWORK_CHANGED));
                } else if (MuzeiProvider.uriMatcher.match(uri) == SOURCES ||
                        MuzeiProvider.uriMatcher.match(uri) == SOURCE_ID) {
                    broadcastSourceChanged = true;
                }
                iterator.remove();
            }
            if (broadcastSourceChanged) {
                getContext().sendBroadcast(new Intent(MuzeiContract.Sources.ACTION_SOURCE_CHANGED));
            }
        }
    }

    private void notifyChange(Uri uri) {
        if (holdNotifyChange) {
            pendingNotifyChange.add(uri);
        } else {
            getContext().getContentResolver().notifyChange(uri, null);
            if (MuzeiContract.Artwork.CONTENT_URI.equals(uri)) {
                getContext().sendBroadcast(new Intent(MuzeiContract.Artwork.ACTION_ARTWORK_CHANGED));
            } else if (MuzeiProvider.uriMatcher.match(uri) == SOURCES ||
                    MuzeiProvider.uriMatcher.match(uri) == SOURCE_ID) {
                getContext().sendBroadcast(new Intent(MuzeiContract.Sources.ACTION_SOURCE_CHANGED));
            }
        }
    }

    @Override
    public int delete(@NonNull final Uri uri, final String selection, final String[] selectionArgs) {
        if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK) {
            throw new UnsupportedOperationException("Deletes are not supported");
        } else if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCES ||
                MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCE_ID) {
            return deleteSource(uri, selection, selectionArgs);
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private int deleteSource(@NonNull final Uri uri, final String selection, final String[] selectionArgs) {
        // Opens the database object in "write" mode.
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        int count;
        // Does the delete based on the incoming URI pattern.
        switch (MuzeiProvider.uriMatcher.match(uri)) {
            case SOURCES:
                // If the incoming pattern matches the general pattern for
                // sources, does a delete based on the incoming "where"
                // column and arguments.
                count = db.delete(MuzeiContract.Sources.TABLE_NAME, selection, selectionArgs);
                break;
            case SOURCE_ID:
                // If the incoming URI matches a single source ID, does the
                // delete based on the incoming data, but modifies the where
                // clause to restrict it to the particular source ID.
                String finalWhere = BaseColumns._ID + " = " + uri.getPathSegments().get(1);
                // If there were additional selection criteria, append them to the final WHERE clause
                if (selection != null)
                    finalWhere = finalWhere + " AND " + selection;
                count = db.delete(MuzeiContract.Sources.TABLE_NAME, finalWhere, selectionArgs);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        if (count > 0) {
            notifyChange(uri);
        }
        return count;
    }

    @Override
    public String getType(@NonNull final Uri uri) {
        /**
         * Chooses the MIME type based on the incoming URI pattern
         */
        switch (MuzeiProvider.uriMatcher.match(uri)) {
            case ARTWORK:
                // If the pattern is for artwork, returns the artwork content type.
                return MuzeiContract.Artwork.CONTENT_TYPE;
            case SOURCES:
                // If the pattern is for sources, returns the sources content type.
                return MuzeiContract.Sources.CONTENT_TYPE;
            case SOURCE_ID:
                // If the pattern is for source id, returns the sources content item type.
                return MuzeiContract.Sources.CONTENT_ITEM_TYPE;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public Uri insert(@NonNull final Uri uri, final ContentValues values) {
        if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK) {
            return insertArtwork(uri, values);
        } else if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCES) {
            return insertSource(uri, values);
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private Uri insertArtwork(@NonNull final Uri uri, final ContentValues values) {
        if (values == null) {
            throw new IllegalArgumentException("Invalid ContentValues: must not be null");
        }
        if (!values.containsKey(MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME))
            throw new IllegalArgumentException("Initial values must contain component name " + values);
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        final int countUpdated = db.update(MuzeiContract.Artwork.TABLE_NAME,
                values, BaseColumns._ID + "=1", null);
        if (countUpdated != 1) {
            long rowId = db.insert(MuzeiContract.Artwork.TABLE_NAME,
                    MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI, values);
            if (rowId <= 0) {
                throw new SQLException("Failed to insert row into " + uri);
            }
        }

        notifyChange(MuzeiContract.Artwork.CONTENT_URI);
        return MuzeiContract.Artwork.CONTENT_URI;
    }

    private Uri insertSource(@NonNull final Uri uri, final ContentValues initialValues) {
        if (!initialValues.containsKey(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME))
            throw new IllegalArgumentException("Initial values must contain component name " + initialValues);
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        final long rowId = db.insert(MuzeiContract.Sources.TABLE_NAME,
                MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME, initialValues);
        // If the insert succeeded, the row ID exists.
        if (rowId > 0)
        {
            // Creates a URI with the source ID pattern and the new row ID appended to it.
            final Uri sourceUri = ContentUris.withAppendedId(MuzeiContract.Sources.CONTENT_URI, rowId);
            notifyChange(sourceUri);
            return sourceUri;
        }
        // If the insert didn't succeed, then the rowID is <= 0
        throw new SQLException("Failed to insert row into " + uri);
    }

    /**
     * Creates the underlying DatabaseHelper
     *
     * @see android.content.ContentProvider#onCreate()
     */
    @Override
    public boolean onCreate() {
        databaseHelper = new DatabaseHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(@NonNull final Uri uri, final String[] projection, final String selection,
                        final String[] selectionArgs, final String sortOrder) {
        if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK) {
            return queryArtwork(uri, projection, selection, selectionArgs, sortOrder);
        } else if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCES ||
                MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCE_ID) {
            return querySource(uri, projection, selection, selectionArgs, sortOrder);
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private Cursor queryArtwork(@NonNull final Uri uri, final String[] projection, final String selection,
                        final String[] selectionArgs, final String sortOrder) {
        final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(MuzeiContract.Artwork.TABLE_NAME);
        qb.setProjectionMap(allArtworkColumnProjectionMap);
        final SQLiteDatabase db = databaseHelper.getReadableDatabase();
        final Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, sortOrder, null);
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;
    }

    private Cursor querySource(@NonNull final Uri uri, final String[] projection, final String selection,
                                final String[] selectionArgs, final String sortOrder) {
        final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(MuzeiContract.Sources.TABLE_NAME);
        qb.setProjectionMap(allSourcesColumnProjectionMap);
        final SQLiteDatabase db = databaseHelper.getReadableDatabase();
        if (MuzeiProvider.uriMatcher.match(uri) == SOURCE_ID) {
            // If the incoming URI is for a single source identified by its ID, appends "_ID = <sourceId>"
            // to the where clause, so that it selects that single ingredient
            qb.appendWhere(BaseColumns._ID + "=" + uri.getPathSegments().get(1));
        }
        String orderBy;
        if (TextUtils.isEmpty(sortOrder))
            orderBy = MuzeiContract.Sources.DEFAULT_SORT_ORDER;
        else
            orderBy = sortOrder;
        final Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, orderBy, null);
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(@NonNull final Uri uri, @NonNull final String mode) throws FileNotFoundException {
        // Validates the incoming URI. Only the full provider URI is allowed for openFile
        if (MuzeiProvider.uriMatcher.match(uri) != MuzeiProvider.ARTWORK) {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
        if (!"r".equals(mode)) {
            throw new IllegalArgumentException("Invalid mode for opening file: " + mode + ". Only 'r' is valid");
        }
        String currentArtworkLocation = PreferenceManager.getDefaultSharedPreferences(getContext())
                .getString(CURRENT_ARTWORK_LOCATION, null);
        if (currentArtworkLocation == null) {
            throw new FileNotFoundException("No artwork image is set");
        }
        File file = new File(currentArtworkLocation);
        if (!file.exists()) {
            throw new FileNotFoundException("File " + currentArtworkLocation + " does not exist");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public int update(@NonNull final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs) {
        if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.ARTWORK) {
            throw new UnsupportedOperationException("Updates are not allowed: insert does an insert or update operation");
        } else if (MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCES ||
                MuzeiProvider.uriMatcher.match(uri) == MuzeiProvider.SOURCE_ID) {
            return updateSource(uri, values, selection, selectionArgs);
        } else {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    private int updateSource(@NonNull final Uri uri, final ContentValues values, final String selection,
                             final String[] selectionArgs) {
        final SQLiteDatabase db = databaseHelper.getWritableDatabase();
        int count;
        switch (MuzeiProvider.uriMatcher.match(uri))
        {
            case SOURCES:
                // If the incoming URI matches the general sources pattern, does the update based on the incoming
                // data.
                count = db.update(MuzeiContract.Sources.TABLE_NAME, values, selection, selectionArgs);
                break;
            case SOURCE_ID:
                // If the incoming URI matches a single source ID, does the update based on the incoming data, but
                // modifies the where clause to restrict it to the particular source ID.
                String finalWhere = BaseColumns._ID + " = " + uri.getPathSegments().get(1);
                // If there were additional selection criteria, append them to the final WHERE clause
                if (selection != null)
                    finalWhere = finalWhere + " AND " + selection;
                count = db.update(MuzeiContract.Sources.TABLE_NAME, values, finalWhere, selectionArgs);
                notifyChange(uri);
                return count;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        if (count > 0) {
            notifyChange(uri);
        } else if (values.containsKey(MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME)) {
            insertSource(MuzeiContract.Sources.CONTENT_URI, values);
            count = 1;
        }
        return count;
    }

    /**
     * This class helps open, create, and upgrade the database file.
     */
    static class DatabaseHelper extends SQLiteOpenHelper {
        /**
         * Creates a new DatabaseHelper
         *
         * @param context context of this database
         */
        DatabaseHelper(final Context context) {
            super(context, MuzeiProvider.DATABASE_NAME, null, MuzeiProvider.DATABASE_VERSION);
        }

        /**
         * Creates the underlying database with table name and column names taken from the MuzeiContract class.
         */
        @Override
        public void onCreate(final SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + MuzeiContract.Sources.TABLE_NAME + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME + " TEXT,"
                    + MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED + " INTEGER,"
                    + MuzeiContract.Sources.COLUMN_NAME_DESCRIPTION + " TEXT,"
                    + MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE + " INTEGER,"
                    + MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND + " INTEGER,"
                    + MuzeiContract.Sources.COLUMN_NAME_COMMANDS + " TEXT);");
            db.execSQL("CREATE TABLE " + MuzeiContract.Artwork.TABLE_NAME + " ("
                    + BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME + " TEXT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_IMAGE_URI + " TEXT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_TITLE + " TEXT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_BYLINE + " TEXT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_ATTRIBUTION + " TEXT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_TOKEN + " TEXT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_META_FONT + " TEXT,"
                    + MuzeiContract.Artwork.COLUMN_NAME_VIEW_INTENT + " TEXT,"
                    + " CONSTRAINT fk_source_artwork FOREIGN KEY ("
                    + MuzeiContract.Artwork.COLUMN_NAME_SOURCE_COMPONENT_NAME + ") REFERENCES "
                    + MuzeiContract.Sources.TABLE_NAME + " ("
                    + MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME + ") ON DELETE CASCADE);");
        }

        /**
         * Upgrades the database.
         */
        @Override
        public void onUpgrade(final SQLiteDatabase db, final int oldVersion, final int newVersion) {
            if (oldVersion < 3) {
                // We can't ALTER TABLE to add a foreign key and we wouldn't know what the FK should be
                // at this point anyways so we'll wipe and recreate the artwork table
                db.execSQL("DROP TABLE " + MuzeiContract.Artwork.TABLE_NAME);
                onCreate(db);
            }
        }
    }
}