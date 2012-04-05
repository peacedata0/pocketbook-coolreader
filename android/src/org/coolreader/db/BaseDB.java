package org.coolreader.db;

import java.io.File;

import org.coolreader.crengine.L;
import org.coolreader.crengine.Utils;

import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;

public abstract class BaseDB {
	protected SQLiteDatabase mDB;
	private File mFileName;
	private boolean restoredFromBackup;
	private boolean error = true;

	public File getFileName() {
		return mFileName;
	}

	public boolean isOpened() {
		return mDB != null && !error;
	}

	public boolean open(File dir) {
		error = true;
		File dbFile = new File(dir, dbFileName());
		mFileName = dbFile;
		mDB = openDB(dbFile);
		if (mDB == null)
			return false;
		boolean res = checkSchema();
		if (!res) {
			L.e("Closing DB due error while upgrade of schema: " + dbFile.getAbsolutePath());
			close();
			Utils.moveCorruptedFileToBackup(dbFile);
			if (!restoredFromBackup)
				Utils.restoreFromBackup(dbFile);
			mDB = openDB(dbFile);
			res = checkSchema();
			if (!res)
				close();
		}
		if (mDB != null) {
			error = false;
			return true;
		}
		return false;
	}

	public boolean close() {
		if (mDB != null) {
			try {
				L.d("Closing database");
				flush();
				clearCaches();
				mDB.close();
				mDB = null;
				return true;
			} catch (SQLiteException e) {
				L.e("Error while closing DB " + mFileName);
			}
			mDB = null;
		}
		return false;
	}

	protected boolean checkSchema() {
		try {
			upgradeSchema();
			return true;
		} catch (SQLiteException e) {
			return false;
		}
	}

	protected abstract boolean upgradeSchema();

	protected abstract String dbFileName();
	
	private SQLiteDatabase openDB(File dbFile) {
		restoredFromBackup = false;
		SQLiteDatabase db = null;
		try {
			db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
			return db;
		} catch (SQLiteException e) {
			L.e("Error while opening DB " + dbFile.getAbsolutePath());
			Utils.moveCorruptedFileToBackup(dbFile);
			restoredFromBackup = Utils.restoreFromBackup(dbFile);
			try {
				db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
				return db;
			} catch (SQLiteException ee) {
				L.e("Error while opening DB " + dbFile.getAbsolutePath());
			}
		}
		return null;
	}

	public void execSQLIgnoreErrors( String... sqls )
	{
		for ( String sql : sqls ) {
			try { 
				mDB.execSQL(sql);
			} catch ( SQLException e ) {
				// ignore
				Log.w("cr3db", "query failed, ignoring: " + sql);
			}
		}
	}
	
	protected void ensureOpened() {
		if (!isOpened())
			throw new DBRuntimeException("DB is not opened");
	}

	public void execSQL( String... sqls )
	{
		ensureOpened();
		for ( String sql : sqls ) {
			try { 
				mDB.execSQL(sql);
			} catch ( SQLException e ) {
				// ignore
				Log.w("cr3", "query failed: " + sql);
				throw e;
			}
		}
	}

	public Long longQuery( String sql )
	{
		ensureOpened();
		SQLiteStatement stmt = null;
		try {
			stmt = mDB.compileStatement(sql);
			return stmt.simpleQueryForLong();
		} catch ( Exception e ) {
			// not found or error
			return null;
		} finally {
			if (stmt != null)
				stmt.close();
		}
	}
	
	public static String quoteSqlString(String src) {
		if (src==null)
			return "null";
		String s = src.replaceAll("\\'", "\\\\'");
		return "'" + s + "'";
	}
	
	public void clearCaches() {
		// override it
	}

	private boolean changed = false;
	
	/**
	 * Begin transaction, if not yet started, for changes.
	 */
	public void beginChanges() {
		if (!mDB.inTransaction())
			mDB.beginTransaction();
		changed = true;
	}

	/**
	 * Begin transaction, if not yet started, for faster reading.
	 */
	public void beginReading() {
		if (!mDB.inTransaction())
			mDB.beginTransaction();
	}

	/**
	 * Rolls back transaction, if writing is not started.
	 */
	public void endReading() {
		if (mDB.inTransaction() && !changed) {
			mDB.endTransaction();
		}
	}

	/**
	 * Commits or rolls back transaction, if started, and frees DB resources.
	 * Will commit only if beginChanges() has been called. Otherwise will roll back.
	 */
	public void flush() {
		if (mDB.inTransaction()) {
			if (changed)
				mDB.setTransactionSuccessful();
			mDB.endTransaction();
		}
	}
}
