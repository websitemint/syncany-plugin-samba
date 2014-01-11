/*
 * Syncany, www.syncany.org
 * Copyright (C) 2011-2013 Philipp C. Heckel <philipp.heckel@gmail.com> 
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.syncany.operations;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.syncany.chunk.Deduper;
import org.syncany.config.Config;
import org.syncany.connection.plugins.DatabaseRemoteFile;
import org.syncany.connection.plugins.MultiChunkRemoteFile;
import org.syncany.connection.plugins.StorageException;
import org.syncany.connection.plugins.TransferManager;
import org.syncany.database.DatabaseVersion;
import org.syncany.database.DatabaseVersion.DatabaseVersionStatus;
import org.syncany.database.DatabaseVersionHeader;
import org.syncany.database.FileVersion;
import org.syncany.database.MemoryDatabase;
import org.syncany.database.MultiChunkEntry;
import org.syncany.database.PartialFileHistory;
import org.syncany.database.VectorClock;
import org.syncany.database.dao.CleanupSqlDatabaseDAO;
import org.syncany.database.dao.SqlDatabaseDAO;
import org.syncany.database.dao.WriteSqlDatabaseDAO;
import org.syncany.operations.LsRemoteOperation.LsRemoteOperationResult;
import org.syncany.operations.StatusOperation.StatusOperationOptions;
import org.syncany.operations.StatusOperation.StatusOperationResult;
import org.syncany.operations.UpOperation.UpOperationResult.UpResultCode;

/**
 * The up operation implements a central part of Syncany's business logic. It analyzes the local
 * folder, deduplicates new or changed files and uploads newly packed multichunks to the remote
 * storage. The up operation is the complement to the {@link DownOperation}.
 * 
 * <p>The general operation flow is as follows:
 * <ol>
 *   <li>Load local database (if not already loaded)</li>
 *   <li>Analyze local directory using the {@link StatusOperation} to determine any changed/new/deleted files</li>
 *   <li>Determine if there are unknown remote databases using the {@link LsRemoteOperation}, and skip the rest if there are</li>
 *   <li>If there are changes, use the {@link Deduper} and {@link Indexer} to create a new {@link DatabaseVersion} 
 *       (including new chunks, multichunks, file contents and file versions).</li>
 *   <li>Upload new multichunks (if any) using a {@link TransferManager}</li>
 *   <li>Save new {@link DatabaseVersion} to a new (delta) {@link MemoryDatabase} and upload it</li>
 *   <li>Add delta database to local database and store it locally</li>
 * </ol>
 * 
 * @author Philipp C. Heckel <philipp.heckel@gmail.com>
 */
public class UpOperation extends Operation {
	private static final Logger logger = Logger.getLogger(UpOperation.class.getSimpleName());

	public static final int MIN_KEEP_DATABASE_VERSIONS = 5;
	public static final int MAX_KEEP_DATABASE_VERSIONS = 15;

	private UpOperationOptions options;
	private UpOperationResult result;
	private TransferManager transferManager;
	private SqlDatabaseDAO localDatabase;

	public UpOperation(Config config) {
		this(config, new UpOperationOptions());
	}

	public UpOperation(Config config, UpOperationOptions options) {
		super(config);

		this.options = options;
		this.result = new UpOperationResult();
		this.transferManager = config.getConnection().createTransferManager();
		this.localDatabase = new SqlDatabaseDAO(config.createDatabaseConnection());
	}

	@Override
	public UpOperationResult execute() throws Exception {
		logger.log(Level.INFO, "");
		logger.log(Level.INFO, "Running 'Sync up' at client " + config.getMachineName() + " ...");
		logger.log(Level.INFO, "--------------------------------------------");

		// Find local changes
		ChangeSet statusChangeSet = (new StatusOperation(config, options.getStatusOptions()).execute()).getChangeSet();
		result.getStatusResult().setChangeSet(statusChangeSet);

		if (!statusChangeSet.hasChanges()) {
			logger.log(Level.INFO, "Local database is up-to-date (change set). NOTHING TO DO!");
			result.setResultCode(UpResultCode.OK_NO_CHANGES);

			disconnectTransferManager();

			return result;
		}

		// Find remote changes (unless --force is enabled)
		if (!options.forceUploadEnabled()) {
			LsRemoteOperationResult lsRemoteOperationResult = new LsRemoteOperation(config, transferManager).execute();
			List<DatabaseRemoteFile> unknownRemoteDatabases = lsRemoteOperationResult.getUnknownRemoteDatabases();

			if (unknownRemoteDatabases.size() > 0) {
				logger.log(Level.INFO, "There are remote changes. Call 'down' first or use --force, Luke!.");
				result.setResultCode(UpResultCode.NOK_UNKNOWN_DATABASES);

				disconnectTransferManager();

				return result;
			}
			else {
				logger.log(Level.INFO, "No remote changes, ready to upload.");
			}
		}
		else {
			logger.log(Level.INFO, "Force (--force) is enabled, ignoring potential remote changes.");
		}

		List<File> locallyUpdatedFiles = determineLocallyUpdatedFiles(statusChangeSet);
		statusChangeSet = null; // allow GC to clean up

		// Index
		DatabaseVersion newDatabaseVersion = index(locallyUpdatedFiles);

		if (newDatabaseVersion.getFileHistories().size() == 0) {
			logger.log(Level.INFO, "Local database is up-to-date. NOTHING TO DO!");
			result.setResultCode(UpResultCode.OK_NO_CHANGES);

			disconnectTransferManager();

			return result;
		}

		// Upload multichunks
		logger.log(Level.INFO, "Uploading new multichunks ...");
		uploadMultiChunks(newDatabaseVersion.getMultiChunks());

		// Create delta database
		MemoryDatabase newDeltaDatabase = new MemoryDatabase();
		newDeltaDatabase.addDatabaseVersion(newDatabaseVersion);		
		
		// TODO [high] Must add dirty chunks/filecontents to newDatabaseVersion here
		
		
		// Save delta database locally
		long newestLocalDatabaseVersion = newDatabaseVersion.getVectorClock().getClock(config.getMachineName());
		DatabaseRemoteFile remoteDeltaDatabaseFile = new DatabaseRemoteFile(config.getMachineName(), newestLocalDatabaseVersion);
		File localDeltaDatabaseFile = config.getCache().getDatabaseFile(remoteDeltaDatabaseFile.getName());

		logger.log(Level.INFO, "Saving local delta database, version {0} to file {1} ... ", new Object[] {
				newDatabaseVersion.getHeader(), localDeltaDatabaseFile });
		
		saveLocalDatabase(newDeltaDatabase, localDeltaDatabaseFile);				

		// Upload delta database
		logger.log(Level.INFO, "- Uploading local delta database file ...");
		uploadLocalDatabase(localDeltaDatabaseFile, remoteDeltaDatabaseFile);

		// Save local database
		logger.log(Level.INFO, "Adding newest database version " + newDatabaseVersion.getHeader() + " to local database ...");
		
		logger.log(Level.INFO, "Persisting local SQL database (new database version {0}) ...", newDatabaseVersion.getHeader().toString());
		WriteSqlDatabaseDAO writeSqlDao = new WriteSqlDatabaseDAO(config.createDatabaseConnection());
		writeSqlDao.persistDatabaseVersion(newDatabaseVersion);

		if (options.cleanupEnabled()) {
			new CleanupOperation(config).execute(); 
		}
		
		removeUnreferencedDirtyData();		
		disconnectTransferManager();

		logger.log(Level.INFO, "Sync up done.");

		// Result
		updateResultChangeSet(newDatabaseVersion);
		result.setResultCode(UpResultCode.OK_APPLIED_CHANGES);
		
		return result;
	}

	private void removeUnreferencedDirtyData() {
		logger.log(Level.INFO, "- Removing unreferenced dirty data from database ...");
		
		CleanupSqlDatabaseDAO cleanupSqlDao = new CleanupSqlDatabaseDAO(config.createDatabaseConnection());
		cleanupSqlDao.removeDirtyDatabaseVersions();		
	}

	private List<File> determineLocallyUpdatedFiles(ChangeSet statusChangeSet) {
		List<File> locallyUpdatedFiles = new ArrayList<File>();

		for (String relativeFilePath : statusChangeSet.getNewFiles()) {
			locallyUpdatedFiles.add(new File(config.getLocalDir() + File.separator + relativeFilePath));
		}

		for (String relativeFilePath : statusChangeSet.getChangedFiles()) {
			locallyUpdatedFiles.add(new File(config.getLocalDir() + File.separator + relativeFilePath));
		}

		return locallyUpdatedFiles;
	}

	private void updateResultChangeSet(DatabaseVersion newDatabaseVersion) {
		ChangeSet changeSet = result.getChangeSet();

		for (PartialFileHistory partialFileHistory : newDatabaseVersion.getFileHistories()) {
			FileVersion lastFileVersion = partialFileHistory.getLastVersion();

			switch (lastFileVersion.getStatus()) {
			case NEW:
				changeSet.getNewFiles().add(lastFileVersion.getPath());
				break;

			case CHANGED:
			case RENAMED:
				changeSet.getChangedFiles().add(lastFileVersion.getPath());
				break;

			case DELETED:
				changeSet.getDeletedFiles().add(lastFileVersion.getPath());
				break;
			}
		}
	}

	private void uploadMultiChunks(Collection<MultiChunkEntry> multiChunksEntries) throws InterruptedException, StorageException {
		for (MultiChunkEntry multiChunkEntry : multiChunksEntries) {
			MultiChunkEntry dirtyMultiChunkEntry = localDatabase.getMultiChunkWithStatus(multiChunkEntry.getId(), DatabaseVersionStatus.DIRTY);
			
			if (dirtyMultiChunkEntry != null) {
				logger.log(Level.INFO, "- Ignoring multichunk (from dirty database, already uploaded), " + multiChunkEntry.getId() + " ...");
			}
			else {
				File localMultiChunkFile = config.getCache().getEncryptedMultiChunkFile(multiChunkEntry.getId().getRaw());
				MultiChunkRemoteFile remoteMultiChunkFile = new MultiChunkRemoteFile(multiChunkEntry.getId().getRaw());

				logger.log(Level.INFO, "- Uploading multichunk " + multiChunkEntry.getId() + " from " + localMultiChunkFile + " to "
						+ remoteMultiChunkFile + " ...");
				transferManager.upload(localMultiChunkFile, remoteMultiChunkFile);

				logger.log(Level.INFO, "  + Removing " + multiChunkEntry.getId() + " locally ...");
				localMultiChunkFile.delete();
			}
		}
	}

	private void uploadLocalDatabase(File localDatabaseFile, DatabaseRemoteFile remoteDatabaseFile) throws InterruptedException, StorageException {
		logger.log(Level.INFO, "- Uploading " + localDatabaseFile + " to " + remoteDatabaseFile + " ...");
		transferManager.upload(localDatabaseFile, remoteDatabaseFile);
	}

	private DatabaseVersion index(List<File> localFiles) throws FileNotFoundException, IOException {
		// Get last vector clock
		DatabaseVersionHeader lastDatabaseVersionHeader = localDatabase.getLastDatabaseVersionHeader();
		VectorClock lastVectorClock = (lastDatabaseVersionHeader != null) ? lastDatabaseVersionHeader.getVectorClock() : new VectorClock();

		// New vector clock
		VectorClock newVectorClock = lastVectorClock.clone();

		Long lastLocalValue = lastVectorClock.getClock(config.getMachineName());
		Long lastDirtyLocalValue = localDatabase.getMaxDirtyVectorClock(config.getMachineName());

		Long newLocalValue = null;

		if (lastDirtyLocalValue != null) {
			// TODO [medium] Does this lead to problems? C-1 does not exist! Possible problems with DatabaseReconciliator?
			newLocalValue = lastDirtyLocalValue + 1; 
		}
		else {
			if (lastLocalValue != null) {
				newLocalValue = lastLocalValue + 1;
			}
			else {
				newLocalValue = 1L;
			}
		}

		newVectorClock.setClock(config.getMachineName(), newLocalValue);

		// Index
		Deduper deduper = new Deduper(config.getChunker(), config.getMultiChunker(), config.getTransformer());
		Indexer indexer = new Indexer(config, deduper);

		DatabaseVersion newDatabaseVersion = indexer.index(localFiles);

		newDatabaseVersion.setVectorClock(newVectorClock);
		newDatabaseVersion.setTimestamp(new Date());
		newDatabaseVersion.setClient(config.getMachineName());

		return newDatabaseVersion;
	}
	
	private void disconnectTransferManager() {
		try {
			transferManager.disconnect();
		}
		catch (StorageException e) {
			// Don't care!
		}
	}

	public static class UpOperationOptions implements OperationOptions {
		private StatusOperationOptions statusOptions = new StatusOperationOptions();
		private boolean forceUploadEnabled = false;
		private boolean cleanupEnabled = true;

		public StatusOperationOptions getStatusOptions() {
			return statusOptions;
		}

		public void setStatusOptions(StatusOperationOptions statusOptions) {
			this.statusOptions = statusOptions;
		}

		public boolean forceUploadEnabled() {
			return forceUploadEnabled;
		}

		public void setForceUploadEnabled(boolean forceUploadEnabled) {
			this.forceUploadEnabled = forceUploadEnabled;
		}

		public boolean cleanupEnabled() {
			return cleanupEnabled;
		}

		public void setCleanupEnabled(boolean cleanupEnabled) {
			this.cleanupEnabled = cleanupEnabled;
		}
	}

	public static class UpOperationResult implements OperationResult {
		public enum UpResultCode {
			OK_APPLIED_CHANGES, OK_NO_CHANGES, NOK_UNKNOWN_DATABASES
		};

		private UpResultCode resultCode;
		private StatusOperationResult statusResult = new StatusOperationResult();
		private ChangeSet uploadChangeSet = new ChangeSet();

		public UpResultCode getResultCode() {
			return resultCode;
		}

		public void setResultCode(UpResultCode resultCode) {
			this.resultCode = resultCode;
		}

		public void setStatusResult(StatusOperationResult statusResult) {
			this.statusResult = statusResult;
		}

		public void setUploadChangeSet(ChangeSet uploadChangeSet) {
			this.uploadChangeSet = uploadChangeSet;
		}

		public StatusOperationResult getStatusResult() {
			return statusResult;
		}

		public ChangeSet getChangeSet() {
			return uploadChangeSet;
		}
	}
}
