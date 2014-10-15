/*
 * Copyright (c) 2014. The Trustees of Indiana University.
 *
 * This version of the code is licensed under the MPL 2.0 Open Source license with additional
 * healthcare disclaimer. If the user is an entity intending to commercialize any application
 * that uses this code in a for-profit venture, please contact the copyright holder.
 */

package com.muzima.service;

import android.app.IntentService;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import com.muzima.MuzimaApplication;
import com.muzima.R;
import com.muzima.api.model.APIName;
import com.muzima.api.model.LastSyncTime;
import com.muzima.api.model.Patient;
import com.muzima.api.service.LastSyncTimeService;
import com.muzima.view.BroadcastListenerActivity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.muzima.utils.Constants.DataSyncServiceConstants.SyncStatusConstants;
import static java.util.Arrays.asList;

public class DataSyncService extends IntentService {

    private static final int MUZIMA_NOTIFICATION = 0;
    private static final String TAG = "DataSyncService";
    private final String notificationServiceRunning = "Muzima Sync Service Running";
    private final String notificationServiceFinished = "Muzima Sync Service Finished";
    private String notificationMsg;
    private MuzimaSyncService muzimaSyncService;

    public DataSyncService() {
        super("DataSyncService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        muzimaSyncService = ((MuzimaApplication) getApplication()).getMuzimaSyncService();
        updateNotificationMsg("Sync service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        int syncType = intent.getIntExtra(Constants.DataSyncServiceConstants.SYNC_TYPE, -1);
        Intent broadcastIntent = new Intent();
        String[] credentials = intent.getStringArrayExtra(Constants.DataSyncServiceConstants.CREDENTIALS);
        broadcastIntent.setAction(BroadcastListenerActivity.MESSAGE_SENT_ACTION);
        broadcastIntent.putExtra(Constants.DataSyncServiceConstants.SYNC_TYPE, syncType);

        switch (syncType) {
            case Constants.DataSyncServiceConstants.SYNC_FORMS:
                updateNotificationMsg("Downloading Forms Metadata");
                if (authenticationSuccessful(credentials, broadcastIntent)) {
                    int[] result = muzimaSyncService.downloadForms();
                    String msg = "Downloaded " + result[1] + " forms and Deleted " + result[2] + " forms";
                    prepareBroadcastMsg(broadcastIntent, result, msg);
                    saveSyncTime(result,APIName.DOWNLOAD_FORMS);
                }
                break;
            case Constants.DataSyncServiceConstants.SYNC_TEMPLATES:
                String[] formIds = intent.getStringArrayExtra(Constants.DataSyncServiceConstants.FORM_IDS);
                updateNotificationMsg("Downloading Forms Template for " + formIds.length + " forms");
                if (authenticationSuccessful(credentials, broadcastIntent)) {
                    int[] result = muzimaSyncService.downloadFormTemplates(formIds);
                    String msg = "Downloaded " + result[1] + " form templates and " + result[2] + "concepts";
                    broadcastIntent.putExtra(Constants.DataSyncServiceConstants.DOWNLOAD_COUNT_SECONDARY, result[2]);
                    prepareBroadcastMsg(broadcastIntent, result, msg);
                }
                break;
            case Constants.DataSyncServiceConstants.SYNC_COHORTS:
                updateNotificationMsg("Downloading Cohorts");
                if (authenticationSuccessful(credentials, broadcastIntent)) {
                    int[] result = muzimaSyncService.downloadCohorts();
                    String msg = "Downloaded " + result[1] + " new cohorts" + "; and deleted " + result[2] + " cohorts";
                    prepareBroadcastMsg(broadcastIntent, result, msg);
                    saveSyncTime(result, APIName.DOWNLOAD_COHORTS);
                    consolidateAndSyncIndependentPatients(broadcastIntent);
                }
                break;
            case Constants.DataSyncServiceConstants.SYNC_PATIENTS_FULL_DATA:
                String[] cohortIds = intent.getStringArrayExtra(Constants.DataSyncServiceConstants.COHORT_IDS);
                updateNotificationMsg("Downloading Patients");
                if (authenticationSuccessful(credentials, broadcastIntent)) {
                    downloadPatients(broadcastIntent, cohortIds);
                    downloadObservationsAndEncounters(broadcastIntent, cohortIds);
                }
                break;
            case Constants.DataSyncServiceConstants.SYNC_PATIENTS_ONLY:
                String[] cohortIdsToDownload = intent.getStringArrayExtra(Constants.DataSyncServiceConstants.COHORT_IDS);
                updateNotificationMsg("Downloading Patients");
                if (authenticationSuccessful(credentials, broadcastIntent)) {
                    downloadPatients(broadcastIntent, cohortIdsToDownload);
                }
                break;
            case Constants.DataSyncServiceConstants.SYNC_PATIENTS_DATA_ONLY:
                String[] savedCohortIds = intent.getStringArrayExtra(Constants.DataSyncServiceConstants.COHORT_IDS);
                updateNotificationMsg("Downloading Patients data");
                if (authenticationSuccessful(credentials, broadcastIntent)) {
                    downloadObservationsAndEncounters(broadcastIntent, savedCohortIds);
                }
                break;
            case Constants.DataSyncServiceConstants.SYNC_UPLOAD_FORMS:
                updateNotificationMsg("Uploading Forms");
                if (authenticationSuccessful(credentials, broadcastIntent)) {
                    int[] result = muzimaSyncService.uploadAllCompletedForms();
                    broadcastIntent.putExtra(Constants.DataSyncServiceConstants.SYNC_TYPE, Constants.DataSyncServiceConstants.SYNC_UPLOAD_FORMS);
                    prepareBroadcastMsgForFormUpload(broadcastIntent, result, "Uploaded the forms Successfully");
                }
                break;
            case Constants.DataSyncServiceConstants.DOWNLOAD_PATIENT_ONLY:
                String[] patientsToBeDownloaded = intent.getStringArrayExtra(Constants.DataSyncServiceConstants.PATIENT_UUID_FOR_DOWNLOAD);
                if (authenticationSuccessful(credentials, broadcastIntent)) {
                    downloadPatientsWithObsAndEncounters(broadcastIntent, patientsToBeDownloaded);
                }
            default:
                break;
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);
    }

    private void consolidateAndSyncIndependentPatients(Intent broadcastIntent) {
        muzimaSyncService.consolidatePatients();
        List<Patient> patients = muzimaSyncService.updatePatientsNotPartOfCohorts();

        List<String> patientUuids = muzimaSyncService.getPatientUuids(patients);
        downloadPatientsWithObsAndEncounters(broadcastIntent,patientUuids.toArray(new String[patientUuids.size()]));
    }

    private void downloadPatientsWithObsAndEncounters(Intent broadcastIntent, String[] patientUUIDs) {
        if(patientUUIDs.length == 0){
            return;
        }
        int[] resultForPatients = muzimaSyncService.downloadPatients(patientUUIDs);
        broadCastMessageForPatients(broadcastIntent, resultForPatients);
        List<String> patientUUIDList = new ArrayList<String>(asList(patientUUIDs));
        if (isSuccess(resultForPatients)) {
            int[] resultForObs = muzimaSyncService.downloadObservationsForPatientsByPatientUUIDs(patientUUIDList);
            broadCastMessageForObservationDownload(broadcastIntent, resultForObs);

            int[] resultForEncounters = muzimaSyncService.downloadEncountersForPatientsByPatientUUIDs(patientUUIDList);
            broadCastMessageForEncounters(broadcastIntent, resultForEncounters);
        }
    }

    private void downloadObservationsAndEncounters(Intent broadcastIntent, String[] savedCohortIds) {
        int[] resultForObservations = muzimaSyncService.downloadObservationsForPatientsByCohortUUIDs(savedCohortIds);
        broadCastMessageForObservationDownload(broadcastIntent, resultForObservations);

        int[] resultForEncounters = muzimaSyncService.downloadEncountersForPatientsByCohortUUIDs(savedCohortIds);
        broadCastMessageForEncounters(broadcastIntent, resultForEncounters);
    }

    private void downloadPatients(Intent broadcastIntent, String[] cohortIds) {
        int[] resultForPatients = muzimaSyncService.downloadPatientsForCohorts(cohortIds);
        broadCastMessageForPatients(broadcastIntent, resultForPatients);
    }

    private void broadCastMessageForEncounters(Intent broadcastIntent, int[] resultForEncounters) {
        String msgForEncounters = "Downloaded " + resultForEncounters[1] + " new encounters" +
                "; and deleted " + resultForEncounters[2] + " encounters";
        prepareBroadcastMsg(broadcastIntent, resultForEncounters, msgForEncounters);
        broadcastIntent.putExtra(Constants.DataSyncServiceConstants.SYNC_TYPE, Constants.DataSyncServiceConstants.SYNC_ENCOUNTERS);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);
    }

    private void broadCastMessageForObservationDownload(Intent broadcastIntent, int[] resultForObservations) {
        String msgForObservations = "Downloaded " + resultForObservations[1] + " new observations" +
                "; and deleted " + resultForObservations[2] + " observations";
        prepareBroadcastMsg(broadcastIntent, resultForObservations, msgForObservations);
        broadcastIntent.putExtra(Constants.DataSyncServiceConstants.SYNC_TYPE, Constants.DataSyncServiceConstants.SYNC_OBSERVATIONS);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);
    }

    private void broadCastMessageForPatients(Intent broadcastIntent, int[] resultForPatients) {
        String msgForPatients = "Downloaded " + resultForPatients[1] + " new patients";
        prepareBroadcastMsg(broadcastIntent, resultForPatients, msgForPatients);
        if (isSuccess(resultForPatients) && resultForPatients.length > 2) {
            broadcastIntent.putExtra(Constants.DataSyncServiceConstants.DOWNLOAD_COUNT_SECONDARY, resultForPatients[2]);
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);
    }

    private void prepareBroadcastMsg(Intent broadcastIntent, int[] result, String msg) {
        broadcastIntent.putExtra(Constants.DataSyncServiceConstants.SYNC_STATUS, result[0]);
        if (isSuccess(result)) {
            broadcastIntent.putExtra(Constants.DataSyncServiceConstants.DOWNLOAD_COUNT_PRIMARY, result[1]);
            broadcastIntent.putExtra(Constants.DataSyncServiceConstants.DELETED_COUNT_PRIMARY,result[2]);
            updateNotificationMsg(msg);
        }
    }

    private boolean isSuccess(int[] result) {
        return result[0] == SyncStatusConstants.SUCCESS;
    }

    private void prepareBroadcastMsgForFormUpload(Intent broadcastIntent, int[] result, String msg) {
        broadcastIntent.putExtra(Constants.DataSyncServiceConstants.SYNC_STATUS, result[0]);
        if (isSuccess(result)) {
            updateNotificationMsg(msg);
        }
    }

    private void saveSyncTime(int[] result, APIName apiName) {
        if (isSuccess(result)) {
            LastSyncTimeService lastSyncTimeService = null;
            try {
                lastSyncTimeService = ((MuzimaApplication) getApplication()).getMuzimaContext().getLastSyncTimeService();
                SntpService sntpService = ((MuzimaApplication)getApplicationContext()).getSntpService();
                LastSyncTime lastSyncTime = new LastSyncTime(apiName, sntpService.getLocalTime());
                lastSyncTimeService.saveLastSyncTime(lastSyncTime);
            } catch (IOException e) {
                Log.i(TAG, "Error setting last sync time.");
            }
        }
    }

    private boolean authenticationSuccessful(String[] credentials, Intent broadcastIntent) {
        int authenticationStatus = muzimaSyncService.authenticate(credentials);
        if (authenticationStatus != SyncStatusConstants.AUTHENTICATION_SUCCESS) {
            broadcastIntent.putExtra(Constants.DataSyncServiceConstants.SYNC_STATUS, authenticationStatus);
            return false;
        }
        return true;
    }

    @Override
    public void onDestroy() {
        showNotification(notificationServiceFinished, notificationMsg);
        super.onDestroy();
    }

    private void showNotification(String title, String msg) {
        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(this)
                        .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(), 0))
                        .setSmallIcon(R.drawable.ic_launcher_logo)
                        .setContentTitle(title)
                        .setContentText(msg);
        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(MUZIMA_NOTIFICATION, mBuilder.getNotification());
    }


    private void updateNotificationMsg(String msg) {
        notificationMsg = msg;
        showNotification(notificationServiceRunning, notificationMsg);
    }
}
